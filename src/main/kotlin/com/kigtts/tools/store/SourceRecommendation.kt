package com.kigtts.tools.store

import com.kigtts.plugin.api.*

/** A source invitation is shown only after a confirmed installation/import. */
internal fun sourceRecommendation(sources: SourceStore, jobs: StoreJobs, entry: CatalogEntry): List<PageBlock> {
    val completed = jobs.downloads?.tasks?.any {
        it.phase == StoreDownloadPhase.INSTALLED && it.root.entry.source.url == entry.source.url && it.root.entry.item.id == entry.item.id
    } == true
    if (!completed || !sources.recommends(entry)) return emptyList()
    return listOf(PageBlock.Group("喜欢这个来源？", listOf(PageBlock.Text("添加 ${entry.source.name}，方便下次发现更多内容。"),
        PageBlock.Actions(listOf(PageAction("recommend-source", "添加来源", PluginIcon.ADD))))))
}
