package com.kigtts.tools.store

import com.kigtts.plugin.api.PluginJson
import com.kigtts.plugin.api.PluginStorage
import kotlinx.serialization.json.*
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Catalogue hashes do not grant trust. Check archive identity and its actual signing key before host review. */
class ArtifactInspector(private val storage: PluginStorage) {
    fun inspect(file: File, entry: CatalogEntry, release: PackageRelease, cancelled: () -> Unit = {}): VerifiedArtifact = ZipFile(file).use { zip ->
        require(file.length() == release.bytes && file.sha256() == release.sha256) { "缓存文件已变化" }
        val entries = zip.entries().asSequence().toList()
        require(entries.size in 4..4096 && entries.map { it.name.lowercase() }.distinct().size == entries.size) { "包路径重复或数量无效" }
        require(entries.all { safeName(it.name) && it.size in 0..512L * 1024 * 1024 }) { "包包含不合法路径或大小" }
        require(entries.sumOf { it.size } <= 768L * 1024 * 1024) { "包解压后过大" }
        val manifestName = if (release.format == PackageFormat.NATIVE) "plugin.json" else "manifest.json"
        val raw = read(zip, manifestName, 256 * 1024)
        val manifest = PluginJson.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject
        require(manifest.string("id") == entry.item.id && manifest.string("version") == release.version && manifest.getValue("versionCode").jsonPrimitive.int == release.versionCode) { "包 ID 或版本与目录不一致" }
        require((manifest["apiVersion"]?.jsonPrimitive?.int ?: release.apiVersion) == release.apiVersion)
        require((manifest["minSdk"]?.jsonPrimitive?.int ?: release.minSdk) == release.minSdk)
        require(manifest["abis"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty().toSet() == release.abis.toSet()) { "包架构与目录不一致" }
        val keyBytes = read(zip, "signature/public.der", 8192)
        require(keyBytes.sha256() == release.expectedPublisher) { "包发布者与目录不一致" }
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
        require((key as java.security.interfaces.RSAPublicKey).modulus.bitLength() >= 2048)
        require(Signature.getInstance("SHA256withRSA").run { initVerify(key); update(raw); verify(read(zip, "signature/manifest.sig", 8192)) }) { "包签名不正确" }
        release.permissions?.let { advertised ->
            val actual = manifest[if (release.format == PackageFormat.NATIVE) "capabilities" else "permissions"]
                ?.jsonArray.orEmpty().map { it.jsonPrimitive.content }.filterNot { it == "ui" }.toSet()
            require(actual == advertised.toSet()) { "签名清单的权限与目录不同" }
            val purposes = manifest["permissionPurposes"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.content }
            require(release.permissionPurposes.all { (id, purpose) -> purposes[id] == purpose }) { "签名清单的权限用途与目录不同" }
        }
        val declared = manifest.getValue("files").jsonObject.mapValues { it.value.jsonPrimitive.content }
        manifest["dependencies"]?.jsonObject?.let { require(it.mapValues { entry -> entry.value.jsonPrimitive.content } == release.dependencies) { "签名清单的依赖与目录不同" } }
        val payload = entries.filter { !it.isDirectory && it.name !in setOf(manifestName, "signature/public.der", "signature/manifest.sig") }
        require(declared.keys == payload.map { it.name }.toSet() && declared.values.all(CatalogCodec.HASH::matches)) { "包文件清单不一致" }
        if (release.format == PackageFormat.NATIVE) {
            require(manifest.string("runtime") == "android-dex" && "code/classes.dex" in declared)
            require(entries.none { it.name.startsWith("code/oat") })
        } else {
            require(entries.size <= 132 && entries.sumOf { it.size } <= 16 * 1024 * 1024)
            require(raw.size <= 16 * 1024 && payload.size + 1 <= 128 && payload.all { it.size <= 8 * 1024 * 1024 })
            require(payload.any { it.name == "main.lua" && it.size in 1..256 * 1024L })
            require(payload.all { it.name == "main.lua" || it.name.startsWith("assets/") }) { "Lua 包只允许源码和 assets" }
            require(manifest["permissions"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }.all { it in setOf("app-control", "quick-text-read", "quick-text-write") })
            val source = read(zip, "main.lua", 256 * 1024)
            require(source.none { it == 0.toByte() } && source.firstOrNull() != 27.toByte()) { "Lua 包只接受文本源码" }
            Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(source))
        }
        payload.forEach { entry -> cancelled(); require(hash(zip, entry, cancelled) == declared.getValue(entry.name)) { "包资源校验失败" } }
        VerifiedArtifact(file, release.format, manifestName)
    }
    /** Lua runtime accepts sources and assets; signature metadata was already checked above, never evaluated. */
    fun luaResources(artifact: VerifiedArtifact, cancelled: () -> Unit = {}): File {
        require(artifact.format == PackageFormat.LUA)
        val destination = storage.directory("resources/lua-${UUID.randomUUID()}")
        try {
            ZipFile(artifact.file).use { zip -> zip.entries().asSequence().filter { !it.isDirectory && !it.name.startsWith("signature/") }.forEach { entry ->
                cancelled(); require(safeName(entry.name))
                val file = destination.resolve(entry.name); file.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> file.outputStream().use { input.copyTo(it) } }
            } }
            return destination
        } catch (failure: Throwable) { destination.deleteRecursively(); throw failure }
    }
    private fun read(zip: ZipFile, name: String, maximum: Int): ByteArray {
        val entry = zip.getEntry(name) ?: error("包缺少必要文件")
        require(entry.size in 1..maximum.toLong())
        return zip.getInputStream(entry).use { stream ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val count = stream.read(buffer); if (count < 0) break
                require(out.size() + count <= maximum); out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
    }
    private fun hash(zip: ZipFile, entry: ZipEntry, cancelled: () -> Unit): String = zip.getInputStream(entry).use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024); var bytes = 0L
        while (true) { cancelled(); val count = input.read(buffer); if (count < 0) break
            bytes += count; require(bytes <= entry.size); digest.update(buffer, 0, count)
        }
        require(bytes == entry.size); digest.digest().joinToString("") { "%02x".format(it) }
    }
    companion object {
        fun safeName(name: String): Boolean = name.length in 1..240 && !name.startsWith('/') && !name.contains('\\') && !name.contains(':') && !name.contains('\u0000') &&
            name.trimEnd('/').split('/').none { it.isEmpty() || it == "." || it == ".." }
    }
}
data class VerifiedArtifact(val file: File, val format: PackageFormat, val manifestName: String)
private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
