package com.kigtts.tools.store

import com.kigtts.plugin.api.*
import java.util.UUID

internal enum class StoreDownloadPhase { CONFIRM, QUEUED, DOWNLOADING, VERIFYING, READY, INSTALLING, INSTALLED, FAILED, CANCELLED }
internal data class StoreDownload(val id: String = UUID.randomUUID().toString(), val plan: DependencyPlan,
    val phase: StoreDownloadPhase = StoreDownloadPhase.CONFIRM, val progress: Float? = null,
    val detail: String = "", val artifacts: List<Pair<PlannedRelease, VerifiedArtifact>> = emptyList(), val resourceToken: String? = null,
    val prompted: Boolean = false, val automaticPrompt: Boolean = false) {
    val root get() = plan.root
    val title get() = root.entry.item.name
    fun button(installed: InstalledPackage? = null, copy: StoreCopy = StoreCopy.Chinese): ManagementProgressAction {
        val updating = root.entry.item.resourceType == null && installed?.let(root.release::isUpdateFrom) == true
        return when (phase) {
            StoreDownloadPhase.CONFIRM -> ManagementProgressAction(copy.text("download.confirm"), "download-review/$id")
            StoreDownloadPhase.QUEUED -> ManagementProgressAction(copy.text("download.stop_queue"), "download-cancel/$id", 0f, true)
            StoreDownloadPhase.DOWNLOADING -> ManagementProgressAction(copy.text("install_cancel_download"), "download-cancel/$id", progress, true)
            StoreDownloadPhase.VERIFYING -> ManagementProgressAction(copy.text("download.stop_verify"), "download-cancel/$id", running = true)
            StoreDownloadPhase.READY -> ManagementProgressAction(if (root.entry.item.resourceType != null) copy.text("add") else if (updating) copy.text("download.update") else copy.text("download.install"), "download-install/$id")
            StoreDownloadPhase.INSTALLING -> ManagementProgressAction(if (resourceToken != null) copy.text("wait_confirm") else if (updating) copy.text("download.continue_update") else copy.text("download.continue_install"), if (resourceToken != null) null else "download-install/$id", running = resourceToken != null)
            StoreDownloadPhase.INSTALLED -> ManagementProgressAction(if (root.entry.item.resourceType == null) copy.text("installed") else copy.text("download.added"))
            StoreDownloadPhase.FAILED, StoreDownloadPhase.CANCELLED -> ManagementProgressAction(copy.text("download.retry"), "download-retry/$id")
        }
    }
    fun status(copy: StoreCopy = StoreCopy.Chinese) = when (phase) {
        StoreDownloadPhase.CONFIRM -> copy.text("wait_confirm")
        StoreDownloadPhase.QUEUED -> copy.text("download.queued")
        StoreDownloadPhase.DOWNLOADING -> copy.text("download.downloading") + (progress?.let { " · ${(it * 100).toInt()}%" } ?: "")
        StoreDownloadPhase.VERIFYING -> copy.text("download.verifying")
        StoreDownloadPhase.READY -> if (root.entry.item.resourceType == null) copy.text("download.ready_install") else copy.text("download.ready_add")
        StoreDownloadPhase.INSTALLING -> if (root.entry.item.resourceType == null) copy.text("download.review_install") else copy.text("download.review_add")
        StoreDownloadPhase.INSTALLED -> if (root.entry.item.resourceType == null) copy.text("installed") else copy.text("download.added")
        StoreDownloadPhase.FAILED -> copy.text("download.failed")
        StoreDownloadPhase.CANCELLED -> copy.text("download.cancelled")
    }
}
