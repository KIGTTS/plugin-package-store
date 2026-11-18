package com.kigtts.tools.store

import com.kigtts.plugin.api.*

/** Resource imports live in their own libraries, never in the installed plugin collection. */
internal fun resourceSectionsPage(id: String, sources: SourceStore, jobs: StoreJobs, copy: StoreCopy): PluginPage {
    val actions = listOf(PageAction("add-source", copy.text("add_source"), symbol = "add"),
        PageAction("section/resource-sources", copy.text("resource_sources"), symbol = "source"))
    if (id == "resource-sources") {
        val records = sources.records().filter { record -> record.enabled && sources.catalog(record.key)?.let {
            it.kind == SourceKind.RESOURCES || it.packages.any { item -> item.resourceType != null }
        } == true }
        return PluginPage(id, copy.text("resource_sources"), commonStatus(jobs, copy) + PageBlock.Actions(listOf(actions.first())),
            ManagementList(id, records.map { sourceSectionItem(it, sources, jobs, copy) },
                emptyText = copy.text("empty_resource_sources"), style = ManagementListStyle.CATALOG))
    }
    return PluginPage(id, copy.text("resources"), commonStatus(jobs, copy) + PageBlock.Actions(actions), ManagementList(id,
        ResourceType.entries.map { type -> ManagementItem(type.name, copy.resourceTypeLabel(type), icon = PluginIcon.NONE,
            symbol = type.symbol, primaryAction = "open-catalog/${catalogPageId(category = type.label)}") },
        style = ManagementListStyle.CATALOG))
}
