package com.kigtts.tools.store

import com.kigtts.plugin.api.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FontSourceTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun fontsRequireSourceApprovalThenOpenTheirOwnCategory() {
        val sources = SourceStore(PluginStorage(temporary.root), builtinSources = emptyList())
        val view = StoreView().apply { query = "旧搜索" }
        assertEquals(PluginOutcome.OpenPage("source-review"), openFontLibrary(sources, view))
        assertTrue(sources.records().isEmpty())
        val source = sources.confirm(requireNotNull(sources.pending()).token)
        assertEquals(PluginOutcome.OpenPage(catalogPageId(source.key, "字体")), openFontLibrary(sources, view))
        assertEquals("", view.query)
        assertEquals(1, sources.records().size)
        assertTrue(ResourceType.entries.any { it.name == "FONT" && it.label == "字体" })
    }
}
