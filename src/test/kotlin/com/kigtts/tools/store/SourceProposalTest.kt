package com.kigtts.tools.store

import com.kigtts.plugin.api.PluginOutcome
import com.kigtts.plugin.api.PluginStorage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceProposalTest {
    @get:Rule val temporary = TemporaryFolder()
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
