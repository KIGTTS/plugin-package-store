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
        assertEquals(com.kigtts.plugin.api.OfficialPluginSource.URL, first.records().single { it.key == "kigtts-official" }.url)
        assertEquals(setOf("kigtts-official", "kigtts-resources", "kigtts-expressions"), first.records().map { it.key }.toSet())
        first.records().forEach { first.remove(it.key) }
        assertTrue(SourceStore(storage).records().isEmpty())
    }
    @Test fun existingStoreAddsExpressionSourceOnceWithoutRestoringRemovedSources() {
        val storage = PluginStorage(temporary.root)
        storage.file("config/sources.json").apply {
            parentFile!!.mkdirs()
            writeText("""{"officialSourceSeeded":true,"resourceSourceSeeded":true}""")
        }
        val store = SourceStore(storage)
        val expression = store.records().single()
        assertEquals("kigtts.expressions", expression.catalogId)
        assertTrue(expression.url.contains("/expressions.json"))
        store.remove(expression.key)
        assertTrue(SourceStore(storage).records().isEmpty())
    }
    @Test fun mergesDeclaredCategoriesAcrossSourcesAndRestoresThemOffline() {
        val storage = PluginStorage(temporary.root)
        val store = SourceStore(storage, emptyList())
        val a = store.confirm(store.propose("https://example.invalid/market-a").token)
        val b = store.confirm(store.propose("https://example.invalid/market-b").token)
        val voices = CatalogCategory("plugin.audio", "语音工具", localizations = mapOf("ja" to "音声ツール"))
        val spare = CatalogCategory("plugin.toolkit", "百宝箱")
        val packageA = entry("test.audio-a", release()).item.copy(categoryId = voices.id, category = "音频")
        val packageB = entry("test.audio-b", release()).item.copy(categoryId = voices.id, category = "音频")
        fun catalogFor(id: String, pkg: CatalogPackage, vararg types: CatalogCategory) =
            catalog(id, pkg).copy(categories = types.toList())
        store.fetched(a.key, catalogFor("test.source-a", packageA, voices, spare))
        store.fetched(b.key, catalogFor("test.source-b", packageB,
            voices.copy(name = "配音", localizations = mapOf("en" to "Audio Tools"))))

        val restored = SourceStore(storage, emptyList())
        val categories = restored.categories().associateBy { it.id }
        assertEquals(setOf(voices.id, spare.id), categories.keys)
        val audio = categories.getValue(voices.id)
        assertEquals("语音工具", audio.name)
        assertEquals("Audio Tools", StoreCopy(null, "en-US").categoryLabel(audio))
        assertEquals("音声ツール", StoreCopy(null, "ja-JP").categoryLabel(audio))
        assertTrue(restored.entries().all { it.category == audio })
        assertEquals(setOf(packageA.id, packageB.id), discover(restored.entries(),
            StoreView(category = voices.id), emptyMap(), testDevice).map { it.item.id }.toSet())
        restored.failed(b.key, "offline")
        assertEquals(categories, SourceStore(storage, emptyList()).categories().associateBy { it.id })
        restored.toggle(a.key)
        assertEquals(listOf(voices.id), restored.categories().map { it.id })
        assertEquals(listOf(packageB.id), restored.entries().map { it.item.id })
    }

    @Test fun rejectsInvalidDeclarationsWithoutLosingTheCachedDirectory() {
        val storage = PluginStorage(temporary.root)
        val store = SourceStore(storage, emptyList())
        val source = store.confirm(store.propose("https://example.invalid/types").token)
        val valid = CatalogCategory("resource.expression", "表情特效", SourceKind.RESOURCES)
        val pack = entry("test.expression", release().copy(format = PackageFormat.RESOURCE)).item
            .copy(resourceType = com.kigtts.plugin.api.ResourceType.EXPRESSION, categoryId = valid.id)
        val named = catalog("test.types", pack).copy(schemaVersion = 2, categories = listOf(valid))
        store.fetched(source.key, named)
        assertThrows(IllegalArgumentException::class.java) {
            store.fetched(source.key, named.copy(categories = emptyList()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.fetched(source.key, named.copy(categories = listOf(valid.copy(kind = SourceKind.PLUGINS))))
        }
        assertEquals(named, SourceStore(storage, emptyList()).catalog(source.key))
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
    @Test fun pausedCloudSharingIsHiddenFromCachedAndPreviewCatalogsAndCannotBeOpenedByLink() {
        val storage = PluginStorage(temporary.root)
        val store = SourceStore(storage, emptyList())
        val url = "https://example.invalid/source.json"
        val source = store.confirm(store.propose(url).token)
        val cloud = entry("cloud.sharing", release()).item
        val other = entry("test.plugin", release()).item
        store.fetched(source.key, catalog("test.source", cloud, other))
        val restored = SourceStore(storage, emptyList())
        assertEquals(listOf("test.plugin"), restored.catalog(source.key)?.packages?.map { it.id })
        assertEquals(listOf("test.plugin"), restored.entries().map { it.item.id })
        val preview = restored.preview("https://example.invalid/preview.json")
        restored.fetched(preview.key, catalog("test.preview", cloud))
        assertEquals(listOf("test.plugin"), restored.allEntries().map { it.item.id })
        assertThrows(IllegalArgumentException::class.java) {
            proposeSource(restored, StoreView(), url, "cloud.sharing", requireAddition = false)
        }
        assertNull(restored.pending())
    }
}
