package com.kigtts.tools.store

import com.kigtts.plugin.api.*

internal fun downloadActions(entries: List<CatalogEntry>, jobs: StoreJobs, device: StoreDevice): Map<String, ManagementProgressAction> =
    entries.associate { it.item.id to downloadAction(it, jobs, device) }

/** Both discovery and detail observe the same task; the chosen version remains explicit. */
internal fun downloadAction(entry: CatalogEntry, jobs: StoreJobs, device: StoreDevice,
    release: PackageRelease? = entry.latest(device), detail: Boolean = false): ManagementProgressAction {
    if (release == null || !release.compatible(device)) return ManagementProgressAction("不兼容")
    val installed = jobs.installed[entry.item.id]
    if (installed?.let { it.version == release.version && it.apiVersion == release.apiVersion &&
            (release.format == PackageFormat.LUA || it.versionCode == release.versionCode) } == true)
        return ManagementProgressAction("已安装", if (detail) null else "package/${entry.source.key}/${entry.item.id}")
    val task = jobs.downloads?.latest(entry.item.id)?.takeIf { it.root.release.versionCode == release.versionCode }
    return task?.button() ?: ManagementProgressAction(when {
        installed == null -> "获取"
        SemVersion.parse(release.version) > SemVersion.parse(installed.version) -> "更新"
        else -> "安装"
    }, "download-get/${entry.source.key}/${entry.item.id}/${release.versionCode}")
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
