package com.kigtts.tools.store

import com.kigtts.plugin.api.PluginStorage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun officialSourceIsSeededOnceAndRemovalStaysRemoved() {
        val storage = PluginStorage(temporary.root)
        val first = SourceStore(storage)
        assertEquals(com.kigtts.plugin.api.OfficialPluginSource.URL, first.records().single().url)
        first.remove(first.records().single().key)
        assertTrue(SourceStore(storage).records().isEmpty())
    }
    @Test fun deepLinkOnlyCreatesPendingUntilUserConfirms() {
        val store = SourceStore(PluginStorage(temporary.root), builtinSources = emptyList())
        val pending = store.propose("https://example.invalid/source.json")
        assertTrue(store.records().isEmpty()); assertEquals(pending, store.pending())
        val restored = SourceStore(PluginStorage(temporary.root), builtinSources = emptyList()); assertEquals(pending, restored.pending())
        restored.confirm(pending.token); assertEquals(1, restored.records().size); assertNull(restored.pending())
    }
    @Test fun unknownTokenAndDuplicateSourceCannotAddRecord() {
        val store = SourceStore(PluginStorage(temporary.root), builtinSources = emptyList()); val pending = store.propose("https://example.invalid/source.json")
        assertThrows(IllegalStateException::class.java) { store.confirm("unknown") }
        store.confirm(pending.token)
        assertThrows(IllegalArgumentException::class.java) { store.propose("https://example.invalid/source.json") }
    }
    @Test fun preloadCacheAndFailureKeepPreviouslyAvailablePackages() {
        val store = SourceStore(PluginStorage(temporary.root), builtinSources = emptyList()); val pending = store.propose("https://example.invalid/source.json")
        val record = store.confirm(pending.token); store.fetched(record.key, catalog("test.source", entry("test.plugin", release()).item))
        store.failed(record.key, "offline")
        val restored = SourceStore(PluginStorage(temporary.root), builtinSources = emptyList())
        assertEquals(1, restored.entries().size); assertEquals("offline", restored.source(record.key).error)
    }
    @Test fun changedCatalogIdentityAndDuplicateCatalogIdsAreRejected() {
        val store = SourceStore(PluginStorage(temporary.root), builtinSources = emptyList())
        val a = store.confirm(store.propose("https://example.invalid/a.json").token)
        val b = store.confirm(store.propose("https://example.invalid/b.json").token)
        store.fetched(a.key, catalog("test.source"))
        assertThrows(IllegalArgumentException::class.java) { store.fetched(a.key, catalog("test.changed")) }
        assertThrows(IllegalArgumentException::class.java) { store.fetched(b.key, catalog("test.source")) }
    }
    @Test fun disabledSourceIsRetainedButExcludedFromUnifiedBrowse() {
        val store = SourceStore(PluginStorage(temporary.root), builtinSources = emptyList()); val a = store.confirm(store.propose("https://example.invalid/a.json").token)
        store.fetched(a.key, catalog("test.source", entry("test.plugin", release()).item)); store.toggle(a.key)
        assertTrue(store.entries().isEmpty()); assertEquals(1, store.records().size)
    }
}
