package com.kigtts.tools.store

import com.kigtts.plugin.api.*

/** A source invitation is shown only after a confirmed installation/import. */
internal fun sourceRecommendation(sources: SourceStore, jobs: StoreJobs, entry: CatalogEntry, copy: StoreCopy = StoreCopy.Chinese): List<PageBlock> {
    val completed = jobs.downloads?.tasks?.any {
        it.phase == StoreDownloadPhase.INSTALLED && it.root.entry.source.url == entry.source.url && it.root.entry.item.id == entry.item.id
    } == true
    if (!completed || !sources.recommends(entry)) return emptyList()
    return listOf(PageBlock.Group(copy.text("source.invite.title"), listOf(PageBlock.Text(copy.text("source.invite.body", "name" to sources.sourceName(entry.source, copy.locale))),
        PageBlock.Actions(listOf(PageAction("recommend-source", copy.text("source.invite.add"), PluginIcon.ADD))))))
}
