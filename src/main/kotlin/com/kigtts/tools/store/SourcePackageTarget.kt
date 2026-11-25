package com.kigtts.tools.store

import com.kigtts.plugin.api.*

data class SourcePackageTarget(val url: String, val packageId: String)

/** Resolve within the linked source, never from another source with the same package id. */
internal fun sourcePackagePage(sources: SourceStore, jobs: StoreJobs, view: StoreView, device: StoreDevice, copy: StoreCopy = StoreCopy.Chinese): PluginPage? {
    val target = view.linkTarget ?: return null
    val source = sources.findUrl(target.url)
    fun notice(message: String) = PluginPage("package", copy.text("source.preview.title"), listOf(
        PageBlock.Notice(message), PageBlock.Actions(listOf(
            PageAction("target-refresh", copy.text("download.retry"), PluginIcon.EXPORT), PageAction("sources", copy.text("plugin_sources"), PluginIcon.NONE)
        ))), loading = jobs.busy)
    if (source == null) return notice(copy.text("source.issue.refresh"))
    if (!source.enabled) return notice(copy.text("source.preview.disabled"))
    val catalog = sources.catalog(source.key)
    val item = catalog?.packages?.firstOrNull { it.id == target.packageId }
    if (item == null) return notice(when {
        jobs.busy -> copy.text("source.preview.loading")
        source.error.isNotBlank() -> source.displayError(copy)
        catalog == null -> copy.text("source.issue.refresh")
        else -> copy.text("source.preview.removed")
    })
    val entry = CatalogEntry(source, item, catalog.category(item))
    if (view.selected?.source?.key != source.key || view.selected?.item?.id != item.id) {
        view.selectedVersion = entry.latest(device)?.versionCode
    }
    view.selected = entry
    return null
}
