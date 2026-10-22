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
        assertEquals(listOf("search", "library", "downloads", "store-settings"), page.blocks.filterIsInstance<PageBlock.Actions>().single().actions.map { it.id })
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
        assertEquals(0, fetched); assertNull(jobs.prepared); assertNotNull(jobs.pendingPlan); assertFalse(jobs.busy)
        val planPage = storePage("install-plan", sources, jobs, StoreView(), testDevice)!!
        assertEquals("v1.0.0", planPage.management!!.items.single().subtitle)
        assertEquals("本次安装", planPage.management!!.items.single().group)
        val preview = PluginJson.encodeToString(planPage)
        assertTrue(preview.contains("download-plan")); assertFalse(preview.contains("继续后仍会检查发布者"))
        jobs.downloadPlan(); advanceUntilIdle()
        assertEquals(1, fetched); assertEquals(1, jobs.prepared?.artifacts?.size); assertFalse(jobs.busy)
        val snapshot = PluginSnapshot(PluginDescriptor("tools.package-store", "商店", "测试"), PluginContributions(),
            listOfNotNull(storePage("store", sources, jobs, StoreView(), testDevice), storePage("install-plan", sources, jobs, StoreView(), testDevice)))
        assertTrue(PluginJson.encodeToString(snapshot).toByteArray().size < 512 * 1024)
    }
    @Test fun dependencyDownloadsStartOnlyAfterConfirmationAndKeepReviewedVersions() = runTest {
        val env = environment(); val sources = SourceStore(env.storage, builtinSources = emptyList())
        val dependency = signedFixture(temporary.root, id = "test.service")
        val root = signedFixture(temporary.root, id = "test.engine")
        val requested = root.entry.item.copy(releases = listOf(root.release.copy(dependencies = mapOf("test.service" to "*"))))
        val record = sources.confirm(sources.propose("https://example.invalid/source.json").token)
        sources.fetched(record.key, catalog("test.source", requested, dependency.entry.item))
        val downloads = mutableListOf<String>()
        val transport = object : StoreTransport {
            override suspend fun fetch(url: String, destination: File, maximumBytes: Long, progress: (Long) -> Unit) {
                val selected = if (downloads.isEmpty()) dependency else root
                downloads += selected.entry.item.id; destination.parentFile!!.mkdirs(); selected.file.copyTo(destination)
                progress(selected.file.length())
            }
        }
        val jobs = StoreJobs(env, sources, testDevice, PackageDownloader(env.storage, transport), ArtifactInspector(env.storage), {}, this)
        jobs.prepare(sources.entries().first { it.item.id == "test.engine" }, 1); advanceUntilIdle()
        assertTrue(downloads.isEmpty())
        assertEquals(listOf("test.service", "test.engine"), jobs.pendingPlan!!.ordered.map { it.entry.item.id })
        // A refresh cannot add a new version/dependency to the already reviewed plan.
        sources.fetched(record.key, catalog("test.source", requested.copy(releases = listOf(root.release.copy(version = "2.0.0", versionCode = 2))), dependency.entry.item))
        jobs.downloadPlan(); advanceUntilIdle()
        assertEquals(listOf("test.service", "test.engine"), downloads)
        assertEquals("1.0.0", jobs.prepared!!.plan.root.release.version)
        assertTrue(jobs.error.isEmpty())
        jobs.clearPrepared(); assertNull(jobs.pendingPlan); assertNull(jobs.prepared)
    }
}
