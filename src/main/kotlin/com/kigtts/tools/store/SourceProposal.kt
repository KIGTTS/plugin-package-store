package com.kigtts.tools.store

import com.kigtts.plugin.api.PluginOutcome

/** A repeated link opens the existing list, so the host acknowledges it without a failure toast. */
internal fun proposeSource(sources: SourceStore, view: StoreView, url: String, packageId: String? = null, requireAddition: Boolean = true, copy: StoreCopy = StoreCopy.Chinese): PluginOutcome {
    require(packageId == null || CatalogCodec.ID.matches(packageId)) { throw StoreSourceIssue("source.issue.package_id", "插件标识不合法") }
    require(packageId == null || !isPausedPackage(packageId)) { throw StoreSourceIssue("source.issue.paused", "此插件暂不可用") }
    val address = CatalogCodec.https(url)
    view.linkTarget = packageId?.let { SourcePackageTarget(address, it) }
    view.selected = null; view.selectedVersion = null
    val existing = sources.records().firstOrNull { it.url == address }
    if (existing != null) {
        if (packageId != null) return PluginOutcome.OpenPage("package")
        view.sourceNotice = copy.text("source.preview.duplicate") + if (!existing.enabled) " " + copy.text("source.preview.switch") else ""
        return PluginOutcome.OpenPage("sources")
    }
    view.sourceNotice = ""
    if (packageId != null && !requireAddition) {
        sources.preview(address)
        return PluginOutcome.OpenPage("package")
    }
    sources.propose(address, packageId)
    return PluginOutcome.OpenPage("source-review")
}
