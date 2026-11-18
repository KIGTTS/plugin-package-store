package com.kigtts.tools.store

import com.kigtts.plugin.api.ResourceType
import java.util.Locale

internal fun StoreCopy.sectionLabel(section: String): String = when (section) {
    "音频" -> text("category.audio")
    "外观" -> text("category.appearance")
    "内容" -> text("category.content")
    "输入" -> text("category.input")
    "开发" -> text("category.development")
    "扩展" -> text("category.extensions")
    else -> ResourceType.entries.firstOrNull { it.label == section }?.let(::resourceTypeLabel) ?: section
}

internal fun StoreCopy.resourceTypeLabel(type: ResourceType): String =
    text("resource.${type.name.lowercase(Locale.ROOT)}")
