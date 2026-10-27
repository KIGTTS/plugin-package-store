package com.kigtts.tools.store

import com.kigtts.plugin.api.*
import java.util.UUID

internal enum class StoreDownloadPhase { CONFIRM, QUEUED, DOWNLOADING, VERIFYING, READY, INSTALLING, INSTALLED, FAILED, CANCELLED }
internal data class StoreDownload(val id: String = UUID.randomUUID().toString(), val plan: DependencyPlan,
    val phase: StoreDownloadPhase = StoreDownloadPhase.CONFIRM, val progress: Float? = null,
    val detail: String = "", val artifacts: List<Pair<PlannedRelease, VerifiedArtifact>> = emptyList(), val resourceToken: String? = null) {
    val root get() = plan.root
    val title get() = root.entry.item.name
    fun button(installed: InstalledPackage? = null): ManagementProgressAction {
        val updating = root.entry.item.resourceType == null && installed?.let(root.release::isUpdateFrom) == true
        return when (phase) {
            StoreDownloadPhase.CONFIRM -> ManagementProgressAction("确认", "download-review/$id")
            StoreDownloadPhase.QUEUED -> ManagementProgressAction("取消排队", "download-cancel/$id", 0f, true)
            StoreDownloadPhase.DOWNLOADING -> ManagementProgressAction("取消下载", "download-cancel/$id", progress, true)
            StoreDownloadPhase.VERIFYING -> ManagementProgressAction("取消检查", "download-cancel/$id", running = true)
            StoreDownloadPhase.READY -> ManagementProgressAction(if (root.entry.item.resourceType != null) "添加" else if (updating) "更新" else "安装", "download-install/$id")
            StoreDownloadPhase.INSTALLING -> ManagementProgressAction(if (resourceToken != null) "等待确认" else if (updating) "继续更新" else "继续安装", if (resourceToken != null) null else "download-install/$id", running = resourceToken != null)
            StoreDownloadPhase.INSTALLED -> ManagementProgressAction(if (root.entry.item.resourceType == null) "已安装" else "已添加")
            StoreDownloadPhase.FAILED, StoreDownloadPhase.CANCELLED -> ManagementProgressAction("重试", "download-retry/$id")
        }
    }
    fun status() = when (phase) {
        StoreDownloadPhase.CONFIRM -> "等待确认"
        StoreDownloadPhase.QUEUED -> "等待下载"
        StoreDownloadPhase.DOWNLOADING -> "正在下载" + (progress?.let { " · ${(it * 100).toInt()}%" } ?: "")
        StoreDownloadPhase.VERIFYING -> "正在检查安装包"
        StoreDownloadPhase.READY -> "下载完成，等待安装"
        StoreDownloadPhase.INSTALLING -> "请完成安装确认"
        StoreDownloadPhase.INSTALLED -> if (root.entry.item.resourceType == null) "已安装" else "已添加"
        StoreDownloadPhase.FAILED -> detail.ifBlank { "下载未完成" }
        StoreDownloadPhase.CANCELLED -> "已取消"
    }
}
