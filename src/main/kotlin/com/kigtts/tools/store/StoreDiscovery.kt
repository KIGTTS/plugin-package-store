package com.kigtts.tools.store

import com.kigtts.plugin.api.*
import java.security.MessageDigest

/** Stable discovery order is independent of which plugin the user last opened or installed. */
internal fun discover(entries: List<CatalogEntry>, view: StoreView, installed: Map<String, InstalledPackage>, device: StoreDevice): List<CatalogEntry> = entries
    .filter { entry -> (view.source == null || entry.source.key == view.source) &&
        (view.category == null || entry.item.category == view.category) &&
        (view.query.isBlank() || (entry.item.name + " " + entry.item.summary + " " + entry.item.tags.joinToString()).contains(view.query, true)) &&
        when (view.filter) { "installed" -> entry.item.id in installed; "updates" -> entry.hasUpdate(installed, device); else -> true } }
    .groupBy { it.item.id }.values.map { choices -> choices.maxByOrNull { it.latest(device)?.let { release -> SemVersion.parse(release.version) } ?: SemVersion.parse("0.0.0") }!! }
    .sortedWith(compareBy<CatalogEntry> { it.item.category }.thenBy { it.item.name }.thenBy { it.item.id })

internal fun CatalogEntry.hasUpdate(installed: Map<String, InstalledPackage>, device: StoreDevice): Boolean =
    latest(device)?.let { latest -> installed[item.id]?.let { old -> (latest.apiVersion > old.apiVersion && SemVersion.parse(latest.version) >= SemVersion.parse(old.version)) ||
        SemVersion.parse(latest.version) > SemVersion.parse(old.version) || latest.format == PackageFormat.NATIVE && latest.versionCode > old.versionCode } } == true
internal fun categoryKey(category: String) = MessageDigest.getInstance("SHA-256").digest(category.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }
private fun storeAction(id: String, label: String, symbol: String) = PageAction(id, label, PluginIcon.NONE, symbol)

internal fun discoveryPage(sources: SourceStore, jobs: StoreJobs, view: StoreView, device: StoreDevice): PluginPage {
    val all = sources.entries()
    val entries = discover(all, view.copy(filter = "all", query = ""), jobs.installed, device)
    val categories = all.map { it.item.category }.distinct().sorted()
    val key = "store/${view.category?.let(::categoryKey) ?: "all"}"
    val visible = entries.take(storeLimit(view, key))
    val filters = listOf(ManagementFilter("all", "全部", "category/all", view.category == null, "apps")) +
        categories.map { category ->
            ManagementFilter(categoryKey(category), category, "category/${categoryKey(category)}",
                view.category == category, categorySymbol(category))
        }
    val updates = discover(all, StoreView(filter = "updates"), jobs.installed, device).size
    return PluginPage("store", "插件商店", listOf(storeHeaderActions(updates, jobs.downloads?.tasks.orEmpty().any { it.phase in setOf(StoreDownloadPhase.QUEUED, StoreDownloadPhase.DOWNLOADING, StoreDownloadPhase.VERIFYING) })) + commonStatus(jobs),
        ManagementList(key, visible.map { entry ->
            storeItem(entry, sources.catalog(entry.source.key), jobs)
        }, emptyText = if (all.isEmpty()) "暂无插件，请刷新目录" else "没有匹配的插件", filters = filters,
            style = ManagementListStyle.CATALOG, itemActions = downloadActions(visible, jobs, device),
            shortcuts = storeShortcuts(), loadMore = storeLoadMore(view, key, entries.size)), onEnterAction = "store-enter")
}

internal fun libraryPage(id: String, sources: SourceStore, jobs: StoreJobs, view: StoreView, device: StoreDevice): PluginPage {
    val updating = id == "updates"
    val entries = discover(sources.entries(), view.copy(filter = if (updating) "updates" else "installed", query = "", category = null, source = null), jobs.installed, device)
    val updates = discover(sources.entries(), StoreView(filter = "updates"), jobs.installed, device).size
    return PluginPage(id, if (updating) "插件更新" else "我的插件", commonStatus(jobs) + listOf(PageBlock.Actions(listOf(
        storeAction("browse", "发现插件", "storefront"), storeAction("refresh", "检查更新", "refresh"), storeAction("store-settings", "设置", "settings"))),
        PageBlock.Group("管理", listOf(PageBlock.Actions(listOfNotNull(
            if (updating) null else storeAction("updates", if (updates > 0) "插件更新 · $updates" else "插件更新", "upgrade"),
            storeAction("manage-plugins", "管理全部插件", "extension")))))),
        ManagementList(id, entries.map { entry -> storeItem(entry, sources.catalog(entry.source.key), jobs,
            entry.item.summary + "\n" + if (updating) "${jobs.installed[entry.item.id]?.version} → ${entry.latest(device)?.version}" else "已安装 ${jobs.installed[entry.item.id]?.version}",
            group = "已安装") } + if (updating) emptyList() else jobs.installed.values.filter { installed -> entries.none { it.item.id == installed.id } }.sortedBy { it.id }.map { installed ->
                ManagementItem(installed.id, installed.id, "已安装 ${installed.version} · 不在当前来源中", icon = PluginIcon.MENU, primaryAction = "manage-plugins")
            },
            emptyText = if (updating) "当前目录中的插件均已是最新版本" else "安装喜欢的插件后，会在这里找到它们", style = ManagementListStyle.CATALOG, itemActions = downloadActions(entries, jobs, device)))
}

internal fun storeSettingsPage(jobs: StoreJobs) = PluginPage("store-settings", "商店设置", commonStatus(jobs) + listOf(
    PageBlock.Group("内容来源", listOf(
        PageBlock.Actions(listOf(storeAction("sources", "插件来源", "source"))))),
    PageBlock.Group("下载与存储", listOf(PageBlock.Actions(listOf(storeAction("downloads", "下载管理", "download")))))
))
internal fun downloadsPage(jobs: StoreJobs) = PluginPage("download-storage", "存储空间", commonStatus(jobs) + listOf(
    PageBlock.Text("清除已下载的安装包，可释放存储空间。已安装的插件不受影响。"),
    PageBlock.Actions(listOf(storeAction("clear-cache", "清除安装包缓存", "cleaning_services")))
))
