package com.kigtts.tools.store

import com.kigtts.plugin.api.*

internal const val STORE_PAGE_SIZE = 12
internal fun catalogPageId(source: String? = null, category: String? = null) = "catalog/${source ?: "all"}/${category?.let(::categoryKey) ?: "all"}"
internal fun storeHeaderActions(updates: Int = 0, downloading: Boolean = false, copy: StoreCopy = StoreCopy.Chinese) = PageBlock.Actions(listOf(
    PageAction("search", copy.text("search"), symbol = "search"), PageAction("library", copy.text("library"), symbol = when { downloading -> "download"; updates > 0 -> "upgrade"; else -> "widgets" }, badge = updateBadge(updates, copy)),
    PageAction("downloads", copy.text("downloads"), symbol = "download"), PageAction("store-settings", copy.text("settings"), symbol = "settings")))

internal fun updateBadge(updates: Int, copy: StoreCopy = StoreCopy.Chinese): PluginBadge? = updates.takeIf { it > 0 }?.let { PluginBadge(it, copy.text("update_count", "count" to (it).toString())) }

internal fun storeShortcuts(copy: StoreCopy = StoreCopy.Chinese) = listOf(
    PageAction("section/categories", copy.text("categories"), PluginIcon.NONE, "category"),
    PageAction("section/source-sections", copy.text("sources"), PluginIcon.NONE, "source"),
    PageAction("open-catalog/${catalogPageId()}", copy.text("all_plugins"), PluginIcon.NONE, "apps"),
    PageAction("section/resources", copy.text("resources"), PluginIcon.NONE, "collections_bookmark"))

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

internal fun sectionPage(id: String, sources: SourceStore, jobs: StoreJobs, copy: StoreCopy = StoreCopy.Chinese): PluginPage {
    if (id == "resources" || id == "resource-sources") return resourceSectionsPage(id, sources, jobs)
    if (id == "source-sections") return PluginPage(id, copy.text("plugin_sources"), commonStatus(jobs, copy),
        ManagementList(id, sources.records().filter { it.enabled }.map { sourceSectionItem(it, sources, jobs) },
            emptyText = copy.text("empty_sources"), style = ManagementListStyle.CATALOG))
    val key = id.substringAfter('/', "").takeIf { it.isNotEmpty() }
    val source = key?.let { value -> sources.records().firstOrNull { it.key == value && it.enabled } }
    if (key != null && source == null) return PluginPage(id, copy.text("plugin_sources"), listOf(PageBlock.Notice(copy.text("source_gone"))))
    val categories = sources.entries().filter { key == null || it.source.key == key }.map { it.item.section }.distinct().sorted()
    return PluginPage(id, source?.name ?: copy.text("plugin_categories"), commonStatus(jobs, copy), ManagementList(id,
        listOf(ManagementItem("all", copy.text("all_plugins"), icon = PluginIcon.NONE, symbol = "apps", primaryAction = "open-catalog/${catalogPageId(key)}")) +
            categories.map { category -> ManagementItem(categoryKey(category), category, icon = PluginIcon.NONE,
                symbol = categorySymbol(category), primaryAction = "open-catalog/${catalogPageId(key, category)}") },
        emptyText = copy.text("empty_categories"), style = ManagementListStyle.CATALOG), header = source?.let { sourceSectionItem(it, sources, jobs).copy(primaryAction = null) })
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

internal fun catalogListingPage(id: String, sources: SourceStore, jobs: StoreJobs, view: StoreView, device: StoreDevice, copy: StoreCopy = StoreCopy.Chinese): PluginPage {
    val scope = catalogScope(id, sources) ?: return PluginPage(id, copy.text("store"), listOf(PageBlock.Notice(copy.text("unavailable_section"))))
    val entries = discover(sources.entries(), scope, jobs.installed, device)
    val visible = entries.take(storeLimit(view, id))
    return PluginPage(id, scope.category ?: scope.source?.let { sources.source(it).name } ?: copy.text("all_plugins"),
        commonStatus(jobs, copy), ManagementList(id, visible.map { storeItem(it, sources.catalog(it.source.key), jobs, copy = copy) },
            emptyText = copy.text("no_plugins"), style = ManagementListStyle.CATALOG, itemActions = downloadActions(visible, jobs, device),
            loadMore = storeLoadMore(view, id, entries.size)))
}
