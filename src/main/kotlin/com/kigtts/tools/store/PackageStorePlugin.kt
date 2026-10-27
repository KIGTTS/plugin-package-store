package com.kigtts.tools.store

import android.os.Build
import com.kigtts.plugin.api.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PackageStorePlugin : KigPlugin {
    override val descriptor = PluginDescriptor("tools.package-store", "插件商店", "发现让 KIGTTS 更好用的插件", "0.16.7")
    private lateinit var environment: PluginEnvironment
    private lateinit var sources: SourceStore
    private lateinit var jobs: StoreJobs
    private lateinit var downloader: PackageDownloader
    private lateinit var device: StoreDevice
    private val view = StoreView()
    private val changes = MutableStateFlow(0L)
    override val uiChanges get() = changes
    private val actions = Mutex()
    private fun changed() { changes.update { it + 1 } }
    override suspend fun attach(environment: PluginEnvironment) {
        this.environment = environment
        sources = SourceStore(environment.storage); device = StoreDevice(Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toSet())
        val transport = HttpsStoreTransport { environment.requirePermission("network") }
        downloader = PackageDownloader(environment.storage, transport)
        jobs = StoreJobs(environment, sources, device, downloader, ArtifactInspector(environment.storage), ::changed,
            artwork = CatalogArtworkCache(environment.storage, transport))
        jobs.downloads = StoreDownloads(environment, sources, device,
            PackageDownloader(environment.storage, HttpsStoreTransport { environment.requirePermission("network") }), ArtifactInspector(environment.storage), ::changed)
        jobs.reloadInstalled() // Preload local state; visible page entry requests the network refresh.
    }
    override val contributions get(): PluginContributions {
        val updates = if (::jobs.isInitialized) discover(sources.entries(), StoreView(filter = "updates"), jobs.installed, device).size else 0
        return PluginContributions(settingsEntries = listOf(
        SettingsEntry("store", "插件商店", "store", PluginIcon.NONE, summary = "发现、安装与更新插件", symbol = "storefront", badge = updateBadge(updates))),
        automationActions = listOf("add-source"), sourceAction = "add-source")
    }
    override fun page(pageId: String, context: PluginContext): PluginPage? {
        val page = if (pageId == "package") sourcePackagePage(sources, jobs, view, device)
            ?: storePage(pageId, sources, jobs, view.copy(), device) else storePage(pageId, sources, jobs, view.copy(), device)
        return page?.let { current -> jobs.downloads?.automaticAction()?.let { current.copy(onEnterAction = it) } ?: current }
    }
    override suspend fun execute(actionId: String, context: PluginContext): PluginOutcome = actions.withLock {
        try { perform(actionId, context).also { changed() } }
        catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (failure: Exception) { PluginOutcome.Message(failure.message?.take(200) ?: "操作暂时无法完成") }
    }
    private suspend fun perform(actionId: String, context: PluginContext): PluginOutcome {
        val prefix = actionId.substringBefore('/'); val id = actionId.substringAfter('/', "")
        val value = context.inputs["value"].orEmpty()
        return when (prefix) {
            "store-enter" -> { jobs.downloads?.reconcile(); jobs.entered(); PluginOutcome.RefreshUi }
            "downloads-enter" -> { jobs.downloads?.reconcile(); jobs.reloadInstalled(); PluginOutcome.RefreshUi }
            "add-source" -> context.inputs["url"]?.let { url ->
                proposeSource(sources, view, url, context.inputs["packageId"], context.inputs["requireSource"] != "false").also {
                    if (view.linkTarget != null && sources.findUrl(url)?.enabled == true) jobs.preview(url)
                }
            } ?: PluginOutcome.TextInput("插件源地址（HTTPS）", "source-address")
            "source-address" -> propose(value)
            "source-confirm" -> {
                check(!jobs.busy)
                val candidate = requireNotNull(sources.pending())
                val source = sources.confirm(id)
                view.linkTarget = candidate.packageId?.let { SourcePackageTarget(source.url, it) }
                view.selected = null; view.selectedVersion = null
                jobs.refresh(source.key)
                PluginOutcome.OpenPage(when {
                    view.linkTarget != null -> "package"
                    source.url.substringBefore('?') == FONT_SOURCE.substringBefore('?') -> catalogPageId(source.key, "字体")
                    else -> "sources"
                })
            }
            "source-cancel" -> { sources.cancelCandidate(); view.linkTarget = null; PluginOutcome.OpenPage("sources") }
            "target-refresh" -> { view.linkTarget?.let { jobs.preview(it.url) }; PluginOutcome.RefreshUi }
            "recommend-source" -> {
                val entry = requireNotNull(view.selected)
                proposeSource(sources, view, entry.source.url, entry.item.id)
            }
            "sources", "store-settings", "downloads", "download-storage", "library", "updates" -> { jobs.reloadInstalled(); PluginOutcome.OpenPage(prefix) }
            "manage-plugins" -> { val reply = requireNotNull(environment.appBroker).request(AppCommand.Navigate("plugins")); if (reply is AppReply.Failure) PluginOutcome.Message(reply.message) else PluginOutcome.RefreshUi }
            "browse" -> { view.offset = 0; PluginOutcome.OpenPage("store") }
            "font-library" -> openFontLibrary(sources, view)
            "section" -> { require(id in setOf("categories", "all-categories", "source-sections", "resources", "resource-sources") || id.startsWith("categories/")); PluginOutcome.OpenPage(if (id == "all-categories") "categories" else id) }
            "open-catalog" -> { requireNotNull(catalogScope(id, sources)); PluginOutcome.OpenPage(id) }
            "load-more" -> {
                appendStoreBatch(view, id.substringBeforeLast('/'), id.substringAfterLast('/').toInt())
                PluginOutcome.RefreshUi
            }
            "category" -> { view.category = if (id == "all") null else sources.entries().map { it.item.section }.first { categoryKey(it) == id }; view.offset = 0; PluginOutcome.RefreshUi }
            "refresh" -> { if (id.isBlank()) jobs.entered() else jobs.refresh(id); PluginOutcome.RefreshUi }
            "source-toggle" -> { check(!jobs.busy); sources.toggle(id); view.offset = 0; PluginOutcome.RefreshUi }
            "source-remove" -> PluginOutcome.Menu("移除此源？", listOf(PageAction("source-remove-confirm/$id", "移除", PluginIcon.CLOSE)))
            "source-remove-confirm" -> { check(!jobs.busy); sources.remove(id); if (view.source == id) view.source = null; view.offset = 0; PluginOutcome.RefreshUi }
            "sources-reorder" -> { sources.reorder(PluginJson.decodeFromString(requireNotNull(context.inputs["order"]))); PluginOutcome.RefreshUi }
            "browse-source" -> PluginOutcome.OpenPage("categories/$id")
            "source-select" -> PluginOutcome.Menu("来源", listOf(PageAction("source-filter/all", "全部源")) + sources.records().filter { it.enabled }.map { PageAction("source-filter/${it.key}", it.name) })
            "source-filter" -> PluginOutcome.OpenPage(catalogPageId(id.takeUnless { it == "all" }))
            "search" -> { view.query = ""; view.offset = 0; PluginOutcome.OpenPage("search") }
            "search-save" -> { require(value.length <= 100); view.query = value; view.offset = 0; PluginOutcome.RefreshUi }
            "filter" -> { require(id in listOf("all", "installed", "updates")); view.filter = id; view.offset = 0; PluginOutcome.OpenPage(when(id) { "installed" -> "library"; "updates" -> "updates"; else -> "store" }) }
            "next" -> { view.offset += 12; PluginOutcome.RefreshUi }
            "previous" -> { view.offset = (view.offset - 12).coerceAtLeast(0); PluginOutcome.RefreshUi }
            "package" -> {
                view.linkTarget = null
                val sourceKey = id.substringBefore('/'); val packageId = id.substringAfter('/')
                view.selected = sources.allEntries().first { it.source.key == sourceKey && it.item.id == packageId }
                view.selectedVersion = view.selected!!.latest(device)?.versionCode; PluginOutcome.OpenPage("package")
            }
            "versions", "package-info" -> PluginOutcome.OpenPage(prefix)
            "version" -> { requireNotNull(view.selected).item.releases.single { it.versionCode == id.toInt() && it.compatible(device) }; view.selectedVersion = id.toInt(); PluginOutcome.OpenPage("package") }
            "prepare" -> beginDownload(requireNotNull(view.selected), requireNotNull(view.selectedVersion))
            "download-get" -> {
                val parts = id.split('/')
                val entry = sources.allEntries().first { it.source.key == parts[0] && it.item.id == parts[1] }
                beginDownload(entry, parts[2].toInt())
            }
            "download-review" -> reviewDownload(requireNotNull(jobs.downloads).task(id))
            "download-confirm" -> { requireNotNull(jobs.downloads).confirm(id); PluginOutcome.RefreshUi }
            "download-retry" -> { requireNotNull(jobs.downloads).retry(id); PluginOutcome.RefreshUi }
            "download-cancel" -> { requireNotNull(jobs.downloads).cancel(id); PluginOutcome.RefreshUi }
            "download-install" -> requireNotNull(jobs.downloads).install(id)
            "download-auto-install" -> requireNotNull(jobs.downloads).installAutomatically(id)
            "downloads-clear-finished" -> { jobs.downloads?.clearFinished(); PluginOutcome.RefreshUi }
            "show-plan", "continue-install" -> PluginOutcome.OpenPage("downloads")
            "install-cancel" -> { jobs.clearPrepared(); PluginOutcome.OpenPage("store") }
            "cancel-job" -> { jobs.cancel(); PluginOutcome.RefreshUi }
            "clear-cache" -> PluginOutcome.Menu("清除已下载的包？", listOf(PageAction("clear-cache-confirm", "清除", PluginIcon.CLOSE)))
            "clear-cache-confirm" -> { check(!jobs.busy && jobs.downloads?.canClearCache() != false) { "请先完成或取消下载任务" }; downloader.clearDownloads(); jobs.clearPrepared(); PluginOutcome.RefreshUi }
            else -> PluginOutcome.Message("未知商店操作")
        }
    }
    private fun propose(url: String): PluginOutcome = proposeSource(sources, view, url)
    private suspend fun beginDownload(entry: CatalogEntry, version: Int): PluginOutcome {
        val downloads = requireNotNull(jobs.downloads)
        downloads.reconcile()
        val task = downloads.offer(entry, version, jobs.installed)
        return reviewDownload(task)
    }
    private fun reviewDownload(task: StoreDownload): PluginOutcome {
        if (task.phase != StoreDownloadPhase.CONFIRM) return PluginOutcome.RefreshUi
        if (task.plan.ordered.size > 1 || task.plan.downgrades.isNotEmpty()) {
            val names = task.plan.ordered.joinToString("\n") { "• ${it.entry.item.name} ${it.release.version}" }
            return PluginOutcome.Confirm("确认下载", "将下载以下内容：\n\n$names" +
                if (task.plan.downgrades.isNotEmpty()) "\n\n包含较早版本，安装前请确认兼容性。" else "",
                PageAction("download-confirm/${task.id}", "确认并下载", PluginIcon.NONE, "download"), "稍后")
        }
        requireNotNull(jobs.downloads).confirm(task.id)
        return PluginOutcome.RefreshUi
    }
    override suspend fun close() { if (::jobs.isInitialized) { jobs.downloads?.close(); jobs.close() } }
}
