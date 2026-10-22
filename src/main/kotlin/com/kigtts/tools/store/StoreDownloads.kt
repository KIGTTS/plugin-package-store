package com.kigtts.tools.store

import com.kigtts.plugin.api.*
import kotlinx.coroutines.*

/** Serial network work, independently actionable rows, and installation verified from host metadata. */
internal class StoreDownloads(private val environment: PluginEnvironment, private val sources: SourceStore,
    private val device: StoreDevice, private val downloader: PackageDownloader, private val inspector: ArtifactInspector,
    private val changed: () -> Unit, private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
    @Volatile var tasks: List<StoreDownload> = emptyList(); private set
    private var active: Pair<String, Job>? = null
    private var closed = false
    @Synchronized private fun update(id: String, change: (StoreDownload) -> StoreDownload) {
        tasks = tasks.map { if (it.id == id) change(it) else it }; changed()
    }
    fun task(id: String) = tasks.firstOrNull { it.id == id } ?: error("下载记录已移除，请重新选择插件")
    fun latest(packageId: String) = tasks.lastOrNull { it.root.entry.item.id == packageId }
    suspend fun offer(entry: CatalogEntry, versionCode: Int, installed: Map<String, InstalledPackage>): StoreDownload {
        val existing = latest(entry.item.id)
        if (existing != null && existing.root.release.versionCode == versionCode && existing.phase !in setOf(StoreDownloadPhase.INSTALLED, StoreDownloadPhase.CANCELLED, StoreDownloadPhase.FAILED)) return existing
        check(existing == null || existing.phase in setOf(StoreDownloadPhase.INSTALLED, StoreDownloadPhase.CANCELLED, StoreDownloadPhase.FAILED)) { "此插件已有下载任务，请先完成或取消" }
        val plan = DependencyResolver(sources.entries(), installed, device).resolve(entry, versionCode)
        val next = StoreDownload(plan = plan)
        synchronized(this) {
            check(!closed)
            if (tasks.size >= 50) tasks = tasks.filterNot { it.phase in setOf(StoreDownloadPhase.INSTALLED, StoreDownloadPhase.CANCELLED, StoreDownloadPhase.FAILED) }.takeLast(49)
            check(tasks.size < 50) { "下载任务较多，请先完成或取消部分任务" }
            tasks = tasks + next
        }
        changed(); return next
    }
    @Synchronized fun confirm(id: String) {
        val current = task(id)
        if (current.phase != StoreDownloadPhase.CONFIRM) return
        update(id) { it.copy(phase = StoreDownloadPhase.QUEUED) }; startNext()
    }
    @Synchronized fun retry(id: String) {
        check(active?.first != id) { "正在取消，请稍后重试" }
        check(task(id).phase in setOf(StoreDownloadPhase.FAILED, StoreDownloadPhase.CANCELLED))
        update(id) { it.copy(phase = StoreDownloadPhase.QUEUED, detail = "", progress = null) }; startNext()
    }
    @Synchronized fun cancel(id: String) {
        val current = task(id)
        if (current.phase in setOf(StoreDownloadPhase.INSTALLED, StoreDownloadPhase.INSTALLING)) return
        update(id) { it.copy(phase = StoreDownloadPhase.CANCELLED, progress = null) }
        if (active?.first == id) { active?.second?.cancel(); downloader.cancel() }
    }
    @Synchronized private fun startNext() {
        if (closed || active != null) return
        val next = tasks.firstOrNull { it.phase == StoreDownloadPhase.QUEUED } ?: return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val artifacts = mutableListOf<Pair<PlannedRelease, VerifiedArtifact>>()
                val total = next.plan.ordered.sumOf { it.release.bytes }.coerceAtLeast(1)
                var completed = 0L; var lastUpdate = 0L
                for (selected in next.plan.ordered) {
                    currentCoroutineContext().ensureActive()
                    update(next.id) { it.copy(phase = StoreDownloadPhase.DOWNLOADING, progress = completed.toFloat() / total) }
                    val file = downloader.packageFile(selected.entry, selected.release) { state ->
                        val now = System.nanoTime()
                        if (now - lastUpdate >= 150_000_000L) {
                            lastUpdate = now
                            update(next.id) { it.copy(progress = ((completed + state.bytes).toFloat() / total).coerceIn(0f, 1f)) }
                        }
                    }
                    update(next.id) { it.copy(phase = StoreDownloadPhase.VERIFYING) }
                    val context = currentCoroutineContext()
                    artifacts += selected to inspector.inspect(file, selected.entry, selected.release) { context.ensureActive() }
                    completed += selected.release.bytes
                }
                currentCoroutineContext().ensureActive()
                update(next.id) { it.copy(phase = StoreDownloadPhase.READY, progress = 1f, artifacts = artifacts) }
            } catch (cancelled: CancellationException) {
                update(next.id) { it.copy(phase = StoreDownloadPhase.CANCELLED, progress = null) }; throw cancelled
            } catch (failure: Exception) {
                update(next.id) { it.copy(phase = StoreDownloadPhase.FAILED, progress = null,
                    detail = failure.message?.take(180) ?: "下载未完成，请重试") }
            } finally {
                synchronized(this@StoreDownloads) { active = null; startNext() }
                changed()
            }
        }
        active = next.id to job; job.start()
    }
    /** Called after returning from the installer, never assumes that showing it meant success. */
    suspend fun reconcile() {
        val metadata = (environment.appBroker?.request(AppCommand.Describe) as? AppReply.Data)?.value ?: return
        for (task in tasks.filter { it.phase in setOf(StoreDownloadPhase.READY, StoreDownloadPhase.INSTALLING) }) {
            if (task.plan.ordered.all { actualInstalled(metadata, it) }) update(task.id) { it.copy(phase = StoreDownloadPhase.INSTALLED, detail = "") }
            else if (task.phase == StoreDownloadPhase.INSTALLING) update(task.id) { it.copy(phase = StoreDownloadPhase.READY) }
        }
    }
    suspend fun install(id: String): PluginOutcome {
        val current = task(id)
        check(current.phase in setOf(StoreDownloadPhase.READY, StoreDownloadPhase.INSTALLING)) { "请等待下载完成" }
        val metadata = (environment.appBroker?.request(AppCommand.Describe) as? AppReply.Data)?.value ?: error("暂时无法检查安装状态，请重试")
        val missing = current.artifacts.filterNot { actualInstalled(metadata, it.first) }
        if (missing.isEmpty()) { update(id) { it.copy(phase = StoreDownloadPhase.INSTALLED) }; return PluginOutcome.RefreshUi }
        val native = missing.filter { it.second.format == PackageFormat.NATIVE }
        update(id) { it.copy(phase = StoreDownloadPhase.INSTALLING) }
        if (native.isNotEmpty()) return PluginOutcome.InstallPackage(resource(native.last().second.file),
            allowDowngrade = current.plan.downgrades.isNotEmpty(), dependencies = native.dropLast(1).map { resource(it.second.file) })
        val lua = missing.first()
        val result = environment.appBroker?.request(AppCommand.DeliverResource("runtime.lua", resource(inspector.luaResources(lua.second)), "import"))
        if (result is AppReply.Failure || result == null) {
            update(id) { it.copy(phase = StoreDownloadPhase.READY) }
            return PluginOutcome.Message((result as? AppReply.Failure)?.message ?: "请先启用插件编辑器，再继续安装")
        }
        return PluginOutcome.RefreshUi
    }
    @Synchronized fun clearFinished() { tasks = tasks.filterNot { it.phase in setOf(StoreDownloadPhase.INSTALLED, StoreDownloadPhase.CANCELLED, StoreDownloadPhase.FAILED) }; changed() }
    fun canClearCache() = tasks.all { it.phase in setOf(StoreDownloadPhase.INSTALLED, StoreDownloadPhase.CANCELLED, StoreDownloadPhase.FAILED, StoreDownloadPhase.CONFIRM) }
    private fun resource(file: java.io.File) = PluginResource(file.relativeTo(environment.storage.root).invariantSeparatorsPath)
    suspend fun close() {
        val running = synchronized(this) { closed = true; active?.second }
        running?.cancel(); downloader.cancel(); running?.join(); scope.cancel()
    }
}
