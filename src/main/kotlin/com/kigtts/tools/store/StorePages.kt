package com.kigtts.tools.store

import com.kigtts.plugin.api.*

data class StoreView(var query: String = "", var filter: String = "all", var source: String? = null, var offset: Int = 0,
    var selected: CatalogEntry? = null, var selectedVersion: Int? = null, var category: String? = null, var sourceNotice: String = "",
    var linkTarget: SourcePackageTarget? = null, var catalogLimits: Map<String, Int> = emptyMap())
internal fun storePage(id: String, sources: SourceStore, jobs: StoreJobs, view: StoreView, device: StoreDevice, copy: StoreCopy = StoreCopy.Chinese): PluginPage? = when (id) {
    "store" -> discoveryPage(sources, jobs, view, device, copy)
    "categories", "source-sections", "resources", "resource-sources" -> sectionPage(id, sources, jobs, copy)
    "search" -> storeSearchPage(sources, jobs, view, device, copy)
    "library", "updates" -> libraryPage(id, sources, jobs, view, device, copy)
    "store-settings" -> storeSettingsPage(jobs, copy)
    "downloads" -> downloadManagementPage(jobs, sources, copy)
    "download-storage" -> downloadsPage(jobs, copy)
    "sources" -> PluginPage(id, copy.text("plugin_sources"), commonStatus(jobs, copy) + listOfNotNull(view.sourceNotice.takeIf { it.isNotBlank() }?.let { PageBlock.Notice(it) }) + listOf(
        PageBlock.Actions(listOf(PageAction("add-source", copy.text("add_source"), PluginIcon.ADD), PageAction("refresh", copy.text("refresh_all"), PluginIcon.EXPORT)))),
        management = ManagementList("sources", sources.records().map { source ->
            ManagementItem(source.key, sources.sourceName(source, copy.locale), source.displayError(copy).ifBlank { sources.catalog(source.key)?.packages?.size?.let { copy.text("item_count", "count" to (it).toString()) } ?: copy.text("not_refreshed") },
                icon = PluginIcon.NONE, image = jobs.icon(sources.catalog(source.key), sources.catalog(source.key)?.icon),
                symbol = sources.catalog(source.key)?.icon?.symbol, primaryAction = "browse-source/${source.key}",
                actions = listOf(PageAction("refresh/${source.key}", copy.text("refresh"), PluginIcon.EXPORT), PageAction("source-remove/${source.key}", copy.text("remove"), PluginIcon.CLOSE)),
                toggle = ManagementToggle(source.enabled, "source-toggle/${source.key}"))
        }, reorderAction = "sources-reorder", emptyText = copy.text("source_management")))
    "source-review" -> sources.pending()?.let { pending -> PluginPage(id, copy.text("add_source"), listOf(
        PageBlock.Group(copy.text("confirm_source"), listOf(PageBlock.Text(pending.url), PageBlock.Notice(copy.text("source_explanation")),
            PageBlock.Actions(listOf(PageAction("source-confirm/${pending.token}", copy.text("add"), PluginIcon.ADD), PageAction("source-cancel", copy.text("cancel"), PluginIcon.CLOSE))))))) }
    "package", "versions", "package-info" -> packageDetailsPage(id, sources, jobs, view, device, copy)
    "install-plan" -> installationPlanPage(id, sources, jobs, copy)
    "await-install" -> PluginPage(id, copy.text("wait_confirm"), listOf(PageBlock.Notice(copy.text("install_first")),
        PageBlock.Actions(listOf(PageAction("continue-install", copy.text("continue"), PluginIcon.EXPORT), PageAction("install-cancel", copy.text("return_store"), PluginIcon.CLOSE)))))
    else -> when {
        id.startsWith("categories/") -> sectionPage(id, sources, jobs, copy)
        id.startsWith("catalog/") -> catalogListingPage(id, sources, jobs, view, device, copy)
        else -> null
    }
}?.copy(loading = jobs.busy, refreshAction = if (id in setOf("store", "library", "updates", "sources", "categories", "source-sections") || id.startsWith("categories/") || id.startsWith("catalog/")) "refresh" else null,
    onEnterAction = when { id == "store" -> "store-enter"; id.startsWith("catalog/") || id in setOf("search", "library", "updates", "downloads", "package") -> "downloads-enter"; else -> null })
internal fun commonStatus(jobs: StoreJobs, copy: StoreCopy = StoreCopy.Chinese): List<PageBlock> = buildList {
    if (jobs.busy && !jobs.refreshing && jobs.status.isNotEmpty()) {
        add(PageBlock.Notice(jobs.progress?.let { "${jobs.status(copy)} · ${(it.bytes * 100 / it.total).coerceIn(0, 100)}%" } ?: jobs.status(copy)))
        add(PageBlock.Actions(listOf(PageAction("cancel-job", copy.text("cancel"), PluginIcon.CLOSE))))
    }
    if (jobs.error.isNotEmpty()) add(PageBlock.Notice(jobs.error(copy)))
    if (jobs.pendingPlan != null) add(PageBlock.Actions(listOf(PageAction("show-plan", copy.text("show_plan"), PluginIcon.ADD))))
}
