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
    @Test fun signedLuaPluginUsesTheNormalPackageRoute() {
        val fixture = signedFixture(temporary.root, extra = mapOf("assets/lua-project/main.lua" to "return {}".toByteArray()))
        val verified = ArtifactInspector(PluginStorage(temporary.root)).inspect(fixture.file, fixture.entry, fixture.release)
        assertEquals(PackageFormat.NATIVE, verified.format)
    }
    @Test fun legacySourceOnlyLuaPackageIsRejectedBeforeDelivery() {
        val fixture = signedFixture(temporary.root, PackageFormat.LUA)
        assertThrows(IllegalArgumentException::class.java) { ArtifactInspector(PluginStorage(temporary.root)).inspect(fixture.file, fixture.entry, fixture.release) }
    }
    @Test fun modifiedCachedBytesFailBeforeSignatureOrInstall() {
        val fixture = signedFixture(temporary.root); fixture.file.appendBytes(byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) { ArtifactInspector(PluginStorage(temporary.root)).inspect(fixture.file, fixture.entry, fixture.release) }
    }
}
