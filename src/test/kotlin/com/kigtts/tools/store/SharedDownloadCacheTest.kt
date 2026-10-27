package com.kigtts.tools.store

import com.kigtts.plugin.api.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SharedDownloadCacheTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun aCompletedDependencyStaysUntilOtherTaskReleasesItEvenAfterHistoryIsCleared() = runTest {
        val core = signedFixture(temp.root, id = "test.core")
        val first = signedFixture(temp.root, id = "test.first")
        val second = signedFixture(temp.root, id = "test.second")
        fun dependent(fixture: SignedFixture) = fixture.entry.copy(item = fixture.entry.item.copy(
            releases = listOf(fixture.release.copy(dependencies = mapOf("test.core" to "1.x")))))
        var installed = emptyList<String>()
        val env = object : PluginEnvironment {
            override val storage = PluginStorage(temp.root)
            override val assets = temp.root
            override val appBroker = object : AppBroker {
                override suspend fun request(command: AppCommand) = AppReply.Data(buildJsonObject {
                    put("plugins", buildJsonArray { installed.forEach { id -> add(buildJsonObject {
                        put("id", id); put("installed", true); put("version", "1.0.0"); put("versionCode", 1); put("apiVersion", PLUGIN_API_VERSION)
                    }) } })
                })
            }
            override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("unused")
        }
        val sources = SourceStore(env.storage, emptyList())
        val source = sources.confirm(sources.propose("https://example.invalid/source.json").token)
        sources.fetched(source.key, catalog("test.source", core.entry.item, dependent(first).item, dependent(second).item))
        var calls = 0
        // Use the expected cache basename instead of assuming separately signed packages have different lengths.
        val byHash = listOf(core, first, second).associate { it.release.sha256 to it.file }
        val queue = StoreDownloads(env, sources, testDevice, PackageDownloader(env.storage, object : StoreTransport {
            override suspend fun fetch(url: String, destination: File, maximumBytes: Long, progress: (Long) -> Unit) {
                calls++
                // The destination is temporary; mirror URLs below identify the signed fixture.
                byHash.getValue(url.substringAfterLast('/')).copyTo(destination)
            }
        }), ArtifactInspector(env.storage), {}, this)
        // Assign distinct mirror paths without changing the signed archive bytes.
        val items = sources.entries().map { entry -> entry.item.copy(releases = entry.item.releases.map {
            it.copy(mirrors = listOf(PackageMirror("test", "https://example.invalid/${it.sha256}")))
        }) }
        sources.fetched(source.key, catalog("test.source", *items.toTypedArray()))
        val a = queue.offer(sources.entries().first { it.item.id == "test.first" }, 1, emptyMap())
        val b = queue.offer(sources.entries().first { it.item.id == "test.second" }, 1, emptyMap())
        queue.confirm(a.id); queue.confirm(b.id); advanceUntilIdle()
        assertEquals(3, calls) // The common dependency was downloaded once.
        assertEquals("download-auto-install/${a.id}", queue.automaticAction())
        assertTrue(queue.installAutomatically(a.id) is PluginOutcome.InstallPackage)
        assertEquals("download-auto-install/${a.id}", queue.automaticAction())
        queue.reconcile() // Cancel the first automatic confirmation.
        assertEquals("download-auto-install/${b.id}", queue.automaticAction())
        assertTrue(queue.install(a.id) is PluginOutcome.InstallPackage)
        assertNull(queue.automaticAction()) // Pending B waits until the manual confirmation for A is closed.
        val shared = queue.task(a.id).artifacts.first { it.first.entry.item.id == "test.core" }.second.file
        val own = queue.task(a.id).artifacts.first { it.first.entry.item.id == "test.first" }.second.file
        installed = listOf("test.first", "test.core")
        queue.reconcile()
        assertFalse(own.exists()); assertTrue(shared.isFile)
        assertEquals("download-auto-install/${b.id}", queue.automaticAction())
        queue.clearFinished()
        queue.cancel(b.id)
        assertFalse(shared.exists())
    }
}
