package com.kigtts.tools.store

import android.os.Build
import com.kigtts.plugin.api.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PackageStorePlugin : KigPlugin {
    override val descriptor = PluginDescriptor("tools.package-store", "插件商店", "发现让 KIGTTS 更好用的插件", "0.10.3")
    private lateinit var environment: PluginEnvironment
    private lateinit var sources: SourceStore
    private lateinit var jobs: StoreJobs
    private lateinit var downloader: PackageDownloader
    private lateinit var device: StoreDevice
    private val view = StoreView()
    private val changes = MutableStateFlow(0L)
    override val uiChanges get() = changes
    private val actions = Mutex()
    private var awaiting: PreparedInstall? = null
    private var nativeSubmitted = false
    private val submittedLua = mutableSetOf<String>()
    private fun changed() { changes.update { it + 1 } }
    override suspend fun attach(environment: PluginEnvironment) {
        this.environment = environment
        sources = SourceStore(environment.storage); device = StoreDevice(Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toSet())
        val transport = HttpsStoreTransport { environment.requirePermission("network") }
        downloader = PackageDownloader(environment.storage, transport)
        jobs = StoreJobs(environment, sources, device, downloader, ArtifactInspector(environment.storage), ::changed,
            artwork = CatalogArtworkCache(environment.storage, transport))
        jobs.reloadInstalled() // Preload local state; visible page entry requests the network refresh.
    }
    override val contributions = PluginContributions(settingsEntries = listOf(
        SettingsEntry("store", "插件商店", "store", PluginIcon.NONE, summary = "发现、安装与更新插件", symbol = "storefront")),
        automationActions = listOf("add-source"), sourceAction = "add-source")
    override fun page(pageId: String, context: PluginContext) = storePage(pageId, sources, jobs, view.copy(), device)
    override suspend fun execute(actionId: String, context: PluginContext): PluginOutcome = actions.withLock {
        try { perform(actionId, context).also { changed() } }
        catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (failure: Exception) { PluginOutcome.Message(failure.message?.take(200) ?: "操作暂时无法完成") }
    }
    private suspend fun perform(actionId: String, context: PluginContext): PluginOutcome {
        val prefix = actionId.substringBefore('/'); val id = actionId.substringAfter('/', "")
        val value = context.inputs["value"].orEmpty()
        return when (prefix) {
            "store-enter" -> { jobs.entered(); PluginOutcome.RefreshUi }
            "add-source" -> context.inputs["url"]?.let(::propose) ?: PluginOutcome.TextInput("包源 HTTPS 地址", "source-address")
            "source-address" -> propose(value)
            "source-confirm" -> { check(!jobs.busy); val source = sources.confirm(id); jobs.refresh(source.key); PluginOutcome.OpenPage("sources") }
            "source-cancel" -> { sources.cancelCandidate(); PluginOutcome.OpenPage("sources") }
            "sources", "store-settings", "downloads", "library", "updates" -> { jobs.reloadInstalled(); PluginOutcome.OpenPage(prefix) }
            "manage-plugins" -> { val reply = requireNotNull(environment.appBroker).request(AppCommand.Navigate("plugins")); if (reply is AppReply.Failure) PluginOutcome.Message(reply.message) else PluginOutcome.RefreshUi }
            "browse" -> { view.offset = 0; PluginOutcome.OpenPage("store") }
            "category" -> { view.category = if (id == "all") null else sources.entries().map { it.item.category }.first { categoryKey(it) == id }; view.offset = 0; PluginOutcome.RefreshUi }
            "refresh" -> { if (id.isBlank()) jobs.entered() else jobs.refresh(id); PluginOutcome.RefreshUi }
            "source-toggle" -> { check(!jobs.busy); sources.toggle(id); view.offset = 0; PluginOutcome.RefreshUi }
            "source-remove" -> PluginOutcome.Menu("移除此源？", listOf(PageAction("source-remove-confirm/$id", "移除", PluginIcon.CLOSE)))
            "source-remove-confirm" -> { check(!jobs.busy); sources.remove(id); if (view.source == id) view.source = null; view.offset = 0; PluginOutcome.RefreshUi }
            "sources-reorder" -> { sources.reorder(PluginJson.decodeFromString(requireNotNull(context.inputs["order"]))); PluginOutcome.RefreshUi }
            "browse-source" -> { view.source = id; view.offset = 0; PluginOutcome.OpenPage("store") }
            "source-select" -> PluginOutcome.Menu("来源", listOf(PageAction("source-filter/all", "全部源")) + sources.records().filter { it.enabled }.map { PageAction("source-filter/${it.key}", it.name) })
            "source-filter" -> { view.source = id.takeUnless { it == "all" }; view.offset = 0; PluginOutcome.OpenPage("store") }
            "search" -> PluginOutcome.TextInput("搜索插件", "search-save", view.query)
            "search-save" -> { require(value.length <= 100); view.query = value; view.offset = 0; PluginOutcome.RefreshUi }
            "filter" -> { require(id in listOf("all", "installed", "updates")); view.filter = id; view.offset = 0; PluginOutcome.OpenPage(when(id) { "installed" -> "library"; "updates" -> "updates"; else -> "store" }) }
            "next" -> { view.offset += 12; PluginOutcome.RefreshUi }
            "previous" -> { view.offset = (view.offset - 12).coerceAtLeast(0); PluginOutcome.RefreshUi }
            "package" -> {
                val sourceKey = id.substringBefore('/'); val packageId = id.substringAfter('/')
                view.selected = sources.entries().first { it.source.key == sourceKey && it.item.id == packageId }
                view.selectedVersion = view.selected!!.latest(device)?.versionCode; PluginOutcome.OpenPage("package")
            }
            "versions", "package-info" -> PluginOutcome.OpenPage(prefix)
            "version" -> { requireNotNull(view.selected).item.releases.single { it.versionCode == id.toInt() && it.compatible(device) }; view.selectedVersion = id.toInt(); PluginOutcome.OpenPage("package") }
            "prepare" -> {
                check(awaiting == null) { "请先处理上一份安装计划" }
                jobs.prepare(requireNotNull(view.selected), requireNotNull(view.selectedVersion)); PluginOutcome.OpenPage("install-plan")
            }
            "show-plan" -> PluginOutcome.OpenPage("install-plan")
            "download-plan" -> { jobs.downloadPlan(); PluginOutcome.RefreshUi }
            "install-plan" -> {
                check(!jobs.busy)
                if (awaiting == null) { awaiting = requireNotNull(jobs.prepared); nativeSubmitted = false; submittedLua.clear() }
                continueInstallation()
            }
            "continue-install" -> continueInstallation()
            "install-cancel" -> { check(!jobs.busy); awaiting = null; nativeSubmitted = false; submittedLua.clear(); jobs.clearPrepared(); PluginOutcome.OpenPage("store") }
            "cancel-job" -> { jobs.cancel(); PluginOutcome.RefreshUi }
            "clear-cache" -> PluginOutcome.Menu("清除已下载的包？", listOf(PageAction("clear-cache-confirm", "清除", PluginIcon.CLOSE)))
            "clear-cache-confirm" -> { check(!jobs.busy && awaiting == null); downloader.clearDownloads(); jobs.clearPrepared(); PluginOutcome.RefreshUi }
            else -> PluginOutcome.Message("未知商店操作")
        }
    }
    private fun propose(url: String): PluginOutcome { sources.propose(url); return PluginOutcome.OpenPage("source-review") }
    private suspend fun continueInstallation(): PluginOutcome {
        val ready = awaiting ?: return PluginOutcome.Message("没有待继续的安装")
        val native = ready.artifacts.filter { it.second.format == PackageFormat.NATIVE }
        if (native.isNotEmpty() && !nativeSubmitted) {
            val current = environment.appBroker?.request(AppCommand.Describe) as? AppReply.Data
            if (current != null && native.all { actualInstalled(current.value, it.first) }) nativeSubmitted = true
        }
        if (native.isNotEmpty() && !nativeSubmitted) {
            nativeSubmitted = true
            val root = native.last().second
            return PluginOutcome.InstallPackage(resource(root.file), allowDowngrade = ready.plan.downgrades.isNotEmpty(),
                dependencies = native.dropLast(1).map { resource(it.second.file) })
        }
        if (native.isNotEmpty()) {
            val state = environment.appBroker?.request(AppCommand.Describe) as? AppReply.Data ?: return PluginOutcome.Message("无法检查安装状态")
            // Check typed metadata below; never infer success from submitting an installation outcome.
            if (!native.all { actualInstalled(state.value, it.first) }) return PluginOutcome.Message("部分前置包尚未安装，请完成确认后再继续")
        }
        if (submittedLua.isNotEmpty()) {
            val state = environment.appBroker?.request(AppCommand.Describe) as? AppReply.Data ?: return PluginOutcome.Message("无法检查 Lua 安装状态")
            val submitted = ready.artifacts.filter { it.first.entry.item.id in submittedLua }
            if (!submitted.all { actualInstalled(state.value, it.first) }) return PluginOutcome.Message("请完成 Lua 插件的审核后再继续")
        }
        val lua = ready.artifacts.firstOrNull { it.second.format == PackageFormat.LUA && it.first.entry.item.id !in submittedLua }
        if (lua != null) {
            val directory = jobs.luaResources(lua.second)
            val reply = requireNotNull(environment.appBroker).request(AppCommand.DeliverResource("runtime.lua", resource(directory), "import"))
            if (reply is AppReply.Failure) return PluginOutcome.Message(reply.message)
            submittedLua += lua.first.entry.item.id
            // The broker has opened runtime.lua's review page; do not navigate away from that approval.
            return PluginOutcome.RefreshUi
        }
        awaiting = null; jobs.clearPrepared(); jobs.reloadInstalled()
        return PluginOutcome.OpenPage("store")
    }
    private fun resource(file: java.io.File) = PluginResource(file.relativeTo(environment.storage.root).invariantSeparatorsPath)
    override suspend fun close() { if (::jobs.isInitialized) jobs.close() }
}
