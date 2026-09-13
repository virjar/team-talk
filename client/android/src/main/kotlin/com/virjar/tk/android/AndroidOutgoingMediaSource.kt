package com.virjar.tk.android

import android.content.Context
import android.net.Uri
import com.virjar.tk.app.navigation.feature.chat.UploadedVideoMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 把应用自产的录音/拍摄文件交给一次会话动作。外部 picker 只传 URI，不传 ownedFile。
 * 准入动作按 SessionUiActionExecutor 的约定在返回前 UNDISPATCHED 启动；被拒绝的源仍由
 * 调用方释放，已开始的动作则在完成、失败或取消时释放，页面离开不会提前删除上传中的源。
 */
internal fun launchWithOwnedMediaSource(
    ownedFile: File?,
    launchAdmittedAction: (suspend () -> Unit) -> Boolean,
    action: suspend () -> Unit,
): Boolean {
    var started = false
    try {
        return launchAdmittedAction {
            started = true
            try {
                action()
            } finally {
                ownedFile?.delete()
            }
        }
    } finally {
        if (!started) ownedFile?.delete()
    }
}

/** 上传仅消费应用拥有的 URI 快照；副本和可选本地缩略图均在本次调用结束时释放。 */
internal suspend fun uploadAndroidVideo(
    context: Context,
    uri: Uri,
    mediaSession: AndroidMediaSession,
): UploadedVideoMedia = MediaHelper.prepareSelectedMedia(context, uri, mediaSession).use { prepared ->
    val uploaded = MediaHelper.uploadWithMeta(
        prepared.file, prepared.fileName, prepared.contentType, mediaSession,
    )
    var width = uploaded.width
    var height = uploaded.height
    var duration = uploaded.durationSec ?: 0
    var thumbnail = uploaded.thumbnail
    // 服务端元数据优先；缺失时保留 Android 本地提取的回退。
    if (width == 0 || thumbnail == null) {
        val local = withContext(Dispatchers.IO) { MediaHelper.getVideoMetadata(context, uri) }
        duration = local?.first ?: duration
        width = local?.second ?: width
        height = local?.third ?: height
        if (thumbnail == null) {
            var thumbnailFile: File? = null
            try {
                withContext(Dispatchers.IO) {
                    // 在 IO 返回前接住文件，避免取消恰好发生在 dispatcher 交接时遗留缩略图。
                    thumbnailFile = MediaHelper.extractVideoThumbnail(context, prepared.file, mediaSession)
                }
                thumbnailFile?.let { file ->
                    thumbnail = MediaHelper.uploadFile(file, "thumb.jpg", "image/jpeg", mediaSession)
                }
            } finally {
                thumbnailFile?.delete()
            }
        }
    }
    UploadedVideoMedia(uploaded.file, duration, width, height, thumbnail)
}
