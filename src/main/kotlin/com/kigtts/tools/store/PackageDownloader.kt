package com.kigtts.tools.store

import com.kigtts.plugin.api.PluginStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class DownloadState(val packageName: String, val mirrorName: String, val bytes: Long, val total: Long)
class PackageDownloader(private val storage: PluginStorage, private val transport: StoreTransport) {
    suspend fun catalog(source: SourceRecord): PackageCatalog {
        val temporary = storage.file("tmp/source-${UUID.randomUUID()}.json")
        try {
            transport.fetch(source.url, temporary, CatalogCodec.MAX_BYTES.toLong())
            currentCoroutineContext().ensureActive()
            return CatalogCodec.parse(temporary.readBytes())
        } finally { temporary.delete() }
    }
    suspend fun packageFile(entry: CatalogEntry, release: PackageRelease, progress: (DownloadState) -> Unit): File {
        val extension = when (release.format) { PackageFormat.NATIVE -> "kigplugin"; PackageFormat.LUA -> "kiglua"; PackageFormat.RESOURCE -> "kigresource" }
        val file = storage.file("downloads/${release.sha256}.$extension")
        if (file.isFile && file.length() == release.bytes && file.sha256() == release.sha256) return file
        file.delete()
        for (mirror in release.mirrors) {
            currentCoroutineContext().ensureActive()
            val temporary = storage.file("tmp/package-${UUID.randomUUID()}.part")
            try {
                transport.fetch(mirror.url, temporary, release.bytes) { bytes -> progress(DownloadState(entry.item.name, mirror.name, bytes, release.bytes)) }
                currentCoroutineContext().ensureActive()
                require(temporary.length() == release.bytes && temporary.sha256() == release.sha256) { "下载内容与源目录校验值不一致" }
                file.parentFile?.mkdirs()
                java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                return file
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { currentCoroutineContext().ensureActive() /* Next mirror is the same content, never an install retry. */ }
            finally { temporary.delete() }
        }
        error("所有镜像均下载失败或校验不符，请稍后重试")
    }
    fun cancel() = transport.cancel()
    fun clearDownloads() {
        storage.downloads.listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() }
    }
}
internal fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
internal fun File.sha256(): String = inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024)
    while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
    digest.digest().joinToString("") { "%02x".format(it) }
}
