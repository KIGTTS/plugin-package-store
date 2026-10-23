package com.kigtts.tools.store

import com.kigtts.plugin.api.*

data class SourcePackageTarget(val url: String, val packageId: String)

/** Resolve within the linked source, never from another source with the same package id. */
internal fun sourcePackagePage(sources: SourceStore, jobs: StoreJobs, view: StoreView, device: StoreDevice): PluginPage? {
    val target = view.linkTarget ?: return null
    val source = sources.records().firstOrNull { it.url == target.url }
    fun notice(message: String) = PluginPage("package", "插件详情", listOf(
        PageBlock.Notice(message), PageBlock.Actions(listOf(
            PageAction("target-refresh", "重试", PluginIcon.EXPORT), PageAction("sources", "插件来源", PluginIcon.NONE)
        ))), loading = jobs.busy)
    if (source == null) return notice("这个源尚未添加，请先确认添加。")
    if (!source.enabled) return notice("这个源已停用，请先在插件来源中开启。")
    val catalog = sources.catalog(source.key)
    val item = catalog?.packages?.firstOrNull { it.id == target.packageId }
    if (item == null) return notice(when {
        jobs.busy -> "正在读取插件信息…"
        source.error.isNotBlank() -> source.error
        catalog == null -> "暂时无法读取这个源，请重试。"
        else -> "这个源中暂时没有找到该插件，可能已被移除。"
    })
    val entry = CatalogEntry(source, item)
    if (view.selected?.source?.key != source.key || view.selected?.item?.id != item.id) {
        view.selectedVersion = entry.latest(device)?.versionCode
    }
    view.selected = entry
    return null
}
