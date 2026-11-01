package com.kigtts.tools.store

import com.kigtts.plugin.api.MIN_PLUGIN_API_VERSION
import com.kigtts.plugin.api.PLUGIN_API_VERSION
import kotlinx.serialization.json.*

/** One shared device snapshot for browsing, dependency resolution and downloads. */
class StoreDevice(val sdk: Int, val abis: Set<String>, api: Int = PLUGIN_API_VERSION,
    minimumApi: Int = if (api >= 10) MIN_PLUGIN_API_VERSION else api) {
    @Volatile private var supportedApis = minimumApi..api
    val api get() = supportedApis.last
    val minimumApi get() = supportedApis.first

    /** Older hosts omit these fields; keep the conservative compiled-SDK fallback. */
    internal fun updateHostCompatibility(metadata: JsonObject) {
        val maximum = (metadata["pluginApi"] as? JsonPrimitive)?.intOrNull ?: return
        val minimum = (metadata["minimumPluginApi"] as? JsonPrimitive)?.intOrNull ?: return
        if (minimum < 1 || maximum < minimum) return
        supportedApis = minimum..maximum
    }
}
