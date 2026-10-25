package com.kigtts.tools.store

import com.kigtts.plugin.api.PLUGIN_API_VERSION
import com.kigtts.plugin.api.PluginJson
import kotlinx.serialization.json.*
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal val testDevice = StoreDevice(36, setOf("arm64-v8a"))
internal fun release(version: String = "1.0.0", code: Int = 1, dependencies: Map<String, String> = emptyMap(), publisher: String = "a".repeat(64)) = PackageRelease(
    version, code, PackageFormat.NATIVE, "b".repeat(64), 100, publisher, listOf(PackageMirror("镜像", "https://example.invalid/plugin.kigplugin")), dependencies = dependencies)
internal fun entry(id: String, vararg versions: PackageRelease, source: String = "source-a") = CatalogEntry(
    SourceRecord(key = source, url = "https://example.invalid/$source.json", name = source), CatalogPackage(id, id, "测试包", versions.toList()))
internal fun catalog(id: String = "test.source", vararg packages: CatalogPackage) = PackageCatalog(id = id, name = "测试源", packages = packages.toList())

internal data class SignedFixture(val file: File, val entry: CatalogEntry, val release: PackageRelease)
internal fun signedFixture(directory: File, format: PackageFormat = PackageFormat.NATIVE, archiveVersion: String = "1.0.0", id: String = "test.plugin",
    extra: Map<String, ByteArray> = emptyMap()): SignedFixture {
    val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val manifestName = when(format) { PackageFormat.NATIVE -> "plugin.json"; PackageFormat.LUA -> "manifest.json"; PackageFormat.RESOURCE -> "resource.json" }
    val payload = if (format == PackageFormat.RESOURCE) mapOf("content.json" to "{\"payload\":\"data/cards.kigcards\"}".toByteArray(), "data/cards.kigcards" to "fixture".toByteArray()) + extra else linkedMapOf(if (format == PackageFormat.NATIVE) "code/classes.dex" to "dex fixture".toByteArray() else "main.lua" to "return {}".toByteArray()) + extra
    val manifest = buildJsonObject {
        if(format == PackageFormat.RESOURCE) { put("schemaVersion", 1); put("resourceType", "QUICK_TEXT"); put("content", "content.json") }; put("id", id); put("name", "测试"); put("version", archiveVersion); put("versionCode", 1)
        put("apiVersion", PLUGIN_API_VERSION); put("minSdk", 26); put("runtime", "android-dex")
        put("permissions", JsonArray(emptyList())); put("files", buildJsonObject { payload.forEach { (name, bytes) -> put(name, bytes.sha256()) } })
    }.toString().toByteArray(Charsets.UTF_8)
    val signature = Signature.getInstance("SHA256withRSA").run { initSign(key.private); update(manifest); sign() }
    val file = File(directory, "fixture-${System.nanoTime()}.zip")
    ZipOutputStream(file.outputStream()).use { out ->
        val all = payload + mapOf(manifestName to manifest, "signature/public.der" to key.public.encoded, "signature/manifest.sig" to signature)
        all.forEach { (name, bytes) -> out.putNextEntry(ZipEntry(name)); out.write(bytes); out.closeEntry() }
    }
    val version = release().copy(format = format, sha256 = file.sha256(), bytes = file.length(), expectedPublisher = key.public.encoded.sha256())
    return SignedFixture(file, entry(id, version).let { if(format == PackageFormat.RESOURCE) it.copy(item = it.item.copy(resourceType = com.kigtts.plugin.api.ResourceType.QUICK_TEXT)) else it }, version)
}
