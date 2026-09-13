package com.virjar.tk.server.domain.message

/**
 * 服务号指令消费端口（内测反馈 T058）：发往 sys_service 的消息经此进入指令路由。
 * 实现方拥有消息事务提交后的异步回复及其关闭；派发本身不等待回复完成。
 */
fun interface SystemCommandHandler {
    fun onServiceMessage(senderUid: String, chatId: String, clientMsgId: String, text: String)
}
