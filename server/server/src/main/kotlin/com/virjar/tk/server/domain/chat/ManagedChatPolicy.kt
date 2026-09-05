package com.virjar.tk.server.domain.chat

import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext

data class ManagedChatAuthority(
    val managed: Boolean,
    val ready: Boolean,
    val ownerLabel: String? = null,
)

/** 一个聊天 id 的外部领域所有权与修订就绪状态。 */
interface ManagedChatPolicy {
    fun authority(chatId: String): ManagedChatAuthority

    /**
     * 事务绑定的 Chat 前置围栏。它只锁定已存在的按聊天区分的投影行，从不锁定全局的
     * OrganizationState，并从该已锁定快照返回所有权/就绪状态。对已有聊天的全局顺序是：
     * 本围栏，然后是 Chat，再是任何 User/Bot 行。聊天创建是唯一的 User -> Chat 例外，
     * 因为它的新 id 既没有权威行也没有 Chat。调用方在已经持有 User 行锁时绝不能进入
     * 本围栏。
     */
    fun lockAuthority(
        transaction: PgWriteTransactionContext,
        chatIds: Collection<String>,
    ): Map<String, ManagedChatAuthority>

    fun managedBy(chatId: String): String? = authority(chatId).ownerLabel
    fun isProjectionReady(chatId: String): Boolean = authority(chatId).ready
}

object UnmanagedChatPolicy : ManagedChatPolicy {
    override fun authority(chatId: String) = ManagedChatAuthority(managed = false, ready = true)

    override fun lockAuthority(
        transaction: PgWriteTransactionContext,
        chatIds: Collection<String>,
    ): Map<String, ManagedChatAuthority> = chatIds.distinct().associateWith(::authority)
}

fun interface ManagedChatProjectionCache {
    fun invalidateManagedChat(chatId: String)
}
