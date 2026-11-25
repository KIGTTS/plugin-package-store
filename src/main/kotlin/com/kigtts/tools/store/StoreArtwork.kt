package com.kigtts.tools.store

import com.kigtts.plugin.api.*

internal fun StoreJobs.image(catalog: PackageCatalog?, id: String?) = id?.let { catalog?.assets?.get(it) }?.let { artwork?.resource(it) }
internal fun StoreJobs.icon(catalog: PackageCatalog?, icon: CatalogIcon?) = image(catalog, icon?.image)?.copy(monochrome = icon?.monochrome == true)
internal fun storeItem(entry: CatalogEntry, catalog: PackageCatalog?, jobs: StoreJobs, subtitle: String? = null,
    group: String? = null, copy: StoreCopy = StoreCopy.Chinese): ManagementItem {
    val item = entry.item.localized(copy.locale)
    return ManagementItem(item.id, item.name, subtitle ?: item.summary, icon = if (item.icon == null) PluginIcon.NONE else PluginIcon.IMAGE,
        image = jobs.icon(catalog, item.icon), group = group ?: copy.categoryLabel(entry.category), primaryAction = "package/${entry.source.key}/${item.id}", symbol = item.icon?.symbol)
}
internal fun markdown(text: String, catalog: PackageCatalog?, jobs: StoreJobs) = PageBlock.Markdown(text,
    catalog?.assets.orEmpty().mapNotNull { (id, _) -> jobs.image(catalog, id)?.let { "asset:$id" to it } }.toMap())
internal fun thumbnails(entry: CatalogEntry, catalog: PackageCatalog?, jobs: StoreJobs) = PageBlock.Gallery(entry.item.thumbnails.mapNotNull { id ->
    jobs.image(catalog, id)?.let { resource -> catalog?.assets?.get(id)?.let { PageImage(resource, it.alt, it.width, it.height) } }
})
