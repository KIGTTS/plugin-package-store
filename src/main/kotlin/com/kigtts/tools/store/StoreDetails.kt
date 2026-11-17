package com.kigtts.tools.store

import com.kigtts.plugin.api.*

internal fun packageDetailsPage(id: String, sources: SourceStore, jobs: StoreJobs, view: StoreView, device: StoreDevice, copy: StoreCopy = StoreCopy.Chinese): PluginPage? {
    val entry = view.selected?.let { it.copy(item = it.item.localized(copy.locale)) } ?: return null
    val release = entry.item.releases.firstOrNull { it.versionCode == view.selectedVersion } ?: entry.latest(device)
    val installed = if (entry.item.resourceType == null) jobs.installed[entry.item.id] else null
    val catalog = sources.catalog(entry.source.key)
    return when (id) {
        "package" -> PluginPage(id, entry.item.name, commonStatus(jobs, copy) + sourceRecommendation(sources, jobs, entry) + listOf(
            PageBlock.Group(copy.text("overview"), listOf(markdown(entry.item.description.ifBlank { entry.item.summary }, catalog, jobs),
                thumbnails(entry, catalog, jobs), PageBlock.Text((listOf(entry.item.section) + entry.item.tags).distinct().joinToString(" · "))) +
                listOfNotNull(installed?.let { PageBlock.Text(copy.text("detail_installed", "version" to (it.version).toString())) }) +
                listOfNotNull(release?.takeUnless { it.compatible(device) }?.let {
                    PageBlock.Notice(copy.text("incompatible_version")) })),
            PageBlock.Group(copy.text("release_notes"), listOf(markdown(release?.notes?.ifBlank { copy.text("no_release_notes") } ?: copy.text("no_compatible"), catalog, jobs),
                PageBlock.Actions(listOf(PageAction("versions", copy.text("all_versions"), PluginIcon.NONE, "history"))))),
            if (entry.item.resourceType == null) permissionDetails(release) else PageBlock.Text(copy.text("resource_available", "section" to (entry.item.section).toString())),
            PageBlock.Actions(listOf(PageAction("package-info", copy.text("compatibility"), PluginIcon.NONE, "info")))
        ), header = storeItem(entry, catalog, jobs, group = "").copy(primaryAction = null),
            headerAction = downloadAction(entry, jobs, device, release, detail = true))
        "versions" -> PluginPage(id, entry.item.name + " · " + copy.text("versions"), emptyList(), ManagementList("versions", entry.item.releases.sortedByDescending { SemVersion.parse(it.version) }.map { version ->
            ManagementItem(version.versionCode.toString(), version.version,
                listOfNotNull(if (!version.compatible(device)) copy.text("incompatible") else null, if (installed?.version == version.version) copy.text("installed") else null, version.notes.takeIf(String::isNotBlank)).joinToString(" · "),
                icon = PluginIcon.NONE, selected = version.versionCode == view.selectedVersion,
                primaryAction = if (version.compatible(device)) "version/${version.versionCode}" else null)
        }))
        "package-info" -> PluginPage(id, copy.text("compatibility"), listOf(PageBlock.Group(copy.text("content_sources"), listOf(
            PageBlock.Text(entry.source.name), markdown(catalog?.readme?.ifBlank { catalog.description } ?: "", catalog, jobs), PageBlock.Text(copy.text("identifier", "id" to (entry.item.id).toString())), PageBlock.Text(copy.text("license", "license" to (entry.item.license).toString())))),
            PageBlock.Group(copy.text("selected_version"), buildList {
                add(PageBlock.Text(release?.let { "${it.version} · Android ${it.minSdk}+ · KIGTTS API ${it.apiVersion}+" } ?: "暂无兼容版本"))
                release?.let { add(PageBlock.Text("发布者 SHA-256：${it.expectedPublisher}"))
                    add(PageBlock.Text("下载大小：${(it.bytes + 1023) / 1024} KiB · ${it.mirrors.size} 个下载地址"))
                    if (it.dependencies.isNotEmpty()) add(PageBlock.Text("还需要安装：" + it.dependencies.entries.joinToString { dependency -> "${dependency.key} ${dependency.value}" })) }
            })))
        else -> null
    }
}
