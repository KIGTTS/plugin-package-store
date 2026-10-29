package com.kigtts.tools.store

import com.kigtts.plugin.api.*

internal const val STORE_PAGE_SIZE = 12
internal fun catalogPageId(source: String? = null, category: String? = null) = "catalog/${source ?: "all"}/${category?.let(::categoryKey) ?: "all"}"
internal fun storeHeaderActions(updates: Int = 0, downloading: Boolean = false) = PageBlock.Actions(listOf(
    PageAction("search", "搜索", symbol = "search"), PageAction("library", "我的插件", symbol = when { downloading -> "download"; updates > 0 -> "upgrade"; else -> "widgets" }, badge = updateBadge(updates)),
    PageAction("downloads", "下载管理", symbol = "download"), PageAction("store-settings", "设置", symbol = "settings")))

internal fun updateBadge(updates: Int): PluginBadge? = updates.takeIf { it > 0 }?.let { PluginBadge(it, "$it 个插件可更新") }

internal fun storeShortcuts() = listOf(
    PageAction("section/categories", "分类", PluginIcon.NONE, "category"),
    PageAction("section/source-sections", "来源", PluginIcon.NONE, "source"),
    PageAction("open-catalog/${catalogPageId()}", "所有插件", PluginIcon.NONE, "apps"),
    PageAction("section/resources", "资源", PluginIcon.NONE, "collections_bookmark"))

internal fun storeLimit(view: StoreView, id: String) = (view.catalogLimits[id] ?: STORE_PAGE_SIZE).coerceIn(STORE_PAGE_SIZE, 1000)
internal fun storeLoadMore(view: StoreView, id: String, count: Int): ManagementLoadMore? =
    storeLimit(view, id).takeIf { it < count && it < 1000 }?.let { ManagementLoadMore("load-more/$id/$it") }
internal fun appendStoreBatch(view: StoreView, id: String, expected: Int) {
    if (storeLimit(view, id) == expected) view.catalogLimits = view.catalogLimits + (id to (expected + STORE_PAGE_SIZE).coerceAtMost(1000))
}

internal fun sourceSectionItem(source: SourceRecord, sources: SourceStore, jobs: StoreJobs): ManagementItem {
    val catalog = sources.catalog(source.key)
    return ManagementItem(source.key, catalog?.name ?: source.name, icon = PluginIcon.NONE,
        symbol = catalog?.icon?.symbol, image = jobs.icon(catalog, catalog?.icon), primaryAction = "section/categories/${source.key}")
}

internal fun sectionPage(id: String, sources: SourceStore, jobs: StoreJobs): PluginPage {
    if (id == "resources" || id == "resource-sources") return resourceSectionsPage(id, sources, jobs)
    if (id == "source-sections") return PluginPage(id, "插件来源", commonStatus(jobs),
        ManagementList(id, sources.records().filter { it.enabled }.map { sourceSectionItem(it, sources, jobs) },
            emptyText = "还没有可浏览的来源", style = ManagementListStyle.CATALOG))
    val key = id.substringAfter('/', "").takeIf { it.isNotEmpty() }
    val source = key?.let { value -> sources.records().firstOrNull { it.key == value && it.enabled } }
    if (key != null && source == null) return PluginPage(id, "插件来源", listOf(PageBlock.Notice("这个来源已停用或移除。")))
    val categories = sources.entries().filter { key == null || it.source.key == key }.map { it.item.section }.distinct().sorted()
    return PluginPage(id, source?.name ?: "插件分类", commonStatus(jobs), ManagementList(id,
        listOf(ManagementItem("all", "所有插件", icon = PluginIcon.NONE, symbol = "apps", primaryAction = "open-catalog/${catalogPageId(key)}")) +
            categories.map { category -> ManagementItem(categoryKey(category), category, icon = PluginIcon.NONE,
                symbol = categorySymbol(category), primaryAction = "open-catalog/${catalogPageId(key, category)}") },
        emptyText = "暂无分类", style = ManagementListStyle.CATALOG), header = source?.let { sourceSectionItem(it, sources, jobs).copy(primaryAction = null) })
}

internal fun categorySymbol(category: String) = when (category) {
    "音频" -> "graphic_eq"; "外观" -> "palette"; "内容" -> "library_books"; "输入" -> "keyboard"
    "开发" -> "code"; "扩展" -> "extension"; else -> ResourceType.entries.firstOrNull { it.label == category }?.symbol ?: "category"
}

internal fun catalogScope(id: String, sources: SourceStore): StoreView? {
    val parts = id.split('/')
    if (parts.size != 3 || parts[0] != "catalog") return null
    val source = parts[1].takeUnless { it == "all" }
    if (source != null && sources.records().none { it.key == source && it.enabled }) return null
    val category = if (parts[2] == "all") null else (sources.entries().firstOrNull {
        (source == null || it.source.key == source) && categoryKey(it.item.section) == parts[2]
    }?.item?.section ?: ResourceType.entries.firstOrNull { categoryKey(it.label) == parts[2] }?.label ?: return null)
    return StoreView(source = source, category = category)
}

internal fun catalogListingPage(id: String, sources: SourceStore, jobs: StoreJobs, view: StoreView, device: StoreDevice): PluginPage {
    val scope = catalogScope(id, sources) ?: return PluginPage(id, "插件商店", listOf(PageBlock.Notice("这个分区暂时没有可浏览的插件。")))
    val entries = discover(sources.entries(), scope, jobs.installed, device)
    val visible = entries.take(storeLimit(view, id))
    return PluginPage(id, scope.category ?: scope.source?.let { sources.source(it).name } ?: "所有插件",
        commonStatus(jobs), ManagementList(id, visible.map { storeItem(it, sources.catalog(it.source.key), jobs) },
            emptyText = "暂无插件", style = ManagementListStyle.CATALOG, itemActions = downloadActions(visible, jobs, device),
            loadMore = storeLoadMore(view, id, entries.size)))
}
