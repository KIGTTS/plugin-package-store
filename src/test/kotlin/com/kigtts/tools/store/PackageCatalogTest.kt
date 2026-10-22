package com.kigtts.tools.store

import com.kigtts.plugin.api.PluginJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class PackageCatalogTest {
    @Test fun validMultipleVersionsMirrorsAndDependenciesRoundTrip() {
        val packageEntry = entry("test.plugin", release("1.0.0", 1), release("1.2.0", 2, mapOf("test.core" to "^1.0.0")))
        val source = catalog("test.source", packageEntry.item)
        assertEquals(source, CatalogCodec.parse(PluginJson.encodeToString(source).toByteArray()))
    }
    @Test fun duplicatePackageAndVersionIdsAreRejected() {
        val item = entry("test.plugin", release()).item
        assertThrows(IllegalArgumentException::class.java) { CatalogCodec.validate(catalog("test.source", item, item)) }
        assertThrows(IllegalArgumentException::class.java) { CatalogCodec.validate(catalog("test.source", item.copy(releases = listOf(release(), release("2.0.0", 1))))) }
    }
    @Test fun missingHashPublisherAndTooLargeArtifactsAreRejected() {
        listOf(release().copy(sha256 = ""), release().copy(expectedPublisher = "name-is-not-a-key"), release().copy(bytes = 300L * 1024 * 1024)).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { CatalogCodec.validate(catalog("test.source", entry("test.plugin", invalid).item)) }
        }
    }
    @Test fun rejectsHttpCredentialsFragmentsAndMirrorDuplicates() {
        listOf("http://example.invalid/a", "https://user@example.invalid/a", "https://example.invalid/a#fragment").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { CatalogCodec.https(url) }
        }
        val version = release().copy(mirrors = listOf(PackageMirror("1", "https://example.invalid/a"), PackageMirror("2", "https://example.invalid/a")))
        assertThrows(IllegalArgumentException::class.java) { CatalogCodec.validate(catalog("test.source", entry("test.plugin", version).item)) }
    }
    @Test fun prereleaseIsVisibleByChoiceButNotDefaultLatest() {
        val packageEntry = entry("test.plugin", release("1.0.0", 1), release("2.0.0-beta.1", 2))
        assertEquals("1.0.0", packageEntry.latest(testDevice)?.version)
    }
    @Test fun luaSourcesMustDeclareInterpreterDependency() {
        val lua = entry("test.lua", release().copy(format = PackageFormat.LUA)).item
        assertThrows(IllegalArgumentException::class.java) { CatalogCodec.validate(catalog("test.source", lua)) }
        CatalogCodec.validate(catalog("test.source", lua.copy(releases = lua.releases.map { it.copy(dependencies = mapOf("runtime.lua" to "^0.8.0")) })))
    }
}
