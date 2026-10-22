package com.kigtts.tools.store

import com.kigtts.plugin.api.PluginOutcome

/** A repeated link opens the existing list, so the host acknowledges it without a failure toast. */
internal fun proposeSource(sources: SourceStore, view: StoreView, url: String): PluginOutcome {
    val address = CatalogCodec.https(url)
    val existing = sources.records().firstOrNull { it.url == address }
    if (existing != null) {
        view.sourceNotice = "这个源已经添加过了，可在下方查看。" + if (!existing.enabled) "需要使用时，打开它右侧的开关即可。" else ""
        return PluginOutcome.OpenPage("sources")
    }
    view.sourceNotice = ""
    sources.propose(address)
    return PluginOutcome.OpenPage("source-review")
}
