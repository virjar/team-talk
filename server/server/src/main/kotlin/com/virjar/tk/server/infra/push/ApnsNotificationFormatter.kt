package com.virjar.tk.server.infra.push

import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.body.CallLogBody
import com.virjar.tk.protocol.body.EmbeddedAssetPresentation
import com.virjar.tk.protocol.body.FileBody
import com.virjar.tk.protocol.body.MarkdownAssetPolicy
import com.virjar.tk.protocol.body.OfficeRefBody
import com.virjar.tk.protocol.body.ReplyBody
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.protocol.model.ChatType
import com.virjar.tk.protocol.model.MentionPolicy
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.server.domain.message.MessageTextExtractor

/** 格式化后的 APNs 展示文案。 */
internal data class ApnsAlertContent(
    val title: String,
    val body: String,
)

/**
 * 根据 [ApnsContentMode]、会话类型与消息类型生成锁屏通知文案。
 *
 * - [ApnsContentMode.SENDER_ONLY]（默认）：私聊标题为对方名称、正文为消息类型；群聊标题为群名称、
 *   正文为发送者与消息类型（被 `@` 时前置提醒），锁屏不暴露文字正文或具体文件名。
 * - [ApnsContentMode.PREVIEW]：展示消息正文摘要或附件文件名，适合不限制锁屏摘要的团队。
 * - [ApnsContentMode.GENERIC]：仅展示应用名与固定未读提示。
 */
internal object ApnsNotificationFormatter {
    private const val MAX_TITLE_CHARS = 40
    private const val MAX_BODY_CHARS = 120

    fun format(
        contentMode: ApnsContentMode,
        fallbackTitle: String,
        chatType: Int,
        groupName: String?,
        senderDisplayName: String?,
        recipientUid: String,
        message: Message,
    ): ApnsAlertContent {
        val safeFallbackTitle = truncateSingleLine(fallbackTitle, MAX_TITLE_CHARS).ifEmpty { "TeamTalk" }
        if (contentMode == ApnsContentMode.GENERIC) {
            return ApnsAlertContent(title = safeFallbackTitle, body = OEM_PUSH_CONTENT)
        }
        val senderName = senderDisplayName?.let { truncateSingleLine(it, MAX_TITLE_CHARS) }
            ?.takeIf { it.isNotEmpty() }
            ?: "联系人"
        val resolvedType = ChatType.fromCode(chatType)
        val title = when (resolvedType) {
            ChatType.GROUP -> groupName?.let { truncateSingleLine(it, MAX_TITLE_CHARS) }
                ?.takeIf { it.isNotEmpty() }
                ?: "群聊"
            ChatType.SAVED -> "保存的消息"
            else -> senderName
        }
        val mentionPrefix = if (resolvedType == ChatType.GROUP) mentionPrefix(message, recipientUid) else ""
        val contentText = when (contentMode) {
            ApnsContentMode.SENDER_ONLY -> typePhrase(message)
            ApnsContentMode.PREVIEW -> previewPhrase(message)
            ApnsContentMode.GENERIC -> OEM_PUSH_CONTENT
        }
        val rawBody = when (resolvedType) {
            ChatType.GROUP -> when (contentMode) {
                ApnsContentMode.SENDER_ONLY -> "$mentionPrefix$senderName $contentText"
                ApnsContentMode.PREVIEW -> "$mentionPrefix$senderName: $contentText"
                ApnsContentMode.GENERIC -> OEM_PUSH_CONTENT
            }
            else -> contentText
        }
        return ApnsAlertContent(
            title = title,
            body = truncateSingleLine(rawBody, MAX_BODY_CHARS),
        )
    }

    private fun mentionPrefix(message: Message, recipientUid: String): String {
        val mentionedUids = when (val body = message.body) {
            is RichTextBody -> body.mentions.map { it.uid }
            is ReplyBody -> runCatching { buildRichTextBody(body.content, body.assets).mentions.map { it.uid } }
                .getOrDefault(emptyList())
            else -> emptyList()
        }
        return when {
            recipientUid.isNotEmpty() && recipientUid in mentionedUids -> "[有人@你] "
            MentionPolicy.ALL in mentionedUids -> "[@所有人] "
            else -> ""
        }
    }

    private fun typePhrase(message: Message): String {
        val body = message.body
        return when (MessageType.fromCode(message.messageType)) {
            MessageType.RICH_TEXT -> richTextTypePhrase(body as? RichTextBody)
            MessageType.IMAGE -> "发来一张[图片]"
            MessageType.VOICE -> "发来一条[语音]"
            MessageType.VIDEO -> "发来一段[视频]"
            MessageType.FILE -> "发来一个[文件]"
            MessageType.LOCATION -> "发来一个[位置]"
            MessageType.CARD -> "发来一张[个人名片]"
            MessageType.REPLY -> replyTypePhrase(body as? ReplyBody)
            MessageType.FORWARD,
            MessageType.MERGE_FORWARD,
            MessageType.MERGE_FORWARD_SNAPSHOT,
            -> "发来[聊天记录]"
            MessageType.STICKER -> "发来一个[表情]"
            MessageType.INTERACTIVE_CARD -> "发来一张[卡片]"
            MessageType.OFFICE_REF -> if ((body as? OfficeRefBody)?.isDocument == true) {
                "分享了一篇[文档]"
            } else {
                "分享了一个[群文件]"
            }
            MessageType.TASK_REF -> "分享了一个[待办任务]"
            MessageType.CALL_LOG -> if ((body as? CallLogBody)?.video == true) {
                "发来[视频通话]"
            } else {
                "发来[语音通话]"
            }
            else -> "发来一条消息"
        }
    }

    private fun richTextTypePhrase(body: RichTextBody?): String {
        if (body == null || body.assets.isEmpty()) return "发来一条消息"
        val refs = runCatching { MarkdownAssetPolicy.references(body.markdown) }.getOrNull().orEmpty()
        if (refs.isEmpty() || !markdownHasOnlyAssetReferences(body.markdown, refs)) return "发来一条消息"
        return if (refs.all { it.presentation == EmbeddedAssetPresentation.IMAGE }) {
            "发来一张[图片]"
        } else if (refs.none { it.presentation == EmbeddedAssetPresentation.IMAGE }) {
            "发来一个[文件]"
        } else {
            "发来一张[图片]"
        }
    }

    private fun replyTypePhrase(body: ReplyBody?): String {
        if (body == null || body.content.isBlank()) return "回复了一条消息"
        val resolved = runCatching { buildRichTextBody(body.content, body.assets) }.getOrNull()
        if (resolved != null && resolved.assets.isNotEmpty()) {
            val refs = runCatching { MarkdownAssetPolicy.references(resolved.markdown) }.getOrNull().orEmpty()
            if (refs.isNotEmpty() && markdownHasOnlyAssetReferences(resolved.markdown, refs)) {
                return if (refs.any { it.presentation == EmbeddedAssetPresentation.IMAGE }) {
                    "回复了一张[图片]"
                } else {
                    "回复了一个[文件]"
                }
            }
        }
        return "回复了一条消息"
    }

    private fun markdownHasOnlyAssetReferences(
        markdown: String,
        refs: List<com.virjar.tk.protocol.body.MarkdownAssetReference>,
    ): Boolean {
        var cursor = 0
        for (ref in refs) {
            if (ref.startOffset > cursor && markdown.substring(cursor, ref.startOffset).isNotBlank()) {
                return false
            }
            cursor = maxOf(cursor, ref.endOffsetExclusive)
        }
        return cursor >= markdown.length || markdown.substring(cursor).isBlank()
    }

    private fun previewPhrase(message: Message): String {
        val body = message.body
        val extracted = MessageTextExtractor.extractConversationPreview(message, body)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (extracted != null) {
            return when (MessageType.fromCode(message.messageType)) {
                MessageType.FILE -> "[文件] $extracted"
                MessageType.LOCATION -> "[位置] $extracted"
                MessageType.CARD -> "[个人名片] $extracted"
                else -> extracted
            }
        }
        return when (MessageType.fromCode(message.messageType)) {
            MessageType.RICH_TEXT -> {
                val rich = body as? RichTextBody
                if (rich != null && rich.assets.isNotEmpty()) {
                    if (firstAssetIsImage(rich.markdown, rich.assets)) "[图片]" else "[文件]"
                } else {
                    "发来一条消息"
                }
            }
            MessageType.IMAGE -> "[图片]"
            MessageType.VOICE -> "[语音]"
            MessageType.VIDEO -> "[视频]"
            MessageType.FILE -> "[文件]"
            MessageType.LOCATION -> "[位置]"
            MessageType.CARD -> "[个人名片]"
            MessageType.STICKER -> "[表情]"
            MessageType.CALL_LOG -> if ((body as? CallLogBody)?.video == true) "[视频通话]" else "[语音通话]"
            MessageType.FORWARD,
            MessageType.MERGE_FORWARD,
            MessageType.MERGE_FORWARD_SNAPSHOT,
            -> "[聊天记录]"
            MessageType.INTERACTIVE_CARD -> "[卡片]"
            else -> "发来一条消息"
        }
    }

    private fun firstAssetIsImage(
        markdown: String,
        assets: List<com.virjar.tk.protocol.model.EmbeddedAsset>,
    ): Boolean {
        val presentation = runCatching {
            MarkdownAssetPolicy.references(markdown).firstOrNull()?.presentation
        }.getOrNull()
        if (presentation != null) return presentation == EmbeddedAssetPresentation.IMAGE
        return assets.firstOrNull()?.attachment?.contentType?.startsWith("image/", ignoreCase = true) == true
    }

    private fun truncateSingleLine(raw: String, maxChars: Int): String {
        val collapsed = raw.trim().replace(WHITESPACE_RUN, " ")
        if (collapsed.length <= maxChars) return collapsed
        return collapsed.take((maxChars - 1).coerceAtLeast(1)) + "…"
    }

    private val WHITESPACE_RUN = Regex("\\s+")
}
