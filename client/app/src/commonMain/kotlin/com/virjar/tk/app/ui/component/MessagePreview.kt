package com.virjar.tk.app.ui.component

import com.virjar.tk.protocol.body.*
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.body.MessageBody
import com.virjar.tk.protocol.MessageType

/**
 * 消息预览文本工具。
 *
 * 将 [Message]（含任意 [MessageBody] 子类型）转成单行纯文本预览，
 * 供会话行、消息搜索结果和回复编辑提示等场景共用；不回写消息或持久投影。
 *
 * 让每种消息类型都有可读预览而非 `[2]` `[5]` 这样的占位符。
 */
object MessagePreview {

    /** 服务端按连接版本投影/未知类型的统一占位文案（向前兼容兜底，双层防线的客户端侧）。 */
    const val UNSUPPORTED_PLACEHOLDER_TEXT = "[当前版本不支持此消息]"

    /**
     * 返回消息的单行预览文本。
     *
     * @param message 消息
     * @param flagsAware 是否体现已撤回/已编辑标记（会话列表通常需要，纯内容场景可关闭）
     */
    fun preview(message: Message, flagsAware: Boolean = true): String {
        // 撤回/编辑标记优先（会话列表要体现状态）
        if (flagsAware) {
            if (message.flags and Message.FLAG_REVOKED != 0) return "撤回了一条消息"
            if (message.flags and Message.FLAG_EDITED != 0) return previewBody(message.body) + "（已编辑）"
        }
        if (isUnsupportedPlaceholder(message)) return UNSUPPORTED_PLACEHOLDER_TEXT
        return previewBody(message.body, message.messageType)
    }

    /**
     * 服务端投影占位（FLAG_PROJECTION_PLACEHOLDER，正文被剥除，升级刷新前一律按占位展示）
     * 或本版本不认识的类型（旁路到达的向前兼容兜底）。
     */
    fun isUnsupportedPlaceholder(message: Message): Boolean =
        (message.flags and Message.FLAG_PROJECTION_PLACEHOLDER != 0 && message.body == null) ||
            (message.body == null && MessageType.fromCode(message.messageType) == null)

    /** 仅按 body 生成预览（不考虑 flags）。 */
    fun previewBody(body: MessageBody?, messageType: Int = MessageType.RICH_TEXT.code): String = when (body) {
        // 已发行 plainText 的图片 alt 参与幂等哈希；只在读侧从 Markdown + sidecar 派生摘要。
        is RichTextBody -> if (body.assets.isEmpty()) body.plainText else
            richTextDisplayText(body.markdown, body.assets)
        is InteractiveCardBody -> "[卡片] " + (body.toCard()?.title ?: "")
        is FileBody -> "[文件] ${body.attachment.name}"
        is VoiceBody -> "[语音] ${body.duration}″"
        is ImageBody -> "[图片]"
        is VideoBody -> "[视频] ${body.duration}″"
        is LocationBody -> body.title ?: body.address ?: "[位置]"
        is CardBody -> "[名片] ${body.targetName}"
        is StickerBody -> "[表情]"
        is ReplyBody -> body.content.takeIf(String::isNotBlank)?.let { richTextDisplayText(it, body.assets) }
            ?: body.replySnippet
            ?: "[回复]"
        is ForwardBody -> body.forwardNote?.let { "[转发] $it" } ?: "[转发消息]"
        is MergeForwardBody -> body.title ?: "[合并转发]"
        is RevokeBody -> "撤回了一条消息"
        is EditBody -> "已编辑：${body.newContent}"
        is ReactionBody -> "[表情回应]"
        is com.virjar.tk.protocol.body.OfficeRefBody ->
            (if (body.isDocument) "[文档] " else "[群文件] ") + body.title
        is TaskRefBody -> "[任务] " + body.title
        is CallLogBody -> if (body.durationSec > 0)
            "[通话] " + (body.durationSec / 60).toString().padStart(2, '0') + ":" + (body.durationSec % 60).toString().padStart(2, '0')
        else "[通话] 未接听"
        null -> if (messageType == MessageType.TYPING.code) "正在输入..." else "[未知消息]"
    }
}
