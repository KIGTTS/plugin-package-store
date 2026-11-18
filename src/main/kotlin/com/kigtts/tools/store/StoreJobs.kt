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
    internal var downloads: StoreDownloads? = null
    @Volatile private var active: Job? = null
    private var refreshOnIdle = false
    private val previewQueue = linkedSetOf<String>()
    private var refreshingAll = false
    private var closed = false
    @Volatile private var statusValues: Map<String, String> = emptyMap()
    @Volatile private var errorKey: String? = null
    internal fun status(copy: StoreCopy) = copy.text(status, *statusValues.toList().toTypedArray())
    internal fun error(copy: StoreCopy) = errorKey?.let(copy::text) ?: copy.text("operation_failed")
    @Volatile var status: String = ""; private set
    @Volatile var error: String = ""; private set
    @Volatile var progress: DownloadState? = null; private set
    @Volatile var prepared: PreparedInstall? = null; private set
    @Volatile var pendingPlan: DependencyPlan? = null; private set
    @Volatile var refreshing: Boolean = false; private set
    @Volatile var installed: Map<String, InstalledPackage> = emptyMap(); private set
    val busy get() = active != null
    /** Entry requests coalesce with an active full refresh, or wait for downloads/local reloads. */
    @Synchronized fun entered() {
        if (closed || refreshingAll) return
        if (busy) refreshOnIdle = true else refresh()
    }
    @Synchronized fun refresh(key: String? = null) = start("source.jobs.refresh", refreshAll = key == null, sourceRefresh = true) {
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
                val issue = failure as? StoreSourceProblem
                sources.failed(source.key, failure.message?.takeIf { issue != null } ?: "源暂时无法更新，请检查地址与目录格式",
                    issue?.key ?: "source.issue.refresh")
            }
            changed()
        }
    }
    @Synchronized fun preview(url: String) {
        if (closed) return
        val source = sources.preview(url)
        if (!source.enabled) return
        if (busy) { previewQueue += source.key; return }
        refresh(source.key)
    }
    @Synchronized fun prepare(entry: CatalogEntry, versionCode: Int) = start("source.jobs.dependencies") {
        prepared = null; pendingPlan = null; refreshInstalled()
        pendingPlan = DependencyResolver(sources.allEntries(), installed, device).resolve(entry, versionCode)
    }
    /** Downloads only the exact dependency/version snapshot the user has reviewed. */
    @Synchronized fun downloadPlan() {
        val plan = requireNotNull(pendingPlan) { throw StoreSourceIssue("source.issue.check_plan", "请先检查安装计划") }
        start("source.jobs.download") {
        prepared = null
        val artifacts = mutableListOf<Pair<PlannedRelease, VerifiedArtifact>>()
        plan.ordered.forEach { selected ->
            statusValues = mapOf("name" to selected.entry.item.name, "version" to selected.release.version)
            status = "source.jobs.package"; changed()
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
    }
    @Synchronized fun reloadInstalled() { if (!busy && !closed) start("") { refreshInstalled() } }
    private fun start(label: String, refreshAll: Boolean = false, sourceRefresh: Boolean = false, work: suspend () -> Unit) {
        check(!closed)
        check(!busy) { throw StoreSourceIssue("source.issue.busy", "请等待当前任务完成或先取消") }
        refreshingAll = refreshAll
        refreshing = sourceRefresh
        error = ""; errorKey = null; statusValues = emptyMap(); status = label
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try { work() }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { if (currentCoroutineContext().isActive) {
                errorKey = (failure as? StoreSourceProblem)?.key
                error = failure.message?.take(200) ?: "操作暂时无法完成"
            } }
            finally {
                synchronized(this@StoreJobs) {
                    progress = null; status = ""; active = null; refreshingAll = false; refreshing = false
                    if (!closed && previewQueue.isNotEmpty()) {
                        val key = previewQueue.first(); previewQueue.remove(key); refresh(key)
                    } else if (refreshOnIdle && !closed) { refreshOnIdle = false; refresh() }
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
        device.updateHostCompatibility(reply.value)
        installed = reply.value["plugins"]?.jsonArray.orEmpty().mapNotNull { item ->
            runCatching {
                val value = item.jsonObject
                if (value["installed"]?.jsonPrimitive?.booleanOrNull != true) return@runCatching null
                val id = value.getValue("id").jsonPrimitive.content
                if (isPausedPackage(id)) return@runCatching null
                InstalledPackage(id, value.getValue("version").jsonPrimitive.content,
                    value["versionCode"]?.jsonPrimitive?.intOrNull ?: 0, value["enabled"]?.jsonPrimitive?.booleanOrNull ?: false,
                    value["publisherSha256"]?.jsonPrimitive?.contentOrNull ?: value["publisher"]?.jsonPrimitive?.contentOrNull,
                    value["apiVersion"]?.jsonPrimitive?.intOrNull ?: PLUGIN_API_VERSION,
                    if (value["kind"]?.jsonPrimitive?.content == "lua") PackageFormat.LUA else PackageFormat.NATIVE,
                    value["builtin"]?.jsonPrimitive?.booleanOrNull == true)
            }.getOrNull()
        }.associateBy { it.id }
    }
    fun clearPrepared() { prepared = null; pendingPlan = null; changed() }
    fun luaResources(artifact: VerifiedArtifact) = inspector.luaResources(artifact)
    @Synchronized fun cancel() { refreshOnIdle = false; previewQueue.clear(); active?.cancel(); downloader.cancel(); changed() }
    suspend fun close() { synchronized(this) { closed = true }; cancel(); active?.join(); scope.cancel() }
}
