package com.kigtts.tools.store

import com.kigtts.plugin.api.*
import java.util.UUID

internal enum class StoreDownloadPhase { CONFIRM, QUEUED, DOWNLOADING, VERIFYING, READY, INSTALLING, INSTALLED, FAILED, CANCELLED }
internal data class StoreDownload(val id: String = UUID.randomUUID().toString(), val plan: DependencyPlan,
    val phase: StoreDownloadPhase = StoreDownloadPhase.CONFIRM, val progress: Float? = null,
    val detail: String = "", val artifacts: List<Pair<PlannedRelease, VerifiedArtifact>> = emptyList()) {
    val root get() = plan.root
    val title get() = root.entry.item.name
    fun button() = when (phase) {
        StoreDownloadPhase.CONFIRM -> ManagementProgressAction("确认", "download-review/$id")
        StoreDownloadPhase.QUEUED -> ManagementProgressAction("取消排队", "download-cancel/$id", 0f, true)
        StoreDownloadPhase.DOWNLOADING -> ManagementProgressAction("取消下载", "download-cancel/$id", progress, true)
        StoreDownloadPhase.VERIFYING -> ManagementProgressAction("取消检查", "download-cancel/$id", running = true)
        StoreDownloadPhase.READY -> ManagementProgressAction("安装", "download-install/$id")
        StoreDownloadPhase.INSTALLING -> ManagementProgressAction("继续安装", "download-install/$id")
        StoreDownloadPhase.INSTALLED -> ManagementProgressAction("已安装")
        StoreDownloadPhase.FAILED, StoreDownloadPhase.CANCELLED -> ManagementProgressAction("重试", "download-retry/$id")
    }
    fun status() = when (phase) {
        StoreDownloadPhase.CONFIRM -> "等待确认"
        StoreDownloadPhase.QUEUED -> "等待下载"
        StoreDownloadPhase.DOWNLOADING -> "正在下载" + (progress?.let { " · ${(it * 100).toInt()}%" } ?: "")
        StoreDownloadPhase.VERIFYING -> "正在检查安装包"
        StoreDownloadPhase.READY -> "下载完成，等待安装"
        StoreDownloadPhase.INSTALLING -> "请完成安装确认"
        StoreDownloadPhase.INSTALLED -> "已安装"
        StoreDownloadPhase.FAILED -> detail.ifBlank { "下载未完成" }
        StoreDownloadPhase.CANCELLED -> "已取消"
    }
}
