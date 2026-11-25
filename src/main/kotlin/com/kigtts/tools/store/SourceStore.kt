package com.kigtts.tools.store

import com.kigtts.plugin.api.PluginJson
import com.kigtts.plugin.api.PluginStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.UUID

@Serializable data class SourceRecord(val key: String = UUID.randomUUID().toString(), val url: String,
    val name: String, val enabled: Boolean = true, val catalogId: String? = null, val lastFetched: Long = 0, val error: String = "", val errorKey: String? = null)
@Serializable data class SourceCandidate(val token: String = UUID.randomUUID().toString(), val url: String, val packageId: String? = null)
@Serializable private data class SourceConfiguration(val records: List<SourceRecord> = emptyList(), val pending: SourceCandidate? = null, val officialSourceSeeded: Boolean = false, val resourceSourceSeeded: Boolean = false, val expressionSourceSeeded: Boolean = false)

/** Source approval and package publisher trust are independent; adding a source never installs a package. */
class SourceStore(private val storage: PluginStorage, private val builtinSources: List<SourceRecord> = listOf(SourceRecord(key = "kigtts-official", url = com.kigtts.plugin.api.OfficialPluginSource.URL,
    name = com.kigtts.plugin.api.OfficialPluginSource.NAME, catalogId = com.kigtts.plugin.api.OfficialPluginSource.ID), SourceRecord(key = "kigtts-resources", url = "https://modelscope.cn/datasets/LHTSTUDIO/KIGTTS-Resources/resolve/master/index.json?view=false", name = "KIGTTS 资源", catalogId = "kigtts.resources"),
    SourceRecord(key = "kigtts-expressions", url = "https://modelscope.cn/datasets/LHTSTUDIO/KIGTTS-Resources/resolve/master/expressions.json?view=false",
        name = "KIGTTS 表情特效", catalogId = "kigtts.expressions"))) {
    private val configuration = storage.file("config/sources.json")
    private var state = read<SourceConfiguration>(configuration) ?: SourceConfiguration()
    private val catalogs = linkedMapOf<String, PackageCatalog>()
    private val previews = linkedMapOf<String, SourceRecord>()
    init {
        if (!state.officialSourceSeeded && builtinSources.isNotEmpty()) {
            state = state.copy(records = state.records + builtinSources.filter { builtin -> state.records.none { it.url == builtin.url || it.key == builtin.key || it.catalogId == builtin.catalogId } }.take((32 - state.records.size).coerceAtLeast(0)), officialSourceSeeded = true)
            save()
        }
        seed("kigtts-resources", state.resourceSourceSeeded) { it.copy(resourceSourceSeeded = true) }
        seed("kigtts-expressions", state.expressionSourceSeeded) { it.copy(expressionSourceSeeded = true) }
        state.records.forEach { source ->
            runCatching { CatalogCodec.parse(storage.file("catalogs/${source.key}.json").readBytes()) }
                .getOrNull()?.takeIf { it.id == source.catalogId }?.let { catalogs[source.key] = it }
        }
    }
    private fun seed(key: String, seeded: Boolean, mark: (SourceConfiguration) -> SourceConfiguration) {
        if (seeded) return
        val source = builtinSources.firstOrNull { it.key == key } ?: return
        if (state.records.none { old -> old.key == key || old.url == source.url || old.catalogId == source.catalogId }) {
            if (state.records.size >= 32) return
            state = state.copy(records = state.records + source)
        }
        state = mark(state)
        save()
    }

    @Synchronized fun records() = state.records
    @Synchronized fun pending() = state.pending
    @Synchronized fun findUrl(url: String) = state.records.firstOrNull { it.url == url } ?: previews.values.firstOrNull { it.url == url }
    @Synchronized fun preview(url: String): SourceRecord {
        val address = CatalogCodec.https(url)
        findUrl(address)?.let { return it }
        require(previews.size < 16) { throw StoreSourceIssue("source.issue.previews", "打开的临时来源较多，请关闭商店后重试") }
        return SourceRecord(url = address, name = java.net.URI(address).host).also { previews[it.key] = it }
    }
    @Synchronized fun allEntries() = entries() + previews.values.flatMap(::visibleEntries)
    @Synchronized fun recommends(entry: CatalogEntry) = state.records.none { it.url == entry.source.url || it.catalogId == catalogs[entry.source.key]?.id } && catalogs[entry.source.key]?.linkPolicy?.recommendAfterInstall == true
    @Synchronized fun propose(url: String, packageId: String? = null): SourceCandidate {
        require(packageId == null || CatalogCodec.ID.matches(packageId)) { throw StoreSourceIssue("source.issue.package_id", "插件标识不合法") }
        require(packageId == null || !isPausedPackage(packageId)) { throw StoreSourceIssue("source.issue.paused", "此插件暂不可用") }
        val normalized = CatalogCodec.https(url)
        require(state.records.none { it.url == normalized }) { throw StoreSourceIssue("source.issue.duplicate", "这个源已经添加过了，可在“插件来源”中查看。") }
        return SourceCandidate(url = normalized, packageId = packageId).also { state = state.copy(pending = it); save() }
    }
    @Synchronized fun cancelCandidate() { state = state.copy(pending = null); save() }
    @Synchronized fun confirm(token: String): SourceRecord {
        val candidate = state.pending?.takeIf { it.token == token } ?: throw StoreSourceStateIssue("source.issue.expired", "确认已失效")
        require(candidate.packageId == null || !isPausedPackage(candidate.packageId)) { throw StoreSourceIssue("source.issue.paused", "此插件暂不可用") }
        require(state.records.size < 32) { throw StoreSourceIssue("source.issue.limit", "最多可添加 32 个源") }
        require(state.records.none { it.url == candidate.url }) { throw StoreSourceIssue("source.issue.duplicate", "这个源已经添加过了") }
        val record = previews.values.firstOrNull { it.url == candidate.url } ?: SourceRecord(url = candidate.url, name = java.net.URI(candidate.url).host)
        require(record.catalogId == null || state.records.none { it.catalogId == record.catalogId }) { throw StoreSourceIssue("source.issue.same", "这个源已通过其它地址添加") }
        previews.remove(record.key)
        catalogs[record.key]?.let { atomic(storage.file("catalogs/${record.key}.json"), PluginJson.encodeToString(it)) }
        state = state.copy(records = state.records + record, pending = null); save(); return record
    }
    @Synchronized fun source(key: String) = state.records.firstOrNull { it.key == key } ?: previews[key] ?: throw StoreSourceStateIssue("source.issue.missing", "源不存在")
    @Synchronized fun catalog(key: String) = catalogs[key]?.let { it.copy(packages = it.packages.filterNot { item -> isPausedPackage(item.id) }) }
    @Synchronized fun categories(sourceKey: String? = null): List<CatalogCategory> {
        val available = state.records.filter { it.enabled && (sourceKey == null || it.key == sourceKey) }
            .flatMap { source -> catalog(source.key)?.availableCategories().orEmpty() }
        return available.groupBy { it.kind to it.id }.values.map { values ->
            val preferred = values.first()
            val translations = linkedMapOf<String, String>()
            values.forEach { category -> category.localizations.forEach { (tag, label) ->
                translations.putIfAbsent(tag.lowercase(java.util.Locale.ROOT), label)
            } }
            preferred.copy(symbol = values.firstNotNullOfOrNull { it.symbol }, localizations = translations)
        }.sortedWith(compareBy<CatalogCategory> { it.name }.thenBy { it.id })
    }

    @Synchronized fun entries(): List<CatalogEntry> {
        val merged = categories().associateBy { it.kind to it.id }
        return state.records.filter { it.enabled }.flatMap(::visibleEntries).map { entry ->
            entry.copy(category = merged[entry.category.kind to entry.category.id] ?: entry.category)
        }
    }
    private fun visibleEntries(source: SourceRecord) = catalog(source.key)?.let { catalog ->
        catalog.packages.map { CatalogEntry(source, it, catalog.category(it)) }
    }.orEmpty()
    @Synchronized fun fetched(key: String, catalog: PackageCatalog) {
        CatalogCodec.validate(catalog)
        val previous = source(key)
        require(previous.catalogId == null || previous.catalogId == catalog.id) { throw StoreSourceIssue("source.issue.changed", "源 ID 发生变化，请移除后重新确认") }
        require(key in previews || state.records.none { it.key != key && it.catalogId == catalog.id }) { throw StoreSourceIssue("source.issue.same", "这个源已通过其它地址添加，可在“插件来源”中查看。") }
        if (key !in previews) atomic(storage.file("catalogs/$key.json"), PluginJson.encodeToString(catalog))
        catalogs[key] = catalog
        update(previous.copy(name = catalog.name, catalogId = catalog.id, lastFetched = System.currentTimeMillis(), error = "", errorKey = null))
    }
    @Synchronized fun failed(key: String, message: String, errorKey: String? = null) {
        update(source(key).copy(error = message.take(180), errorKey = errorKey))
    }
    @Synchronized fun toggle(key: String) { val record = source(key); update(record.copy(enabled = !record.enabled)) }
    @Synchronized fun reorder(keys: List<String>) {
        require(keys.size == state.records.size && keys.distinct().size == keys.size && keys.toSet() == state.records.map { it.key }.toSet())
        state = state.copy(records = keys.map(::source)); save()
    }
    @Synchronized fun remove(key: String) {
        source(key); state = state.copy(records = state.records.filter { it.key != key }); catalogs.remove(key)
        storage.file("catalogs/$key.json").delete(); save()
    }
    private fun update(record: SourceRecord) { if (record.key in previews) { previews[record.key] = record; return }; state = state.copy(records = state.records.map { if (it.key == record.key) record else it }); save() }
    private fun save() = atomic(configuration, PluginJson.encodeToString(state))
    private inline fun <reified T> read(file: File): T? = runCatching {
        require(file.length() <= CatalogCodec.MAX_BYTES); PluginJson.decodeFromString<T>(file.readText(Charsets.UTF_8))
    }.getOrNull()
    private fun atomic(file: File, text: String) {
        file.parentFile?.mkdirs(); val temp = File(file.parentFile, "${file.name}.pending")
        temp.writeText(text, Charsets.UTF_8)
        java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}
