package com.kigtts.tools.store

import com.kigtts.plugin.api.PluginJson
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class PluginPackageDeclarationTest {
    @Test fun executableDeclarationMatchesThePackageManifest() {
        val spec = PluginJson.parseToJsonElement(File("plugin-spec.json").readText()).jsonObject
        val plugin = PackageStorePlugin()
        val descriptor = plugin.descriptor
        assertEquals(spec.getValue("id").jsonPrimitive.content, descriptor.id)
        assertEquals(spec.getValue("version").jsonPrimitive.content, descriptor.version)
        assertEquals(spec.getValue("apiVersion").jsonPrimitive.int, descriptor.apiVersion)
        assertEquals(spec.getValue("license").jsonPrimitive.content, descriptor.license)
        assertEquals(spec.getValue("entryClass").jsonPrimitive.content, plugin.javaClass.name)
    }
}
