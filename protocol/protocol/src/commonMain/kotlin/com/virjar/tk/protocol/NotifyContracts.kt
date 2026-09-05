package com.virjar.tk.protocol

import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.Contact
import com.virjar.tk.protocol.model.ContactApply
import com.virjar.tk.protocol.model.Conversation
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.User

/**
 * NOTIFY 契约表：NotifyType → payload 类型的**唯一事实源**。
 *
 * 历史 bug 根因（3b74b64 / 本次修复的 CONTACT_ACCEPTED-DELETED 错配）：
 * 服务端 emit 的 payload 类型与客户端 decode 的类型靠两边手写 when 分支各自维护，
 * 错配只会在客户端集成时以"数据错乱/解析异常"的形式爆发。
 *
 * 本表同时约束两侧：
 * - 服务器 `SyncEventService` emit 前校验 payload 实际类型与表一致（错配当场抛异常）；
 * - 客户端 `EventProcessor` 统一从本表取 reader decode；
 * - [com.virjar.tk.protocol.NotifyContractTest] 遍历全表做完备性 + round-trip 校验。
 *
 * 新增 NotifyType 时必须在此登记，否则完备性测试失败。
 */
object NotifyContracts {

    /** 有 payload 契约的通知类型；持久化与瞬时事件都必须登记。 */
    val payloads: Map<NotifyType, IProtoReader<out IProto>> = mapOf(
        // 联系人：APPLY 发 ContactApply；ACCEPTED/DELETED 发各自视角的 Contact
        NotifyType.CONTACT_APPLY to ContactApply,
        NotifyType.CONTACT_ACCEPTED to Contact,
        NotifyType.CONTACT_DELETED to Contact,

        // 群组（含成员变更）：统一发 Chat
        NotifyType.CHAT_CREATED to Chat,
        NotifyType.CHAT_UPDATED to Chat,
        NotifyType.CHAT_DELETED to Chat,
        NotifyType.MEMBER_ADDED to Chat,
        NotifyType.MEMBER_REMOVED to Chat,
        NotifyType.MEMBER_MUTED to Chat,
        NotifyType.MEMBER_UNMUTED to Chat,
        NotifyType.MEMBER_ROLE_CHANGED to Chat,

        // 消息
        NotifyType.MESSAGE_RECV to Message,
        NotifyType.TYPING to Message,
        NotifyType.MESSAGE_REACTION to MessageReactionEventPayload,
        NotifyType.GROUP_FILE_CHANGED to GroupFileChangedPayload,

        // 会话
        NotifyType.CONVERSATION_UPDATED to Conversation,
        NotifyType.CONVERSATION_DELETED to Conversation,

        // 多端同步
        NotifyType.READ_SYNC to ReadSyncPayload,

        // 用户
        NotifyType.USER_UPDATED to User,

        // 组织目录：只推送全局 revision，客户端通过二进制 OrganizationRpc 收敛快照
        NotifyType.ORGANIZATION_CHANGED to OrganizationChangedPayload,

        // 在线状态（服务端直写不持久化，但类型契约仍需锁定）
        NotifyType.PRESENCE to PresencePayload,

    )

    /** 仅连接降级投影使用；没有业务 payload，也不能作为新的业务事件持久化。 */
    val exempt: Set<NotifyType> = setOf(NotifyType.EVENT_CURSOR_ADVANCED)

    /**
     * 契约校验：reader 是 IProto data class 的 companion object，
     * 其平台类名形如 "com.virjar.tk.protocol.model.Contact$Companion"，
     * 与 payload 的实际类名（"com.virjar.tk.protocol.model.Contact"）去后缀比对。
     * 服务器 emit 前调用（JVM 反射，客户端不使用）。
     */
    fun expectedPayloadClassName(type: NotifyType, readerClassName: String): String =
        readerClassName.removeSuffix("\$Companion")
}
