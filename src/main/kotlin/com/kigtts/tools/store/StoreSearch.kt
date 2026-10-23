package com.kigtts.tools.store

import com.kigtts.plugin.api.*

internal fun storeSearchPage(sources: SourceStore, jobs: StoreJobs, view: StoreView, device: StoreDevice): PluginPage {
    val results = if (view.query.isBlank()) emptyList() else discover(sources.entries(),
        view.copy(filter = "all", category = null), jobs.installed, device)
    val key = "search/${categoryKey(view.query)}"
    val visible = results.take(storeLimit(view, key))
    return PluginPage("search", "搜索插件", commonStatus(jobs),
        management = ManagementList(key, visible.map { storeItem(it, sources.catalog(it.source.key), jobs) },
            emptyText = if (view.query.isBlank()) "搜索名称、介绍或分类" else "没有匹配的插件",
            style = ManagementListStyle.CATALOG, itemActions = downloadActions(visible, jobs, device),
            loadMore = storeLoadMore(view, key, results.size)),
        search = PluginSearch(view.query, "search-save", "搜索插件"))
}
