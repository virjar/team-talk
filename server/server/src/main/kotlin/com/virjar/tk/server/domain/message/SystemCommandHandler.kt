package com.virjar.tk.server.domain.message

/**
 * 服务号指令消费端口（内测反馈 T058）：发往 sys_service 的消息经此进入指令路由。
 * 实现方在消息事务提交后的异步路径里执行回复（回复走正常发送链路）。
 */
fun interface SystemCommandHandler {
    suspend fun onServiceMessage(senderUid: String, chatId: String, clientMsgId: String, text: String)
}
