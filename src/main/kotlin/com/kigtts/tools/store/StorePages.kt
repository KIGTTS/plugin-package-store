package com.kigtts.tools.store

import com.kigtts.plugin.api.*

data class StoreView(var query: String = "", var filter: String = "all", var source: String? = null, var offset: Int = 0,
    var selected: CatalogEntry? = null, var selectedVersion: Int? = null, var category: String? = null, var sourceNotice: String = "",
    var linkTarget: SourcePackageTarget? = null, var catalogLimits: Map<String, Int> = emptyMap())
internal fun storePage(id: String, sources: SourceStore, jobs: StoreJobs, view: StoreView, device: StoreDevice): PluginPage? = when (id) {
    "store" -> discoveryPage(sources, jobs, view, device)
    "categories", "source-sections", "resources", "resource-sources" -> sectionPage(id, sources, jobs)
    "search" -> storeSearchPage(sources, jobs, view, device)
    "library", "updates" -> libraryPage(id, sources, jobs, view, device)
    "store-settings" -> storeSettingsPage(jobs)
    "downloads" -> downloadManagementPage(jobs, sources)
    "download-storage" -> downloadsPage(jobs)
    "sources" -> PluginPage(id, "插件来源", commonStatus(jobs) + listOfNotNull(view.sourceNotice.takeIf { it.isNotBlank() }?.let { PageBlock.Notice(it) }) + listOf(
        PageBlock.Actions(listOf(PageAction("add-source", "添加源", PluginIcon.ADD), PageAction("refresh", "更新全部", PluginIcon.EXPORT)))),
        management = ManagementList("sources", sources.records().map { source ->
            ManagementItem(source.key, source.name, source.error.ifBlank { sources.catalog(source.key)?.packages?.size?.let { "$it 项内容" } ?: "尚未更新" },
                icon = PluginIcon.NONE, image = jobs.icon(sources.catalog(source.key), sources.catalog(source.key)?.icon),
                symbol = sources.catalog(source.key)?.icon?.symbol, primaryAction = "browse-source/${source.key}",
                actions = listOf(PageAction("refresh/${source.key}", "更新", PluginIcon.EXPORT), PageAction("source-remove/${source.key}", "移除", PluginIcon.CLOSE)),
                toggle = ManagementToggle(source.enabled, "source-toggle/${source.key}"))
        }, reorderAction = "sources-reorder", emptyText = "在这里添加和管理内容来源"))
    "source-review" -> sources.pending()?.let { pending -> PluginPage(id, "添加源", listOf(
        PageBlock.Group("确认源地址", listOf(PageBlock.Text(pending.url), PageBlock.Notice("添加后可浏览这个源的内容。"),
            PageBlock.Actions(listOf(PageAction("source-confirm/${pending.token}", "添加", PluginIcon.ADD), PageAction("source-cancel", "取消", PluginIcon.CLOSE))))))) }
    "package", "versions", "package-info" -> packageDetailsPage(id, sources, jobs, view, device)
    "install-plan" -> installationPlanPage(id, sources, jobs)
    "await-install" -> PluginPage(id, "等待确认", listOf(PageBlock.Notice("请先完成安装，再返回这里继续。"),
        PageBlock.Actions(listOf(PageAction("continue-install", "检查并继续", PluginIcon.EXPORT), PageAction("install-cancel", "返回商店", PluginIcon.CLOSE)))))
    else -> when {
        id.startsWith("categories/") -> sectionPage(id, sources, jobs)
        id.startsWith("catalog/") -> catalogListingPage(id, sources, jobs, view, device)
        else -> null
    }
}?.copy(loading = jobs.busy, refreshAction = if (id in setOf("store", "library", "updates", "sources", "categories", "source-sections") || id.startsWith("categories/") || id.startsWith("catalog/")) "refresh" else null,
    onEnterAction = when { id == "store" -> "store-enter"; id.startsWith("catalog/") || id in setOf("search", "library", "updates", "downloads", "package") -> "downloads-enter"; else -> null })
internal fun commonStatus(jobs: StoreJobs): List<PageBlock> = buildList {
    if (jobs.busy && !jobs.refreshing && jobs.status.isNotEmpty()) {
        add(PageBlock.Notice(jobs.progress?.let { "${jobs.status} · ${(it.bytes * 100 / it.total).coerceIn(0, 100)}%" } ?: jobs.status))
        add(PageBlock.Actions(listOf(PageAction("cancel-job", "取消", PluginIcon.CLOSE))))
    }
    if (jobs.error.isNotEmpty()) add(PageBlock.Notice(jobs.error))
    if (jobs.pendingPlan != null) add(PageBlock.Actions(listOf(PageAction("show-plan", "查看安装计划", PluginIcon.ADD))))
}
