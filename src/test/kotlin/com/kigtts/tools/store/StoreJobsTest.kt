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
class StoreJobsTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun environment() = object : PluginEnvironment {
        override val storage = PluginStorage(temporary.root); override val assets = temporary.root
        override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("disabled")
    }
    @Test fun initialLocalCacheIsAvailableWithoutContactingSource() = runTest {
        val env = environment(); val sources = SourceStore(env.storage, builtinSources = emptyList())
        val record = sources.confirm(sources.propose("https://example.invalid/source.json").token)
        sources.fetched(record.key, catalog("test.source", entry("test.plugin", release()).item))
        var fetched = 0
        val transport = object : StoreTransport {
            override suspend fun fetch(url: String, destination: File, maximumBytes: Long, progress: (Long) -> Unit) { fetched++ }
        }
        val jobs = StoreJobs(env, SourceStore(env.storage, builtinSources = emptyList()), testDevice, PackageDownloader(env.storage, transport), ArtifactInspector(env.storage), {}, this)
        jobs.reloadInstalled(); advanceUntilIdle()
        assertEquals(0, fetched); assertFalse(jobs.busy)
        val page = storePage("store", SourceStore(env.storage, builtinSources = emptyList()), jobs, StoreView(), testDevice)!!
        assertEquals("test.plugin", page.management!!.items.single().title)
        assertEquals(sources.entries().single().item.summary, page.management!!.items.single().subtitle)
        assertEquals(ManagementListStyle.CATALOG, page.management!!.style)
        assertEquals(listOf("search", "library", "store-settings"), page.blocks.filterIsInstance<PageBlock.Actions>().single().actions.map { it.id })
        assertFalse(page.blocks.any { it is PageBlock.Input })
        val detail = storePage("package", sources, jobs, StoreView(selected = sources.entries().single()), testDevice)!!
        assertTrue(detail.blocks.filterIsInstance<PageBlock.Group>().flatMap { it.children }.filterIsInstance<PageBlock.Markdown>().any { it.text == sources.entries().single().item.summary })
    }
    @Test fun immediatelyCompletedWorkerDoesNotLeaveUiPermanentlyBusy() {
        val env = environment(); val transport = object : StoreTransport {
            override suspend fun fetch(url: String, destination: File, maximumBytes: Long, progress: (Long) -> Unit) = error("unused")
        }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
        val jobs = StoreJobs(env, SourceStore(env.storage, builtinSources = emptyList()), testDevice, PackageDownloader(env.storage, transport), ArtifactInspector(env.storage), {}, scope)
        jobs.reloadInstalled(); assertFalse(jobs.busy)
        kotlinx.coroutines.runBlocking { jobs.close() }
    }
    @Test fun invalidSourceUpdateKeepsPreviousCatalogAndSurfacesError() = runTest {
        val env = environment(); val sources = SourceStore(env.storage, builtinSources = emptyList())
        val record = sources.confirm(sources.propose("https://example.invalid/source.json").token)
        sources.fetched(record.key, catalog("test.source", entry("test.plugin", release()).item))
        val transport = object : StoreTransport {
            override suspend fun fetch(url: String, destination: File, maximumBytes: Long, progress: (Long) -> Unit) { destination.parentFile!!.mkdirs(); destination.writeText("not-json") }
        }
        val jobs = StoreJobs(env, sources, testDevice, PackageDownloader(env.storage, transport), ArtifactInspector(env.storage), {}, this)
        jobs.refresh(record.key); advanceUntilIdle()
        assertEquals(1, sources.entries().size); assertTrue(sources.source(record.key).error.isNotEmpty()); assertFalse(jobs.busy)
    }
    @Test fun verifiedPreparedPlanDoesNotInstallOrContactBrokerAutomatically() = runTest {
        val env = environment(); val sources = SourceStore(env.storage, builtinSources = emptyList()); val fixture = signedFixture(temporary.root)
        val record = sources.confirm(sources.propose("https://example.invalid/source.json").token)
        sources.fetched(record.key, catalog("test.source", fixture.entry.item))
        var fetched = 0
        val transport = object : StoreTransport {
            override suspend fun fetch(url: String, destination: File, maximumBytes: Long, progress: (Long) -> Unit) {
                fetched++; destination.parentFile!!.mkdirs(); fixture.file.copyTo(destination); progress(fixture.file.length())
            }
        }
        val jobs = StoreJobs(env, sources, testDevice, PackageDownloader(env.storage, transport), ArtifactInspector(env.storage), {}, this)
        jobs.prepare(sources.entries().single(), 1); advanceUntilIdle()
        assertEquals(1, fetched); assertEquals(1, jobs.prepared?.artifacts?.size); assertFalse(jobs.busy)
        val snapshot = PluginSnapshot(PluginDescriptor("tools.package-store", "商店", "测试"), PluginContributions(),
            listOfNotNull(storePage("store", sources, jobs, StoreView(), testDevice), storePage("install-plan", sources, jobs, StoreView(), testDevice)))
        assertTrue(PluginJson.encodeToString(snapshot).toByteArray().size < 512 * 1024)
    }
}
