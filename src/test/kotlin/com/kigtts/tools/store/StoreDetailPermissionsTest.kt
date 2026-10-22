package com.kigtts.tools.store

import com.kigtts.plugin.api.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StoreDetailPermissionsTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun detailsShowIconAndTheSelectedVersionsCollapsedPermissions() = runTest {
        val env = object : PluginEnvironment {
            override val storage = PluginStorage(temp.root); override val assets = temp.root
            override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("unused")
        }
        val old = release()
        val current = release("2.0.0", 2).copy(permissions = listOf("network"), permissionPurposes = mapOf("network" to "获取插件目录"))
        val sources = SourceStore(env.storage, emptyList())
        val source = sources.confirm(sources.propose("https://example.invalid/source.json").token)
        sources.fetched(source.key, catalog("test.source", entry("test.plugin", old, current).item.copy(icon = CatalogIcon(symbol = "storefront"))))
        val transport = object : StoreTransport {
            override suspend fun fetch(url: String, destination: File, maximumBytes: Long, progress: (Long) -> Unit) = error("page reads cannot fetch")
        }
        val jobs = StoreJobs(env, sources, testDevice, PackageDownloader(env.storage, transport), ArtifactInspector(env.storage), {}, this)
        val selected = sources.entries().single()
        val detail = storePage("package", sources, jobs, StoreView(selected = selected, selectedVersion = 2), testDevice)!!
        assertEquals("storefront", detail.header!!.symbol); assertNull(detail.header!!.primaryAction)
        val permissions = detail.blocks.filterIsInstance<PageBlock.Group>().single { it.title == "权限说明" }
        assertTrue(permissions.collapsible); assertFalse(permissions.initiallyExpanded)
        assertTrue(permissions.children.contains(PageBlock.Text("获取插件目录")))
        val legacy = permissionDetails(old).children.filterIsInstance<PageBlock.Text>().single().text
        assertTrue(legacy.contains("尚未提供"))
        assertTrue((permissionDetails(current.copy(permissions = emptyList(), permissionPurposes = emptyMap())).children.single() as PageBlock.Text).text.contains("未声明"))
        try { validatePermissions(current.copy(permissionPurposes = mapOf("camera" to "拍照"))); fail() } catch (_: IllegalArgumentException) {}
    }
    @Test fun verifiedArchivesCannotAdvertiseFewerPermissionsThanTheyDeclare() {
        val fixture = signedFixture(temp.root)
        ArtifactInspector(PluginStorage(temp.root)).inspect(fixture.file, fixture.entry, fixture.release.copy(permissions = emptyList()))
        try {
            ArtifactInspector(PluginStorage(temp.root)).inspect(fixture.file, fixture.entry, fixture.release.copy(permissions = listOf("network")))
            fail("catalog permissions must match the signed package")
        } catch (_: IllegalArgumentException) {}
    }
}
