package com.kigtts.tools.store

import com.kigtts.plugin.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class StoreDownloadsTest {
    @get:Rule val temp = TemporaryFolder()
    private var metadata = buildJsonObject { put("plugins", JsonArray(emptyList())) }
    private var describeGate: CompletableDeferred<Unit>? = null
    private fun environment() = object : PluginEnvironment {
        override val storage = PluginStorage(temp.root)
        override val assets = temp.root
        override val appBroker = object : AppBroker { override suspend fun request(command: AppCommand): AppReply {
            describeGate?.await()
            return AppReply.Data(metadata)
        } }
        override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("unused")
    }
    @Test fun queueWaitsForConfirmationAndReconcilesActualInstallWithoutBlockingNextPackage() = runTest {
        val env = environment(); val sources = SourceStore(env.storage, emptyList())
        val fixture = signedFixture(temp.root, id = "test.first")
        val second = signedFixture(temp.root, id = "test.second")
        val record = sources.confirm(sources.propose("https://example.invalid/catalog.json").token)
        sources.fetched(record.key, catalog("test.source", fixture.entry.item, second.entry.item))
        var fetched = 0
        val transport = object : StoreTransport {
            override suspend fun fetch(url: String, destination: File, maximumBytes: Long, progress: (Long) -> Unit) {
                fetched++; destination.parentFile!!.mkdirs(); fixture.file.copyTo(destination); progress(fixture.file.length())
            }
        }
        val downloads = StoreDownloads(env, sources, testDevice, PackageDownloader(env.storage, transport), ArtifactInspector(env.storage), {}, this)
        val task = downloads.offer(sources.entries().first(), 1, emptyMap())
        advanceUntilIdle(); assertEquals(0, fetched)
        downloads.confirm(task.id); advanceUntilIdle()
        assertEquals(StoreDownloadPhase.READY, downloads.task(task.id).phase)
        assertEquals(1, fetched)
        val automatic = "download-auto-install/${task.id}"
        assertEquals(automatic, downloads.automaticAction())
        describeGate = CompletableDeferred()
        val confirmation = async { downloads.installAutomatically(task.id) }
        runCurrent()
        assertEquals(StoreDownloadPhase.INSTALLING, downloads.task(task.id).phase)
        assertEquals(automatic, downloads.automaticAction()) // Stable even while Describe is suspended.
        describeGate!!.complete(Unit)
        assertTrue(confirmation.await() is PluginOutcome.InstallPackage)
        describeGate = null
        assertEquals(automatic, downloads.automaticAction()) // Stable while the confirmation is open.
        val cached = downloads.task(task.id).artifacts.single().second.file
        assertTrue(cached.isFile)
        downloads.installAutomatically(task.id) // Host replays entry action on dismissal.
        assertEquals(StoreDownloadPhase.READY, downloads.task(task.id).phase)
        assertNull(downloads.automaticAction()) // Cancellation must not immediately reopen the dialog.
        assertTrue(cached.isFile)
        assertTrue(downloads.install(task.id) is PluginOutcome.InstallPackage) // Manual retry uses the same download.
        assertNull(downloads.automaticAction()) // A manual confirmation must not start a second automatic entry.
        assertEquals(1, fetched)
        assertTrue(cached.isFile) // Showing the installer is not a successful installation.
        metadata = buildJsonObject { put("plugins", buildJsonArray { add(buildJsonObject {
            put("id", "test.first"); put("installed", true); put("version", "1.0.0"); put("versionCode", 1); put("apiVersion", PLUGIN_API_VERSION)
        }) }) }
        downloads.reconcile()
        assertEquals(StoreDownloadPhase.INSTALLED, downloads.task(task.id).phase)
        assertFalse(cached.exists())
        assertTrue(downloads.task(task.id).artifacts.isEmpty())
        assertNull(downloads.automaticAction())
        val next = downloads.offer(sources.entries().last(), 1, emptyMap())
        assertEquals(StoreDownloadPhase.CONFIRM, next.phase)
        downloads.clearFinished()
        assertEquals(listOf(next.id), downloads.tasks.map { it.id })
    }
    @Test fun cancelActiveAndQueuedJobsKeepsProgressBoundedAndOtherRowsUsable() = runTest {
        val env = environment(); val sources = SourceStore(env.storage, emptyList())
        val first = entry("test.first", release())
        val second = entry("test.second", release())
        val record = sources.confirm(sources.propose("https://example.invalid/catalog.json").token)
        sources.fetched(record.key, catalog("test.source", first.item, second.item))
        val started = CompletableDeferred<Unit>()
        val transport = object : StoreTransport {
            override suspend fun fetch(url: String, destination: File, maximumBytes: Long, progress: (Long) -> Unit) {
                progress(maximumBytes / 2); started.complete(Unit); awaitCancellation()
            }
        }
        val queue = StoreDownloads(env, sources, testDevice, PackageDownloader(env.storage, transport), ArtifactInspector(env.storage), {}, this)
        val a = queue.offer(first, 1, emptyMap()); queue.confirm(a.id)
        val b = queue.offer(second, 1, emptyMap()); queue.confirm(b.id)
        runCurrent(); started.await()
        assertEquals(StoreDownloadPhase.DOWNLOADING, queue.task(a.id).phase)
        assertEquals(.5f, queue.task(a.id).progress!!, .01f)
        assertEquals(StoreDownloadPhase.QUEUED, queue.task(b.id).phase)
        assertFalse(queue.canClearCache())
        queue.cancel(b.id); queue.cancel(a.id); advanceUntilIdle()
        assertTrue(queue.tasks.all { it.phase == StoreDownloadPhase.CANCELLED })
        assertTrue(queue.canClearCache())
        assertEquals("重试", queue.task(a.id).button().label)
    }
}
