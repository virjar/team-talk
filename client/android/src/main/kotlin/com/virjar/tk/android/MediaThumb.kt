package com.virjar.tk.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.widget.ImageView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import com.virjar.tk.protocol.model.Attachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 图片/视频缩略图渲染：下载到本地缓存 → 解码 → ImageView 显示。
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
    val bitmap by produceState<Bitmap?>(null, attachment, targetSize, mediaSession) {
        // produceState 会在 producer 键变化时保留其 State。先清空，
        // 避免 USER_UPDATED 描述符在新资源加载期间仍显示旧用户的位图。
        value = null
        if (targetSize.width <= 0 || targetSize.height <= 0) return@produceState
        value = withContext(Dispatchers.IO) {
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
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("MediaThumb", "加载失败: ${attachment.path}", error)
                null
            }
        }
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
            view.setImageBitmap(bitmap)
        },
    )
}

/** 同尺寸的损坏缓存条目只获得一次权威性替换，绝不会陷入重试循环。 */
internal suspend fun <T> loadAndroidThumbWithSingleRefresh(
    load: suspend (forceRefresh: Boolean) -> T?,
): T? = load(false) ?: load(true)

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
