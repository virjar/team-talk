package com.virjar.tk.server.domain.message

/**
 * 服务号指令路由（内测反馈 T058，设计稿 §14）：sys_service 收到的文本消息解析为指令，
 * 回复以 sys_service 身份走正常发送链路。
 *
 * 回复 clientMsgId 由原消息 clientMsgId 派生（"svc-" 前缀），幂等重放同一指令消息时
 * 发送入口按 clientMsgId 返回原 seq，不会产生重复回复。
 */
class SystemCommandRouter(
    private val sendServiceReply: suspend (chatId: String, clientMsgId: String, markdown: String) -> Long,
) : SystemCommandHandler {
    override suspend fun onServiceMessage(senderUid: String, chatId: String, clientMsgId: String, text: String) {
        sendServiceReply(chatId, "svc-$clientMsgId".take(MAX_REPLY_ID_LENGTH), replyFor(text.trim()))
    }

    /** 指令解析：/help 显示帮助；其余 / 指令回退帮助；普通文本同样回退帮助。 */
    internal fun replyFor(text: String): String = when {
        text.equals("/help", ignoreCase = true) -> HELP_TEXT
        text.startsWith("/") -> "未识别指令：$text\n\n$HELP_TEXT"
        else -> HELP_TEXT
    }

    internal companion object {
        const val MAX_REPLY_ID_LENGTH = 256
        const val SERVICE_COMMAND_TRIGGER = "/"

        val HELP_TEXT = """
            服务号可用指令：
            - /help 显示本帮助

            直接发送其他内容会收到本提示。更多指令即将上线。
        """.trimIndent()
    }
}
