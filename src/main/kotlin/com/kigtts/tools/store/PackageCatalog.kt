package com.kigtts.tools.store

import com.kigtts.plugin.api.PLUGIN_API_VERSION
import com.kigtts.plugin.api.PluginJson
import com.kigtts.plugin.api.ResourceType
import kotlinx.serialization.Serializable
import java.net.URI

@Serializable enum class PackageFormat { NATIVE, LUA, RESOURCE }
@Serializable enum class SourceKind { PLUGINS, RESOURCES }
@Serializable data class PackageMirror(val name: String, val url: String)
@Serializable data class PackageRelease(val version: String, val versionCode: Int, val format: PackageFormat,
    val sha256: String, val bytes: Long, val expectedPublisher: String, val mirrors: List<PackageMirror>,
    val apiVersion: Int = PLUGIN_API_VERSION, val minSdk: Int = 26, val abis: List<String> = emptyList(), val notes: String = "",
    val dependencies: Map<String, String> = emptyMap(),
    val permissions: List<String>? = null, val permissionPurposes: Map<String, String> = emptyMap())
@Serializable data class CatalogPackage(val id: String, val name: String, val summary: String, val releases: List<PackageRelease>,
    val category: String = "扩展", val tags: List<String> = emptyList(), val license: String = "MIT", val homepage: String = "",
    val icon: CatalogIcon? = null, val description: String = "", val thumbnails: List<String> = emptyList(),
    val resourceType: ResourceType? = null) {
    val section get() = resourceType?.label ?: category
}
@Serializable data class SourceLinkPolicy(val requireAddition: Boolean = false, val recommendAfterInstall: Boolean = false)
@Serializable data class PackageCatalog(val schemaVersion: Int = 1, val id: String, val name: String,
    val packages: List<CatalogPackage>, val description: String = "", val homepage: String = "", val updatedAt: String = "",
    val icon: CatalogIcon? = null, val readme: String = "", val assets: Map<String, CatalogImage> = emptyMap(),
    val kind: SourceKind = SourceKind.PLUGINS, val linkPolicy: SourceLinkPolicy = SourceLinkPolicy())

object CatalogCodec {
    const val MAX_BYTES = 4 * 1024 * 1024
    val HASH = Regex("[a-f0-9]{64}")
    val ID = Regex("[a-z][a-z0-9.-]{0,63}")
    fun parse(bytes: ByteArray): PackageCatalog {
        require(bytes.size <= MAX_BYTES) { "源目录超过 4 MiB" }
        val catalog = PluginJson.decodeFromString<PackageCatalog>(bytes.toString(Charsets.UTF_8))
        validate(catalog); return catalog
    }
    fun validate(catalog: PackageCatalog) {
        require(catalog.schemaVersion in 1..2) { "请更新插件商店以打开这个源" }
        require(catalog.schemaVersion == 2 || catalog.kind == SourceKind.PLUGINS)
        require(ID.matches(catalog.id) && catalog.name.length in 1..80 && catalog.description.length <= 1000)
        validateArtwork(catalog)
        if (catalog.homepage.isNotEmpty()) https(catalog.homepage)
        require(catalog.packages.size <= 500 && catalog.packages.map { it.id }.distinct().size == catalog.packages.size) { "源内包 ID 重复或数量超过 500" }
        catalog.packages.forEach { item ->
            require(item.resourceType == null || catalog.schemaVersion == 2)
            require(catalog.kind != SourceKind.RESOURCES || item.resourceType != null) { "资源源只能包含资源包" }
            require(ID.matches(item.id) && item.name.length in 1..80 && item.summary.length <= 1000 && item.category.length in 1..40)
            require(item.tags.size <= 16 && item.tags.all { it.length in 1..40 } && item.license.length in 1..80)
            if (item.homepage.isNotEmpty()) https(item.homepage)
            require(item.releases.size in 1..64 && item.releases.map { it.versionCode }.distinct().size == item.releases.size) { "包版本号重复或版本数量无效" }
            require(item.releases.map { it.version }.distinct().size == item.releases.size) { "包版本名称重复" }
            item.releases.forEach { release ->
                require((release.format == PackageFormat.RESOURCE) == (item.resourceType != null)) { "资源类型与安装包格式不一致" }
                if (release.format == PackageFormat.RESOURCE) {
                    require(release.apiVersion >= 29 && release.abis.isEmpty() && release.permissions.orEmpty().isEmpty() && release.permissionPurposes.isEmpty())
                }
                require(release.version.length in 1..64 && release.versionCode > 0)
                SemVersion.parse(release.version)
                require(HASH.matches(release.sha256) && HASH.matches(release.expectedPublisher)) { "包与发布者的 SHA-256 必须填写 64 位小写十六进制值" }
                require(release.bytes in 1..256L * 1024 * 1024 && release.notes.length <= 2000)
                require(release.apiVersion > 0 && release.minSdk in 26..99)
                require(release.abis.size <= 8 && release.abis.all { it in setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") })
                require(release.mirrors.size in 1..8 && release.mirrors.map { https(it.url) }.distinct().size == release.mirrors.size) { "镜像重复或数量无效" }
                release.mirrors.forEach { require(it.name.length in 1..80) }
                require(release.dependencies.size <= 32 && release.dependencies.keys.all(ID::matches))
                release.dependencies.values.forEach(VersionRange::parse)
                validatePermissions(release)
                if (release.format == PackageFormat.LUA) require("runtime.lua" in release.dependencies) { "Lua 包需声明 runtime.lua 前置依赖" }
            }
        }
    }
    fun https(value: String): String {
        require(value.length in 1..2048)
        val uri = URI(value.trim())
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "地址需为不含帐号和片段的 HTTPS URL" }
        return uri.toASCIIString()
    }
}
data class CatalogEntry(val source: SourceRecord, val item: CatalogPackage)
data class InstalledPackage(val id: String, val version: String, val versionCode: Int, val enabled: Boolean, val publisher: String? = null,
    val apiVersion: Int = PLUGIN_API_VERSION, val format: PackageFormat = PackageFormat.NATIVE)
data class StoreDevice(val sdk: Int, val abis: Set<String>, val api: Int = PLUGIN_API_VERSION,
    val minimumApi: Int = if (api in 10..PLUGIN_API_VERSION) com.kigtts.plugin.api.MIN_PLUGIN_API_VERSION else api)
internal fun PackageRelease.compatible(device: StoreDevice) = apiVersion in device.minimumApi..device.api && minSdk <= device.sdk && (abis.isEmpty() || abis.any { it in device.abis })
internal fun CatalogEntry.latest(device: StoreDevice) = item.releases.filter { it.compatible(device) && SemVersion.parse(it.version).pre.isEmpty() }.maxByOrNull { SemVersion.parse(it.version) }
