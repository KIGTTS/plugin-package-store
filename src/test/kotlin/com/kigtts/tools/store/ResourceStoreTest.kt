package com.kigtts.tools.store

import com.kigtts.plugin.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ResourceStoreTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun acceptsResourceSourcesAndRejectsDisguisedExecutablePackages() {
        val resource = entry("test.cards", release().copy(format = PackageFormat.RESOURCE)).item.copy(resourceType = ResourceType.QUICK_TEXT)
        val source = catalog("test.resources", resource).copy(schemaVersion = 2, kind = SourceKind.RESOURCES)
        CatalogCodec.validate(source)
        assertThrows(IllegalArgumentException::class.java) { CatalogCodec.validate(source.copy(schemaVersion = 1)) }
        assertThrows(IllegalArgumentException::class.java) { CatalogCodec.validate(source.copy(packages = listOf(resource.copy(resourceType = null)))) }
        assertThrows(IllegalArgumentException::class.java) { CatalogCodec.validate(source.copy(packages = listOf(resource.copy(releases = listOf(release()))))) }
    }
    @Test fun verifiesSignedResourceIdentityAndAlwaysKeepsItOutOfPluginMetadata() {
        val fixture = signedFixture(temp.root, PackageFormat.RESOURCE)
        val inspector = ArtifactInspector(PluginStorage(temp.root))
        assertEquals("resource.json", inspector.inspect(fixture.file, fixture.entry, fixture.release).manifestName)
        assertThrows(IllegalArgumentException::class.java) { inspector.inspect(fixture.file,
            fixture.entry.copy(item = fixture.entry.item.copy(resourceType = ResourceType.FRAME)), fixture.release) }
        val metadata = buildJsonObject { put("plugins", buildJsonArray { add(buildJsonObject {
            put("id", fixture.entry.item.id); put("installed", true); put("version", "1.0.0"); put("versionCode", 1); put("apiVersion", PLUGIN_API_VERSION)
        }) }) }
        assertFalse(actualInstalled(metadata, PlannedRelease(fixture.entry, fixture.release)))
    }
    @Test fun waitsForRealImportReceiptThenAllowsAnotherCopy() = runTest {
        val fixture = signedFixture(temp.root, PackageFormat.RESOURCE)
        var state = "PENDING"
        val env = object : PluginEnvironment {
            override val storage = PluginStorage(temp.root); override val assets = temp.root
            override val appBroker = object : AppBroker { override suspend fun request(command: AppCommand): AppReply =
                AppReply.Data(if (command is AppCommand.ResourcePackage) buildJsonObject { put("token", "receipt"); put("state", state) }
                    else buildJsonObject { put("plugins", JsonArray(emptyList())) }) }
            override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("unused")
        }
        val sources = SourceStore(env.storage, emptyList())
        val source = sources.confirm(sources.propose("https://example.invalid/resources.json").token)
        sources.fetched(source.key, catalog("test.resources", fixture.entry.item).copy(schemaVersion = 2, kind = SourceKind.RESOURCES))
        val transport = object : StoreTransport { override suspend fun fetch(url: String, destination: File, maximumBytes: Long, progress: (Long) -> Unit) {
            destination.parentFile!!.mkdirs(); fixture.file.copyTo(destination); progress(fixture.file.length())
        } }
        val downloads = StoreDownloads(env, sources, testDevice, PackageDownloader(env.storage, transport), ArtifactInspector(env.storage), {}, backgroundScope)
        val item = sources.entries().single()
        val task = downloads.offer(item, 1, emptyMap()); downloads.confirm(task.id)
        testScheduler.advanceTimeBy(1); runCurrent()
        assertEquals(StoreDownloadPhase.READY, downloads.task(task.id).phase)
        assertEquals("download-auto-install/${task.id}", downloads.automaticAction())
        val cached = downloads.task(task.id).artifacts.single().second.file
        assertEquals(PluginOutcome.RefreshUi, downloads.installAutomatically(task.id))
        assertEquals(StoreDownloadPhase.INSTALLING, downloads.task(task.id).phase)
        downloads.reconcile(); assertEquals(StoreDownloadPhase.INSTALLING, downloads.task(task.id).phase)
        assertTrue(cached.isFile)
        state = "CANCELLED"; downloads.reconcile()
        assertEquals(StoreDownloadPhase.READY, downloads.task(task.id).phase)
        assertNull(downloads.automaticAction())
        assertTrue(cached.isFile)
        state = "PENDING"
        assertEquals(PluginOutcome.RefreshUi, downloads.install(task.id))
        state = "COMPLETED"; downloads.reconcile()
        assertEquals(StoreDownloadPhase.INSTALLED, downloads.task(task.id).phase)
        assertFalse(cached.exists())
        val again = downloads.offer(item, 1, emptyMap())
        assertNotEquals(task.id, again.id)
        assertEquals(StoreDownloadPhase.CONFIRM, again.phase)
        downloads.close()
    }
    @Test fun failedImportAndInterruptedReceiptReadsNeverLeaveResourceWaitingForever() = runTest {
        for (mode in listOf("request-error", "failed-receipt", "temporary-status-error")) {
            val directory = File(temp.root, mode).apply { mkdirs() }
            val fixture = signedFixture(directory, PackageFormat.RESOURCE)
            var state = "PENDING"
            var statusFailures = 0
            val env = object : PluginEnvironment {
                override val storage = PluginStorage(directory); override val assets = directory
                override val appBroker = object : AppBroker {
                    override suspend fun request(command: AppCommand): AppReply {
                        if (command is AppCommand.ResourcePackage) {
                            if (command.operation == "install" && mode == "request-error") error("添加未完成")
                            if (command.operation == "status" && mode == "temporary-status-error" && statusFailures++ == 0) error("连接暂时中断")
                            return AppReply.Data(buildJsonObject { put("token", "receipt"); put("state", state); put("message", "边框读取失败") })
                        }
                        return AppReply.Data(buildJsonObject { put("plugins", JsonArray(emptyList())) })
                    }
                }
                override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("unused")
            }
            val sources = SourceStore(env.storage, emptyList())
            val source = sources.confirm(sources.propose("https://example.invalid/resources.json").token)
            sources.fetched(source.key, catalog("test.resources", fixture.entry.item).copy(schemaVersion = 2, kind = SourceKind.RESOURCES))
            val transport = object : StoreTransport {
                override suspend fun fetch(url: String, destination: File, maximumBytes: Long, progress: (Long) -> Unit) {
                    destination.parentFile!!.mkdirs(); fixture.file.copyTo(destination); progress(fixture.file.length())
                }
            }
            val workerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val downloads = StoreDownloads(env, sources, testDevice, PackageDownloader(env.storage, transport), ArtifactInspector(env.storage), {}, workerScope)
            try {
                val item = sources.entries().single()
                val task = downloads.offer(item, 1, emptyMap())
                assertTrue(downloads.install(task.id) is PluginOutcome.Message)
                assertEquals(StoreDownloadPhase.CONFIRM, downloads.task(task.id).phase)
                downloads.confirm(task.id)
                testScheduler.advanceTimeBy(1); runCurrent()
                val result = downloads.install(task.id)
                if (mode == "request-error") {
                    assertTrue(result is PluginOutcome.Message)
                    assertEquals(StoreDownloadPhase.READY, downloads.task(task.id).phase)
                } else if (mode == "failed-receipt") {
                    state = "FAILED"; downloads.reconcile()
                    assertEquals(StoreDownloadPhase.READY, downloads.task(task.id).phase)
                    assertNull(downloads.task(task.id).resourceToken)
                    assertNotNull(downloads.task(task.id).button().action)
                } else {
                    testScheduler.advanceTimeBy(1001); runCurrent()
                    assertEquals(StoreDownloadPhase.INSTALLING, downloads.task(task.id).phase)
                    state = "COMPLETED"
                    testScheduler.advanceTimeBy(1001); runCurrent()
                    assertEquals(StoreDownloadPhase.INSTALLED, downloads.task(task.id).phase)
                }
            } finally { downloads.close() }
        }
    }
}
