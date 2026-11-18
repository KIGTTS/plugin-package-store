package com.kigtts.tools.store

import java.util.Locale
import kotlinx.serialization.Serializable

@Serializable
data class CatalogSourceTranslation(val name: String? = null, val description: String? = null, val readme: String? = null)

private fun <T : Any> Map<String, T>.translationsFor(locale: String): Sequence<T> =
    generateSequence(Locale.forLanguageTag(locale).toLanguageTag()) { tag ->
        tag.substringBeforeLast('-', "").takeIf(String::isNotEmpty)
    }.takeWhile { it != "und" }.mapNotNull { tag ->
        entries.firstOrNull { it.key.equals(tag, ignoreCase = true) }?.value
    }

internal fun CatalogPackage.localized(locale: String): CatalogPackage {
    val values = localizations.translationsFor(locale).toList()
    if (values.isEmpty()) return this
    return copy(
        name = values.firstNotNullOfOrNull { it.name } ?: name,
        summary = values.firstNotNullOfOrNull { it.summary } ?: summary,
        description = values.firstNotNullOfOrNull { it.description ?: it.summary } ?: description,
        thumbnails = values.firstNotNullOfOrNull { it.thumbnails } ?: thumbnails,
        tags = values.firstNotNullOfOrNull { it.tags } ?: tags,
    )
}

internal fun CatalogPackage.localizedNotes(release: PackageRelease, locale: String): String =
    localizations.translationsFor(locale).firstNotNullOfOrNull { it.releaseNotes[release.version] } ?: release.notes

internal fun PackageCatalog.localized(locale: String): PackageCatalog {
    val values = localizations.translationsFor(locale).toList()
    if (values.isEmpty()) return this
    return copy(
        name = values.firstNotNullOfOrNull { it.name } ?: name,
        description = values.firstNotNullOfOrNull { it.description } ?: description,
        readme = values.firstNotNullOfOrNull { it.readme ?: it.description } ?: readme,
    )
}

internal fun SourceStore.sourceName(source: SourceRecord, locale: String): String =
    catalog(source.key)?.localized(locale)?.name ?: source.name
