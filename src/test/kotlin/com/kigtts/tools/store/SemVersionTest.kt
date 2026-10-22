package com.kigtts.tools.store

import org.junit.Assert.*
import org.junit.Test

class SemVersionTest {
    @Test fun semverPrecedenceFollowsNumericAndPrereleaseOrdering() {
        val ordered = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0")
        assertEquals(ordered, ordered.reversed().sortedBy(SemVersion::parse))
        assertEquals(SemVersion.parse("1.0.0"), SemVersion.parse("1.0.0+build.2"))
    }
    @Test fun supportsExactComparatorsCaretTildeAndWildcards() {
        val examples = mapOf("1.2.3" to listOf("1.2.3"), ">=1.2.0 <2.0.0" to listOf("1.2.3", "1.9.9"),
            "^1.2.3" to listOf("1.2.3", "1.9.0"), "^0.2.3" to listOf("0.2.3", "0.2.9"), "~1.2.3" to listOf("1.2.3", "1.2.9"),
            "1.x" to listOf("1.0.0", "1.9.9"), "1.2.x" to listOf("1.2.0", "1.2.99"), "1.2" to listOf("1.2.5"), "*" to listOf("3.2.1"))
        examples.forEach { (range, versions) -> versions.forEach { assertTrue("$range $it", VersionRange.parse(range).contains(SemVersion.parse(it))) } }
        assertFalse(VersionRange.parse("^0.2.3").contains(SemVersion.parse("0.3.0")))
        assertFalse(VersionRange.parse("~1.2.3").contains(SemVersion.parse("1.3.0")))
    }
    @Test fun supportsOrAndInclusiveHyphenRange() {
        assertTrue(VersionRange.parse("1.0.0 - 2.0.0").contains(SemVersion.parse("2.0.0")))
        assertTrue(VersionRange.parse("1.x || >=3.0.0").contains(SemVersion.parse("3.1.0")))
        assertFalse(VersionRange.parse("1.x || >=3.0.0").contains(SemVersion.parse("2.1.0")))
    }
    @Test fun rejectsMalformedRangesAndImplicitPrereleases() {
        listOf("1.02.3", "1.2.3-01", "latest", "1.2").forEach { assertThrows(IllegalArgumentException::class.java) { SemVersion.parse(it) } }
        assertThrows(IllegalArgumentException::class.java) { VersionRange.parse("^1.2") }
        assertFalse(VersionRange.parse("*").contains(SemVersion.parse("2.0.0-beta")))
        assertTrue(VersionRange.parse("2.0.0-beta").contains(SemVersion.parse("2.0.0-beta")))
    }
    @Test fun prereleaseOptInIsScopedToItsComparatorSetAndBaseVersion() {
        val range = VersionRange.parse("^2.0.0-beta || 3.x")
        assertTrue(range.contains(SemVersion.parse("2.0.0-beta.2")))
        assertTrue(range.contains(SemVersion.parse("2.1.0")))
        assertFalse(range.contains(SemVersion.parse("2.1.0-beta")))
        assertFalse(range.contains(SemVersion.parse("3.0.0-beta")))
    }
}
