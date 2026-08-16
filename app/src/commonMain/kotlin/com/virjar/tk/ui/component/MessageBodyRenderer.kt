package com.virjar.tk.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
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
import com.virjar.tk.body.*
import com.virjar.tk.model.Message
import com.virjar.tk.ui.theme.Tk
import com.virjar.tk.util.MessagePreview
import com.virjar.tk.util.formatFileSize
import kotlin.math.abs

/**
 * 按消息 body 类型渲染消息内容。视觉规格：doc/04-ui-design/components.md §1.3。
 *
 * 媒体类（图片/视频/贴纸）走「贴边气泡」——由 [com.virjar.tk.ui.screen] 的 MessageBubble
 * 判定 [isEdgeToEdgeMedia] 后去掉气泡内边距，媒体自身即气泡面。
 *
 * @param onMediaClick 媒体卡片点击回调（文件/图片/视频；语音走 [voicePlayback] 应用内播放）
 * @param imageContent 平台注入的图片缩略图渲染（null 时 fallback 到图标卡片）
 * @param videoContent 平台注入的视频缩略图渲染（保留参数兼容；视频卡当前走 thumbnailUrl/深色占位）
 * @param voicePlayback 语音应用内播放控制器（null 时语音点击回退 onMediaClick，如 Android 旧路径）
 */
@Composable
fun MessageBodyRenderer(
    message: Message,
    isMe: Boolean = false,
    onMediaClick: ((Message) -> Unit)? = null,
    imageContent: (@Composable (String, Modifier) -> Unit)? = null,
    videoContent: (@Composable (String, Modifier) -> Unit)? = null,
    voicePlayback: VoicePlaybackController? = null,
) {
    when (val body = message.body) {
        // TextBody 整体按 markdown 渲染（Discord 语义；普通文本视觉不变），选型见 doc/10-rich-messaging
        is TextBody -> RichMessageText(body.text)

        is FileBody -> FileCard(
            fileName = body.fileName,
            sizeText = formatFileSize(body.size),
            onClick = onMediaClick?.let { cb -> { cb(message) } },
        )

        is VoiceBody -> VoiceCard(
            url = body.url,
            durationSec = body.duration,
            waveSeed = abs(body.url.hashCode()),
            playing = voicePlayback?.playingUrl == body.url,
            progress = if (voicePlayback?.playingUrl == body.url) voicePlayback.progress else 0f,
            // 已播高亮：自己气泡（蓝底）用纯白，对方气泡（灰底）用主色
            playedColor = if (isMe) Color.White else MaterialTheme.colorScheme.primary,
            onTogglePlay = voicePlayback?.let { vb -> { vb.toggle(body.url, body.duration) } }
                ?: onMediaClick?.let { cb -> { cb(message) } },
        )

        is ImageBody -> {
            val clickAction = onMediaClick?.let { cb -> { cb(message) } }
            if (imageContent != null) {
                ImageThumbCard(
                    imageUrl = body.url,
                    imageContent = imageContent,
                    imgWidth = body.width,
                    imgHeight = body.height,
                    onClick = clickAction,
                )
            } else {
                MediaIconCard(title = "图片", subtitle = "", onClick = clickAction)
            }
        }

        is VideoBody -> {
            val clickAction = onMediaClick?.let { cb -> { cb(message) } }
            VideoThumbCard(
                videoUrl = body.url,
                thumbnailUrl = body.thumbnailUrl,
                videoContent = videoContent,
                imageContent = imageContent,
                imgWidth = body.width,
                imgHeight = body.height,
                duration = body.duration,
                onClick = clickAction,
            )
        }

        is LocationBody -> MediaIconCard(title = body.title ?: "位置", subtitle = body.address ?: "")
        is CardBody -> MediaIconCard(title = "名片", subtitle = body.targetName)

        is StickerBody -> {
            val clickAction = onMediaClick?.let { cb -> { cb(message) } }
            if (imageContent != null) {
                ImageThumbCard(
                    imageUrl = body.url,
                    imageContent = imageContent,
                    imgWidth = body.width,
                    imgHeight = body.height,
                    onClick = clickAction,
                )
            } else {
                MediaIconCard(title = "表情", subtitle = "")
            }
        }

        is ReplyBody -> ReplyView(body)
        is ForwardBody -> ForwardView(body)
        is MergeForwardBody -> MediaIconCard(title = "合并转发", subtitle = "${body.messageCount} 条消息")
        is RevokeBody -> SystemHintText("撤回了一条消息")
        // 编辑消息直接渲染新内容；「（已编辑）」角标由 FLAG_EDITED 统一显示
        is EditBody -> Text(body.newContent, style = MaterialTheme.typography.bodyMedium)
        is ReactionBody -> SystemHintText("表情回应 ${body.emoji}")
        null -> SystemHintText(MessagePreview.previewBody(null, message.messageType))
        else -> SystemHintText(MessagePreview.previewBody(message.body))
    }

    if (message.flags and Message.FLAG_EDITED != 0) {
        Text("（已编辑）", style = MaterialTheme.typography.labelSmall, color = Tk.colors.metaText)
    }
}

/** 贴边媒体：图片/视频/贴纸不以气泡卡片呈现，媒体本身即气泡（无内边距、圆角贴边）。 */
internal fun com.virjar.tk.model.MessageBody?.isEdgeToEdgeMedia(): Boolean =
    this is ImageBody || this is VideoBody || this is StickerBody

// ── 图片/贴纸缩略图（贴边，圆角由气泡 Surface 裁剪） ──

@Composable
private fun ImageThumbCard(
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
private fun VideoThumbCard(
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
private fun VoiceCard(
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
    // 宽度随时长增长（微信范式）：60″ 约满 220dp
    val cardWidth = (64 + duration * 2.6f).coerceIn(64f, 220f).dp
    val barColor = LocalContentColor.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .widthIn(min = cardWidth, max = cardWidth)
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
            modifier = Modifier.weight(1f).height(20.dp),
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
private fun fileIconColor(fileName: String): Color {
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
private fun FileCard(fileName: String, sizeText: String, onClick: (() -> Unit)? = null) {
    val iconColor = fileIconColor(fileName)
    val ext = fileName.substringAfterLast('.', "").uppercase().take(4).ifEmpty { "FILE" }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .widthIn(max = 280.dp)
            .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier),
    ) {
        // 扩展名图标块：圆角方形 + 大写扩展名
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(MaterialTheme.shapes.small)
                .background(iconColor),
            contentAlignment = Alignment.Center,
        ) {
            if (ext.length <= 3) {
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
            Text(sizeText, style = MaterialTheme.typography.labelSmall, color = Tk.colors.metaText)
        }
    }
}

// ── 通用媒体图标卡片（无平台渲染器时的 fallback，不再用 emoji） ──

@Composable
private fun MediaIconCard(title: String, subtitle: String, onClick: (() -> Unit)? = null) {
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

// ── 引用样式：左侧竖线（飞书范式，两种气泡底色下都成立） ──

@Composable
private fun ReplyView(body: ReplyBody) {
    Column {
        // 引用块：3dp 主色竖线 + 引用者 + 截断内容
        Row(
            modifier = Modifier.widthIn(max = 260.dp).padding(vertical = 1.dp),
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(IntrinsicSize.Min)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(LocalContentColor.current.copy(alpha = 0.4f)),
            )
            Spacer(Modifier.width(Tk.spacing.sm))
            Column(Modifier.weight(1f)) {
                body.replyToSenderName?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalContentColor.current,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                body.replySnippet?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalContentColor.current.copy(alpha = 0.65f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        // 回复正文
        if (body.content.isNotBlank()) {
            Spacer(Modifier.height(Tk.spacing.xs))
            Text(body.content, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ForwardView(body: ForwardBody) {
    Column {
        Text("转发的消息", style = MaterialTheme.typography.labelMedium, color = LocalContentColor.current.copy(alpha = 0.7f))
        body.forwardNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun SystemHintText(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = Tk.colors.metaText)
}
