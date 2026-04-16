package com.virjar.tk.service

import com.virjar.tk.protocol.PacketType
import com.virjar.tk.protocol.payload.*
import org.slf4j.LoggerFactory

/**
 * 从 Message 中提取可搜索文本。
 */
object PayloadTextExtractor {

    private val logger = LoggerFactory.getLogger(PayloadTextExtractor::class.java)

    /**
     * 从消息中提取可搜索的文本内容。
     * 返回 null 表示该消息类型不需要索引。
     */
    fun extract(message: Message): String? {
        val packetType = message.packetType
        val body = message.body

        return try {
            extractFromBody(packetType, body)
        } catch (e: Exception) {
            logger.debug("Failed to extract text from message {}: {}", message.messageId, e.message)
            null
        }
    }

    private fun extractFromBody(packetType: PacketType, body: MessageBody): String? {
        return when (packetType) {
            PacketType.TEXT -> (body as? TextBody)?.text

            PacketType.IMAGE -> (body as? ImageBody)?.caption

            PacketType.FILE -> (body as? FileBody)?.fileName

            PacketType.REPLY -> (body as? ReplyBody)?.text

            PacketType.MERGE_FORWARD -> (body as? MergeForwardBody)?.title

            PacketType.CARD -> {
                val card = body as? CardBody
                card?.name
            }

            PacketType.LOCATION -> {
                val loc = body as? LocationBody
                if (loc != null) {
                    val parts = listOfNotNull(loc.title, loc.address).filter { it.isNotEmpty() }
                    parts.joinToString(" ").ifEmpty { null }
                } else null
            }

            PacketType.INTERACTIVE -> {
                val interactive = body as? InteractiveBody
                if (interactive != null) {
                    val parts = listOfNotNull(interactive.title, interactive.content).filter { it.isNotEmpty() }
                    parts.joinToString(" ").ifEmpty { null }
                } else null
            }

            PacketType.RICH -> {
                val rich = body as? RichBody
                rich?.segments?.mapNotNull { it.text }?.joinToString(" ")?.ifEmpty { null }
            }

            PacketType.EDIT -> (body as? EditBody)?.newContent

            // FORWARD, VOICE, VIDEO, TYPING, REACTION, STICKER — 无可搜索文本
            else -> null
        }
    }
}
