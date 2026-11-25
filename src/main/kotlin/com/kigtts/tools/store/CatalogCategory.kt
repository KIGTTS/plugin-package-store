package com.kigtts.tools.store

import com.kigtts.plugin.api.ResourceType
import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.util.Locale

/** This is a navigation category. ResourceType still identifies the signed package payload. */
@Serializable data class CatalogCategory(
    val id: String,
    val name: String,
    val kind: SourceKind = SourceKind.PLUGINS,
    val symbol: String? = null,
    val localizations: Map<String, String> = emptyMap(),
)

private val pluginCategories = mapOf(
    "音频" to "audio", "外观" to "appearance", "内容" to "content",
    "输入" to "input", "开发" to "development", "扩展" to "extensions",
)

internal fun ResourceType.categoryId() = "resource." + name.lowercase(Locale.ROOT).replace('_', '-')

internal fun knownCategoryId(label: String): String {
    ResourceType.entries.firstOrNull { it.label == label }?.let { return it.categoryId() }
    return pluginCategories[label]?.let { "plugin.$it" } ?: label
}

private fun legacyPluginId(label: String): String {
    val normalized = Normalizer.normalize(label.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    return pluginCategories[normalized]?.let { "plugin.$it" } ?: "plugin.legacy." + categoryKey(normalized)
}

internal fun legacyCategory(item: CatalogPackage): CatalogCategory =
    item.resourceType?.let { CatalogCategory(it.categoryId(), it.label, SourceKind.RESOURCES, it.symbol) }
        ?: CatalogCategory(legacyPluginId(item.category), item.category)

internal fun PackageCatalog.category(item: CatalogPackage): CatalogCategory =
    categories.firstOrNull { it.id == item.categoryId } ?: legacyCategory(item)

internal fun PackageCatalog.availableCategories(): List<CatalogCategory> =
    categories + packages.filter { it.categoryId == null }.map(::legacyCategory).distinctBy(CatalogCategory::id)
        .filter { implicit -> categories.none { it.id == implicit.id } }

internal fun StoreCopy.categoryLabel(category: CatalogCategory): String {
    val lang = Locale.forLanguageTag(locale)
    val local = sequenceOf(lang.toLanguageTag(), lang.language).mapNotNull { tag ->
        category.localizations.entries.firstOrNull { it.key.equals(tag, true) }?.value
    }.firstOrNull()
    if (local != null) return local
    val originalType = ResourceType.entries.firstOrNull { it.categoryId() == category.id && it.label == category.name }
    if (originalType != null) return resourceTypeLabel(originalType)
    val originalPlugin = pluginCategories[category.name]
    return if (originalPlugin != null && category.id == "plugin.$originalPlugin") text("category.$originalPlugin") else category.name
}

internal fun validateCategories(catalog: PackageCatalog) {
    require(catalog.categories.size <= 96 && catalog.categories.map { it.id }.distinct().size == catalog.categories.size)
    catalog.categories.forEach { value ->
        require(CatalogCodec.ID.matches(value.id) && value.name.isNotBlank() && value.name.length <= 80)
        require(value.symbol == null || value.symbol.matches(Regex("[a-z][a-z0-9_]{0,47}")))
        require(value.localizations.size <= 32 && value.localizations.keys.map { it.lowercase(Locale.ROOT) }.distinct().size == value.localizations.size)
        require(value.localizations.all { (tag, name) ->
            tag.matches(Regex("[a-zA-Z]{2,8}(-[a-zA-Z0-9]{1,8}){0,5}")) && name.isNotBlank() && name.length <= 80
        })
        val prefix = if (value.kind == SourceKind.RESOURCES) "resource." else "plugin."
        require(value.id.startsWith(prefix) && value.id.length > prefix.length)
    }
    val declared = catalog.categories.associateBy { it.id }
    catalog.packages.forEach { item ->
        val id = item.categoryId ?: return@forEach
        val kind = if (item.resourceType == null) SourceKind.PLUGINS else SourceKind.RESOURCES
        require(declared[id]?.kind == kind)
    }
}
