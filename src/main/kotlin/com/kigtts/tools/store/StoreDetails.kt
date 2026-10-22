package com.kigtts.tools.store

import com.kigtts.plugin.api.*

internal fun packageDetailsPage(id: String, sources: SourceStore, jobs: StoreJobs, view: StoreView, device: StoreDevice): PluginPage? {
    val entry = view.selected ?: return null
    val release = entry.item.releases.firstOrNull { it.versionCode == view.selectedVersion } ?: entry.latest(device)
    val installed = jobs.installed[entry.item.id]
    val catalog = sources.catalog(entry.source.key)
    return when (id) {
        "package" -> PluginPage(id, entry.item.name, commonStatus(jobs) + listOf(
            PageBlock.Group(entry.item.name, listOf(markdown(entry.item.description.ifBlank { entry.item.summary }, catalog, jobs),
                thumbnails(entry, catalog, jobs), PageBlock.Text((listOf(entry.item.category) + entry.item.tags).distinct().joinToString(" · "))) +
                listOfNotNull(installed?.let { PageBlock.Text("已安装 ${it.version}") }) +
                listOfNotNull(release?.let { available -> if (!available.compatible(device)) PageBlock.Notice("此版本不适用于当前设备，请选择其它版本")
                    else PageBlock.Actions(listOf(PageAction("prepare", when {
                        installed == null -> "安装"
                        installed.version == available.version && installed.apiVersion == available.apiVersion -> "重新安装"
                        SemVersion.parse(available.version) > SemVersion.parse(installed.version) -> "更新"
                        else -> "安装所选版本"
                    }, PluginIcon.NONE, "download"))) })),
            PageBlock.Group("版本动态", listOf(markdown(release?.notes?.ifBlank { "暂无版本说明" } ?: "暂未提供兼容版本", catalog, jobs),
                PageBlock.Actions(listOf(PageAction("versions", "所有版本", PluginIcon.NONE, "history"))))),
            PageBlock.Actions(listOf(PageAction("package-info", "来源与兼容性", PluginIcon.NONE, "info")))
        ))
        "versions" -> PluginPage(id, "${entry.item.name} · 版本", emptyList(), ManagementList("versions", entry.item.releases.sortedByDescending { SemVersion.parse(it.version) }.map { version ->
            ManagementItem(version.versionCode.toString(), version.version,
                listOfNotNull(if (!version.compatible(device)) "不兼容当前设备" else null, if (installed?.version == version.version) "已安装" else null, version.notes.takeIf(String::isNotBlank)).joinToString(" · "),
                icon = PluginIcon.NONE, selected = version.versionCode == view.selectedVersion,
                primaryAction = if (version.compatible(device)) "version/${version.versionCode}" else null)
        }))
        "package-info" -> PluginPage(id, "来源与兼容性", listOf(PageBlock.Group("插件来源", listOf(
            PageBlock.Text(entry.source.name), markdown(catalog?.readme?.ifBlank { catalog.description } ?: "", catalog, jobs), PageBlock.Text("插件标识：${entry.item.id}"), PageBlock.Text("许可：${entry.item.license}"))),
            PageBlock.Group("所选版本", buildList {
                add(PageBlock.Text(release?.let { "${it.version} · Android ${it.minSdk}+ · 插件接口 ${it.apiVersion}" } ?: "暂无兼容版本"))
                release?.let { add(PageBlock.Text("发布者 SHA-256：${it.expectedPublisher}"))
                    add(PageBlock.Text("下载大小：${(it.bytes + 1023) / 1024} KiB · ${it.mirrors.size} 个下载地址"))
                    if (it.dependencies.isNotEmpty()) add(PageBlock.Text("安装时自动检查前置依赖：" + it.dependencies.entries.joinToString { dependency -> "${dependency.key} ${dependency.value}" })) }
            })))
        else -> null
    }
}
