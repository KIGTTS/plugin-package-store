package com.kigtts.tools.store

import com.kigtts.plugin.api.*

internal fun packageDetailsPage(id: String, sources: SourceStore, jobs: StoreJobs, view: StoreView, device: StoreDevice, copy: StoreCopy = StoreCopy.Chinese): PluginPage? {
    val entry = view.selected?.let { it.copy(item = it.item.localized(copy.locale)) } ?: return null
    val release = entry.item.releases.firstOrNull { it.versionCode == view.selectedVersion } ?: entry.latest(device)
    val installed = if (entry.item.resourceType == null) jobs.installed[entry.item.id] else null
    val catalog = sources.catalog(entry.source.key)?.localized(copy.locale)
    return when (id) {
        "package" -> PluginPage(id, entry.item.name, commonStatus(jobs, copy) + sourceRecommendation(sources, jobs, entry, copy) + listOf(
            PageBlock.Group(copy.text("overview"), listOf(markdown(entry.item.description.ifBlank { entry.item.summary }, catalog, jobs),
                thumbnails(entry, catalog, jobs), PageBlock.Text((listOf(copy.sectionLabel(entry.item.section)) + entry.item.tags).distinct().joinToString(" · "))) +
                listOfNotNull(installed?.let { PageBlock.Text(copy.text("detail_installed", "version" to (it.version).toString())) }) +
                listOfNotNull(release?.takeUnless { it.compatible(device) }?.let {
                    PageBlock.Notice(copy.text("incompatible_version")) })),
            PageBlock.Group(copy.text("release_notes"), listOf(markdown(release?.let { entry.item.localizedNotes(it, copy.locale) }?.ifBlank { copy.text("no_release_notes") } ?: copy.text("no_compatible"), catalog, jobs),
                PageBlock.Actions(listOf(PageAction("versions", copy.text("all_versions"), PluginIcon.NONE, "history"))))),
            if (entry.item.resourceType == null) permissionDetails(release) else PageBlock.Text(copy.text("resource_available", "section" to copy.sectionLabel(entry.item.section))),
            PageBlock.Actions(listOf(PageAction("package-info", copy.text("compatibility"), PluginIcon.NONE, "info")))
        ), header = storeItem(entry, catalog, jobs, group = "").copy(primaryAction = null),
            headerAction = downloadAction(entry, jobs, device, release, detail = true, copy = copy))
        "versions" -> PluginPage(id, entry.item.name + " · " + copy.text("versions"), emptyList(), ManagementList("versions", entry.item.releases.sortedByDescending { SemVersion.parse(it.version) }.map { version ->
            ManagementItem(version.versionCode.toString(), version.version,
                listOfNotNull(if (!version.compatible(device)) copy.text("incompatible") else null, if (installed?.version == version.version) copy.text("installed") else null, entry.item.localizedNotes(version, copy.locale).takeIf(String::isNotBlank)).joinToString(" · "),
                icon = PluginIcon.NONE, selected = version.versionCode == view.selectedVersion,
                primaryAction = if (version.compatible(device)) "version/${version.versionCode}" else null)
        }))
        "package-info" -> PluginPage(id, copy.text("compatibility"), listOf(PageBlock.Group(copy.text("content_sources"), listOf(
            PageBlock.Text(catalog?.name ?: entry.source.name), markdown(catalog?.readme?.ifBlank { catalog.description } ?: "", catalog, jobs), PageBlock.Text(copy.text("identifier", "id" to (entry.item.id).toString())), PageBlock.Text(copy.text("license", "license" to (entry.item.license).toString())))),
            PageBlock.Group(copy.text("selected_version"), buildList {
                add(PageBlock.Text(release?.let { "${it.version} · Android ${it.minSdk}+ · KIGTTS API ${it.apiVersion}+" } ?: copy.text("no_compatible")))
                release?.let { add(PageBlock.Text(copy.text("download.publisher", "hash" to it.expectedPublisher)))
                    add(PageBlock.Text(copy.text("download.package_size", "size" to ((it.bytes + 1023) / 1024).toString(), "mirrors" to it.mirrors.size.toString())))
                    if (it.dependencies.isNotEmpty()) add(PageBlock.Text(copy.text("download.requirements", "packages" to it.dependencies.entries.joinToString { dependency -> "${dependency.key} ${dependency.value}" }))) }
            })))
        else -> null
    }
}
