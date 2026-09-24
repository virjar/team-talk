package com.virjar.tk.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.ImageView
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.shared.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 图片加载失败的占位提示；在黑底画廊与浅灰气泡底上都可辨。 */
private val THUMB_FAILURE_TINT = Color(0xFF9E9E9E)

private data class ThumbResult(val bitmap: Bitmap?, val failed: Boolean)

/**
 * 图片/视频缩略图渲染：下载到本地缓存 → 解码 → ImageView 显示。
 *
 * 失败绝不静默：下载/解码异常经 [AppLog.fault] 进入客户端遥测（fault.reported，
 * 含异常类与堆栈），画廊/气泡展示可点按重试的错误占位，替代纯色黑屏。
 */
@Composable
fun rememberAsyncThumb(
    attachment: Attachment,
    mediaSession: AndroidMediaSession,
    modifier: Modifier = Modifier,
    placeholderColor: Int = android.graphics.Color.LTGRAY,
    scaleType: ImageView.ScaleType = ImageView.ScaleType.FIT_CENTER,
) {
    val context = LocalContext.current
    var targetSize by remember(attachment, mediaSession.cacheNamespace) { mutableStateOf(IntSize.Zero) }
    // 点按错误占位递增该键，重置 produceState 重新下载/解码。
    var retryAttempt by remember(attachment) { mutableIntStateOf(0) }
    val state by produceState(ThumbResult(null, false), attachment, targetSize, mediaSession, retryAttempt) {
        // produceState 会在 producer 键变化时保留其 State。先清空，
        // 避免 USER_UPDATED 描述符在新资源加载期间仍显示旧用户的位图。
        value = ThumbResult(null, false)
        if (targetSize.width <= 0 || targetSize.height <= 0) return@produceState
        var failed = false
        val loaded = withContext(Dispatchers.IO) {
            try {
                loadAndroidThumbWithSingleRefresh { forceRefresh ->
                    val lease = MediaHelper.downloadToCacheLease(
                        attachment = attachment,
                        cacheDir = context.cacheDir,
                        mediaSession = mediaSession,
                        forceRefresh = forceRefresh,
                    )
                    lease.use {
                        decodeSampledBitmap(it.file, targetSize.width, targetSize.height)
                    }
                }.also { decoded ->
                    if (decoded == null) {
                        // 两次尝试解码均为空：缓存或远端字节流无法构成受支持的图片。
                        failed = true
                        AppLog.fault("MediaThumb", "图片预览失败 reason=decode kind=${attachment.contentType}")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failed = true
                AppLog.fault("MediaThumb", "图片预览失败 kind=${attachment.contentType}", error)
                null
            }
        }
        value = ThumbResult(loaded, failed)
    }
    if (state.failed) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color(placeholderColor))
                .clickable { retryAttempt++ },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Filled.BrokenImage,
                    contentDescription = "图片加载失败",
                    tint = THUMB_FAILURE_TINT,
                    modifier = Modifier.size(28.dp),
                )
                Text(
                    "加载失败，点按重试",
                    color = THUMB_FAILURE_TINT,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        return
    }
    AndroidView(
        modifier = modifier.onSizeChanged { size -> targetSize = size },
        factory = { ctx ->
            ImageView(ctx).apply {
                this.scaleType = scaleType
                setBackgroundColor(placeholderColor)
            }
        },
        update = { view ->
            if (view.scaleType != scaleType) view.scaleType = scaleType
            view.setImageBitmap(state.bitmap)
        },
    )
}

/**
 * 同尺寸的损坏缓存条目只获得一次权威性替换，绝不会陷入重试循环；
 * 下载抛出的异常同样获得这一次刷新机会（内测反馈：弱网下首拉失败
 * 直接黑屏，而缓存重试仅覆盖了解码为空的情形）。
 */
internal suspend fun <T> loadAndroidThumbWithSingleRefresh(
    load: suspend (forceRefresh: Boolean) -> T?,
): T? = try {
    load(false) ?: load(true)
} catch (first: CancellationException) {
    throw first
} catch (first: Exception) {
    try {
        load(true)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (second: Exception) {
        first.addSuppressed(second)
        throw first
    }
}

/** 只按 2 的幂采样，兼容 BitmapFactory 的高效解码路径。 */
internal fun calculateBitmapSampleSize(
    sourceWidth: Int,
    sourceHeight: Int,
    requestedWidth: Int,
    requestedHeight: Int,
): Int {
    if (sourceWidth <= 0 || sourceHeight <= 0 || requestedWidth <= 0 || requestedHeight <= 0) return 1
    val safeWidth = requestedWidth.coerceAtMost(MAX_THUMBNAIL_EDGE_PX)
    val safeHeight = requestedHeight.coerceAtMost(MAX_THUMBNAIL_EDGE_PX)
    var sample = 1
    while (
        sample <= Int.MAX_VALUE / 2 &&
        sourceWidth / (sample * 2) >= safeWidth &&
        sourceHeight / (sample * 2) >= safeHeight
    ) {
        sample *= 2
    }
    while (
        sample <= Int.MAX_VALUE / 2 &&
        decodedPixelCount(sourceWidth, sourceHeight, sample) > MAX_DECODED_THUMBNAIL_PIXELS
    ) {
        // 极高分辨率图片优先守住内存预算；缩略图允许轻微放大显示。
        sample *= 2
    }
    return sample
}

private fun decodedPixelCount(width: Int, height: Int, sample: Int): Long =
    (width / sample).coerceAtLeast(1).toLong() * (height / sample).coerceAtLeast(1).toLong()

private fun decodeSampledBitmap(file: File, requestedWidth: Int, requestedHeight: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateBitmapSampleSize(
            bounds.outWidth,
            bounds.outHeight,
            requestedWidth,
            requestedHeight,
        )
    }
    return BitmapFactory.decodeFile(file.absolutePath, options)
}

private const val MAX_THUMBNAIL_EDGE_PX = 4096
private const val MAX_DECODED_THUMBNAIL_PIXELS = 8L * 1024 * 1024
