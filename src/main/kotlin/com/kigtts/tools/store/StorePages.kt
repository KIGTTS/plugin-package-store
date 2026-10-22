package com.kigtts.tools.store

import com.kigtts.plugin.api.*

data class StoreView(var query: String = "", var filter: String = "all", var source: String? = null, var offset: Int = 0,
    var selected: CatalogEntry? = null, var selectedVersion: Int? = null, var category: String? = null)
internal fun storePage(id: String, sources: SourceStore, jobs: StoreJobs, view: StoreView, device: StoreDevice): PluginPage? = when (id) {
    "store" -> discoveryPage(sources, jobs, view, device)
    "library", "updates" -> libraryPage(id, sources, jobs, view, device)
    "store-settings" -> storeSettingsPage(jobs)
    "downloads" -> downloadsPage(jobs)
    "sources" -> PluginPage(id, "插件来源", commonStatus(jobs) + listOf(
        PageBlock.Actions(listOf(PageAction("add-source", "添加源", PluginIcon.ADD), PageAction("refresh", "更新全部", PluginIcon.EXPORT)))),
        management = ManagementList("sources", sources.records().map { source ->
            ManagementItem(source.key, source.name, source.error.ifBlank { sources.catalog(source.key)?.packages?.size?.let { "$it 个包" } ?: "尚未更新" },
                icon = PluginIcon.NONE, image = jobs.image(sources.catalog(source.key), sources.catalog(source.key)?.icon?.image),
                symbol = sources.catalog(source.key)?.icon?.symbol, primaryAction = "browse-source/${source.key}",
                actions = listOf(PageAction("refresh/${source.key}", "更新", PluginIcon.EXPORT), PageAction("source-remove/${source.key}", "移除", PluginIcon.CLOSE)),
                toggle = ManagementToggle(source.enabled, "source-toggle/${source.key}"))
        }, reorderAction = "sources-reorder", emptyText = "在这里添加和管理插件来源"))
    "source-review" -> sources.pending()?.let { pending -> PluginPage(id, "添加源", listOf(
        PageBlock.Group("确认源地址", listOf(PageBlock.Text(pending.url), PageBlock.Notice("确认后会读取目录。添加源不会安装包或信任发布者。"),
            PageBlock.Actions(listOf(PageAction("source-confirm/${pending.token}", "添加", PluginIcon.ADD), PageAction("source-cancel", "取消", PluginIcon.CLOSE))))))) }
    "package", "versions", "package-info" -> packageDetailsPage(id, sources, jobs, view, device)
    "install-plan" -> jobs.prepared?.let { ready -> PluginPage(id, "检查安装计划", commonStatus(jobs) + listOf(
        PageBlock.Group("本次安装", ready.plan.ordered.map { selected -> PageBlock.Text("${selected.entry.item.name} · ${selected.release.version}") } +
            listOfNotNull(ready.plan.downgrades.takeIf { it.isNotEmpty() }?.let { PageBlock.Notice("包含较早版本：" + it.joinToString()) }) +
            listOf(PageBlock.Notice("继续后仍会检查发布者和权限，并由你确认安装。"), PageBlock.Actions(listOf(
                PageAction("install-plan", "继续安装", PluginIcon.ADD), PageAction("install-cancel", "取消", PluginIcon.CLOSE))))))) }
        ?: PluginPage(id, "检查安装计划", commonStatus(jobs) + listOf(PageBlock.Notice(if (jobs.busy) "正在准备所需的包" else "请先选择一个版本")))
    "await-install" -> PluginPage(id, "等待确认", listOf(PageBlock.Notice("请在安装页面完成确认。商店会以主程序的已安装状态为准。"),
        PageBlock.Actions(listOf(PageAction("continue-install", "检查并继续", PluginIcon.EXPORT), PageAction("install-cancel", "返回商店", PluginIcon.CLOSE)))))
    else -> null
}?.copy(loading = jobs.busy, refreshAction = if (id in setOf("store", "library", "updates", "sources")) "refresh" else null)
internal fun commonStatus(jobs: StoreJobs): List<PageBlock> = buildList {
    if (jobs.busy && !jobs.refreshing && jobs.status.isNotEmpty()) {
        add(PageBlock.Notice(jobs.progress?.let { "${jobs.status} · ${(it.bytes * 100 / it.total).coerceIn(0, 100)}%" } ?: jobs.status))
        add(PageBlock.Actions(listOf(PageAction("cancel-job", "取消", PluginIcon.CLOSE))))
    }
    if (jobs.error.isNotEmpty()) add(PageBlock.Notice(jobs.error))
    if (jobs.prepared != null) add(PageBlock.Actions(listOf(PageAction("show-plan", "查看安装计划", PluginIcon.ADD))))
}
