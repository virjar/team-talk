package com.virjar.tk.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.virjar.tk.body.*
import com.virjar.tk.model.Message
import com.virjar.tk.util.MessagePreview
import com.virjar.tk.util.formatFileSize

/**
 * 按消息 body 类型渲染消息内容。
 *
 * @param onMediaClick 媒体卡片点击回调（文件/语音/图片/视频）
 * @param imageContent 平台注入的图片缩略图渲染（null 时 fallback 到 emoji 卡片）
 * @param videoContent 平台注入的视频缩略图渲染（null 时 fallback 到 emoji 卡片）
 */
@Composable
fun MessageBodyRenderer(
    message: Message,
    isMe: Boolean = false,
    onMediaClick: ((Message) -> Unit)? = null,
    imageContent: (@Composable (url: String, modifier: Modifier) -> Unit)? = null,
    videoContent: (@Composable (url: String, modifier: Modifier) -> Unit)? = null,
) {
    when (val body = message.body) {
        is TextBody -> Text(body.text, style = MaterialTheme.typography.bodyMedium)

        is FileBody -> FileCard(
            fileName = body.fileName,
            sizeText = formatFileSize(body.size),
            onClick = onMediaClick?.let { cb -> { cb(message) } },
        )

        is VoiceBody -> MediaCard(
            emoji = "🎙", title = "语音消息", subtitle = "${body.duration}″",
            onClick = onMediaClick?.let { cb -> { cb(message) } },
        )

        is ImageBody -> {
            val clickAction = onMediaClick?.let { cb -> { cb(message) } }
            if (imageContent != null) {
                ImageThumbCard(
                    imageUrl = body.url,
                    imageContent = imageContent,
                    imgWidth = body.width,
                    imgHeight = body.height,
                    subtitle = "${body.width}×${body.height}",
                    onClick = clickAction,
                )
            } else {
                MediaCard(
                    emoji = "🖼", title = "图片", subtitle = "${body.width}×${body.height}",
                    onClick = clickAction,
                )
            }
        }

        is VideoBody -> {
            val clickAction = onMediaClick?.let { cb -> { cb(message) } }
            if (videoContent != null) {
                VideoThumbCard(
                    videoUrl = body.url,
                    thumbnailUrl = body.thumbnailUrl,
                    videoContent = videoContent,
                    duration = body.duration,
                    subtitle = "${body.width}×${body.height}",
                    onClick = clickAction,
                )
            } else {
                MediaCard(
                    emoji = "🎬", title = "视频", subtitle = "${body.duration}″",
                    onClick = clickAction,
                )
            }
        }

        is LocationBody -> MediaCard(emoji = "📍", title = body.title ?: "位置", subtitle = body.address ?: "")
        is CardBody -> MediaCard(emoji = "👤", title = "名片", subtitle = body.targetName)
        is StickerBody -> MediaCard(emoji = "😎", title = "表情", subtitle = "${body.width}×${body.height}")
        is ReplyBody -> ReplyView(body)
        is ForwardBody -> ForwardView(body)
        is MergeForwardBody -> MediaCard(emoji = "📦", title = "合并转发", subtitle = "${body.messageCount} 条消息")
        is RevokeBody -> SystemHintText("撤回了一条消息")
        is EditBody -> Text("已编辑：${body.newContent}", style = MaterialTheme.typography.bodyMedium)
        is ReactionBody -> SystemHintText("表情回应 ${body.emoji}")
        null -> SystemHintText(MessagePreview.previewBody(null, message.messageType))
        else -> SystemHintText(MessagePreview.previewBody(message.body))
    }

    if (message.flags and Message.FLAG_EDITED != 0) {
        Text("（已编辑）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}

// ── 图片缩略图卡片 ──

@Composable
private fun ImageThumbCard(
    imageUrl: String,
    imageContent: @Composable (String, Modifier) -> Unit,
    imgWidth: Int,
    imgHeight: Int,
    subtitle: String,
    onClick: (() -> Unit)?,
) {
    // 参考 Signal 的 fit-inside 策略：最大 240×320dp 盒子，等比缩放保留宽高比。
    // 宽高已知时计算 fit-inside 尺寸；未知(0)时 fallback 到 200×200 占位。
    val maxW = 240.dp
    val maxH = 320.dp
    val minDim = 120.dp  // 最小边，避免太小不可点
    val displaySize = if (imgWidth > 0 && imgHeight > 0) {
        // fit-inside: 取宽高缩放比的较小值，保证不超出盒子
        val ratio = minOf(maxW.value / imgWidth, maxH.value / imgHeight)
        var w = imgWidth * ratio
        var h = imgHeight * ratio
        // 最小尺寸保障：任一边低于 minDim 时拉伸到 minDim（另一边按宽高比）
        if (w < minDim.value) { val s = minDim.value / w; w = minDim.value; h *= s }
        if (h < minDim.value) { val s = minDim.value / h; h = minDim.value; w *= s }
        // 再次钳制不超过 maxW/maxH
        w = minOf(w, maxW.value)
        h = minOf(h, maxH.value)
        androidx.compose.ui.unit.Dp(w) to androidx.compose.ui.unit.Dp(h)
    } else {
        200.dp to 200.dp
    }

    Column(
        modifier = Modifier
            .widthIn(max = maxW)
            .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)
    ) {
        imageContent(
            imageUrl,
            Modifier.size(displaySize.first, displaySize.second).clip(RoundedCornerShape(8.dp)),
        )
        if (subtitle.isNotBlank() && subtitle != "0×0") {
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

// ── 视频缩略图卡片（带播放按钮覆盖） ──

@Composable
private fun VideoThumbCard(
    videoUrl: String,
    thumbnailUrl: String?,
    videoContent: @Composable (String, Modifier) -> Unit,
    duration: Int,
    subtitle: String,
    onClick: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .widthIn(max = 200.dp)
            .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)
    ) {
        Box(modifier = Modifier.widthIn(max = 200.dp).heightIn(max = 200.dp)) {
            val displayUrl = thumbnailUrl ?: videoUrl
            videoContent(displayUrl, Modifier.wrapContentSize(unbounded = true).clip(RoundedCornerShape(8.dp)))
            // 播放按钮覆盖
            Surface(
                modifier = Modifier.align(Alignment.Center).size(40.dp),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("▶", style = MaterialTheme.typography.titleMedium)
                }
            }
            // 时长标签
            if (duration > 0) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                ) {
                    Text(
                        " ${duration}″ ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

// ── 通用媒体卡片（emoji 图标 fallback） ──

@Composable
private fun MediaCard(emoji: String, title: String, subtitle: String, onClick: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.widthIn(max = 240.dp)
            .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)
    ) {
        Text(emoji, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun FileCard(fileName: String, sizeText: String, onClick: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.widthIn(max = 240.dp)
            .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)
    ) {
        Text("📄", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(fileName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(sizeText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

// ── 引用样式 ──

@Composable
private fun ReplyView(body: ReplyBody) {
    Column {
        // 引用卡片
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                .padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 2.dp)
                .widthIn(max = 200.dp),
        ) {
            body.replyToSenderName?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            body.replySnippet?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1)
            }
        }
        // 回复正文
        if (body.content.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(body.content, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ForwardView(body: ForwardBody) {
    Column {
        Text("转发的消息", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        body.forwardNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun SystemHintText(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
}
