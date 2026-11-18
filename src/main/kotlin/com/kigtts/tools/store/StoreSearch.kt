package com.kigtts.tools.store

import com.kigtts.plugin.api.*

internal fun storeSearchPage(sources: SourceStore, jobs: StoreJobs, view: StoreView, device: StoreDevice, copy: StoreCopy = StoreCopy.Chinese): PluginPage {
    val results = if (view.query.isBlank()) emptyList() else discover(sources.entries(),
        view.copy(filter = "all", category = null), jobs.installed, device, copy)
    val key = "search/${categoryKey(view.query)}"
    val visible = results.take(storeLimit(view, key))
    return PluginPage("search", copy.text("search_plugins"), commonStatus(jobs, copy),
        management = ManagementList(key, visible.map { storeItem(it, sources.catalog(it.source.key), jobs, copy = copy) },
            emptyText = if (view.query.isBlank()) copy.text("search_hint") else copy.text("no_results"),
            style = ManagementListStyle.CATALOG, itemActions = downloadActions(visible, jobs, device, copy),
            loadMore = storeLoadMore(view, key, results.size)),
        search = PluginSearch(view.query, "search-save", copy.text("search_plugins")))
}
