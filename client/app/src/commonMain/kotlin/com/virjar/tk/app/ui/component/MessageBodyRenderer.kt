package com.virjar.tk.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.virjar.tk.protocol.body.*
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.protocol.model.User
import com.virjar.tk.protocol.body.EmbeddedAssetPresentation
import com.virjar.tk.app.ui.component.rich.EmbeddedAssetRenderScope
import com.virjar.tk.app.ui.theme.Tk
import kotlin.math.abs

/**
 * 按消息 body 类型渲染消息内容。规格：doc/05-clients/rich-content.md。
 *
 * 媒体类（图片/视频/贴纸）走「贴边气泡」——由 [com.virjar.tk.ui.screen] 的 MessageBubble
 * 判定 [isEdgeToEdgeMedia] 后去掉气泡内边距，媒体自身即气泡面。
 *
 * @param onMediaClick 媒体卡片点击回调（图片/视频/贴纸）
 * @param imageContent 当前认证平台会话注入的图片缩略图渲染器
 * @param voicePlayback 当前认证会话的语音播放控制器
 */
@Composable
fun MessageBodyRenderer(
    message: Message,
    isMe: Boolean = false,
    onMediaClick: ((Message) -> Unit)? = null,
    onEmbeddedMediaClick: ((Message, EmbeddedAsset) -> Unit)? = null,
    onMessageLongClick: (() -> Unit)? = null,
    imageContent: @Composable (Attachment, Modifier) -> Unit,
    voicePlayback: VoicePlaybackController,
    onMentionClick: ((uid: String) -> Unit)? = null,
    onUrlClick: ((String) -> Unit)? = null,
    resolveSender: ((uid: String) -> User?)? = null,
) {
    val mediaKind = when (message.body) {
        is FileBody -> ChatMessageMediaKind.FILE
        is ImageBody -> ChatMessageMediaKind.IMAGE
        is VoiceBody -> ChatMessageMediaKind.VOICE
        is VideoBody -> ChatMessageMediaKind.VIDEO
        else -> null
    }
    val mediaModifier = mediaKind?.let { kind ->
        Modifier.testTag(chatMessageMediaTestTag(message.serverSeq, message.clientMsgId, kind))
    } ?: Modifier

    when (attachmentRenderMode(message)) {
        AttachmentRenderMode.UPLOAD_PLACEHOLDER -> {
            UploadingIndicator(
                progress = message.uploadProgress.coerceIn(0f, 1f),
                modifier = mediaModifier,
            )
            return
        }
        AttachmentRenderMode.UNAVAILABLE_PLACEHOLDER -> {
            val attachment = (message.body as AttachmentBody).attachment
            MediaIconCard(
                title = attachment.name.ifBlank { "附件" },
                subtitle = if (message.sendStatus == Message.SEND_STATUS_FAILED) "上传失败" else "附件尚未就绪",
                modifier = mediaModifier,
            )
            return
        }
        AttachmentRenderMode.REMOTE_CONTENT, null -> Unit
    }

    when (val body = message.body) {
        is RichTextBody -> EmbeddedAssetMessageText(
            message = message,
            body = body,
            markdown = body.markdown,
            onUrlClick = onUrlClick,
            onMentionClick = onMentionClick,
            onEmbeddedMediaClick = onEmbeddedMediaClick,
            onMessageLongClick = onMessageLongClick,
            imageContent = imageContent,
        )
        is InteractiveCardBody -> body.toCard()?.let { card ->
            com.virjar.tk.app.ui.component.rich.InteractiveCardView(card)
        } ?: MediaIconCard(title = "卡片", subtitle = "")

        is FileBody -> {
            val fileDownloads = LocalFileDownloads.current
            FileCardWithDownload(
                controller = fileDownloads,
                attachment = body.attachment,
                onLongClick = onMessageLongClick,
                modifier = mediaModifier,
            )
        }

        is VoiceBody -> VoiceCard(
            url = body.attachment.path,
            durationSec = body.duration,
            waveSeed = abs(body.attachment.path.hashCode()),
            playing = voicePlayback.playingUrl == body.attachment.path,
            progress = if (voicePlayback.playingUrl == body.attachment.path) voicePlayback.progress else 0f,
            // 已播高亮：自己气泡用容器前景色，对方气泡用主色。
            playedColor = if (isMe) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.primary,
            onTogglePlay = { voicePlayback.toggle(body.attachment, body.duration) },
            onLongClick = onMessageLongClick,
            modifier = mediaModifier,
        )

        is ImageBody -> {
            val clickAction = onMediaClick?.let { cb -> { cb(message) } }
            ImageThumbCard(
                attachment = body.thumbnail ?: body.attachment,
                imageContent = imageContent,
                imgWidth = body.width,
                imgHeight = body.height,
                onClick = clickAction,
                onLongClick = onMessageLongClick,
                modifier = mediaModifier,
            )
        }

        is VideoBody -> {
            val clickAction = onMediaClick?.let { cb -> { cb(message) } }
            VideoThumbCard(
                thumbnail = body.thumbnail,
                imageContent = imageContent,
                imgWidth = body.width,
                imgHeight = body.height,
                duration = body.duration,
                onClick = clickAction,
                onLongClick = onMessageLongClick,
                modifier = mediaModifier,
            )
        }

        is LocationBody -> MediaIconCard(title = body.title ?: "位置", subtitle = body.address ?: "")
        is CardBody -> MediaIconCard(
            title = "名片",
            subtitle = body.targetName,
            leadingContent = {
                AvatarPlaceholder(
                    name = body.targetName,
                    avatar = body.targetAvatar,
                    size = 32,
                )
            },
        )

        is StickerBody -> {
            val clickAction = onMediaClick?.let { cb -> { cb(message) } }
            ImageThumbCard(
                attachment = body.attachment,
                imageContent = imageContent,
                imgWidth = body.width,
                imgHeight = body.height,
                onClick = clickAction,
                onLongClick = onMessageLongClick,
            )
        }

        is ReplyBody -> ReplyView(
            message = message,
            body = body,
            onUrlClick = onUrlClick,
            onMentionClick = onMentionClick,
            resolveSender = resolveSender,
            onEmbeddedMediaClick = onEmbeddedMediaClick,
            onMessageLongClick = onMessageLongClick,
            imageContent = imageContent,
        )
        is com.virjar.tk.protocol.body.OfficeRefBody -> OfficeRefCard(
            body = body,
            // 打开走共享媒体点击链路；MediaClickHandler 分发到平台的 openOfficeRef 重校验。
            onClick = { onMediaClick?.invoke(message) },
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

/** Markdown 消息体只能从其自身所携带的 sidecar 中解析资源。 */
internal fun embeddedAssetRenderScopeFor(body: MessageBody): EmbeddedAssetRenderScope = when (body) {
    is RichTextBody -> EmbeddedAssetRenderScope(body.assets)
    is ReplyBody -> EmbeddedAssetRenderScope(body.assets)
    else -> EmbeddedAssetRenderScope.Empty
}

/** 普通富文本与回复正文共享的内联资源渲染器。 */
@Composable
private fun EmbeddedAssetMessageText(
    message: Message,
    body: MessageBody,
    markdown: String,
    onUrlClick: ((String) -> Unit)?,
    onMentionClick: ((uid: String) -> Unit)?,
    onEmbeddedMediaClick: ((Message, EmbeddedAsset) -> Unit)?,
    onMessageLongClick: (() -> Unit)?,
    imageContent: @Composable (Attachment, Modifier) -> Unit,
) {
    RichMessageText(
        content = markdown,
        onUrlClick = onUrlClick,
        onMentionClick = onMentionClick,
        embeddedAssets = embeddedAssetRenderScopeFor(body),
        embeddedAssetContent = { asset, presentation, modifier ->
            when (presentation) {
                EmbeddedAssetPresentation.IMAGE -> ImageThumbCard(
                    attachment = asset.thumbnail ?: asset.attachment,
                    imageContent = imageContent,
                    imgWidth = asset.width,
                    imgHeight = asset.height,
                    onClick = onEmbeddedMediaClick?.let { callback ->
                        { callback(message, asset) }
                    },
                    onLongClick = onMessageLongClick,
                    modifier = modifier,
                )
                EmbeddedAssetPresentation.FILE -> FileCardWithDownload(
                    controller = LocalFileDownloads.current,
                    attachment = asset.attachment,
                    onLongClick = onMessageLongClick,
                    modifier = modifier,
                )
            }
        },
    )
}

/** 贴边媒体：图片/视频/贴纸不以气泡卡片呈现，媒体本身即气泡（无内边距、圆角贴边）。 */
internal fun MessageBody?.isEdgeToEdgeMedia(): Boolean =
    this is ImageBody || this is VideoBody || this is StickerBody

/**
 * 上传占位在 HTTP 上传完成前刻意携带空的附件 path。它们是本地 UI 状态，而非可远程下载的
 * 附件，因此渲染器绝不能把它们交给文件 controller、图片缓存、画廊或任何其他 path 消费方。
 */
internal enum class AttachmentRenderMode {
    REMOTE_CONTENT,
    UPLOAD_PLACEHOLDER,
    UNAVAILABLE_PLACEHOLDER,
}

internal fun attachmentRenderMode(message: Message): AttachmentRenderMode? {
    val body = message.body as? AttachmentBody ?: return null
    return when {
        message.sendStatus == Message.SEND_STATUS_UPLOADING -> AttachmentRenderMode.UPLOAD_PLACEHOLDER
        body.attachment.path.isBlank() -> AttachmentRenderMode.UNAVAILABLE_PLACEHOLDER
        else -> AttachmentRenderMode.REMOTE_CONTENT
    }
}

/** 贴边媒体外观只有在真实远程附件存在后才启用。 */
internal fun Message.hasReadyEdgeToEdgeMedia(): Boolean =
    body.isEdgeToEdgeMedia() && attachmentRenderMode(this) == AttachmentRenderMode.REMOTE_CONTENT

// ── 引用样式：左侧竖线（飞书范式，两种气泡底色下都成立） ──

@Composable
private fun ReplyView(
    message: Message,
    body: ReplyBody,
    onUrlClick: ((String) -> Unit)?,
    onMentionClick: ((uid: String) -> Unit)?,
    resolveSender: ((uid: String) -> User?)?,
    onEmbeddedMediaClick: ((Message, EmbeddedAsset) -> Unit)?,
    onMessageLongClick: (() -> Unit)?,
    imageContent: @Composable (Attachment, Modifier) -> Unit,
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
            EmbeddedAssetMessageText(
                message = message,
                body = body,
                markdown = body.content,
                onUrlClick = onUrlClick,
                onMentionClick = onMentionClick,
                onEmbeddedMediaClick = onEmbeddedMediaClick,
                onMessageLongClick = onMessageLongClick,
                imageContent = imageContent,
            )
        }
    }
}

/**
 * 优先使用服务端的权威回复快照。旧消息有时会把 UID 存在该字段里；那些情况只能按
 * replyToSenderUid 解析，绝不能按外层回复作者或无关消息解析。若被引用的用户已离开
 * 或通讯录尚未加载，则使用人类可读的占位文本，而不是把内部 UID 泄漏进消息气泡。
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
private fun UploadingIndicator(progress: Float, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        modifier = modifier.width(200.dp),
    ) {
        Text("上传中 ${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = androidx.compose.material3.LocalContentColor.current)
        Spacer(Modifier.height(Tk.spacing.xs))
        androidx.compose.material3.LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}


/** 类型化办公对象引用卡片：图标 + 冻结快照标题/副标题；点击打开时重新校验当前权限。 */
@Composable
private fun OfficeRefCard(
    body: com.virjar.tk.protocol.body.OfficeRefBody,
    onClick: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .let { base -> if (onClick != null) base.clickable(onClick = onClick) else base }
            .testTag("chat.officeref.${body.targetId.take(12)}")
            .padding(horizontal = Tk.spacing.md, vertical = Tk.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (body.isDocument) "文" else "档",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.width(Tk.spacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                body.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (body.subtitle.isNotBlank()) {
                Text(
                    body.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = Tk.colors.metaText,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}
