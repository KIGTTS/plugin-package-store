package com.kigtts.tools.store

import com.kigtts.plugin.api.*

internal fun downloadActions(entries: List<CatalogEntry>, jobs: StoreJobs, device: StoreDevice): Map<String, ManagementProgressAction> = entries.associate { entry ->
    val latest = entry.latest(device)
    val task = jobs.downloads?.latest(entry.item.id)?.takeIf { it.root.release.versionCode == latest?.versionCode }
    entry.item.id to (task?.button() ?: when {
        latest == null -> ManagementProgressAction("不兼容")
        jobs.installed[entry.item.id]?.let { it.version == latest.version && it.apiVersion == latest.apiVersion } == true -> ManagementProgressAction("已安装", "package/${entry.source.key}/${entry.item.id}")
        else -> ManagementProgressAction(if (entry.item.id in jobs.installed) "更新" else "获取", "download-get/${entry.source.key}/${entry.item.id}/${latest.versionCode}")
    })
}
internal fun downloadManagementPage(jobs: StoreJobs, sources: SourceStore): PluginPage {
    val tasks = jobs.downloads?.tasks.orEmpty().asReversed()
    return PluginPage("downloads", "下载管理", listOf(PageBlock.Actions(listOf(
        PageAction("downloads-clear-finished", "清除已完成记录", PluginIcon.NONE, "done_all"),
        PageAction("download-storage", "存储空间", PluginIcon.NONE, "storage")))),
        ManagementList("downloads", tasks.map { task ->
            storeItem(task.root.entry, sources.catalog(task.root.entry.source.key), jobs,
                "${task.root.release.version} · ${task.status()}", "").copy(id = task.id,
                actions = if (task.phase in setOf(StoreDownloadPhase.CONFIRM, StoreDownloadPhase.QUEUED, StoreDownloadPhase.DOWNLOADING, StoreDownloadPhase.VERIFYING, StoreDownloadPhase.READY))
                    listOf(PageAction("download-cancel/${task.id}", "取消任务", PluginIcon.CLOSE)) else emptyList())
        }, emptyText = "还没有下载任务", style = ManagementListStyle.CATALOG,
            itemActions = tasks.associate { it.id to it.button() }), onEnterAction = "downloads-enter")
}
