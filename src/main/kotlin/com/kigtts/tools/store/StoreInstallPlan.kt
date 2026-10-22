package com.kigtts.tools.store

import com.kigtts.plugin.api.*

/** Keep package identities in the same native rows and gutters as the rest of the store. */
internal fun installationPlanPage(id: String, sources: SourceStore, jobs: StoreJobs): PluginPage {
    val plan = jobs.pendingPlan ?: return PluginPage(id, "检查安装计划", commonStatus(jobs) +
        PageBlock.Notice(if (jobs.busy) "正在检查所需插件…" else "请先选择一个版本"))
    val ready = jobs.prepared != null
    val blocks = buildList<PageBlock> {
        if (jobs.error.isNotEmpty()) add(PageBlock.Notice(jobs.error))
        if (jobs.busy) add(PageBlock.Notice(jobs.progress?.let {
            "${jobs.status} · ${(it.bytes * 100 / it.total.coerceAtLeast(1)).coerceIn(0, 100)}%"
        } ?: jobs.status))
        if (plan.ordered.size > 1 && !ready) add(PageBlock.Notice("此插件需要以下前置插件，将在确认后一起下载。"))
        if (plan.downgrades.isNotEmpty()) add(PageBlock.Notice("包含较早版本：${plan.downgrades.joinToString()}"))
        add(PageBlock.Group("", listOf(PageBlock.Actions(if (jobs.busy) listOf(
            PageAction("cancel-job", "取消下载", PluginIcon.CLOSE)) else listOf(
            PageAction(if (ready) "install-plan" else "download-plan", if (ready) "安装插件" else "确认并下载", PluginIcon.ADD),
            PageAction("install-cancel", "取消", PluginIcon.CLOSE))))))
    }
    return PluginPage(id, "检查安装计划", blocks, management = ManagementList("install-plan", plan.ordered.map { selected ->
        storeItem(selected.entry, sources.catalog(selected.entry.source.key), jobs,
            subtitle = "v${selected.release.version}" + if (selected.entry.item.id != plan.root.entry.item.id) " · 前置插件" else "",
            group = "本次安装").copy(primaryAction = null, actions = emptyList())
    }))
}
