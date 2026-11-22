package com.kigtts.tools.store

import kotlinx.serialization.json.*

internal fun actualInstalled(metadata: JsonObject, planned: PlannedRelease): Boolean = planned.release.format == PackageFormat.NATIVE && metadata["plugins"]?.jsonArray.orEmpty().any { element ->
    runCatching {
        val value = element.jsonObject
        value["installed"]?.jsonPrimitive?.booleanOrNull == true && value["id"]?.jsonPrimitive?.content == planned.entry.item.id &&
            value["development"]?.jsonPrimitive?.booleanOrNull != true && value["kind"]?.jsonPrimitive?.content != "lua" &&
            (value["apiVersion"]?.jsonPrimitive?.intOrNull ?: planned.release.apiVersion) == planned.release.apiVersion &&
            value["version"]?.jsonPrimitive?.content == planned.release.version &&
            value["versionCode"]?.jsonPrimitive?.intOrNull == planned.release.versionCode
    }.getOrDefault(false)
}
