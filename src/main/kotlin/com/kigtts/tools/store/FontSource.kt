package com.kigtts.tools.store

import com.kigtts.plugin.api.PluginOutcome

internal const val FONT_SOURCE = "https://modelscope.cn/datasets/LHTSTUDIO/KIGTTS-Fonts/resolve/master/kpm/index.json?view=false"

internal fun openFontLibrary(sources: SourceStore, view: StoreView): PluginOutcome {
    val source = sources.records().firstOrNull { it.url.substringBefore('?') == FONT_SOURCE.substringBefore('?') }
        ?: return proposeSource(sources, view, FONT_SOURCE)
    if (!source.enabled) return PluginOutcome.Message("请先启用字体来源")
    view.query = ""
    return PluginOutcome.OpenPage(catalogPageId(source.key, "字体"))
}
