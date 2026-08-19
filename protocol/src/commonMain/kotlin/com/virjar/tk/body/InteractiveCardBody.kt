package com.virjar.tk.body

import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 交互卡片消息体（wire）。doc/05-clients/rich-content.md：卡片走独立消息类型，
 * 结构化 JSON 而非 markdown 字符串（schema 校验 + 交互事件回传的前提，Slack Block Kit 同构）。
 *
 * wire 层单字段 JSON（payload 结构演进不受 wire 布局约束）；[CardPayload] 是 payload 的
 * 结构化模型，客户端与 bot 共用。
 *
 * 一期为静态卡片（渲染 + 无回调）；二期交互回调走新 RPC（契约表登记后接入）。
 */
data class InteractiveCardBody(
    val payloadJson: String,
) : MessageBody {

    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(payloadJson)
    }

    /** 解析为结构化模型；格式不符返回 null（卡片渲染降级为占位）。 */
    fun toCard(): CardPayload? = try {
        cardJson.decodeFromString(CardPayload.serializer(), payloadJson)
    } catch (_: Exception) {
        null
    }

    companion object : IProtoReader<InteractiveCardBody> {
        override fun readFrom(buf: PacketBuffer): InteractiveCardBody =
            InteractiveCardBody(
                payloadJson = buf.readString(
                    MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_INTERACTIVE_CARD_JSON_LENGTH),
                )!!,
            )

        private val cardJson = Json { ignoreUnknownKeys = true }

        fun of(card: CardPayload): InteractiveCardBody =
            InteractiveCardBody(payloadJson = cardJson.encodeToString(CardPayload.serializer(), card))
    }
}

/**
 * 卡片 payload 模型（跨端共享）。
 *
 * ```
 * CardPayload(
 *   title = "构建通知",
 *   blocks = listOf(
 *     CardBlock.Text("分支 main 构建通过，耗时 3m12s"),
 *     CardBlock.Actions(listOf(CardAction("rerun", "重新构建", CardActionStyle.PRIMARY))),
 *   )
 * )
 * ```
 */
@Serializable
data class CardPayload(
    val title: String? = null,
    val blocks: List<CardBlock> = emptyList(),
)

@Serializable
sealed interface CardBlock {
    @Serializable
    data class Text(val text: String) : CardBlock
}

@Serializable
data class CardAction(
    /** 动作标识（交互回调时回传；一期静态卡片仅展示） */
    val id: String,
    val label: String,
    val style: CardActionStyle = CardActionStyle.DEFAULT,
)

@Serializable
enum class CardActionStyle { DEFAULT, PRIMARY, DANGER }
