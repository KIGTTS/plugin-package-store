package com.kigtts.tools.store

import com.kigtts.plugin.api.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File

data class PreparedInstall(val plan: DependencyPlan, val artifacts: List<Pair<PlannedRelease, VerifiedArtifact>>)

/** Source metadata and packages preload in IO; pages expose stable snapshots without replaying entry animation. */
class StoreJobs(private val environment: PluginEnvironment, private val sources: SourceStore, private val device: StoreDevice,
    private val downloader: PackageDownloader, private val inspector: ArtifactInspector, private val changed: () -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    val artwork: CatalogArtworkCache? = null) {
    @Volatile private var active: Job? = null
    private var refreshOnIdle = false
    private var refreshingAll = false
    private var closed = false
    @Volatile var status: String = ""; private set
    @Volatile var error: String = ""; private set
    @Volatile var progress: DownloadState? = null; private set
    @Volatile var prepared: PreparedInstall? = null; private set
    @Volatile var installed: Map<String, InstalledPackage> = emptyMap(); private set
    val busy get() = active != null
    /** Entry requests coalesce with an active full refresh, or wait for downloads/local reloads. */
    @Synchronized fun entered() {
        if (closed || refreshingAll) return
        if (busy) refreshOnIdle = true else refresh()
    }
    @Synchronized fun refresh(key: String? = null) = start("正在更新源", refreshAll = key == null) {
        refreshInstalled()
        val records = if (key == null) sources.records().filter { it.enabled } else listOf(sources.source(key))
        records.forEach { source ->
            try {
                val catalog = downloader.catalog(source)
                sources.fetched(source.key, catalog)
                artwork?.preload(catalog)
            }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                val message = failure.message?.takeIf { it.any { character -> character in '\u4e00'..'\u9fff' } } ?: "源暂时无法更新，请检查地址与目录格式"
                sources.failed(source.key, message)
            }
            changed()
        }
    }
    @Synchronized fun prepare(entry: CatalogEntry, versionCode: Int) = start("正在检查前置依赖") {
        prepared = null; refreshInstalled()
        val plan = DependencyResolver(sources.entries(), installed, device).resolve(entry, versionCode)
        val artifacts = mutableListOf<Pair<PlannedRelease, VerifiedArtifact>>()
        plan.ordered.forEach { selected ->
            status = "正在下载 ${selected.entry.item.name} ${selected.release.version}"; changed()
            var lastNotification = 0L
            val file = downloader.packageFile(selected.entry, selected.release) { next ->
                progress = next
                val now = System.nanoTime()
                if (now - lastNotification > 150_000_000L) { lastNotification = now; changed() }
            }
            val context = currentCoroutineContext()
            artifacts += selected to inspector.inspect(file, selected.entry, selected.release) { context.ensureActive() }
        }
        prepared = PreparedInstall(plan, artifacts); progress = null
    }
    @Synchronized fun reloadInstalled() = start("") { refreshInstalled() }
    private fun start(label: String, refreshAll: Boolean = false, work: suspend () -> Unit) {
        check(!closed)
        check(!busy) { "请等待当前任务完成或先取消" }
        refreshingAll = refreshAll
        error = ""; status = label
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try { work() }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { if (currentCoroutineContext().isActive) error = failure.message?.take(200) ?: "操作暂时无法完成" }
            finally {
                synchronized(this@StoreJobs) {
                    progress = null; status = ""; active = null; refreshingAll = false
                    if (refreshOnIdle && !closed) { refreshOnIdle = false; refresh() }
                }
                changed()
            }
        }
        active = job
        job.start()
        changed()
    }
    private suspend fun refreshInstalled() {
        val reply = environment.appBroker?.request(AppCommand.Describe) as? AppReply.Data ?: return
        installed = reply.value["plugins"]?.jsonArray.orEmpty().mapNotNull { item ->
            runCatching {
                val value = item.jsonObject
                if (value["installed"]?.jsonPrimitive?.booleanOrNull != true) return@runCatching null
                InstalledPackage(value.getValue("id").jsonPrimitive.content, value.getValue("version").jsonPrimitive.content,
                    value["versionCode"]?.jsonPrimitive?.intOrNull ?: 0, value["enabled"]?.jsonPrimitive?.booleanOrNull ?: false,
                    value["publisherSha256"]?.jsonPrimitive?.contentOrNull ?: value["publisher"]?.jsonPrimitive?.contentOrNull,
                    value["apiVersion"]?.jsonPrimitive?.intOrNull ?: PLUGIN_API_VERSION,
                    if (value["kind"]?.jsonPrimitive?.content == "lua") PackageFormat.LUA else PackageFormat.NATIVE)
            }.getOrNull()
        }.associateBy { it.id }
    }
    fun clearPrepared() { prepared = null; changed() }
    fun luaResources(artifact: VerifiedArtifact) = inspector.luaResources(artifact)
    @Synchronized fun cancel() { refreshOnIdle = false; active?.cancel(); downloader.cancel(); changed() }
    suspend fun close() { synchronized(this) { closed = true }; cancel(); active?.join(); scope.cancel() }
}
