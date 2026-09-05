package com.virjar.tk.server.domain.chat

import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.Member

/**
 * 聊天元数据与管理型聊天查询的持久化端口。
 *
 * 聊天创建会原子地创建初始成员关系与会话行。调用方在本端口返回后绝不能重复这些投影。
 */
interface ChatRepository {
    /**
     * 命令侧创建边界。必需的用户、拉黑事实、Chat、成员关系与 Conversations 都在调用方的
     * PostgreSQL 工作单元中校验/变更。
     */
    fun createPersonalChat(
        transaction: PgWriteTransactionContext,
        uid1: String,
        uid2: String,
    ): ChatCreation

    /**
     * 幂等地获取或创建用户私有的"保存的消息"聊天（chatType 3，恰好一个成员）。唯一的
     * Chats.personalKey 行是重放围栏；调用方仅在 [ChatCreation.created] 为真时发出
     * CHAT_CREATED。
     */
    fun getOrCreateSavedChat(
        transaction: PgWriteTransactionContext,
        uid: String,
    ): ChatCreation

    /**
     * 新用户创建的群使用文档化的 User -> 新 Chat 锁定顺序例外。实现必须在容量准入之前
     * 解析出确切的持久化命令重放，并在第一次新写入之前应用 GroupPolicy；创建者占用一个
     * 名额。
     */
    fun createGroupChat(
        transaction: PgWriteTransactionContext,
        command: GroupCreationCommand,
    ): ChatCreation
    /**
     * 原子地校验并消耗一个邀请，激活成员关系并建立用户的会话投影。返回的快照包含已
     * 提交的接收者；ChatStore 只能在方法返回后使旧缓存状态失效。容量检查在锁定 Chat
     * 聚合之后、消耗链接配额或变更投影之前进行。
     */
    fun joinByInvite(
        transaction: PgWriteTransactionContext,
        uid: String,
        token: String,
        nowMillis: Long,
    ): InviteJoinResult
    fun getChat(chatId: String): Chat?
    /** 在托管聊天权威行被锁定之后的、已有聊天元数据变更。 */
    fun updateGroup(
        transaction: PgWriteTransactionContext,
        chatId: String,
        operatorUid: String,
        name: String? = null,
        avatar: String? = null,
        notice: String? = null,
        authorize: (GroupCommandFacts) -> Unit,
    ): ChatMutation

    /**
     * 先锁定 Chat，再锁定可选的人类操作者，并重新读取成员关系权威。成员行在此刻意不
     * 锁定：解散必须在 Invite/Member/Mute/Conversation 投影之前锁定必需的 User/Bot/grant
     * 事实。持有 Chat 锁可以把正确的写入者挡在外面。
     */
    fun lockForDeactivation(
        transaction: PgWriteTransactionContext,
        chatId: String,
        operatorUid: String?,
        authorize: (GroupCommandFacts) -> Unit,
    ): Chat
    /**
     * 事务绑定的对应版本，在调用方锁定聊天并停用必需的外部参与者之后使用。实现返回
     * 停用前的接收者，用于持久化墓碑。
     */
    fun deactivateChat(transaction: PgWriteTransactionContext, chatId: String): ChatDeactivation
    fun getMemberUids(chatId: String): List<String>
    fun listUserChats(uid: String): List<Chat>
}

data class ChatDeactivation(
    val chat: Chat,
    val memberUids: List<String>,
)

/** 新创建的结果；对已创建的私聊对的重试是显式的事件空操作。 */
data class ChatCreation(
    val chat: Chat,
    val created: Boolean,
    val recipientUids: List<String>,
)

/** 一条用户建群命令的归一化、可安全重试的身份。 */
data class GroupCreationCommand(
    val operationId: String,
    val creatorUid: String,
    val name: String,
    val avatar: String?,
    val memberUids: List<String>,
    val requestFingerprint: String,
)

/** 一个客户端操作身份不能被重新分配给不同的归一化建群请求。 */
class GroupCreationConflictException : IllegalArgumentException(MESSAGE) {
    companion object {
        const val MESSAGE = "建群操作标识已用于不同请求"
    }
}

/** 在变更应用之前提供给领域授权的已锁定群快照。 */
data class GroupCommandFacts(
    val chat: Chat,
    val operator: Member?,
    val target: Member? = null,
    val activeMemberUids: List<String>,
)

/** 一条群命令的完整已提交投影快照与持久化事件接收者。 */
data class ChatMutation(
    val chat: Chat,
    val recipientUids: List<String>,
    val changed: Boolean = true,
)

data class InviteJoinResult(
    val chat: Chat,
    val joined: Boolean,
    val members: List<Member>,
)

/** 新 schema 为私聊对存储的、稳定的、与顺序无关的身份。 */
internal fun personalChatKey(uid1: String, uid2: String): String {
    val (first, second) = if (uid1 <= uid2) uid1 to uid2 else uid2 to uid1
    return "${first.length}:$first${second.length}:$second"
}
