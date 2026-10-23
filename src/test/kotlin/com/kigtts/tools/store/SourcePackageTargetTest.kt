package com.kigtts.tools.store

import com.kigtts.plugin.api.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SourcePackageTargetTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun targetResolvesOnlyInsideItsSourceAndNeverEnablesDisabledSource() = runTest {
        val env = object : PluginEnvironment {
            override val storage = PluginStorage(temp.root)
            override val assets = temp.root
            override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("unused")
        }
        val sources = SourceStore(env.storage, builtinSources = emptyList())
        val first = sources.confirm(sources.propose("https://example.invalid/first.json").token)
        val second = sources.confirm(sources.propose("https://example.invalid/second.json").token)
        sources.fetched(first.key, catalog("test.first", entry("test.plugin", release()).item.copy(name = "First")))
        sources.fetched(second.key, catalog("test.second", entry("test.plugin", release()).item.copy(name = "Second")))
        val transport = object : StoreTransport {
            override suspend fun fetch(url: String, destination: File, maximumBytes: Long, progress: (Long) -> Unit) = error("page cannot fetch")
        }
        val jobs = StoreJobs(env, sources, testDevice, PackageDownloader(env.storage, transport), ArtifactInspector(env.storage), {}, backgroundScope)
        val view = StoreView(linkTarget = SourcePackageTarget(second.url, "test.plugin"))
        assertNull(sourcePackagePage(sources, jobs, view, testDevice))
        assertEquals("Second", view.selected!!.item.name)
        assertEquals(second.key, view.selected!!.source.key)
        view.linkTarget = SourcePackageTarget(second.url, "test.missing")
        assertNotNull(sourcePackagePage(sources, jobs, view, testDevice))
        sources.toggle(second.key)
        view.linkTarget = SourcePackageTarget(second.url, "test.plugin")
        assertNotNull(sourcePackagePage(sources, jobs, view, testDevice))
        assertFalse(sources.source(second.key).enabled)
        jobs.close()
    }
}
