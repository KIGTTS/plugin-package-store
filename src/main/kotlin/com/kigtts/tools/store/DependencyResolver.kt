package com.kigtts.tools.store

data class PlannedRelease(val entry: CatalogEntry, val release: PackageRelease)
data class DependencyPlan(val ordered: List<PlannedRelease>, val root: PlannedRelease,
    val retained: List<InstalledPackage>, val downgrades: List<String>)

/** Bounded backtracking avoids greedy selection when two transitive branches constrain the same package. */
class DependencyResolver(private val entries: List<CatalogEntry>, private val installed: Map<String, InstalledPackage>, private val device: StoreDevice) {
    private var attempts = 0
    fun resolve(root: CatalogEntry, versionCode: Int): DependencyPlan {
        attempts = 0
        val release = root.item.releases.single { it.versionCode == versionCode }
        require(release.compatible(device)) { "所选版本不兼容此设备" }
        val explicit = PlannedRelease(root, release)
        val solution = search(linkedMapOf(root.item.id to explicit), linkedMapOf(), linkedSetOf()) ?: error("缺少前置依赖或版本范围冲突，请调整版本或源")
        val visiting = hashSetOf<String>(); val visited = hashSetOf<String>(); val ordered = mutableListOf<PlannedRelease>()
        fun visit(id: String) {
            if (id in visited || solution[id] == null) return
            require(visiting.add(id)) { "依赖出现循环：$id" }
            val selected = solution.getValue(id)
            selected.release.dependencies.keys.forEach(::visit)
            visiting.remove(id); visited += id; ordered += selected
        }
        visit(root.item.id)
        require(ordered.size <= 32) { "依赖链超过 32 个包" }
        // Avoid breaking another installed tool whose declared range is known to the enabled sources.
        installed.values.filter { it.id !in solution }.forEach { existing ->
            entries.firstOrNull { it.item.id == existing.id }?.item?.releases?.firstOrNull { it.version == existing.version }?.dependencies?.forEach { (id, range) ->
                solution[id]?.let { require(VersionRange.parse(range).contains(SemVersion.parse(it.release.version))) { "版本变更会与已安装的 ${existing.id} 冲突" } }
            }
        }
        val retained = installed.values.filter { it.id !in solution && ordered.any { selected -> it.id in selected.release.dependencies } }
        val downloads = ordered.filter { selected ->
            val old = installed[selected.entry.item.id]
            selected.entry.item.id == root.item.id || old == null || old.version != selected.release.version || old.apiVersion != selected.release.apiVersion ||
                old.format != selected.release.format || (selected.release.format == PackageFormat.NATIVE && old.versionCode != selected.release.versionCode)
        }
        return DependencyPlan(downloads, explicit, retained, downloads.filter { selected -> installed[selected.entry.item.id]?.let { it.versionCode > selected.release.versionCode } == true }.map { it.entry.item.id })
    }
    private fun search(selected: LinkedHashMap<String, PlannedRelease>, constraints: LinkedHashMap<String, List<VersionRange>>, expanded: LinkedHashSet<String>): LinkedHashMap<String, PlannedRelease>? {
        require(++attempts <= 4096 && selected.size <= 32) { "依赖关系过于复杂，请简化版本范围" }
        for ((id, chosen) in selected) {
            if (constraints[id].orEmpty().any { !it.contains(SemVersion.parse(chosen.release.version)) }) return null
            ensurePublisher(id, chosen.release.expectedPublisher)
        }
        val toExpand = selected.keys.firstOrNull { it !in expanded }
        if (toExpand != null) {
            val next = LinkedHashMap(constraints)
            selected.getValue(toExpand).release.dependencies.forEach { (id, range) -> next[id] = next[id].orEmpty() + VersionRange.parse(range) }
            return search(selected, next, LinkedHashSet(expanded).also { it += toExpand })
        }
        val unresolved = constraints.keys.firstOrNull { it !in selected } ?: return selected
        val ranges = constraints.getValue(unresolved)
        val old = installed[unresolved]
        val candidates = entries.filter { it.item.id == unresolved }.flatMap { entry ->
            entry.item.releases.filter { it.compatible(device) && ranges.all { range -> range.contains(SemVersion.parse(it.version)) } }.map { PlannedRelease(entry, it) }
        }.distinctBy { Triple(it.release.version, it.release.sha256, it.release.expectedPublisher) }
            .sortedByDescending { SemVersion.parse(it.release.version) }
        require(candidates.map { it.release.expectedPublisher }.distinct().size <= 1) { "相同包 ID 来自不同发布者，请停用冲突源" }
        // Reuse an installed version when it meets every requirement, but expand its declared dependencies if available.
        val reusable = old?.let { existing -> existing.apiVersion in device.minimumApi..device.api && runCatching { ranges.all { it.contains(SemVersion.parse(existing.version)) } }.getOrDefault(false) } == true
        val preferred = candidates.sortedBy { if (reusable && it.release.version == old!!.version) 0 else 1 }
        for (candidate in preferred) {
            val answer = search(LinkedHashMap(selected).also { it[unresolved] = candidate }, constraints, expanded)
            if (answer != null) return answer
        }
        if (reusable && candidates.none { it.release.version == old!!.version }) {
            // Missing catalog metadata means we cannot prove installed dependencies, so retain only this leaf.
            return search(selected, LinkedHashMap(constraints).also { it.remove(unresolved) }, expanded)
        }
        return null
    }
    private fun ensurePublisher(id: String, publisher: String) {
        val publishers = entries.filter { it.item.id == id }.flatMap { it.item.releases }.map { it.expectedPublisher }.distinct()
        require(publishers.size <= 1 && (publishers.isEmpty() || publishers.single() == publisher)) { "相同包 ID 来自不同发布者，请停用冲突源" }
        entries.filter { it.item.id == id }.flatMap { it.item.releases }.groupBy { it.version }.values.forEach { versions ->
            require(versions.map { Triple(it.sha256, it.versionCode, it.format) }.distinct().size == 1) { "同一包版本在不同源中的内容不同，请停用冲突源" }
        }
        installed[id]?.publisher?.let { require(it == publisher) { "发布者与已安装版本不一致" } }
        installed[id]?.let { old -> require(entries.filter { it.item.id == id }.flatMap { it.item.releases }.all { it.format == old.format }) { "包格式与已安装项目不一致，请使用不同 ID" } }
    }
}
