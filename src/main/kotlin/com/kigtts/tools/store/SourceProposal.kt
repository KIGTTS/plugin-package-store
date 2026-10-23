package com.kigtts.tools.store

import com.kigtts.plugin.api.PluginOutcome

/** A repeated link opens the existing list, so the host acknowledges it without a failure toast. */
internal fun proposeSource(sources: SourceStore, view: StoreView, url: String, packageId: String? = null): PluginOutcome {
    require(packageId == null || CatalogCodec.ID.matches(packageId)) { "插件标识不合法" }
    val address = CatalogCodec.https(url)
    view.linkTarget = packageId?.let { SourcePackageTarget(address, it) }
    view.selected = null; view.selectedVersion = null
    val existing = sources.records().firstOrNull { it.url == address }
    if (existing != null) {
        if (packageId != null) return PluginOutcome.OpenPage("package")
        view.sourceNotice = "这个源已经添加过了，可在下方查看。" + if (!existing.enabled) "需要使用时，打开它右侧的开关即可。" else ""
        return PluginOutcome.OpenPage("sources")
    }
    view.sourceNotice = ""
    sources.propose(address, packageId)
    return PluginOutcome.OpenPage("source-review")
}
