package com.kigtts.tools.store

import com.kigtts.plugin.api.PluginJson
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class InstalledMetadataTest {
    @Test fun nativeInstalledStatusMustMatchActualIdAndVersionCode() {
        val selected = PlannedRelease(entry("test.plugin", release()), release())
        val installed = PluginJson.parseToJsonElement("""{"plugins":[{"id":"test.plugin","version":"1.0.0","versionCode":1,"installed":true}]}""").jsonObject
        assertTrue(actualInstalled(installed, selected))
        assertFalse(actualInstalled(installed, selected.copy(release = release("2.0.0", 2))))
        val submitted = PluginJson.parseToJsonElement("{\"accepted\":true,\"pending\":true}").jsonObject
        assertFalse(actualInstalled(submitted, selected))
    }
    @Test fun luaRequiresManagedPackageInstalledMetadataRatherThanSubmission() {
        val version = release().copy(format = PackageFormat.LUA)
        val selected = PlannedRelease(entry("test.lua", version), version)
        val installed = PluginJson.parseToJsonElement("""{"plugins":[{"id":"test.lua","version":"1.0.0","kind":"lua","installed":true}]}""").jsonObject
        assertTrue(actualInstalled(installed, selected))
        val draft = PluginJson.parseToJsonElement("""{"plugins":[{"id":"test.lua","version":"1.0.0","kind":"lua","installed":false}]}""").jsonObject
        assertFalse(actualInstalled(draft, selected))
    }
}
