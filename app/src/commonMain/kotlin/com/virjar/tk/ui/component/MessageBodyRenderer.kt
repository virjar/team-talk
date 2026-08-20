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
import com.virjar.tk.model.User
import com.virjar.tk.ui.theme.Tk
import com.virjar.tk.util.MessagePreview
import com.virjar.tk.util.formatFileSize
import kotlin.math.abs

/**
 * 按消息 body 类型渲染消息内容。规格：doc/05-clients/rich-content.md。
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
    onMentionClick: ((uid: String) -> Unit)? = null,
    onUrlClick: ((String) -> Unit)? = null,
    resolveSender: ((uid: String) -> User?)? = null,
) {
    @Suppress("DEPRECATION")
    when (val body = message.body) {
        // TextBody 只保留历史消息兼容；新文字统一是 RichTextBody。
        is TextBody -> RichMessageText(body.text, onUrlClick = onUrlClick)
        is RichTextBody -> RichMessageText(body.markdown, onUrlClick = onUrlClick, onMentionClick = onMentionClick)
        is InteractiveCardBody -> body.toCard()?.let { card ->
            com.virjar.tk.ui.component.rich.InteractiveCardView(card)
        } ?: MediaIconCard(title = "卡片", subtitle = "")

        is FileBody -> {
            val fileDownloads = LocalFileDownloads.current
            if (fileDownloads != null) {
                FileCardWithDownload(fileDownloads, body.attachment)
            } else {
                // 未注入下载控制器（旧路径）：点击回退平台 onMediaClick
                FileCard(
                    fileName = body.attachment.name,
                    sizeText = formatFileSize(body.attachment.size),
                    onClick = onMediaClick?.let { cb -> { cb(message) } },
                )
            }
        }

        is VoiceBody -> VoiceCard(
            url = body.attachment.path,
            durationSec = body.duration,
            waveSeed = abs(body.attachment.path.hashCode()),
            playing = voicePlayback?.playingUrl == body.attachment.path,
            progress = if (voicePlayback?.playingUrl == body.attachment.path) voicePlayback.progress else 0f,
            // 已播高亮：自己气泡用容器前景色，对方气泡用主色。
            playedColor = if (isMe) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.primary,
            onTogglePlay = voicePlayback?.let { vb -> { vb.toggle(body.attachment.path, body.duration) } }
                ?: onMediaClick?.let { cb -> { cb(message) } },
        )

        is ImageBody -> {
            val clickAction = onMediaClick?.let { cb -> { cb(message) } }
            if (imageContent != null) {
                // 缩略图数据源（服务端生成；旧消息无缩略图回退原图 URL，由平台缓存层统一处理）
                ImageThumbCard(
                    imageUrl = body.thumbnail?.path ?: body.attachment.path,
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
                videoUrl = body.attachment.path,
                thumbnailUrl = body.thumbnail?.path,
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
                    imageUrl = body.attachment.path,
                    imageContent = imageContent,
                    imgWidth = body.width,
                    imgHeight = body.height,
                    onClick = clickAction,
                )
            } else {
                MediaIconCard(title = "表情", subtitle = "")
            }
        }

        is ReplyBody -> ReplyView(
            body = body,
            onUrlClick = onUrlClick,
            onMentionClick = onMentionClick,
            resolveSender = resolveSender,
        )
        is ForwardBody -> ForwardView(body)
        is MergeForwardBody -> MediaIconCard(title = "合并转发", subtitle = "${body.messageCount} 条消息")
        is RevokeBody -> SystemHintText("撤回了一条消息")
        // 编辑消息直接渲染新内容；「（已编辑）」角标由 FLAG_EDITED 统一显示
        is EditBody -> Text(body.newContent, style = MaterialTheme.typography.bodyMedium)
        is ReactionBody -> SystemHintText("表情回应 ${body.emoji}")
        null -> SystemHintText(MessagePreview.previewBody(null, message.messageType))
    }

    if (message.flags and Message.FLAG_EDITED != 0) {
        Text("（已编辑）", style = MaterialTheme.typography.labelSmall, color = Tk.colors.metaText)
    }
}

/** 贴边媒体：图片/视频/贴纸不以气泡卡片呈现，媒体本身即气泡（无内边距、圆角贴边）。 */
internal fun MessageBody?.isEdgeToEdgeMedia(): Boolean =
    this is ImageBody || this is VideoBody || this is StickerBody

// ── 引用样式：左侧竖线（飞书范式，两种气泡底色下都成立） ──

@Composable
private fun ReplyView(
    body: ReplyBody,
    onUrlClick: ((String) -> Unit)?,
    onMentionClick: ((uid: String) -> Unit)?,
    resolveSender: ((uid: String) -> User?)?,
) {
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
                Text(
                    resolveReplySenderName(body, resolveSender),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalContentColor.current,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
            RichMessageText(
                content = body.content,
                onUrlClick = onUrlClick,
                onMentionClick = onMentionClick,
            )
        }
    }
}

/**
 * Prefer the server's authoritative reply snapshot. Older messages sometimes stored the UID in
 * that field; resolve those only by replyToSenderUid, never by the outer reply author or an
 * unrelated message. If the referenced user has left or the directory is not loaded yet, use a
 * human-readable placeholder rather than leaking an internal UID into the message bubble.
 */
internal fun resolveReplySenderName(
    body: ReplyBody,
    resolveSender: ((uid: String) -> User?)?,
): String {
    body.replyToSenderName
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != body.replyToSenderUid }
        ?.let { return it }

    val user = resolveSender?.invoke(body.replyToSenderUid)
    return user?.name?.trim()?.takeIf(String::isNotEmpty)
        ?: user?.username?.trim()?.takeIf(String::isNotEmpty)
        ?: "未知成员"
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


/** 上传中指示器：进度条 + 百分比（占位消息气泡内容）。 */
@Composable
private fun UploadingIndicator(progress: Float) {
    Column(
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        modifier = Modifier.width(200.dp),
    ) {
        Text("上传中 ${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = androidx.compose.material3.LocalContentColor.current)
        Spacer(Modifier.height(Tk.spacing.xs))
        androidx.compose.material3.LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
