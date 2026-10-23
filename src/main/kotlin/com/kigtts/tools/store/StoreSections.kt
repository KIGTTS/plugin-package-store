package com.kigtts.tools.store

import com.kigtts.plugin.api.*

internal const val STORE_PAGE_SIZE = 12
internal fun catalogPageId(source: String? = null, category: String? = null) = "catalog/${source ?: "all"}/${category?.let(::categoryKey) ?: "all"}"
internal fun storeHeaderActions() = PageBlock.Actions(listOf(
    PageAction("search", "搜索", symbol = "search"), PageAction("library", "我的", symbol = "apps"),
    PageAction("downloads", "下载管理", symbol = "download"), PageAction("store-settings", "设置", symbol = "settings")))

internal fun storeHomePage(jobs: StoreJobs) = PluginPage("store", "插件商店", listOf(storeHeaderActions()) + commonStatus(jobs),
    ManagementList("store-sections", listOf(
        ManagementItem("categories", "按分类浏览", icon = PluginIcon.NONE, symbol = "category", primaryAction = "section/categories"),
        ManagementItem("sources", "按来源浏览", icon = PluginIcon.NONE, symbol = "source", primaryAction = "section/source-sections"),
        ManagementItem("all", "所有插件", icon = PluginIcon.NONE, symbol = "apps", primaryAction = "open-catalog/${catalogPageId()}")
    ), style = ManagementListStyle.CATALOG))

internal fun sourceSectionItem(source: SourceRecord, sources: SourceStore, jobs: StoreJobs): ManagementItem {
    val catalog = sources.catalog(source.key)
    return ManagementItem(source.key, catalog?.name ?: source.name, icon = PluginIcon.NONE,
        symbol = catalog?.icon?.symbol, image = jobs.image(catalog, catalog?.icon?.image), primaryAction = "section/categories/${source.key}")
}

internal fun sectionPage(id: String, sources: SourceStore, jobs: StoreJobs): PluginPage {
    if (id == "source-sections") return PluginPage(id, "插件来源", commonStatus(jobs),
        ManagementList(id, sources.records().filter { it.enabled }.map { sourceSectionItem(it, sources, jobs) },
            emptyText = "还没有可浏览的来源", style = ManagementListStyle.CATALOG))
    val key = id.substringAfter('/', "").takeIf { it.isNotEmpty() }
    val source = key?.let { value -> sources.records().firstOrNull { it.key == value && it.enabled } }
    if (key != null && source == null) return PluginPage(id, "插件来源", listOf(PageBlock.Notice("这个来源已停用或移除。")))
    val categories = sources.entries().filter { key == null || it.source.key == key }.map { it.item.category }.distinct().sorted()
    return PluginPage(id, source?.name ?: "插件分类", commonStatus(jobs), ManagementList(id,
        listOf(ManagementItem("all", "所有插件", icon = PluginIcon.NONE, symbol = "apps", primaryAction = "open-catalog/${catalogPageId(key)}")) +
            categories.map { category -> ManagementItem(categoryKey(category), category, icon = PluginIcon.NONE,
                symbol = categorySymbol(category), primaryAction = "open-catalog/${catalogPageId(key, category)}") },
        emptyText = "暂无分类", style = ManagementListStyle.CATALOG), header = source?.let { sourceSectionItem(it, sources, jobs).copy(primaryAction = null) })
}

private fun categorySymbol(category: String) = when (category) {
    "音频" -> "graphic_eq"; "外观" -> "palette"; "内容" -> "library_books"; "输入" -> "keyboard"
    "开发" -> "code"; "扩展" -> "extension"; else -> "category"
}

internal fun catalogScope(id: String, sources: SourceStore): StoreView? {
    val parts = id.split('/')
    if (parts.size != 3 || parts[0] != "catalog") return null
    val source = parts[1].takeUnless { it == "all" }
    if (source != null && sources.records().none { it.key == source && it.enabled }) return null
    val category = if (parts[2] == "all") null else (sources.entries().firstOrNull {
        (source == null || it.source.key == source) && categoryKey(it.item.category) == parts[2]
    }?.item?.category ?: return null)
    return StoreView(source = source, category = category)
}

internal fun catalogListingPage(id: String, sources: SourceStore, jobs: StoreJobs, view: StoreView, device: StoreDevice): PluginPage {
    val scope = catalogScope(id, sources) ?: return PluginPage(id, "插件商店", listOf(PageBlock.Notice("这个分区暂时没有可浏览的插件。")))
    val entries = discover(sources.entries(), scope, jobs.installed, device)
    val pageCount = ((entries.size + STORE_PAGE_SIZE - 1) / STORE_PAGE_SIZE).coerceAtLeast(1)
    val page = (view.catalogPages[id] ?: 0).coerceIn(0, pageCount - 1)
    val visible = entries.drop(page * STORE_PAGE_SIZE).take(STORE_PAGE_SIZE)
    val navigation = buildList {
        if (page > 0) add(PageAction("catalog-previous/$id", "上一页", symbol = "chevron_left"))
        if (page + 1 < pageCount) add(PageAction("catalog-next/$id", "下一页", symbol = "chevron_right"))
    }
    return PluginPage(id, scope.category ?: scope.source?.let { sources.source(it).name } ?: "所有插件",
        commonStatus(jobs) + if (navigation.isEmpty()) emptyList() else listOf(PageBlock.Group("第 ${page + 1} / $pageCount 页", listOf(PageBlock.Actions(navigation)))),
        ManagementList("$id/$page", visible.map { storeItem(it, sources.catalog(it.source.key), jobs) },
            emptyText = "暂无插件", style = ManagementListStyle.CATALOG, itemActions = downloadActions(visible, jobs, device)))
}
