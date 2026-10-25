package com.kigtts.tools.store

import com.kigtts.plugin.api.*

/** Resource imports live in their own libraries, never in the installed plugin collection. */
internal fun resourceSectionsPage(id: String, sources: SourceStore, jobs: StoreJobs): PluginPage {
    val actions = listOf(PageAction("add-source", "添加源", symbol = "add"),
        PageAction("section/resource-sources", "资源来源", symbol = "source"))
    if (id == "resource-sources") {
        val records = sources.records().filter { record -> record.enabled && sources.catalog(record.key)?.let {
            it.kind == SourceKind.RESOURCES || it.packages.any { item -> item.resourceType != null }
        } == true }
        return PluginPage(id, "资源来源", commonStatus(jobs) + PageBlock.Actions(listOf(actions.first())),
            ManagementList(id, records.map { sourceSectionItem(it, sources, jobs) },
                emptyText = "添加资源源，发现更多字幕、照片与声音", style = ManagementListStyle.CATALOG))
    }
    return PluginPage(id, "资源", commonStatus(jobs) + PageBlock.Actions(actions), ManagementList(id,
        ResourceType.entries.map { type -> ManagementItem(type.name, type.label, icon = PluginIcon.NONE,
            symbol = type.symbol, primaryAction = "open-catalog/${catalogPageId(category = type.label)}") },
        style = ManagementListStyle.CATALOG))
}
