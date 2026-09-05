package com.virjar.tk.server.domain.bot

import com.virjar.tk.protocol.model.Message
import com.virjar.tk.server.domain.chat.GroupMemberAddition
import com.virjar.tk.server.domain.chat.GroupMemberAdditionFacts
import com.virjar.tk.server.domain.chat.GroupMemberRemoval
import com.virjar.tk.server.domain.chat.GroupMemberRemovalFacts
import com.virjar.tk.server.domain.chat.LockedChat
import com.virjar.tk.server.domain.chat.ServiceMemberProjectionCleanup
import com.virjar.tk.server.domain.transaction.PgReadTransactionContext
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.protocol.model.Member

/** 机器人领域为非登录服务账户提供资源时返回的最小身份。 */
data class BotAccountIdentity(
    val uid: String,
    val name: String,
)

fun interface BotAccountProvisioner {
    /** 在调用方的聚合事务中持久化该非登录身份。 */
    fun createServiceAccount(transaction: PgWriteTransactionContext, name: String): BotAccountIdentity
}

/** 聊天领域拥有的群成员投影，在不暴露其完整服务 API 的情况下开放。 */
interface BotGroupMembership {
    fun activeChatIds(uid: String): Set<String>
    fun activeChatIds(transaction: PgReadTransactionContext, uid: String): Set<String>
    fun projectedChatIds(uid: String): Set<String>
    fun projectedChatIds(transaction: PgReadTransactionContext, uid: String): Set<String>

    /**
     * 先锁定托管聊天（managed-chat）权威行，拒绝挂起中的权威，再按相同的字典序锁定 Chats。
     * 实现绝不能先锁定投影行再锁定 Chat。
     */
    fun lockChats(
        transaction: PgWriteTransactionContext,
        chatIds: Collection<String>,
        requireActive: Boolean,
    ): Map<String, LockedChat>

    fun getActiveMember(transaction: PgReadTransactionContext, chatId: String, uid: String): Member?

    /** 在调用方的聚合事务中添加/恢复成员关系与 Conversation。 */
    fun addServiceMember(
        transaction: PgWriteTransactionContext,
        chatId: String,
        operatorUid: String,
        uid: String,
        authorize: (GroupMemberAdditionFacts) -> Unit,
    ): GroupMemberAddition

    /** 若在已锁定的事务快照中仍处于活跃状态，则移除成员关系与 Conversation。 */
    fun removeServiceMemberIfPresent(
        transaction: PgWriteTransactionContext,
        chatId: String,
        operatorUid: String,
        uid: String,
        requireActiveChat: Boolean,
        authorize: (GroupMemberRemovalFacts) -> Unit,
    ): GroupMemberRemoval?

    fun cleanupServiceMemberProjection(
        transaction: PgWriteTransactionContext,
        chatId: String,
        uid: String,
        lockedChat: LockedChat?,
    ): ServiceMemberProjectionCleanup?

    /** 仅在聚合事务提交之后发布缓存失效。 */
    fun invalidateCommittedMembershipChange(chatId: String)
}

/** 通知机器人使用的消息接收边界。 */
fun interface BotMessageSender {
    /**
     * [authorizeAfterChatLock] 在消息准入事务中、托管权威与 Chat 已锁定之后、但成员行之前
     * 运行。生产适配器必须对每条新消息的准入恰好调用它一次。
     */
    suspend fun send(
        senderUid: String,
        message: Message,
        authorizeAfterChatLock: (PgWriteTransactionContext) -> Unit,
    ): Long
}
