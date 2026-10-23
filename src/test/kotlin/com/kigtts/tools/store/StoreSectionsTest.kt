package com.kigtts.tools.store

import com.kigtts.plugin.api.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StoreSectionsTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun partitionsPageWithoutMixingSourcesOrRememberedPositions() = runTest {
        val environment = object : PluginEnvironment {
            override val storage = PluginStorage(temp.root); override val assets = temp.root
            override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("unused")
        }
        val sources = SourceStore(environment.storage, emptyList())
        val a = sources.confirm(sources.propose("https://example.invalid/a.json").token)
        val b = sources.confirm(sources.propose("https://example.invalid/b.json").token)
        val packages = (0..26).map { entry("test.a${it.toString().padStart(2, '0')}", release()).item.copy(category = "音频") }
        sources.fetched(a.key, catalog("a.source", *packages.toTypedArray()).copy(icon = CatalogIcon(symbol = "graphic_eq")))
        sources.fetched(b.key, catalog("b.source", entry("test.b", release()).item.copy(category = "创作")))
        val transport = object : StoreTransport {
            override suspend fun fetch(url: String, destination: File, maximumBytes: Long, progress: (Long) -> Unit) = error("page reads cannot fetch")
        }
        val jobs = StoreJobs(environment, sources, testDevice, PackageDownloader(environment.storage, transport), ArtifactInspector(environment.storage), {}, backgroundScope)
        val id = catalogPageId(a.key, "音频")
        val view = StoreView(query = "unmatched")
        fun page(key: String) = storePage(key, sources, jobs, view, testDevice)!!
        val home = page("store").management!!
        assertEquals(listOf("分类", "来源", "所有分类"), home.shortcuts.map { it.label })
        assertEquals(12, home.items.size); assertNotNull(home.loadMore)
        assertEquals(listOf("全部", "创作", "音频"), home.filters.map { it.label })
        assertEquals("graphic_eq", page("source-sections").management!!.items.first { it.id == a.key }.symbol)
        assertEquals(listOf("所有插件", "音频"), page("categories/${a.key}").management!!.items.map { it.title })
        assertEquals(packages.take(12).map { it.id }, page(id).management!!.items.map { it.id })
        appendStoreBatch(view, id, 12)
        appendStoreBatch(view, id, 12) // Stale duplicate cannot skip a batch.
        assertEquals(packages.take(24).map { it.id }, page(id).management!!.items.map { it.id })
        assertEquals(12, page(catalogPageId()).management!!.items.size)
        assertEquals(listOf("test.b"), page(catalogPageId(b.key)).management!!.items.map { it.id })
        appendStoreBatch(view, id, 24)
        assertEquals(27, page(id).management!!.items.size)
        assertNull(page(id).management!!.loadMore)
        assertEquals("refresh", page(id).refreshAction)
        sources.fetched(a.key, catalog("a.source", *packages.take(2).toTypedArray()))
        assertEquals(packages.take(2).map { it.id }, page(id).management!!.items.map { it.id })
        sources.toggle(a.key)
        assertNull(page(id).management)
        assertFalse(page("source-sections").management!!.items.any { it.id == a.key })
    }
    @Test fun verifiedSvgSourceIconsUseHashCacheWithoutASecondFetch() = runTest {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\"><circle cx=\"12\" cy=\"12\" r=\"10\"/></svg>".toByteArray()
        val storage = PluginStorage(temp.root)
        var fetches = 0
        val cache = CatalogArtworkCache(storage, object : StoreTransport {
            override suspend fun fetch(url: String, destination: File, maximumBytes: Long, progress: (Long) -> Unit) {
                fetches++; destination.parentFile!!.mkdirs(); destination.writeBytes(svg)
            }
        })
        val image = CatalogImage("https://example.invalid/icon.svg", svg.sha256(), svg.size.toLong())
        val catalog = catalog().copy(icon = CatalogIcon(image = "source"), assets = mapOf("source" to image))
        CatalogCodec.validate(catalog)
        cache.preload(catalog); cache.preload(catalog)
        assertNotNull(cache.resource(image)); assertEquals(1, fetches)
        CatalogCodec.validate(catalog.copy(icon = null))
    }
}
