package com.kigtts.tools.store

data class SemVersion(val major: Int, val minor: Int, val patch: Int, val pre: List<String> = emptyList()) : Comparable<SemVersion> {
    override fun compareTo(other: SemVersion): Int {
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }).takeIf { it != 0 }?.let { return it }
        if (pre.isEmpty() || other.pre.isEmpty()) return if (pre.isEmpty() && other.pre.isEmpty()) 0 else if (pre.isEmpty()) 1 else -1
        for (index in 0 until minOf(pre.size, other.pre.size)) {
            val a = pre[index]; val b = other.pre[index]; val an = a.all(Char::isDigit); val bn = b.all(Char::isDigit)
            val comparison = when { an && bn -> if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)
                an -> -1; bn -> 1; else -> a.compareTo(b) }
            if (comparison != 0) return comparison
        }
        return pre.size.compareTo(other.pre.size)
    }
    companion object {
        private val VERSION = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?")
        fun parse(value: String): SemVersion {
            val match = VERSION.matchEntire(value) ?: throw IllegalArgumentException("版本需要使用 SemVer，例如 1.2.3")
            val parts = match.groupValues[4].takeIf(String::isNotEmpty)?.split('.').orEmpty()
            require(parts.none { it.all(Char::isDigit) && it.length > 1 && it.startsWith('0') })
            return SemVersion(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt(), parts)
        }
    }
}
class VersionRange private constructor(private val alternatives: List<ConstraintGroup>) {
    private data class ConstraintGroup(val tests: List<(SemVersion) -> Boolean>, val prereleaseBases: Set<Triple<Int, Int, Int>>)
    fun contains(value: SemVersion) = alternatives.any { group ->
        (value.pre.isEmpty() || Triple(value.major, value.minor, value.patch) in group.prereleaseBases) && group.tests.all { it(value) }
    }
    companion object {
        fun parse(value: String): VersionRange {
            require(value.length in 1..128)
            val groups = value.trim().split("||").map { alternative ->
                val text = alternative.trim(); require(text.isNotEmpty())
                val hyphen = Regex("^(\\d+\\.\\d+\\.\\d+)\\s+-\\s+(\\d+\\.\\d+\\.\\d+)$").matchEntire(text)
                val tests = if (hyphen != null) {
                    val lower = SemVersion.parse(hyphen.groupValues[1]); val upper = SemVersion.parse(hyphen.groupValues[2])
                    listOf<(SemVersion) -> Boolean>({ it >= lower }, { it <= upper })
                } else text.replace(",", " ").split(Regex("\\s+")).filter(String::isNotEmpty).map(::predicate)
                val prereleases = Regex("[0-9]+\\.[0-9]+\\.[0-9]+-[0-9A-Za-z.-]+").findAll(text).map { match ->
                    val version = SemVersion.parse(match.value); Triple(version.major, version.minor, version.patch)
                }.toSet()
                ConstraintGroup(tests, prereleases)
            }
            return VersionRange(groups)
        }
        private fun predicate(token: String): (SemVersion) -> Boolean {
            if (token in listOf("*", "x", "X")) return { true }
            val match = Regex("^(>=|<=|>|<|=|\\^|~)?(.+)$").matchEntire(token) ?: error("版本范围格式不支持")
            val operator = match.groupValues[1]; val value = match.groupValues[2]
            if (value.split('.').any { it in listOf("*", "x", "X") } || Regex("\\d+(?:\\.\\d+)?").matches(value)) {
                require(operator.isEmpty() || operator == "=") { "部分版本只支持 x 或精确范围" }
                val parts = value.split('.'); require(parts.size <= 3)
                val firstWildcard = parts.indexOfFirst { it in listOf("*", "x", "X") }.let { if (it < 0) parts.size else it }
                require(parts.drop(firstWildcard).all { it in listOf("*", "x", "X") })
                val fixed = parts.take(firstWildcard).map { it.toInt().also { number -> require(number >= 0) } }
                return { version -> listOf(version.major, version.minor, version.patch).take(fixed.size) == fixed }
            }
            val version = SemVersion.parse(value)
            return when (operator) {
                ">=" -> { it -> it >= version }; ">" -> { it -> it > version }; "<=" -> { it -> it <= version }; "<" -> { it -> it < version }
                "", "=" -> { it -> it == version }
                "~" -> { it -> it >= version && it < SemVersion(version.major, Math.addExact(version.minor, 1), 0) }
                "^" -> {
                    val upper = when { version.major > 0 -> SemVersion(Math.addExact(version.major, 1), 0, 0)
                        version.minor > 0 -> SemVersion(0, Math.addExact(version.minor, 1), 0)
                        else -> SemVersion(0, 0, Math.addExact(version.patch, 1)) }
                    { it -> it >= version && it < upper }
                }
                else -> error("版本范围格式不支持")
            }
        }
    }
}
