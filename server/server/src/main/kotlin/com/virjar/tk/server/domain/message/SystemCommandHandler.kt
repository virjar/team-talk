package com.virjar.tk.server.domain.message

/**
 * 服务号指令消费端口（内测反馈 T058，恢复语义 CODE-01）：发往 sys_service 的原消息提交时
 * 已在权威存储留下 [PendingServiceReply]，实现方负责实际发送与结算（成功或终态放弃后删除记录）。
 * 派发本身不等待回复完成；启动恢复经同一实现排空仍未结算的记录。
 */
fun interface SystemCommandHandler {
    fun dispatchServiceReply(pending: PendingServiceReply)
}
