package com.kigtts.tools.store

import com.kigtts.plugin.api.*

internal fun storeSearchPage(sources: SourceStore, jobs: StoreJobs, view: StoreView, device: StoreDevice): PluginPage {
    val results = if (view.query.isBlank()) emptyList() else discover(sources.entries(),
        view.copy(filter = "all", category = null), jobs.installed, device)
    val visible = results.drop(view.offset).take(12)
    val navigation = buildList {
        if (view.offset > 0) add(PageAction("previous", "上一页", symbol = "chevron_left"))
        if (view.offset + visible.size < results.size) add(PageAction("next", "更多插件", symbol = "chevron_right"))
    }
    return PluginPage("search", "搜索插件", commonStatus(jobs) +
        if (navigation.isEmpty()) emptyList() else listOf(PageBlock.Actions(navigation)),
        management = ManagementList("search", visible.map { storeItem(it, sources.catalog(it.source.key), jobs) },
            emptyText = if (view.query.isBlank()) "搜索名称、介绍或分类" else "没有匹配的插件",
            style = ManagementListStyle.CATALOG, itemActions = downloadActions(visible, jobs, device)),
        search = PluginSearch(view.query, "search-save", "搜索插件"))
}
