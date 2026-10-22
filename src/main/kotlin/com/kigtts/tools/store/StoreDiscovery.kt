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
    latest(device)?.let { latest -> installed[item.id]?.let { old -> old.apiVersion != latest.apiVersion ||
        SemVersion.parse(latest.version) > SemVersion.parse(old.version) || latest.format == PackageFormat.NATIVE && latest.versionCode > old.versionCode } } == true
internal fun categoryKey(category: String) = MessageDigest.getInstance("SHA-256").digest(category.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }
private fun storeAction(id: String, label: String, symbol: String) = PageAction(id, label, PluginIcon.NONE, symbol)

internal fun discoveryPage(sources: SourceStore, jobs: StoreJobs, view: StoreView, device: StoreDevice): PluginPage {
    val all = sources.entries()
    val entries = discover(all, view.copy(filter = "all"), jobs.installed, device)
    val categories = all.map { it.item.category }.distinct().sorted()
    val blocks = listOf(PageBlock.Actions(listOf(
        storeAction("search", "搜索", "search"), storeAction("library", "我的", "apps"), storeAction("store-settings", "设置", "settings")
    ))) + commonStatus(jobs) + listOfNotNull(
        view.query.takeIf { it.isNotBlank() }?.let { PageBlock.Text("“$it”的搜索结果") },
        categories.takeIf { it.isNotEmpty() }?.let { PageBlock.Select("分类", listOf(storeAction("category/all", "全部", "category")) + it.map { category -> storeAction("category/${categoryKey(category)}", category, "extension") }, view.category?.let { "category/${categoryKey(it)}" } ?: "category/all", symbol = "category") },
        view.source?.let { key -> PageBlock.Group("来源", listOf(PageBlock.Actions(listOf(storeAction("source-filter/all", "${sources.records().firstOrNull { it.key == key }?.name ?: "当前来源"} · 显示全部来源", "filter_alt_off"))))) }
    ) + if (entries.isEmpty()) listOf(PageBlock.Group("发现插件", listOf(
        PageBlock.Actions(listOf(storeAction("refresh", "刷新插件目录", "refresh")))
    ))) else emptyList()
    val navigation = buildList {
        if (view.offset > 0) add(storeAction("previous", "上一页", "chevron_left"))
        if (view.offset + 12 < entries.size) add(storeAction("next", "更多插件", "chevron_right"))
    }
    return PluginPage("store", "插件商店", blocks + if (navigation.isEmpty()) emptyList() else listOf(
        PageBlock.Group("浏览", listOf(PageBlock.Actions(navigation)))),
        ManagementList("store", entries.drop(view.offset).take(12).map { entry ->
            storeItem(entry, sources.catalog(entry.source.key), jobs)
        }, emptyText = if (all.isEmpty()) "暂无插件，请刷新目录" else "没有匹配的插件", style = ManagementListStyle.CATALOG))
}

internal fun libraryPage(id: String, sources: SourceStore, jobs: StoreJobs, view: StoreView, device: StoreDevice): PluginPage {
    val updating = id == "updates"
    val entries = discover(sources.entries(), view.copy(filter = if (updating) "updates" else "installed", query = "", category = null, source = null), jobs.installed, device)
    val updates = discover(sources.entries(), StoreView(filter = "updates"), jobs.installed, device).size
    return PluginPage(id, if (updating) "插件更新" else "我的插件", commonStatus(jobs) + listOf(PageBlock.Actions(listOf(
        storeAction("browse", "发现插件", "storefront"), storeAction("refresh", "检查更新", "refresh"), storeAction("store-settings", "设置", "settings"))),
        PageBlock.Group("管理", listOf(PageBlock.Actions(listOfNotNull(
            if (updating) null else storeAction("updates", if (updates > 0) "插件更新 · $updates" else "插件更新", "system_update"),
            storeAction("manage-plugins", "管理全部插件", "extension")))))),
        ManagementList(id, entries.map { entry -> storeItem(entry, sources.catalog(entry.source.key), jobs,
            entry.item.summary + "\n" + if (updating) "${jobs.installed[entry.item.id]?.version} → ${entry.latest(device)?.version}" else "已安装 ${jobs.installed[entry.item.id]?.version}",
            group = "已安装") } + if (updating) emptyList() else jobs.installed.values.filter { installed -> entries.none { it.item.id == installed.id } }.sortedBy { it.id }.map { installed ->
                ManagementItem(installed.id, installed.id, "已安装 ${installed.version} · 不在当前来源中", icon = PluginIcon.MENU, primaryAction = "manage-plugins")
            },
            emptyText = if (updating) "当前目录中的插件均已是最新版本" else "安装喜欢的插件后，会在这里找到它们", style = ManagementListStyle.CATALOG))
}

internal fun storeSettingsPage(jobs: StoreJobs) = PluginPage("store-settings", "商店设置", commonStatus(jobs) + listOf(
    PageBlock.Group("内容来源", listOf(
        PageBlock.Actions(listOf(storeAction("sources", "插件来源", "source"), storeAction("source-select", "筛选浏览来源", "filter_alt"))))),
    PageBlock.Group("下载与存储", listOf(PageBlock.Actions(listOf(storeAction("downloads", "下载与缓存", "download")))))
))
internal fun downloadsPage(jobs: StoreJobs) = PluginPage("downloads", "下载与缓存", commonStatus(jobs) + listOf(
    PageBlock.Text("插件下载完成后会核对完整性、发布者与依赖，安装前保留确认步骤。"),
    PageBlock.Actions(listOf(storeAction("clear-cache", "清除安装包缓存", "cleaning_services")))
))
