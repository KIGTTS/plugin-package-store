package com.kigtts.tools.store

import com.kigtts.plugin.api.PluginStorage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArtifactInspectorTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun verifiesArchiveHashManifestIdentitySignatureAndPublisher() {
        val fixture = signedFixture(temporary.root)
        val result = ArtifactInspector(PluginStorage(temporary.root)).inspect(fixture.file, fixture.entry, fixture.release)
        assertEquals(PackageFormat.NATIVE, result.format)
    }
    @Test fun catalogVersionConflictAndPublisherMismatchFail() {
        val fixture = signedFixture(temporary.root, archiveVersion = "2.0.0")
        val inspector = ArtifactInspector(PluginStorage(temporary.root))
        assertThrows(IllegalArgumentException::class.java) { inspector.inspect(fixture.file, fixture.entry, fixture.release) }
        val valid = signedFixture(temporary.root)
        assertThrows(IllegalArgumentException::class.java) { inspector.inspect(valid.file, valid.entry, valid.release.copy(expectedPublisher = "a".repeat(64))) }
    }
    @Test fun pathTraversalAndCaseDuplicateArchivesAreRejected() {
        val inspector = ArtifactInspector(PluginStorage(temporary.root))
        val bad = signedFixture(temporary.root, extra = mapOf("assets/../bad.txt" to byteArrayOf(1)))
        assertThrows(IllegalArgumentException::class.java) { inspector.inspect(bad.file, bad.entry, bad.release) }
        val duplicate = signedFixture(temporary.root, extra = mapOf("assets/A.txt" to byteArrayOf(1), "assets/a.txt" to byteArrayOf(2)))
        assertThrows(IllegalArgumentException::class.java) { inspector.inspect(duplicate.file, duplicate.entry, duplicate.release) }
    }
    @Test fun signedLuaExtractionDropsSignatureWithoutEvaluatingSource() {
        val fixture = signedFixture(temporary.root, PackageFormat.LUA, extra = mapOf("assets/help.txt" to "help".toByteArray()))
        val inspector = ArtifactInspector(PluginStorage(temporary.root)); val verified = inspector.inspect(fixture.file, fixture.entry, fixture.release)
        val directory = inspector.luaResources(verified)
        assertTrue(directory.resolve("manifest.json").isFile); assertEquals("return {}", directory.resolve("main.lua").readText())
        assertFalse(directory.resolve("signature").exists()); assertTrue(directory.resolve("assets/help.txt").isFile)
    }
    @Test fun invalidPayloadInLuaPackageIsRejectedBeforeDelivery() {
        val fixture = signedFixture(temporary.root, PackageFormat.LUA, extra = mapOf("code/malicious.dex" to byteArrayOf(1)))
        assertThrows(IllegalArgumentException::class.java) { ArtifactInspector(PluginStorage(temporary.root)).inspect(fixture.file, fixture.entry, fixture.release) }
    }
    @Test fun modifiedCachedBytesFailBeforeSignatureOrInstall() {
        val fixture = signedFixture(temporary.root); fixture.file.appendBytes(byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) { ArtifactInspector(PluginStorage(temporary.root)).inspect(fixture.file, fixture.entry, fixture.release) }
    }
}
