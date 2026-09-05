package com.virjar.tk.server.domain.chat

import com.virjar.tk.server.domain.transaction.PgReadTransactionContext
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.Member

/** 在移除成员之前向领域策略暴露的已锁定聚合事实。 */
data class GroupMemberRemovalFacts(
    val chat: Chat,
    val operator: Member?,
    val target: Member?,
)

/** 由停用目标成员关系的同一事务产生的快照。 */
data class GroupMemberRemoval(
    val chat: Chat,
    val remainingMemberUids: List<String>,
)

/** 在添加成员命令变更群组之前暴露的已锁定聚合事实。 */
data class GroupMemberAdditionFacts(
    val chat: Chat,
    val operator: Member?,
    val requestedUids: List<String>,
)

/** 由建立成员会话的同一事务产生的快照。 */
data class GroupMemberAddition(
    val chat: Chat,
    val addedUids: List<String>,
    val activeMemberUids: List<String>,
)

data class LockedChat(
    val chat: Chat,
    val active: Boolean,
)

/** 从某个聊天中移除服务身份时实际发生的投影变更。 */
data class ServiceMemberProjectionCleanup(
    val chat: Chat,
    val membershipDeactivated: Boolean,
    val conversationDeleted: Boolean,
    val muteDeleted: Boolean,
    val remainingMemberUids: List<String>,
)

data class MessageAdmissionFacts(
    val chat: Chat,
    val sender: Member?,
    val senderMuted: Boolean,
    val activeMemberUids: List<String>,
)

data class MessageAdmission(
    val chatType: Int,
    val recipientUids: List<String>,
)

/** 成员关系、角色与禁言状态的持久化端口。 */
interface ChatMemberRepository {
    fun getMembers(chatId: String): List<Member>
    fun getMember(chatId: String, uid: String): Member?
    fun getMemberUids(chatId: String): List<String>
    fun getActiveChatIds(uid: String): Set<String>
    /** 在机器人行被锁定之后，加入所属聚合事务的同一次读取。 */
    fun getActiveChatIds(transaction: PgReadTransactionContext, uid: String): Set<String>
    /** 成员关系（活跃或非活跃）、Conversation 与禁言投影的并集，用于恢复。 */
    fun getProjectedChatIds(uid: String): Set<String>
    fun getProjectedChatIds(transaction: PgReadTransactionContext, uid: String): Set<String>
    fun isMember(chatId: String, uid: String): Boolean
    /**
     * 按 id 字典序锁定聊天行，而不触碰成员关系。机器人命令把它作为第一道数据库锁，
     * 使每个进程都遵循 chat -> user -> bot/grant -> membership 的顺序。
     */
    fun lockChats(
        transaction: PgWriteTransactionContext,
        chatIds: Collection<String>,
        requireActive: Boolean,
    ): Map<String, LockedChat>

    /** 在聊天与所属应用行都已锁定之后读取操作者。 */
    fun getActiveMember(transaction: PgReadTransactionContext, chatId: String, uid: String): Member?

    /**
     * 调用方事务快照中已排序的活跃成员 uid。调用方必须先通过 Chat 行锁串行化，
     * 使并发的成员变更无法交错。
     */
    fun getActiveMemberUids(transaction: PgReadTransactionContext, chatId: String): List<String>

    /**
     * 在一个已锁定的 Chat 快照中进行有权威围栏的消息准入。MessageStore 拥有序号分配，
     * 因此跨存储的失败不会在未存储消息的情况下消耗序号。[afterChatLocked] 是跨领域的
     * User/Bot/grant 锁定接缝，必须在 Member/Mute 之前运行。
     */
    fun admitMessage(
        transaction: PgWriteTransactionContext,
        chatId: String,
        senderUid: String,
        nowMillis: Long,
        afterChatLocked: () -> Unit = {},
        authorize: (MessageAdmissionFacts) -> Unit,
    ): MessageAdmission
    /**
     * 按顺序锁定活跃聊天、必需的人类 User 与成员关系，针对快照授权，对活跃目标与
     * 不同的非活跃目标执行 GroupPolicy，然后在工作单元中添加/恢复成员并建立
     * Conversation 行。
     */
    fun addMembers(
        transaction: PgWriteTransactionContext,
        chatId: String,
        operatorUid: String,
        uids: List<String>,
        requiredHumanUids: Set<String> = emptySet(),
        authorize: (GroupMemberAdditionFacts) -> Unit,
    ): GroupMemberAddition
    /**
     * 锁定活跃聊天，然后按一次有序获取锁定操作者 + 目标 User。操作者必须是活跃的人类；
     * 一个不同的目标必须仍然以人类身份存在，但可以已被禁用，以便管理员能移除被封禁
     * 的账户。自行退群者必须是活跃的。成员关系、授权与投影删除随后共享同一个已锁定
     * 的事务快照。
     */
    fun removeMember(
        transaction: PgWriteTransactionContext,
        chatId: String,
        operatorUid: String,
        targetUid: String,
        authorize: (GroupMemberRemovalFacts) -> Unit,
    ): GroupMemberRemoval

    /**
     * 服务领域对账用的变体。缺失的成员关系是幂等的空操作，而已存在的成员关系仍然
     * 使用与 [removeMember] 相同的已锁定快照与投影删除。
     */
    fun removeMemberIfPresent(
        transaction: PgWriteTransactionContext,
        chatId: String,
        operatorUid: String,
        targetUid: String,
        requireActiveChat: Boolean = true,
        authorize: (GroupMemberRemovalFacts) -> Unit,
    ): GroupMemberRemoval?

    /**
     * 即使成员已经非活跃/缺失、或引用的聊天行悬空，也移除每个服务投影。调用方已经
     * 先锁定了每个存在的聊天，然后是服务身份与机器人聚合。
     */
    fun cleanupServiceMemberProjection(
        transaction: PgWriteTransactionContext,
        chatId: String,
        uid: String,
        lockedChat: LockedChat?,
    ): ServiceMemberProjectionCleanup?

    fun transferOwner(
        transaction: PgWriteTransactionContext,
        chatId: String,
        oldOwnerUid: String,
        newOwnerUid: String,
        authorize: (GroupCommandFacts) -> Unit,
    ): ChatMutation

    fun setRole(
        transaction: PgWriteTransactionContext,
        chatId: String,
        operatorUid: String,
        targetUid: String,
        role: Int,
        authorize: (GroupCommandFacts) -> Unit,
    ): ChatMutation

    fun setMemberMute(
        transaction: PgWriteTransactionContext,
        chatId: String,
        operatorUid: String,
        targetUid: String,
        expiresAt: Long?,
        authorize: (GroupCommandFacts) -> Unit,
    ): ChatMutation

    fun isMuted(chatId: String, uid: String): Boolean
    fun setMuteAll(
        transaction: PgWriteTransactionContext,
        chatId: String,
        operatorUid: String?,
        mutedAll: Boolean,
        authorize: (GroupCommandFacts) -> Unit,
    ): ChatMutation
}
