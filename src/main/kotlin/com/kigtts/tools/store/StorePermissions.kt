package com.kigtts.tools.store

import com.kigtts.plugin.api.PageBlock

internal fun validatePermissions(release: PackageRelease) {
    val permissions = release.permissions
    require(permissions == null || permissions.size <= 32 && permissions.distinct().size == permissions.size &&
        permissions.all { it.matches(Regex("[a-z][a-z0-9-]{0,79}")) }) { "权限列表无效" }
    require(release.permissionPurposes.size <= 32 && release.permissionPurposes.all { (id, purpose) ->
        id in permissions.orEmpty() && purpose.length in 1..600
    }) { "权限用途需对应已声明权限" }
}

/** Null is an older source, not evidence that a package has no permissions. */
internal fun permissionDetails(release: PackageRelease?): PageBlock.Group {
    val content = when {
        release?.permissions == null -> listOf(PageBlock.Text("此版本尚未提供权限说明，可在安装确认时查看实际请求。"))
        release.permissions.isEmpty() -> listOf(PageBlock.Text("此版本未声明额外权限。"))
        else -> release.permissions.flatMap { id -> listOf(
            PageBlock.Text(permissionName(id), heading = true),
            PageBlock.Text(release.permissionPurposes[id] ?: "使用时会说明并申请此权限。")
        ) }
    }
    return PageBlock.Group("权限说明", content, collapsible = true)
}

private fun permissionName(id: String) = when (id) {
    "network" -> "访问网络"
    "app-control" -> "操作软件"
    "quick-text-read" -> "读取快捷文本"
    "quick-text-write" -> "管理快捷文本"
    "stickers-read" -> "读取相片与贴纸"
    "stickers-write" -> "管理相片与贴纸"
    "microphone" -> "使用麦克风"
    "camera" -> "使用相机"
    "location" -> "使用位置"
    "notifications" -> "发送通知"
    "tts", "speech-service" -> "合成与播放语音"
    "asr-batch", "asr-segmented", "asr-incremental", "asr-service" -> "处理语音识别音频"
    "voice-packs" -> "管理语音包"
    "motion-sensors" -> "读取运动与重力传感器"
    "downloads" -> "下载文件"
    else -> id
}
