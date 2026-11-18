package com.kigtts.tools.store

import com.kigtts.plugin.api.*

internal fun downloadActions(entries: List<CatalogEntry>, jobs: StoreJobs, device: StoreDevice, copy: StoreCopy = StoreCopy.Chinese): Map<String, ManagementProgressAction> =
    entries.associate { it.item.id to downloadAction(it, jobs, device, copy = copy) }

/** Both discovery and detail observe the same task; the chosen version remains explicit. */
internal fun downloadAction(entry: CatalogEntry, jobs: StoreJobs, device: StoreDevice,
    release: PackageRelease? = entry.latest(device), detail: Boolean = false, copy: StoreCopy = StoreCopy.Chinese): ManagementProgressAction {
    if (release == null || !release.compatible(device)) return ManagementProgressAction(copy.text("download.incompatible"))
    val installed = if (entry.item.resourceType == null) jobs.installed[entry.item.id] else null
    if (installed?.builtin == true) return ManagementProgressAction(copy.text("download.built_in"))
    if (installed?.let { it.version == release.version && it.apiVersion == release.apiVersion &&
            (release.format == PackageFormat.LUA || it.versionCode == release.versionCode) } == true)
        return ManagementProgressAction(copy.text("installed"), if (detail) null else "package/${entry.source.key}/${entry.item.id}")
    val task = jobs.downloads?.latest(entry.item.id)?.takeIf { task -> task.root.release.let {
        it.versionCode == release.versionCode && it.version == release.version && it.apiVersion == release.apiVersion &&
            it.format == release.format && it.sha256 == release.sha256
    } }
    if (entry.item.resourceType != null && task?.phase == StoreDownloadPhase.INSTALLED) return ManagementProgressAction(copy.text("download.add_again"), "download-get/${entry.source.key}/${entry.item.id}/${release.versionCode}")
    return task?.button(installed, copy) ?: ManagementProgressAction(when {
        installed == null -> copy.text("download.get")
        release.isUpdateFrom(installed) -> copy.text("download.update")
        else -> copy.text("download.install")
    }, "download-get/${entry.source.key}/${entry.item.id}/${release.versionCode}")
}
internal fun downloadManagementPage(jobs: StoreJobs, sources: SourceStore, copy: StoreCopy): PluginPage {
    val tasks = jobs.downloads?.tasks.orEmpty().asReversed()
    return PluginPage("downloads", copy.text("downloads"), listOf(PageBlock.Actions(listOf(
        PageAction("downloads-clear-finished", copy.text("download.clear_finished"), PluginIcon.NONE, "done_all"),
        PageAction("download-storage", copy.text("storage"), PluginIcon.NONE, "storage")))),
        ManagementList("downloads", tasks.map { task ->
            storeItem(task.root.entry, sources.catalog(task.root.entry.source.key), jobs,
                "${task.root.release.version} · ${task.status(copy)}", "", copy).copy(id = task.id,
                actions = if (task.phase in setOf(StoreDownloadPhase.CONFIRM, StoreDownloadPhase.QUEUED, StoreDownloadPhase.DOWNLOADING, StoreDownloadPhase.VERIFYING, StoreDownloadPhase.READY))
                    listOf(PageAction("download-cancel/${task.id}", copy.text("download.cancel_task"), PluginIcon.CLOSE)) else emptyList())
        }, emptyText = copy.text("download.empty"), style = ManagementListStyle.CATALOG,
            itemActions = tasks.associate { it.id to it.button(jobs.installed[it.root.entry.item.id], copy) }), onEnterAction = "downloads-enter")
}
