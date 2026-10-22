package com.kigtts.tools.store

import org.junit.Assert.*
import org.junit.Test

class DependencyResolverTest {
    @Test fun compatibleApiNineDependencyIsRetainedOnApiTen() {
        val root = entry("test.root", release(dependencies = mapOf("test.core" to "1.x")).copy(apiVersion = 10))
        val existing = InstalledPackage("test.core", "1.0.0", 1, true, apiVersion = 9)
        val plan = DependencyResolver(listOf(root), mapOf(existing.id to existing), StoreDevice(35, setOf("arm64-v8a"), api = 10)).resolve(root, 1)
        assertEquals(listOf("test.root"), plan.ordered.map { it.entry.item.id })
        assertTrue(release().copy(apiVersion = 9).compatible(StoreDevice(35, setOf("arm64-v8a"), api = 10)))
    }
    @Test fun sortsTransitiveDependenciesBeforeRoot() {
        val root = entry("test.root", release(dependencies = mapOf("test.core" to "^1.0.0")))
        val core = entry("test.core", release(dependencies = mapOf("test.base" to "1.x")))
        val base = entry("test.base", release())
        val plan = DependencyResolver(listOf(root, core, base), emptyMap(), testDevice).resolve(root, 1)
        assertEquals(listOf("test.base", "test.core", "test.root"), plan.ordered.map { it.entry.item.id })
    }
    @Test fun backtracksInsteadOfPickingIncompatibleLatestDependency() {
        val root = entry("test.root", release(dependencies = mapOf("test.b" to ">=1.0.0", "test.c" to "1.0.0")))
        val b = entry("test.b", release("1.0.0", 1, mapOf("test.d" to "1.x")), release("2.0.0", 2, mapOf("test.d" to "2.x")))
        val c = entry("test.c", release(dependencies = mapOf("test.d" to "1.x")))
        val d = entry("test.d", release("1.0.0", 1), release("2.0.0", 2))
        val plan = DependencyResolver(listOf(root, b, c, d), emptyMap(), testDevice).resolve(root, 1)
        assertEquals("1.0.0", plan.ordered.first { it.entry.item.id == "test.b" }.release.version)
    }
    @Test fun missingOrConflictingDependencyFailsBeforeDownload() {
        val root = entry("test.root", release(dependencies = mapOf("test.missing" to "^1.0.0")))
        assertThrows(IllegalStateException::class.java) { DependencyResolver(listOf(root), emptyMap(), testDevice).resolve(root, 1) }
    }
    @Test fun cyclesAreRejected() {
        val root = entry("test.root", release(dependencies = mapOf("test.core" to "1.0.0")))
        val core = entry("test.core", release(dependencies = mapOf("test.root" to "1.0.0")))
        assertThrows(IllegalArgumentException::class.java) { DependencyResolver(listOf(root, core), emptyMap(), testDevice).resolve(root, 1) }
    }
    @Test fun duplicateIdPublishersAndSameVersionDifferentContentFail() {
        val root = entry("test.root", release())
        val otherPublisher = entry("test.root", release(publisher = "c".repeat(64)), source = "source-b")
        assertThrows(IllegalArgumentException::class.java) { DependencyResolver(listOf(root, otherPublisher), emptyMap(), testDevice).resolve(root, 1) }
        val differentContent = entry("test.root", release().copy(sha256 = "c".repeat(64)), source = "source-b")
        assertThrows(IllegalArgumentException::class.java) { DependencyResolver(listOf(root, differentContent), emptyMap(), testDevice).resolve(root, 1) }
    }
    @Test fun retainedCompatibleInstalledDependencyNeedsNoDownload() {
        val root = entry("test.root", release(dependencies = mapOf("test.core" to "^1.0.0")))
        val core = entry("test.core", release("1.1.0", 2))
        val installed = InstalledPackage("test.core", "1.1.0", 2, true, "a".repeat(64))
        val plan = DependencyResolver(listOf(root, core), mapOf(installed.id to installed), testDevice).resolve(root, 1)
        assertEquals(listOf("test.root"), plan.ordered.map { it.entry.item.id })
    }
    @Test fun explicitEarlierRootVersionRequiresReviewedDowngrade() {
        val root = entry("test.root", release("1.0.0", 1), release("2.0.0", 2))
        val old = InstalledPackage("test.root", "2.0.0", 2, true)
        val plan = DependencyResolver(listOf(root), mapOf(old.id to old), testDevice).resolve(root, 1)
        assertEquals(listOf("test.root"), plan.downgrades)
    }
    @Test fun rejectsChangesWhichBreakAnotherInstalledPackage() {
        val root = entry("test.core", release("1.0.0", 1), release("2.0.0", 2))
        val app = entry("test.app", release(dependencies = mapOf("test.core" to "1.x")))
        val installed = InstalledPackage("test.app", "1.0.0", 1, true)
        assertThrows(IllegalArgumentException::class.java) { DependencyResolver(listOf(root, app), mapOf(installed.id to installed), testDevice).resolve(root, 2) }
    }
    @Test fun incompatibleInstalledApiIsNotReusedEvenWithSameVersionText() {
        val root = entry("test.root", release(dependencies = mapOf("test.core" to "1.x")))
        val core = entry("test.core", release("1.0.0", 2))
        val installed = InstalledPackage("test.core", "1.0.0", 1, true, apiVersion = 8)
        val plan = DependencyResolver(listOf(root, core), mapOf(installed.id to installed), testDevice).resolve(root, 1)
        assertEquals(listOf("test.core", "test.root"), plan.ordered.map { it.entry.item.id })
    }
}
