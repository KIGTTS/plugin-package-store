package com.kigtts.tools.store

import com.kigtts.plugin.api.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StoreHeaderSearchTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun detailsAndListingUseTheSameTaskButHistoricVersionsRetainTheirOwnDownload() = runTest {
        val env = object : PluginEnvironment {
            override val storage = PluginStorage(temp.root); override val assets = temp.root
            override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("unused")
        }
        val sources = SourceStore(env.storage, emptyList())
        val source = sources.confirm(sources.propose("https://example.invalid/source.json").token)
        sources.fetched(source.key, catalog("test.source", entry("test.plugin", release(), release("2.0.0", 2)).item))
        val transport = object : StoreTransport {
            override suspend fun fetch(url: String, destination: File, maximumBytes: Long, progress: (Long) -> Unit) = error("page reads cannot fetch")
        }
        val downloader = PackageDownloader(env.storage, transport)
        val jobs = StoreJobs(env, sources, testDevice, downloader, ArtifactInspector(env.storage), {}, this)
        jobs.downloads = StoreDownloads(env, sources, testDevice, downloader, ArtifactInspector(env.storage), {}, backgroundScope)
        val entry = sources.entries().single(); val view = StoreView(selected = entry, selectedVersion = 2)
        assertEquals(downloadActions(listOf(entry), jobs, testDevice)[entry.item.id], storePage("package", sources, jobs, view, testDevice)!!.headerAction)
        jobs.downloads!!.offer(entry, 2, emptyMap())
        val detail = storePage("package", sources, jobs, view, testDevice)!!
        assertEquals("确认", detail.headerAction!!.label)
        assertEquals(downloadActions(listOf(entry), jobs, testDevice)[entry.item.id], detail.headerAction)
        val old = storePage("package", sources, jobs, view.copy(selectedVersion = 1), testDevice)!!
        assertEquals("download-get/${source.key}/test.plugin/1", old.headerAction!!.action)
        assertFalse(detail.blocks.filterIsInstance<PageBlock.Group>().flatMap { it.children }.filterIsInstance<PageBlock.Actions>()
            .flatMap { it.actions }.any { it.id == "prepare" })
        val search = storePage("search", sources, jobs, view.copy(query = "test.plugin"), testDevice)!!
        assertEquals(PluginSearch("test.plugin", "search-save", "搜索插件"), search.search)
        assertEquals(listOf("test.plugin"), search.management!!.items.map { it.id })
        assertEquals(1, storePage("store", sources, jobs, view.copy(query = "unmatched"), testDevice)!!.management!!.items.size)
        assertEquals(1, storePage(catalogPageId(), sources, jobs, view.copy(query = "unmatched"), testDevice)!!.management!!.items.size)
        jobs.downloads!!.close()
    }
}
