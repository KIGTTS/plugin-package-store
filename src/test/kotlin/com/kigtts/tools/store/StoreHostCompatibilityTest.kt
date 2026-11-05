package com.kigtts.tools.store

import com.kigtts.plugin.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class StoreHostCompatibilityTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun builtinReadingIsHiddenAndSatisfiesEngineDependencyWithoutDownload() {
        val device = StoreDevice(35, setOf("arm64-v8a"))
        val reading = entry("service.speech-synthesis", release().copy(version = "0.8.5"))
        val engine = entry("test.cloud-engine", release().copy(dependencies = mapOf(reading.item.id to ">=0.8.0")))
        val installed = mapOf(reading.item.id to InstalledPackage(reading.item.id, "0.8.5", 0, true, builtin = true))
        assertEquals(listOf(engine), discover(listOf(reading, engine), StoreView(), installed, device))
        val plan = DependencyResolver(listOf(reading, engine), installed, device).resolve(engine, 1)
        assertEquals(listOf(engine.item.id), plan.ordered.map { it.entry.item.id })
        assertEquals(listOf(reading.item.id), plan.retained.map { it.id })
        assertTrue(runCatching { DependencyResolver(listOf(reading), installed, device).resolve(reading, 1) }.isFailure)
        val incompatible = engine.copy(item = engine.item.copy(releases = listOf(engine.item.releases.single().copy(
            dependencies = mapOf(reading.item.id to ">=9.0.0")))))
        assertTrue(runCatching { DependencyResolver(listOf(reading, incompatible), installed, device).resolve(incompatible, 1) }.isFailure)
    }

    @Test fun newerHostSupportsPhotoStickersWithoutDroppingOlderPlugins() {
        val device = StoreDevice(36, setOf("arm64-v8a"), api = 33)
        val photo = release().copy(apiVersion = 34, format = PackageFormat.RESOURCE)
        assertFalse(photo.compatible(device))
        device.updateHostCompatibility(buildJsonObject { put("pluginApi", 34); put("minimumPluginApi", 9) })
        assertTrue(photo.compatible(device))
        assertTrue(release().copy(apiVersion = 31).compatible(device))
        assertTrue(release().copy(apiVersion = 9).compatible(device))
        assertFalse(release().copy(apiVersion = 35).compatible(device))
        assertFalse(photo.copy(minSdk = 37).compatible(device))
        assertFalse(release().copy(abis = listOf("x86")).compatible(device))
    }

    @Test fun olderHostsAndMalformedMetadataDoNotRelaxCompatibility() {
        val device = StoreDevice(35, setOf("arm64-v8a"), api = 33)
        for (value in listOf("{}", """{"pluginApi":34}""", """{"pluginApi":{},"minimumPluginApi":9}""",
                """{"pluginApi":34,"minimumPluginApi":0}""", """{"pluginApi":8,"minimumPluginApi":9}""")) {
            device.updateHostCompatibility(PluginJson.parseToJsonElement(value).jsonObject)
            assertEquals(33, device.api)
            assertEquals(9, device.minimumApi)
            assertFalse(release().copy(apiVersion = 34).compatible(device))
        }
        device.updateHostCompatibility(buildJsonObject { put("pluginApi", 32); put("minimumPluginApi", 9) })
        assertEquals(32, device.api)
        assertFalse(release().copy(apiVersion = 33).compatible(device))
    }

    @Test fun installedRefreshUpdatesListDetailsAndDependencyPlanTogether() = runTest {
        var hostApi = 34
        val environment = object : PluginEnvironment {
            override val storage = PluginStorage(temp.root)
            override val assets = temp.root
            override val appBroker = object : AppBroker {
                override suspend fun request(command: AppCommand): AppReply = AppReply.Data(buildJsonObject {
                    put("pluginApi", hostApi); put("minimumPluginApi", 9); put("plugins", JsonArray(emptyList()))
                })
            }
            override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("unused")
        }
        val sources = SourceStore(environment.storage, emptyList())
        val transport = object : StoreTransport {
            override suspend fun fetch(url: String, destination: File, maximumBytes: Long, progress: (Long) -> Unit) = error("No network expected")
        }
        val device = StoreDevice(36, setOf("arm64-v8a"), api = 33)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val jobs = StoreJobs(environment, sources, device, PackageDownloader(environment.storage, transport),
            ArtifactInspector(environment.storage), {}, scope)
        val photo = entry("resources.info-stickers", release().copy(apiVersion = 34, format = PackageFormat.RESOURCE))
        assertEquals("不兼容", downloadAction(photo, jobs, device).label)
        jobs.reloadInstalled(); advanceUntilIdle()
        assertEquals("获取", downloadAction(photo, jobs, device).label)
        assertEquals("获取", storePage("package", sources, jobs, StoreView(selected = photo), device)!!.headerAction!!.label)
        assertEquals(34, DependencyResolver(listOf(photo), emptyMap(), device).resolve(photo, 1).root.release.apiVersion)
        hostApi = 33
        jobs.reloadInstalled(); advanceUntilIdle()
        assertEquals("不兼容", downloadAction(photo, jobs, device).label)
        jobs.close()
    }
}
