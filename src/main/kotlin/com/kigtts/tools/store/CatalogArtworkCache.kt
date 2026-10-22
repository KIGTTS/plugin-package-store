package com.kigtts.tools.store

import com.kigtts.plugin.api.PluginResource
import com.kigtts.plugin.api.PluginStorage
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Metadata and artwork are ready before page entry. Hash-addressed cache survives source updates. */
class CatalogArtworkCache(private val storage: PluginStorage, private val transport: StoreTransport) {
    fun resource(image: CatalogImage): PluginResource? {
        val path = "catalog-art/${image.sha256}.img"
        return storage.file(path).takeIf { it.isFile && it.length() == image.bytes }?.let { PluginResource(path) }
    }
    suspend fun preload(catalog: PackageCatalog) {
        for (image in catalog.assets.values.distinctBy { it.sha256 }) {
            currentCoroutineContext().ensureActive()
            val destination = storage.file("catalog-art/${image.sha256}.img")
            if (destination.isFile && destination.length() == image.bytes && destination.sha256() == image.sha256) continue
            val temporary = storage.file("tmp/art-${UUID.randomUUID()}.part")
            try {
                transport.fetch(image.url, temporary, image.bytes)
                currentCoroutineContext().ensureActive()
                require(temporary.length() == image.bytes && temporary.sha256() == image.sha256) { "图片校验失败" }
                require(isBitmap(temporary)) { "商店图片需为PNG、JPEG或WebP" }
                destination.parentFile!!.mkdirs()
                Files.move(temporary.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { currentCoroutineContext().ensureActive() /* A failed image never hides a valid package. */ }
            finally { temporary.delete() }
        }
        var total = storage.file("catalog-art").listFiles().orEmpty().sumOf { it.length() }
        storage.file("catalog-art").listFiles().orEmpty().sortedBy { it.lastModified() }.forEach {
            val size = it.length()
            if (total > 128L * 1024 * 1024 && it.delete()) total -= size
        }
    }
    private fun isBitmap(file: File): Boolean {
        val bytes = file.inputStream().use { input ->
            val header = ByteArray(12); var count = 0
            while (count < header.size) { val read = input.read(header, count, header.size - count); if (read < 0) break; count += read }
            header.copyOf(count)
        }
        return bytes.take(8).toByteArray().contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) ||
            bytes.size >= 3 && bytes[0].toInt() == -1 && bytes[1].toInt() == -40 && bytes[2].toInt() == -1 ||
            bytes.size == 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
    }
}
