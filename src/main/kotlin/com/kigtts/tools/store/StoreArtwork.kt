package com.kigtts.tools.store

import com.kigtts.plugin.api.*

internal fun StoreJobs.image(catalog: PackageCatalog?, id: String?) = id?.let { catalog?.assets?.get(it) }?.let { artwork?.resource(it) }
internal fun storeItem(entry: CatalogEntry, catalog: PackageCatalog?, jobs: StoreJobs, subtitle: String = entry.item.summary, group: String = entry.item.category) = ManagementItem(
    entry.item.id, entry.item.name, subtitle, icon = if (entry.item.icon == null) PluginIcon.NONE else PluginIcon.IMAGE,
    image = jobs.image(catalog, entry.item.icon?.image), group = group, primaryAction = "package/${entry.source.key}/${entry.item.id}", symbol = entry.item.icon?.symbol)
internal fun markdown(text: String, catalog: PackageCatalog?, jobs: StoreJobs) = PageBlock.Markdown(text,
    catalog?.assets.orEmpty().mapNotNull { (id, _) -> jobs.image(catalog, id)?.let { "asset:$id" to it } }.toMap())
internal fun thumbnails(entry: CatalogEntry, catalog: PackageCatalog?, jobs: StoreJobs) = PageBlock.Gallery(entry.item.thumbnails.mapNotNull { id ->
    jobs.image(catalog, id)?.let { PageImage(it, catalog?.assets?.get(id)?.alt.orEmpty()) }
})
