package com.virjar.tk.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.virjar.tk.ui.theme.Tk

// ── 图片/贴纸缩略图（贴边，圆角由气泡 Surface 裁剪） ──

@Composable
internal fun ImageThumbCard(
    imageUrl: String,
    imageContent: @Composable (String, Modifier) -> Unit,
    imgWidth: Int,
    imgHeight: Int,
    onClick: (() -> Unit)?,
) {
    // 参考 Signal 的 fit-inside 策略：最大 240×320dp 盒子，等比缩放保留宽高比。
    // 宽高已知时计算 fit-inside 尺寸；未知(0)时 fallback 到 200×200 占位。
    val maxW = 240.dp
    val maxH = 320.dp
    val minDim = 120.dp  // 最小边，避免太小不可点
    val displaySize = if (imgWidth > 0 && imgHeight > 0) {
        val ratio = minOf(maxW.value / imgWidth, maxH.value / imgHeight)
        var w = imgWidth * ratio
        var h = imgHeight * ratio
        if (w < minDim.value) { val s = minDim.value / w; w = minDim.value; h *= s }
        if (h < minDim.value) { val s = minDim.value / h; h = minDim.value; w *= s }
        w = minOf(w, maxW.value)
        h = minOf(h, maxH.value)
        androidx.compose.ui.unit.Dp(w) to androidx.compose.ui.unit.Dp(h)
    } else {
        200.dp to 200.dp
    }

    Box(
        modifier = Modifier
            .size(displaySize.first, displaySize.second)
            .background(Tk.colors.bubbleIncoming)
            .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier),
    ) {
        imageContent(imageUrl, Modifier.fillMaxSize())
    }
}

// ── 视频缩略图（真缩略图优先，否则深色 16:9 占位卡 + 播放键 + 时长角标） ──

/**
 * 视频卡：贴边 16:9（宽高已知时等比）。有服务端缩略图（thumbnailUrl）时显示真缩略图，
 * 无则深色底 + 居中播放键。点击进画廊窗口内嵌播放器（应用内渲染，非系统播放器）。
 */
@Composable
internal fun VideoThumbCard(
    videoUrl: String,
    thumbnailUrl: String?,
    videoContent: (@Composable (String, Modifier) -> Unit)?, // 保留参数兼容；当前不使用
    imageContent: (@Composable (String, Modifier) -> Unit)?,
    imgWidth: Int,
    imgHeight: Int,
    duration: Int,
    onClick: (() -> Unit)?,
) {
    val maxW = 240.dp
    val maxH = 320.dp
    val displaySize = if (imgWidth > 0 && imgHeight > 0) {
        val ratio = minOf(maxW.value / imgWidth, maxH.value / imgHeight)
        var w = imgWidth * ratio
        var h = imgHeight * ratio
        if (w < 160.dp.value) { val s = 160.dp.value / w; w = 160.dp.value; h *= s }
        if (h < 120.dp.value) { val s = 120.dp.value / h; h = 120.dp.value; w *= s }
        w = minOf(w, maxW.value)
        h = minOf(h, maxH.value)
        androidx.compose.ui.unit.Dp(w) to androidx.compose.ui.unit.Dp(h)
    } else {
        240.dp to 135.dp  // 无元数据时按 16:9
    }

    Box(
        modifier = Modifier
            .size(displaySize.first, displaySize.second)
            .background(Color(0xFF20242C))  // 视频区固定深色（明暗主题一致的"屏幕"隐喻）
            .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier),
    ) {
        // 真缩略图（服务端生成后自动生效；当前链路无缩略图）
        if (thumbnailUrl != null && imageContent != null) {
            imageContent(thumbnailUrl, Modifier.fillMaxSize())
        }

        // 播放按钮：半透明黑底 + 白色三角（微信/飞书范式）
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(40.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "播放",
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }

        // 时长角标：右下黑底白字
        if (duration > 0) {
            Text(
                "${duration}″",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(Tk.spacing.xs)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

// ── 语音：应用内播放卡片（波形进度着色 + 播放/暂停态，微信/飞书范式） ──

/**
 * 语音卡片：播放键 + 竖条波形 + 时长。波形高度由 url 哈希确定性生成；
 * 播放中已播部分条形高亮（progress 比例），时长切换为「当前/总长」。
 */
@Composable
internal fun VoiceCard(
    url: String,
    durationSec: Int,
    waveSeed: Int,
    playing: Boolean,
    progress: Float,
    playedColor: Color,
    onTogglePlay: (() -> Unit)?,
) {
    val barCount = 14
    val duration = if (durationSec > 0) durationSec else 1
    // 宽度随时长增长（微信范式）：下限必须容纳固定内容（播放钮18+间距8+波形68+间距8+时长文字约44 = 146dp），
    // 旧公式 64dp 起步小于内容宽度导致遮挡
    val contentMin = 146f
    val cardWidth = (contentMin + duration * 2.0f).coerceIn(contentMin, 260f).dp
    val barColor = LocalContentColor.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .width(cardWidth)
            .then(onTogglePlay?.let { Modifier.clickable(onClick = it) } ?: Modifier),
    ) {
        Icon(
            imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (playing) "暂停" else "播放",
            tint = barColor,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(Tk.spacing.sm))
        VoiceWave(
            seed = waveSeed,
            barCount = barCount,
            color = barColor,
            playedColor = playedColor,
            progress = progress,
            // 固定宽（14 根×3dp+13 间×2dp=68dp）：weight 在气泡无界约束下塌缩（曾两次重叠/折叠）
            modifier = Modifier.width(68.dp).height(20.dp),
        )
        Spacer(Modifier.width(Tk.spacing.sm))
        Text(
            if (playing && progress > 0f) "${(duration * progress).toInt()}/${duration}″" else "${duration}″",
            style = MaterialTheme.typography.labelMedium,
            color = barColor,
        )
    }
}

/** 波形：确定性伪随机高度竖条（seed → 线性同余序列）；播放中按进度两段着色。 */
@Composable
private fun VoiceWave(
    seed: Int,
    barCount: Int,
    color: Color,
    playedColor: Color,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val barWidthPx = with(LocalDensity.current) { 3.dp.toPx() }
    val gapPx = with(LocalDensity.current) { 2.dp.toPx() }
    Canvas(modifier = modifier) {
        val contentW = barCount * barWidthPx + (barCount - 1) * gapPx
        var x = (size.width - contentW) / 2f
        val playedBars = (progress * barCount).toInt()
        var s = seed.toLong() and 0x7FFFFFFFL
        repeat(barCount) { i ->
            // LCG：确定性伪随机 0.25..1.0
            s = (s * 48271) % 0x7FFFFFFFL
            val level = 0.25f + (s % 100) / 100f * 0.75f
            val h = size.height * level
            drawRoundRect(
                color = if (i < playedBars && progress > 0f) playedColor else color,
                topLeft = Offset(x, (size.height - h) / 2f),
                size = Size(barWidthPx, h),
                cornerRadius = CornerRadius(barWidthPx / 2f),
            )
            x += barWidthPx + gapPx
        }
    }
}

// ── 文件：扩展名彩色图标块 + 文件名 + 大小 ──

/** 扩展名 → 图标块底色（飞书文件卡片配色家族）。 */
internal fun fileIconColor(fileName: String): Color {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "pdf" -> Color(0xFFF54A45)
        "doc", "docx" -> Color(0xFF3370FF)
        "xls", "xlsx", "csv" -> Color(0xFF00A870)
        "ppt", "pptx" -> Color(0xFFFF7D00)
        "zip", "rar", "7z", "tar", "gz" -> Color(0xFFFF9A4D)
        "png", "jpg", "jpeg", "gif", "webp", "svg" -> Color(0xFF7B61FF)
        "mp3", "wav", "flac", "m4a" -> Color(0xFF00B89A)
        "mp4", "mov", "avi", "mkv" -> Color(0xFFE6294A)
        else -> Color(0xFF646A73)
    }
}

@Composable
internal fun FileCard(
    fileName: String,
    sizeText: String,
    onClick: (() -> Unit)? = null,
    downloadState: FileDownloadState? = null,
) {
    val iconColor = fileIconColor(fileName)
    val ext = fileName.substringAfterLast('.', "").uppercase().take(4).ifEmpty { "FILE" }
    val targetProgress = (downloadState as? FileDownloadState.Downloading)
        ?.progress?.takeIf { it >= 0f }?.coerceIn(0f, 1f) ?: 0f
    val animatedProgress = animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 180),
        label = "file-download-progress",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .widthIn(max = 280.dp)
            .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier),
    ) {
        // 扩展名图标块：圆角方形 + 大写扩展名（下载中替换为进度环）
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(MaterialTheme.shapes.small)
                .background(iconColor),
            contentAlignment = Alignment.Center,
        ) {
            if (downloadState is FileDownloadState.Downloading) {
                if (downloadState.progress < 0f) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.3f),
                        strokeWidth = 2.dp,
                    )
                } else {
                    CircularProgressIndicator(
                        progress = { animatedProgress.value },
                        modifier = Modifier.size(22.dp),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.3f),
                        strokeWidth = 2.dp,
                    )
                }
            } else if (ext.length <= 3) {
                Text(ext, style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = FontWeight.SemiBold)
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = "文件",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.width(Tk.spacing.md))
        Column {
            Text(
                fileName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                sizeText,
                style = MaterialTheme.typography.labelSmall,
                color = if (downloadState is FileDownloadState.Failed) MaterialTheme.colorScheme.error
                else Tk.colors.metaText,
            )
        }
    }
}

// ── 通用媒体图标卡片（无平台渲染器时的 fallback，不再用 emoji） ──

@Composable
internal fun MediaIconCard(title: String, subtitle: String, onClick: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .widthIn(max = 240.dp)
            .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            tint = LocalContentColor.current.copy(alpha = 0.7f),
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(Tk.spacing.sm))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Tk.colors.metaText)
            }
        }
    }
}
