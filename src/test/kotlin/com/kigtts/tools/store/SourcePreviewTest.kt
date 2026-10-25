package com.kigtts.tools.store

import com.kigtts.plugin.api.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourcePreviewTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun previewNeverAddsARecordAndDependenciesStayInTheLinkedSource() {
        val storage = PluginStorage(temp.root); val sources = SourceStore(storage, emptyList())
        val view = StoreView()
        assertEquals(PluginOutcome.OpenPage("package"), proposeSource(sources, view, "https://example.invalid/frames.json", "test.frame", false))
        assertTrue(sources.records().isEmpty()); assertNull(sources.pending())
        val preview = sources.findUrl(view.linkTarget!!.url)!!
        val root = entry("test.frame", release(dependencies = mapOf("test.dep" to "*"))).item
        sources.fetched(preview.key, catalog("test.preview", root, entry("test.dep", release()).item).copy(
            linkPolicy = SourceLinkPolicy(recommendAfterInstall = true)))
        assertTrue(sources.entries().isEmpty()); assertEquals(2, sources.allEntries().size)
        val candidate = sources.allEntries().first { it.item.id == "test.frame" }
        assertEquals(2, DependencyResolver(sources.allEntries(), emptyMap(), testDevice).resolve(candidate, 1).ordered.size)
        assertTrue(sources.recommends(candidate))
        assertTrue(SourceStore(storage, emptyList()).allEntries().isEmpty())
        val pending = sources.propose(preview.url, root.id)
        assertTrue(sources.records().isEmpty())
        val confirmed = sources.confirm(pending.token)
        assertEquals(preview.key, confirmed.key); assertEquals(2, sources.entries().size)
        assertFalse(sources.recommends(candidate)); assertEquals(2, SourceStore(storage, emptyList()).entries().size)
    }
    @Test fun requiredLinkStillWaitsForApprovalAndPolicyDefaultsToNoInvitation() {
        val sources = SourceStore(PluginStorage(temp.root), emptyList()); val view = StoreView()
        assertEquals(PluginOutcome.OpenPage("source-review"), proposeSource(sources, view, "https://example.invalid/source.json", "test.frame", true))
        assertTrue(sources.records().isEmpty()); assertNotNull(sources.pending())
        sources.cancelCandidate(); assertTrue(sources.allEntries().isEmpty())
        val preview = sources.preview("https://example.invalid/source.json")
        sources.fetched(preview.key, catalog("test.source", entry("test.frame", release()).item))
        assertFalse(sources.recommends(sources.allEntries().single()))
    }
}
