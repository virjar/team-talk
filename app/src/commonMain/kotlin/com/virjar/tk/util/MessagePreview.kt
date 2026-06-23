package com.virjar.tk.util

import com.virjar.tk.body.*
import com.virjar.tk.model.Message
import com.virjar.tk.model.MessageBody
import com.virjar.tk.protocol.MessageType

/**
 * 消息预览文本工具。
 *
 * 将 [Message]（含任意 [MessageBody] 子类型）转成单行纯文本预览，
 * 供会话列表 lastMessage 预览、消息搜索结果、通知等场景共用。
 *
 * 替代旧代码中 `msg.body as? TextBody ?: "[${msg.messageType}]"` 的降级逻辑，
 * 让每种消息类型都有可读预览而非 `[2]` `[5]` 这样的占位符。
 */
object MessagePreview {

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
        return previewBody(message.body, message.messageType)
    }

    /** 仅按 body 生成预览（不考虑 flags）。 */
    fun previewBody(body: MessageBody?, messageType: Int = MessageType.TEXT.code): String = when (body) {
        is TextBody -> body.text
        is FileBody -> "[文件] ${body.fileName}"
        is VoiceBody -> "[语音] ${body.duration}″"
        is ImageBody -> "[图片]"
        is VideoBody -> "[视频] ${body.duration}″"
        is LocationBody -> body.title ?: body.address ?: "[位置]"
        is CardBody -> "[名片] ${body.targetName}"
        is StickerBody -> "[表情]"
        is ReplyBody -> body.content.ifBlank { body.replySnippet ?: "[回复]" }
        is ForwardBody -> body.forwardNote?.let { "[转发] $it" } ?: "[转发消息]"
        is MergeForwardBody -> body.title ?: "[合并转发]"
        is RevokeBody -> "撤回了一条消息"
        is EditBody -> "已编辑：${body.newContent}"
        is ReactionBody -> "[表情回应]"
        null -> if (messageType == MessageType.TYPING.code) "正在输入..." else "[未知消息]"
        else -> "[消息]"
    }
}

/**
 * 格式化文件大小（字节 → 可读字符串）。
 *
 * 示例：512 → "512 B"，1536 → "1.5 KB"，524288 → "512 KB"，1048576 → "1.0 MB"
 */
fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var size = bytes.toDouble()
    var unitIndex = 0
    while (size >= 1024 && unitIndex < units.lastIndex) {
        size /= 1024
        unitIndex++
    }
    // B 取整，其余保留 1 位小数（KMP 无 String.format，手动四舍五入）
    return if (unitIndex == 0) {
        "$bytes B"
    } else {
        val rounded = ((size * 10).toLong().toDouble()) / 10.0
        val s = if (rounded == rounded.toLong().toDouble()) "${rounded.toLong()}" else rounded.toString()
        "$s ${units[unitIndex]}"
    }
}
