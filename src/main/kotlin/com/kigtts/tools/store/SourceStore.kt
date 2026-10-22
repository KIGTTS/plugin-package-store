package com.kigtts.tools.store

import com.kigtts.plugin.api.PluginJson
import com.kigtts.plugin.api.PluginStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.UUID

@Serializable data class SourceRecord(val key: String = UUID.randomUUID().toString(), val url: String,
    val name: String, val enabled: Boolean = true, val catalogId: String? = null, val lastFetched: Long = 0, val error: String = "")
@Serializable data class SourceCandidate(val token: String = UUID.randomUUID().toString(), val url: String)
@Serializable private data class SourceConfiguration(val records: List<SourceRecord> = emptyList(), val pending: SourceCandidate? = null, val officialSourceSeeded: Boolean = false)

/** Source approval and package publisher trust are independent; adding a source never installs a package. */
class SourceStore(private val storage: PluginStorage, private val builtinSources: List<SourceRecord> = listOf(SourceRecord(key = "kigtts-official", url = com.kigtts.plugin.api.OfficialPluginSource.URL,
    name = com.kigtts.plugin.api.OfficialPluginSource.NAME, catalogId = com.kigtts.plugin.api.OfficialPluginSource.ID))) {
    private val configuration = storage.file("config/sources.json")
    private var state = read<SourceConfiguration>(configuration) ?: SourceConfiguration()
    private val catalogs = linkedMapOf<String, PackageCatalog>()
    init {
        if (!state.officialSourceSeeded && builtinSources.isNotEmpty()) {
            state = state.copy(records = state.records + builtinSources.filter { builtin -> state.records.none { it.url == builtin.url || it.key == builtin.key || it.catalogId == builtin.catalogId } }.take((32 - state.records.size).coerceAtLeast(0)), officialSourceSeeded = true)
            save()
        }
        state.records.forEach { source ->
            runCatching { CatalogCodec.parse(storage.file("catalogs/${source.key}.json").readBytes()) }
                .getOrNull()?.takeIf { it.id == source.catalogId }?.let { catalogs[source.key] = it }
        }
    }
    @Synchronized fun records() = state.records
    @Synchronized fun pending() = state.pending
    @Synchronized fun propose(url: String): SourceCandidate {
        val normalized = CatalogCodec.https(url)
        require(state.records.none { it.url == normalized }) { "这个源已经添加过了，可在“插件来源”中查看。" }
        return SourceCandidate(url = normalized).also { state = state.copy(pending = it); save() }
    }
    @Synchronized fun cancelCandidate() { state = state.copy(pending = null); save() }
    @Synchronized fun confirm(token: String): SourceRecord {
        val candidate = state.pending?.takeIf { it.token == token } ?: error("确认已失效")
        require(state.records.size < 32) { "最多可添加 32 个源" }
        val record = SourceRecord(url = candidate.url, name = java.net.URI(candidate.url).host)
        state = state.copy(records = state.records + record, pending = null); save(); return record
    }
    @Synchronized fun source(key: String) = state.records.firstOrNull { it.key == key } ?: error("源不存在")
    @Synchronized fun catalog(key: String) = catalogs[key]
    @Synchronized fun entries() = state.records.filter { it.enabled }.flatMap { source -> catalogs[source.key]?.packages.orEmpty().map { CatalogEntry(source, it) } }
    @Synchronized fun fetched(key: String, catalog: PackageCatalog) {
        CatalogCodec.validate(catalog)
        val previous = source(key)
        require(previous.catalogId == null || previous.catalogId == catalog.id) { "源 ID 发生变化，请移除后重新确认" }
        require(state.records.none { it.key != key && it.catalogId == catalog.id }) { "这个源已通过其它地址添加，可在“插件来源”中查看。" }
        atomic(storage.file("catalogs/$key.json"), PluginJson.encodeToString(catalog))
        catalogs[key] = catalog
        update(previous.copy(name = catalog.name, catalogId = catalog.id, lastFetched = System.currentTimeMillis(), error = ""))
    }
    @Synchronized fun failed(key: String, message: String) { update(source(key).copy(error = message.take(180))) }
    @Synchronized fun toggle(key: String) { val record = source(key); update(record.copy(enabled = !record.enabled)) }
    @Synchronized fun reorder(keys: List<String>) {
        require(keys.size == state.records.size && keys.distinct().size == keys.size && keys.toSet() == state.records.map { it.key }.toSet())
        state = state.copy(records = keys.map(::source)); save()
    }
    @Synchronized fun remove(key: String) {
        source(key); state = state.copy(records = state.records.filter { it.key != key }); catalogs.remove(key)
        storage.file("catalogs/$key.json").delete(); save()
    }
    private fun update(record: SourceRecord) { state = state.copy(records = state.records.map { if (it.key == record.key) record else it }); save() }
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
