package com.virjar.tk.server.domain.user

import com.virjar.tk.protocol.model.User

/**
 * 一条已提交资料事实的尽力而为的进程本地发布。
 *
 * 实现只通知已完成 SYNC_READY 的会话，并排除已经拥有相同持久化 USER_UPDATED 事件的
 * 接收者。重连与权威 RPC 仍是离线恢复路径。消费者按 [User.revision] 对这条瞬态通道与
 * 持久化/RPC 快照排序；这个提示绝不能成为全局持久化扇出或成功依赖。
 */
fun interface UserProfileChangePublisher {
    suspend fun publish(user: User, durableRecipientUids: Set<String>)
}

internal data class CommittedUserProfileChange(
    val user: User,
    val durableRecipientUids: Set<String>,
)
