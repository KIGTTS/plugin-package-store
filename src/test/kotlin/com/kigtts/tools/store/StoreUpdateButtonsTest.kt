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
class StoreUpdateButtonsTest {
    @get:Rule val temp = TemporaryFolder()
    private suspend fun TestScope.fixture(installed: InstalledPackage?): Pair<SourceStore, StoreJobs> {
        val env = object : PluginEnvironment {
            override val storage = PluginStorage(temp.newFolder()); override val assets = storage.root
            override val appBroker = object : AppBroker {
                override suspend fun request(command: AppCommand) = AppReply.Data(buildJsonObject {
                    put("plugins", buildJsonArray { installed?.let { old -> add(buildJsonObject {
                        put("id", old.id); put("version", old.version); put("versionCode", old.versionCode)
                        put("apiVersion", old.apiVersion); put("installed", true); put("enabled", old.enabled)
                        put("kind", if (old.format == PackageFormat.LUA) "lua" else "native")
                    }) } })
                })
            }
            override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("unused")
        }
        val sources = SourceStore(env.storage, emptyList())
        val transport = object : StoreTransport {
            override suspend fun fetch(url: String, destination: File, maximumBytes: Long, progress: (Long) -> Unit) = error("page reads cannot fetch")
        }
        val downloader = PackageDownloader(env.storage, transport)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val jobs = StoreJobs(env, sources, testDevice, downloader, ArtifactInspector(env.storage), {}, scope)
        jobs.downloads = StoreDownloads(env, sources, testDevice, downloader, ArtifactInspector(env.storage), {}, scope)
        jobs.reloadInstalled(); runCurrent()
        return sources to jobs
    }

    @Test fun updateFiltersListingAndDetailsAgreeForVersionsBuildsAndApiChanges() = runTest {
        val old = InstalledPackage("test.plugin", "1.0.0", 1, true)
        val lua = release().copy(format = PackageFormat.LUA, dependencies = mapOf("runtime.lua" to "*"))
        data class Case(val release: PackageRelease, val installed: InstalledPackage?, val update: Boolean, val label: String)
        val cases = listOf(Case(release("2.0.0", 2), old, true, "更新"),
            Case(release(code = 2), old, true, "更新"),
            Case(release(), old.copy(apiVersion = PLUGIN_API_VERSION - 1), true, "更新"),
            Case(release(), old, false, "已安装"),
            Case(release("0.9.0"), old.copy(versionCode = 2), false, "安装"),
            Case(release("2.0.0", 2).copy(apiVersion = testDevice.api + 1), old, false, "不兼容"),
            Case(lua.copy(versionCode = 2), old.copy(format = PackageFormat.LUA), false, "已安装"),
            Case(lua, old.copy(format = PackageFormat.LUA, apiVersion = PLUGIN_API_VERSION - 1), true, "更新"),
            Case(release(), null, false, "获取"))
        for (case in cases) {
            val (sources, jobs) = fixture(case.installed)
            val entry = entry("test.plugin", case.release)
            assertEquals(case.toString(), case.update, entry.hasUpdate(jobs.installed, testDevice))
            assertEquals(case.toString(), case.label, downloadActions(listOf(entry), jobs, testDevice).getValue(entry.item.id).label)
            val page = storePage("package", sources, jobs, StoreView(selected = entry, selectedVersion = case.release.versionCode), testDevice)!!
            assertEquals(case.toString(), case.label, page.headerAction!!.label)
            jobs.close()
        }
    }

    @Test fun selectedHistoricVersionAndOldDownloadTaskCannotMaskANewApiRelease() = runTest {
        val old = InstalledPackage("test.plugin", "1.0.0", 1, true, apiVersion = PLUGIN_API_VERSION - 1)
        val (sources, jobs) = fixture(old)
        val older = release().copy(apiVersion = old.apiVersion)
        val oldEntry = entry(old.id, older)
        val task = jobs.downloads!!.offer(oldEntry, older.versionCode, jobs.installed)
        val updated = entry(old.id, release())
        assertEquals("更新", downloadAction(updated, jobs, testDevice).label)
        assertEquals(StoreDownloadPhase.CONFIRM, jobs.downloads!!.task(task.id).phase)
        val history = entry(old.id, release("0.9.0"), release("2.0.0", 2))
        assertTrue(history.hasUpdate(jobs.installed, testDevice))
        assertEquals("安装", storePage("package", sources, jobs, StoreView(selected = history, selectedVersion = 1), testDevice)!!.headerAction!!.label)
        jobs.close()
    }

    @Test fun readyUpdatesKeepTheirLabelWhileNewInstallsAndResourcesKeepTheirOwnActions() {
        val old = InstalledPackage("test.plugin", "1.0.0", 1, true)
        val entry = entry(old.id, release(code = 2))
        val planned = PlannedRelease(entry, entry.item.releases.single())
        val task = StoreDownload(plan = DependencyPlan(listOf(planned), planned, emptyList(), emptyList()), phase = StoreDownloadPhase.READY)
        assertEquals("更新", task.button(old).label); assertEquals("安装", task.button().label)
        assertEquals("继续更新", task.copy(phase = StoreDownloadPhase.INSTALLING).button(old).label)
        assertEquals("取消下载", task.copy(phase = StoreDownloadPhase.DOWNLOADING, progress = .5f).button(old).label)
        val resource = entry.copy(item = entry.item.copy(resourceType = ResourceType.QUICK_TEXT))
        val resourceTask = task.copy(plan = task.plan.copy(root = planned.copy(entry = resource, release = planned.release.copy(format = PackageFormat.RESOURCE))))
        assertFalse(resource.hasUpdate(mapOf(old.id to old), testDevice))
        assertEquals("添加", resourceTask.button(old).label)
    }
}
