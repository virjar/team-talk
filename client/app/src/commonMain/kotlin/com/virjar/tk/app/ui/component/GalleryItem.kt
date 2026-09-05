package com.virjar.tk.app.ui.component

import com.virjar.tk.protocol.body.ImageBody
import com.virjar.tk.protocol.body.EmbeddedAssetPresentation
import com.virjar.tk.protocol.body.MarkdownAssetPolicy
import com.virjar.tk.protocol.body.ReplyBody
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.protocol.body.VideoBody
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.protocol.model.Message

/**
 * 媒体画廊项（图片或视频）。commonMain 共享，供全屏画廊使用。
 */
enum class GalleryMediaType { IMAGE, VIDEO }

data class GalleryItem(
    val attachment: Attachment,
    val type: GalleryMediaType,
    val sourceMessageId: String = attachment.path,
    /** null 表示独立媒体消息；非 null 表示该消息 Markdown sidecar 中的资源。 */
    val sourceAssetId: String? = null,
) {
    val path: String get() = attachment.path
    val stableId: String get() = buildString {
        append(sourceMessageId)
        sourceAssetId?.let { append(':').append(it) }
        append(':').append(type.name).append(':').append(path)
    }
}

/**
 * 从消息列表中提取图片+视频媒体项（用于全屏滑动浏览器）。
 *
 * 注意：视频项使用主附件 path（视频本体），不用 thumbnail（缩略图是 JPEG，
 * ExoPlayer 无法播放）。缩略图用于消息列表预览，画廊里应播放完整视频。
 */
fun buildMediaList(messages: List<Message>): List<GalleryItem> = buildList {
    messages.forEach { msg ->
        // 被撤回的消息体在时间线中被刻意替换为系统提示。若把它们的附件保留在相邻项的
        // 滑动画廊中，就会绕过该展示边界，泄露会话已不再渲染的内容。
        if (msg.flags and Message.FLAG_REVOKED != 0) return@forEach
        when (val body = msg.body) {
            is ImageBody -> if (attachmentRenderMode(msg) == AttachmentRenderMode.REMOTE_CONTENT) {
                add(
                    GalleryItem(
                        attachment = body.attachment,
                        type = GalleryMediaType.IMAGE,
                        sourceMessageId = msg.clientMsgId,
                    ),
                )
            }
            is VideoBody -> if (attachmentRenderMode(msg) == AttachmentRenderMode.REMOTE_CONTENT) {
                add(
                    GalleryItem(
                        attachment = body.attachment,
                        type = GalleryMediaType.VIDEO,
                        sourceMessageId = msg.clientMsgId,
                    ),
                )
            }
            is RichTextBody -> addEmbeddedMarkdownImages(msg, body.markdown, body.assets)
            is ReplyBody -> addEmbeddedMarkdownImages(msg, body.content, body.assets)
            else -> Unit
        }
    }
}

private fun MutableList<GalleryItem>.addEmbeddedMarkdownImages(
    message: Message,
    markdown: String,
    assets: List<EmbeddedAsset>,
) {
    // 纯 Markdown 是主流场景。避免在用户打开不相关的画廊项时，为每条纯文本消息重新扫描
    // 多达 10 万个字符。
    if (assets.isEmpty()) return
    val assetsById = assets.associateBy(EmbeddedAsset::assetId)
    MarkdownAssetPolicy.references(markdown)
        .asSequence()
        .filter { it.presentation == EmbeddedAssetPresentation.IMAGE }
        .mapNotNull { it.assetId }
        .distinct()
        .mapNotNull(assetsById::get)
        .filter { it.attachment.path.isNotBlank() }
        .forEach { asset ->
            add(
                GalleryItem(
                    attachment = asset.attachment,
                    type = GalleryMediaType.IMAGE,
                    sourceMessageId = message.clientMsgId,
                    sourceAssetId = asset.assetId,
                ),
            )
        }
}
