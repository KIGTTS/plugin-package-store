package com.kigtts.tools.store

import com.kigtts.plugin.api.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class StoreEntryRefreshTest {
    @get:Rule val temp = TemporaryFolder()
    private fun environment() = object : PluginEnvironment {
        override val storage = PluginStorage(temp.root)
        override val assets = temp.root
        override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("unused")
    }
    @Test fun eachVisibleVisitRefreshesEnabledSourcesAndCoalescesBusyEntries() = runTest {
        val env = environment(); val sources = SourceStore(env.storage, emptyList())
        val enabled = sources.confirm(sources.propose("https://example.invalid/enabled.json").token)
        val disabled = sources.confirm(sources.propose("https://example.invalid/disabled.json").token)
        sources.toggle(disabled.key)
        val cached = catalog("test.source", entry("old.plugin", release()).item)
        sources.fetched(enabled.key, cached)
        var calls = 0; var offline = false
        val transport = object : StoreTransport {
            override suspend fun fetch(url: String, destination: File, maximumBytes: Long, progress: (Long) -> Unit) {
                assertEquals(enabled.url, url); calls++
                if (offline) throw java.io.IOException("offline")
                destination.parentFile!!.mkdirs()
                destination.writeText(PluginJson.encodeToString(catalog("test.source", entry("new.plugin", release()).item)))
            }
        }
        val jobs = StoreJobs(env, sources, testDevice, PackageDownloader(env.storage, transport), ArtifactInspector(env.storage), {}, this)
        val before = storePage("store", sources, jobs, StoreView(), testDevice)!!
        assertEquals("refresh", before.refreshAction)
        assertFalse(before.loading)
        jobs.reloadInstalled(); jobs.entered(); jobs.entered()
        assertEquals("old.plugin", sources.entries().single().item.id)
        advanceUntilIdle()
        assertEquals(1, calls); assertFalse(jobs.busy)
        assertEquals("new.plugin", sources.entries().single().item.id)
        jobs.entered(); jobs.entered(); advanceUntilIdle()
        assertEquals(2, calls)
        jobs.entered()
        assertTrue(jobs.refreshing)
        val updating = storePage("store", sources, jobs, StoreView(), testDevice)!!
        assertTrue(updating.loading)
        assertFalse(updating.blocks.filterIsInstance<PageBlock.Notice>().any { it.text.contains("正在更新源") })
        assertEquals(listOf("search", "library", "downloads", "store-settings"), updating.blocks.filterIsInstance<PageBlock.Actions>().flatMap { it.actions }.map { it.id })
        // Repeated manual pulls and visible entries use the same coalesced request.
        jobs.entered(); advanceUntilIdle()
        assertEquals(3, calls); assertFalse(jobs.refreshing)
        offline = true; jobs.entered(); advanceUntilIdle()
        assertEquals(4, calls); assertEquals("new.plugin", sources.entries().single().item.id)
        assertTrue(sources.source(enabled.key).error.isNotEmpty())
        assertEquals("store-enter", storePage("store", sources, jobs, StoreView(), testDevice)!!.onEnterAction)
        listOf("store", "library", "updates", "sources").forEach { id ->
            val page = storePage(id, sources, jobs, StoreView(), testDevice)!!
            assertEquals("refresh", page.refreshAction); assertFalse(page.loading)
        }
        assertNull(storePage("store-settings", sources, jobs, StoreView(), testDevice)!!.refreshAction)
    }
}
