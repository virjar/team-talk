package com.virjar.tk.server.domain.message

import com.virjar.tk.protocol.body.MessageBody
import com.virjar.tk.protocol.body.MessageBodyPolicy
import com.virjar.tk.protocol.body.ReplyBody
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.protocol.body.buildRichTextBody
import java.security.MessageDigest
import java.util.HexFormat

/**
 * 一条欠发的服务号回复（CODE-01）：原消息与它同批持久化，回复完成或被终态拒绝前一直存在。
 *
 * `clientMsgId` 是触发指令的原消息身份；`replyClientMsgId` 与 `markdown` 在原消息提交时冻结，
 * 保证任何时点的重放/恢复都使用同一回复身份与内容——回复发送链本身按 clientMsgId 幂等，
 * 因此恢复重发不会产生第二条回复消息。
 */
data class PendingServiceReply(
    val chatId: String,
    val clientMsgId: String,
    val replyClientMsgId: String,
    val markdown: String,
)

/** 服务号指令解析与回复身份派生（原内测反馈 T058 路由逻辑的纯函数部分）。 */
object ServiceCommandReplies {
    /** 回复 clientMsgId 由原消息 clientMsgId 派生，重放与恢复共用同一身份。 */
    fun replyId(clientMsgId: String): String {
        val legacy = "svc-$clientMsgId"
        if (legacy.length <= MessageBodyPolicy.MAX_CLIENT_MESSAGE_ID_LENGTH) return legacy
        // 保留已经分发的短 ID；长 ID 编码完整身份，不能截断最后四个合法字符。
        val digest = MessageDigest.getInstance("SHA-256").digest(clientMsgId.toByteArray(Charsets.UTF_8))
        // 与旧映射的 svc- 前缀分开，避免合法短 ID 恰好等于长 ID 的摘要名称。
        return "svc.sha256:" + HexFormat.of().formatHex(digest)
    }

    /** 指令解析：/help 显示帮助；其余 / 指令回退帮助；普通文本同样回退帮助。 */
    fun replyFor(text: String): String = when {
        text.equals("/help", ignoreCase = true) -> HELP_TEXT
        text.startsWith("/") -> "未识别指令：$text\n\n$HELP_TEXT"
        else -> HELP_TEXT
    }

    /** 从原消息正文提取指令文本；与服务号指令无关的消息体返回 null，不欠回复。 */
    fun commandText(body: MessageBody?): String? = when (body) {
        is RichTextBody -> body.plainText
        is ReplyBody -> buildRichTextBody(body.content, body.assets).plainText
        else -> null
    }

    val HELP_TEXT = """
        服务号可用指令：
        - /help 显示本帮助

        直接发送其他内容会收到本提示。更多指令即将上线。
    """.trimIndent()
}
