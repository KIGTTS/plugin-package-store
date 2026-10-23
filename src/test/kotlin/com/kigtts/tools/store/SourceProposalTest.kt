package com.kigtts.tools.store

import com.kigtts.plugin.api.PluginOutcome
import com.kigtts.plugin.api.PluginStorage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceProposalTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun packageLinkWaitsForSourceConfirmationAndSurvivesSourceStoreRestart() {
        val storage = PluginStorage(temporary.root)
        val store = SourceStore(storage, builtinSources = emptyList())
        val view = StoreView()
        val url = "https://example.invalid/catalog.json"
        assertEquals(PluginOutcome.OpenPage("source-review"), proposeSource(store, view, url, "test.plugin"))
        assertTrue(store.records().isEmpty())
        val reloaded = SourceStore(storage, builtinSources = emptyList())
        assertEquals("test.plugin", reloaded.pending()!!.packageId)
        val source = reloaded.confirm(reloaded.pending()!!.token)
        assertEquals(PluginOutcome.OpenPage("package"), proposeSource(reloaded, view, url, "test.plugin"))
        assertEquals(SourcePackageTarget(url, "test.plugin"), view.linkTarget)
        assertEquals(listOf(source), reloaded.records())
        assertNull(reloaded.pending())
    }
    @Test fun repeatedLinkOpensExistingSourcesWithoutAddingOrEnablingThem() {
        val store = SourceStore(PluginStorage(temporary.root), builtinSources = emptyList())
        val url = "https://example.invalid/catalog.json"
        val record = store.confirm(store.propose(url).token)
        store.toggle(record.key)
        val before = store.records()
        val view = StoreView()
        assertEquals(PluginOutcome.OpenPage("sources"), proposeSource(store, view, url))
        assertEquals(before, store.records())
        assertNull(store.pending())
        assertTrue(view.sourceNotice.contains("已经添加"))
        assertTrue(view.sourceNotice.contains("开关"))
        assertEquals(PluginOutcome.OpenPage("source-review"), proposeSource(store, view, "https://example.invalid/another.json"))
        assertEquals("", view.sourceNotice)
        assertEquals(before, store.records())
        assertNotNull(store.pending())
    }
}
