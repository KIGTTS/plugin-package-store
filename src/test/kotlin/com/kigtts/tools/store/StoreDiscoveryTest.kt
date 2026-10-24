package com.kigtts.tools.store

import org.junit.Assert.*
import org.junit.Test

class StoreDiscoveryTest {
    @Test fun updateEntryShowsCountAndClearsWhenNoUpdatesRemain() {
        val mine = storeHeaderActions(103).actions.single { it.id == "library" }
        assertEquals("upgrade", mine.symbol)
        assertEquals("我的插件", mine.label)
        assertEquals(103, mine.badge!!.count)
        assertNull(storeHeaderActions().actions.single { it.id == "library" }.badge)
        val previousCatalog = entry("test.store", release("1.0.0", 1).copy(apiVersion = 25))
        val installed = mapOf("test.store" to InstalledPackage("test.store", "2.0.0", 2, true, apiVersion = 26))
        assertFalse("A stale catalog must not count an older API release as an update", previousCatalog.hasUpdate(installed, testDevice))
    }
    @Test fun discoveryKeepsStableOrderAndMergesMirroredCatalogListings() {
        val older = entry("test.audio", release("1.0.0"), source = "a")
        val newer = entry("test.audio", release("2.0.0", 2), source = "b")
        val other = entry("test.draw", release()).let { it.copy(item = it.item.copy(category = "创作", summary = "画板")) }
        val entries = listOf(other, older, newer)
        val first = discover(entries, StoreView(), emptyMap(), testDevice)
        assertEquals(2, first.size)
        assertEquals("b", first.single { it.item.id == "test.audio" }.source.key)
        assertEquals(first, discover(entries, StoreView(selected = newer), mapOf("test.audio" to InstalledPackage("test.audio", "2.0.0", 2, true)), testDevice))
        assertEquals(listOf(other), discover(entries, StoreView(query = "画板"), emptyMap(), testDevice))
        assertEquals(listOf(other), discover(entries, StoreView(category = "创作"), emptyMap(), testDevice))
        assertEquals(listOf(older), discover(entries, StoreView(source = "a"), emptyMap(), testDevice))
    }
    @Test fun installedAndUpdatesHaveIndependentSecondaryResults() {
        val first = entry("test.a", release("2.0.0", 2))
        val second = entry("test.b", release("1.0.0"))
        val installed = mapOf("test.a" to InstalledPackage("test.a", "1.0.0", 1, true))
        assertEquals(listOf(first), discover(listOf(second, first), StoreView(filter = "updates"), installed, testDevice))
        assertEquals(listOf(first), discover(listOf(second, first), StoreView(filter = "installed"), installed, testDevice))
        assertTrue(discover(listOf(first), StoreView(filter = "updates"), mapOf("test.a" to InstalledPackage("test.a", "2.0.0", 2, true)), testDevice).isEmpty())
        assertNotEquals(categoryKey("音频/合成"), categoryKey("音频/识别"))
        assertTrue(categoryKey("音频/合成").matches(Regex("[0-9a-f]{16}")))
    }
}
