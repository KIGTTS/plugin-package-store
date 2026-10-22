package com.kigtts.tools.store

import kotlinx.serialization.Serializable

/** Referenced by stable asset ID; display metadata never changes package identity or trust. */
@Serializable data class CatalogIcon(val symbol: String? = null, val image: String? = null)
@Serializable data class CatalogImage(val url: String, val sha256: String, val bytes: Long, val alt: String = "")

internal fun validateArtwork(catalog: PackageCatalog) {
    require(catalog.assets.size <= 128 && catalog.assets.values.sumOf { it.bytes.coerceAtLeast(0) } <= 16L * 1024 * 1024) { "源图片超过16MiB或128项" }
    require(catalog.readme.length <= 32000) { "源介绍过长" }
    catalog.assets.forEach { (id, image) ->
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) { "图片标识不合法" }
        CatalogCodec.https(image.url)
        require(CatalogCodec.HASH.matches(image.sha256) && image.bytes in 1..2L * 1024 * 1024 && image.alt.length <= 160) { "图片需明确SHA-256、大小和有效说明" }
    }
    fun icon(value: CatalogIcon?) { value?.let {
        require(it.symbol != null || it.image != null) { "图标需提供symbol或image" }
        it.symbol?.let { symbol -> require(symbol.matches(Regex("[a-z0-9_]{1,80}"))) { "Material Symbols名称不合法" } }
        it.image?.let { asset -> require(asset in catalog.assets) { "图标引用的图片未注册" } }
    } }
    icon(catalog.icon)
    catalog.packages.forEach { item ->
        icon(item.icon)
        require(item.description.length <= 32000 && item.thumbnails.size <= 8 && item.thumbnails.distinct().size == item.thumbnails.size) { "插件介绍或缩略图数量无效" }
        require(item.thumbnails.all { it in catalog.assets }) { "缩略图引用的图片未注册" }
    }
}
