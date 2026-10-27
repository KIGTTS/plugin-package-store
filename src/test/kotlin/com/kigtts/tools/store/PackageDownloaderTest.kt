package com.kigtts.tools.store

import com.kigtts.plugin.api.PluginStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PackageDownloaderTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun completedCleanupOnlyRemovesRequestedPackageArchivesIncludingLua() {
        val storage = PluginStorage(temporary.root)
        val hash = "a".repeat(64)
        val removed = listOf("kigplugin", "kiglua", "kigresource").map { storage.file("downloads/$hash.$it").apply { parentFile!!.mkdirs(); writeText("package") } }
        val unrelated = listOf(storage.file("downloads/other.kigplugin"), storage.file("downloads/$hash.svg"), storage.file("tmp/$hash.kigplugin"))
            .onEach { it.parentFile!!.mkdirs(); it.writeText("keep") }
        val downloader = PackageDownloader(storage, object : StoreTransport {
            override suspend fun fetch(url: String, destination: File, maximumBytes: Long, progress: (Long) -> Unit) = error("unused")
        })
        assertEquals(setOf(hash), downloader.clearCompleted(setOf(hash)))
        assertTrue(removed.none { it.exists() })
        assertTrue(unrelated.all { it.isFile })
    }
    @Test fun failedMirrorFallsBackAndThenUsesVerifiedCache() = runTest {
        val content = "verified-package".toByteArray(); var calls = 0
        val transport = object : StoreTransport {
            override suspend fun fetch(url: String, destination: File, maximumBytes: Long, progress: (Long) -> Unit) {
                calls++; if (url.endsWith("bad")) throw java.io.IOException("offline")
                destination.parentFile!!.mkdirs(); destination.writeBytes(content); progress(content.size.toLong())
            }
        }
        val version = release().copy(bytes = content.size.toLong(), sha256 = content.sha256(), mirrors = listOf(PackageMirror("bad", "https://example.invalid/bad"), PackageMirror("good", "https://example.invalid/good")))
        val packageEntry = entry("test.plugin", version); val downloader = PackageDownloader(PluginStorage(temporary.root), transport)
        val file = downloader.packageFile(packageEntry, version) {}
        assertArrayEquals(content, file.readBytes()); assertEquals(2, calls)
        downloader.packageFile(packageEntry, version) {}; assertEquals(2, calls)
        assertTrue(File(temporary.root, "tmp").listFiles().orEmpty().isEmpty())
    }
    @Test fun hashMismatchCannotBecomeCachedAndFallsBackToNextMirror() = runTest {
        val correct = "good".toByteArray(); var calls = 0
        val transport = object : StoreTransport {
            override suspend fun fetch(url: String, destination: File, maximumBytes: Long, progress: (Long) -> Unit) {
                calls++; destination.parentFile!!.mkdirs(); destination.writeBytes(if (calls == 1) "evil".toByteArray() else correct)
            }
        }
        val version = release().copy(bytes = 4, sha256 = correct.sha256(), mirrors = listOf(PackageMirror("1", "https://example.invalid/1"), PackageMirror("2", "https://example.invalid/2")))
        val file = PackageDownloader(PluginStorage(temporary.root), transport).packageFile(entry("test.plugin", version), version) {}
        assertArrayEquals(correct, file.readBytes()); assertEquals(2, calls)
    }
    @Test fun cancellationDoesNotTryAnotherMirrorAndDeletesPartial() = runTest {
        var calls = 0
        val transport = object : StoreTransport {
            override suspend fun fetch(url: String, destination: File, maximumBytes: Long, progress: (Long) -> Unit) {
                calls++; destination.parentFile!!.mkdirs(); destination.writeText("partial"); throw CancellationException("cancelled")
            }
        }
        val version = release().copy(mirrors = listOf(PackageMirror("1", "https://example.invalid/1"), PackageMirror("2", "https://example.invalid/2")))
        try { PackageDownloader(PluginStorage(temporary.root), transport).packageFile(entry("test.plugin", version), version) {}; fail() }
        catch (_: CancellationException) { }
        assertEquals(1, calls); assertTrue(File(temporary.root, "tmp").listFiles().orEmpty().isEmpty())
    }
    @Test fun allBadMirrorsLeaveNoTrustedCache() = runTest {
        val transport = object : StoreTransport {
            override suspend fun fetch(url: String, destination: File, maximumBytes: Long, progress: (Long) -> Unit) {
                destination.parentFile!!.mkdirs(); destination.writeText("wrong")
            }
        }
        try { PackageDownloader(PluginStorage(temporary.root), transport).packageFile(entry("test.plugin", release()), release()) {}; fail() }
        catch (_: IllegalStateException) { }
        assertTrue(File(temporary.root, "downloads").listFiles().orEmpty().isEmpty())
    }
}
