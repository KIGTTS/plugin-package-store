package com.kigtts.tools.store

import com.kigtts.plugin.api.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CatalogArtworkTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun monochromeRegistrationSurvivesCatalogAndHostResourceMapping() = runTest {
        val bytes = "<svg xmlns=\"http://www.w3.org/2000/svg\"><path d=\"M0 0h24v24z\"/></svg>".toByteArray()
        val image = CatalogImage("https://example.invalid/icon", bytes.sha256(), bytes.size.toLong())
        val icon = CatalogIcon(image = "glyph", monochrome = true)
        val value = catalog("test.source", entry("test.plugin", release()).item.copy(icon = icon))
            .copy(icon = icon, assets = mapOf("glyph" to image))
        val parsed = CatalogCodec.parse(PluginJson.encodeToString(PackageCatalog.serializer(), value).toByteArray())
        assertTrue(parsed.icon!!.monochrome); assertTrue(parsed.packages.single().icon!!.monochrome)
        assertThrows(IllegalArgumentException::class.java) { CatalogCodec.validate(value.copy(icon = CatalogIcon(symbol = "extension", monochrome = true))) }
        val storage = PluginStorage(temporary.root)
        val transport = object : StoreTransport {
            override suspend fun fetch(url: String, destination: File, maximumBytes: Long, progress: (Long) -> Unit) {
                destination.parentFile!!.mkdirs(); destination.writeBytes(bytes)
            }
        }
        val cache = CatalogArtworkCache(storage, transport)
        cache.preload(parsed)
        assertNotNull(cache.resource(image))
        val environment = object : PluginEnvironment {
            override val storage = storage
            override val assets = temporary.root
            override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("unused")
        }
        val jobs = StoreJobs(environment, SourceStore(storage), testDevice, PackageDownloader(storage, transport),
            ArtifactInspector(storage), {}, backgroundScope, cache)
        assertTrue(jobs.icon(parsed, parsed.icon)!!.monochrome)
        assertTrue(storeItem(entry("test.plugin", release()).copy(item = parsed.packages.single()), parsed, jobs).image!!.monochrome)
        assertFalse(jobs.image(parsed, "glyph")!!.monochrome)
    }
    @Test fun optionalMetadataRoundTripsAndRejectsUnregisteredArtwork() {
        val image = CatalogImage("https://example.invalid/icon.png", "a".repeat(64), 32)
        val item = entry("test.plugin", release()).item.copy(icon = CatalogIcon("extension", "icon"),
            description = "## 介绍\n\n**Markdown**", thumbnails = listOf("icon"))
        val value = catalog("test.source", item).copy(readme = "# 源介绍", assets = mapOf("icon" to image))
        assertEquals(value, CatalogCodec.parse(PluginJson.encodeToString(PackageCatalog.serializer(), value).toByteArray()))
        assertThrows(IllegalArgumentException::class.java) { CatalogCodec.validate(value.copy(assets = emptyMap())) }
        CatalogCodec.validate(catalog("old.source", entry("old.plugin", release()).item))
    }
    @Test fun failedHashDoesNotExposeAnImageAndVerifiedCacheNeedsNoSecondDownload() = runTest {
        val bytes = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 0)
        val storage = PluginStorage(temporary.root)
        var fetches = 0
        val cache = CatalogArtworkCache(storage, object : StoreTransport {
            override suspend fun fetch(url: String, destination: File, maximumBytes: Long, progress: (Long) -> Unit) {
                fetches++; destination.parentFile!!.mkdirs(); destination.writeBytes(bytes)
            }
        })
        val good = CatalogImage("https://example.invalid/icon.png", bytes.sha256(), bytes.size.toLong())
        val bad = good.copy(sha256 = "f".repeat(64))
        cache.preload(catalog().copy(assets = mapOf("good" to good, "bad" to bad)))
        assertNotNull(cache.resource(good)); assertNull(cache.resource(bad))
        cache.preload(catalog().copy(assets = mapOf("good" to good)))
        assertEquals(2, fetches)
    }
}
