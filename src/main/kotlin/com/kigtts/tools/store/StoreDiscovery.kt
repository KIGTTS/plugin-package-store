package com.kigtts.tools.store

import com.kigtts.plugin.api.*
import java.security.MessageDigest

/** Stable discovery order is independent of which plugin the user last opened or installed. */
internal fun discover(entries: List<CatalogEntry>, view: StoreView, installed: Map<String, InstalledPackage>, device: StoreDevice,
    copy: StoreCopy = StoreCopy.Chinese): List<CatalogEntry> = entries
    .filter { entry -> installed[entry.item.id]?.builtin != true && (view.source == null || entry.source.key == view.source) &&
        (view.category == null || entry.item.section == view.category) &&
        (view.query.isBlank() || entry.item.localized(copy.locale).let {
            listOf(it.name, it.summary, entry.item.name, entry.item.summary, entry.item.section, copy.sectionLabel(entry.item.section), entry.item.tags.joinToString()).any { field ->
                field.contains(view.query, ignoreCase = true)
            }
        }) &&
        when (view.filter) { "installed" -> entry.item.resourceType == null && entry.item.id in installed; "updates" -> entry.hasUpdate(installed, device); else -> true } }
    .groupBy { it.item.id }.values.map { choices -> choices.maxByOrNull { it.latest(device)?.let { release -> SemVersion.parse(release.version) } ?: SemVersion.parse("0.0.0") }!! }
    .sortedWith(compareBy<CatalogEntry> { it.item.section }.thenBy { it.item.name }.thenBy { it.item.id })

internal fun CatalogEntry.hasUpdate(installed: Map<String, InstalledPackage>, device: StoreDevice): Boolean =
    (if (item.resourceType != null) null else latest(device))?.let { release -> installed[item.id]?.let(release::isUpdateFrom) } == true
internal fun categoryKey(category: String) = MessageDigest.getInstance("SHA-256").digest(category.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }
private fun storeAction(id: String, label: String, symbol: String) = PageAction(id, label, PluginIcon.NONE, symbol)

internal fun discoveryPage(sources: SourceStore, jobs: StoreJobs, view: StoreView, device: StoreDevice, copy: StoreCopy = StoreCopy.Chinese): PluginPage {
    val all = sources.entries()
    val entries = discover(all, view.copy(filter = "all", query = ""), jobs.installed, device)
    val categories = all.map { it.item.section }.distinct().sorted()
    val key = "store/${view.category?.let(::categoryKey) ?: "all"}"
    val grouped = view.category == null
    val visible = if (grouped) entries.groupBy { it.item.section }.values.flatMap { it.take(3) } else entries.take(storeLimit(view, key))
    val filters = listOf(ManagementFilter("all", copy.text("all"), "category/all", view.category == null, "apps")) +
        categories.map { category ->
            ManagementFilter(categoryKey(category), copy.sectionLabel(category), "category/${categoryKey(category)}",
                view.category == category, categorySymbol(category))
        }
    val updates = discover(all, StoreView(filter = "updates"), jobs.installed, device).size
    return PluginPage("store", copy.text("store"), listOf(storeHeaderActions(updates, jobs.downloads?.tasks.orEmpty().any { it.phase in setOf(StoreDownloadPhase.QUEUED, StoreDownloadPhase.DOWNLOADING, StoreDownloadPhase.VERIFYING) }, copy)) + commonStatus(jobs, copy),
        ManagementList(key, visible.map { entry ->
            storeItem(entry, sources.catalog(entry.source.key), jobs, copy = copy)
        }, emptyText = if (all.isEmpty()) copy.text("empty_store") else copy.text("no_matches"), filters = filters,
            style = ManagementListStyle.CATALOG, itemActions = downloadActions(visible, jobs, device),
            shortcuts = storeShortcuts(copy), loadMore = if (grouped) null else storeLoadMore(view, key, entries.size),
            sectionActions = if (grouped) entries.map { it.item.section }.distinct().associate { category ->
                copy.sectionLabel(category) to PageAction("open-catalog/${catalogPageId(category = category)}", copy.text("more"), symbol = "chevron_right")
            } else emptyMap()), onEnterAction = "store-enter")
}

internal fun libraryPage(id: String, sources: SourceStore, jobs: StoreJobs, view: StoreView, device: StoreDevice, copy: StoreCopy = StoreCopy.Chinese): PluginPage {
    val updating = id == "updates"
    val entries = discover(sources.entries(), view.copy(filter = if (updating) "updates" else "installed", query = "", category = null, source = null), jobs.installed, device)
    val updates = discover(sources.entries(), StoreView(filter = "updates"), jobs.installed, device).size
    return PluginPage(id, if (updating) copy.text("updates") else copy.text("library"), commonStatus(jobs, copy) + listOf(PageBlock.Actions(listOf(
        storeAction("browse", copy.text("browse"), "storefront"), storeAction("refresh", copy.text("check_updates"), "refresh"), storeAction("store-settings", copy.text("settings"), "settings"))),
        PageBlock.Group(copy.text("manage"), listOf(PageBlock.Actions(listOfNotNull(
            if (updating) null else storeAction("updates", if (updates > 0) copy.text("updates") + " · $updates" else copy.text("updates"), "upgrade"),
            storeAction("manage-plugins", copy.text("manage_all"), "extension")))))),
        ManagementList(id, entries.map { entry -> storeItem(entry, sources.catalog(entry.source.key), jobs,
            entry.item.localized(copy.locale).summary + "\n" + if (updating) "${jobs.installed[entry.item.id]?.version} → ${entry.latest(device)?.version}" else copy.text("installed_version", "version" to jobs.installed[entry.item.id]?.version.orEmpty()),
            group = copy.text("installed"), copy = copy) } + if (updating) emptyList() else jobs.installed.values.filter { installed -> !installed.builtin && entries.none { it.item.id == installed.id } }.sortedBy { it.id }.map { installed ->
                ManagementItem(installed.id, installed.id, copy.text("installed_other", "version" to (installed.version).toString()), icon = PluginIcon.MENU, primaryAction = "manage-plugins")
            },
            emptyText = if (updating) copy.text("up_to_date") else copy.text("empty_library"), style = ManagementListStyle.CATALOG, itemActions = downloadActions(entries, jobs, device)))
}

internal fun storeSettingsPage(jobs: StoreJobs, copy: StoreCopy = StoreCopy.Chinese) = PluginPage("store-settings", copy.text("store_settings"), commonStatus(jobs, copy) + listOf(
    PageBlock.Group(copy.text("content_sources"), listOf(
        PageBlock.Actions(listOf(storeAction("sources", copy.text("plugin_sources"), "source"))))),
    PageBlock.Group(copy.text("download_storage"), listOf(PageBlock.Actions(listOf(storeAction("downloads", copy.text("downloads"), "download")))))
))
internal fun downloadsPage(jobs: StoreJobs, copy: StoreCopy = StoreCopy.Chinese) = PluginPage("download-storage", copy.text("storage"), commonStatus(jobs, copy) + listOf(
    PageBlock.Text(copy.text("cache_info")),
    PageBlock.Actions(listOf(storeAction("clear-cache", copy.text("clear_cache"), "cleaning_services")))
))
