package com.kigtts.tools.store

import com.kigtts.plugin.api.*

/** Keep package identities in the same native rows and gutters as the rest of the store. */
internal fun installationPlanPage(id: String, sources: SourceStore, jobs: StoreJobs, copy: StoreCopy = StoreCopy.Chinese): PluginPage {
    val plan = jobs.pendingPlan ?: return PluginPage(id, copy.text("install_plan"), commonStatus(jobs, copy) +
        PageBlock.Notice(if (jobs.busy) copy.text("install_resolving") else copy.text("install_choose")))
    val ready = jobs.prepared != null
    val blocks = buildList<PageBlock> {
        if (jobs.error.isNotEmpty()) add(PageBlock.Notice(jobs.error))
        if (jobs.busy) add(PageBlock.Notice(jobs.progress?.let {
            "${jobs.status} · ${(it.bytes * 100 / it.total.coerceAtLeast(1)).coerceIn(0, 100)}%"
        } ?: jobs.status))
        if (plan.ordered.size > 1 && !ready) add(PageBlock.Notice(copy.text("install_requires")))
        if (plan.downgrades.isNotEmpty()) add(PageBlock.Notice(copy.text("install_older", "packages" to plan.downgrades.joinToString())))
        add(PageBlock.Group("", listOf(PageBlock.Actions(if (jobs.busy) listOf(
            PageAction("cancel-job", copy.text("install_cancel_download"), PluginIcon.CLOSE)) else listOf(
            PageAction(if (ready) "install-plan" else "download-plan", if (ready) copy.text("install_plugin") else copy.text("install_confirm_download"), PluginIcon.ADD),
            PageAction("install-cancel", copy.text("cancel"), PluginIcon.CLOSE))))))
    }
    return PluginPage(id, copy.text("install_plan"), blocks, management = ManagementList("install-plan", plan.ordered.map { selected ->
        storeItem(selected.entry, sources.catalog(selected.entry.source.key), jobs,
            subtitle = "v${selected.release.version}" + if (selected.entry.item.id != plan.root.entry.item.id) " · " + copy.text("install_dependency") else "",
            group = copy.text("install_group"), copy = copy).copy(primaryAction = null, actions = emptyList())
    }))
}
