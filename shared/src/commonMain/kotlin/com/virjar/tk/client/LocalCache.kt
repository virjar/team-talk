package com.virjar.tk.client

import com.virjar.tk.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** 一条等待镜像到服务端的本地会话草稿操作。[draft] 为 null 表示明确清空。 */
data class PendingConversationDraft(
    val chatId: String,
    val draft: String?,
    val generation: Long,
)

/**
 * 客户端本地缓存接口。
 * 具体实现由各平台提供（基于 SQLDelight）。
 *
 * 内存治理（Phase C）：
 * - [pager] 提供聊天级分页观察，配合 LRU 淘汰
 * - [onChatInactive] 在 ViewModel 销毁时释放该聊天的内存窗口
 */
interface LocalCache {
    // ── 用户 ──
    fun getUser(uid: String): User?
    fun upsertUser(user: User)

    // ── 联系人 ──
    fun getContacts(): List<Contact>
    fun observeContacts(): Flow<List<Contact>>
    fun upsertContact(contact: Contact)
    fun deleteContact(friendUid: String)

    /**
     * 当前联系人投影的进程内代次。Repository 在发起好友全量请求前捕获它，
     * 用于防止请求期间到达的 CONTACT_ACCEPTED / CONTACT_DELETED 被迟到快照覆盖。
     */
    fun contactProjectionGeneration(): Long

    /**
     * 应用服务端的好友全量快照。
     *
     * 当 [expectedGeneration] 仍是当前代次时，快照会原子替换 SQLite 和内存投影，
     * 从而清理旧客户端误写的联系人。如果请求期间已有实时关系事件，则不执行
     * 删除，且只合并没有被更新事件触及的快照项。
     *
     * @return true 表示完成了全量替换；false 表示检测到并发变化并采用了安全合并。
     */
    fun applyContactSnapshot(expectedGeneration: Long, contacts: List<Contact>): Boolean

    // ── 聊天 ──
    fun getChat(chatId: String): Chat?
    fun upsertChat(chat: Chat)
    fun deleteChat(chatId: String)

    // ── 成员 ──
    fun getMembers(chatId: String): List<Member>
    fun observeMembers(chatId: String): Flow<List<Member>>
    fun upsertMember(member: Member)
    fun removeMember(chatId: String, uid: String)

    // ── 消息 ──
    fun getMessages(chatId: String, limit: Int = 50): List<Message>
    fun observeMessages(chatId: String): Flow<List<Message>>
    fun insertMessage(message: Message)

    /**
     * Persist one authoritative server-history response atomically for a resident message window.
     * [resetResidentWindow] is true for the newest-page sync (`fromSeq = 0`); older pages extend
     * that same server-proven interval. The default keeps lightweight fakes source-compatible.
     */
    fun insertMessagePage(
        chatId: String,
        messages: List<Message>,
        resetResidentWindow: Boolean,
    ) {
        messages.forEach(::insertMessage)
    }
    fun updateMessage(chatId: String, clientMsgId: String, serverSeq: Long)
    fun updateMessageStatus(chatId: String, clientMsgId: String, sendStatus: Int)
    /** 变换更新（上传进度等纯 UI 状态，只更新驻留窗口不落库）。 */
    fun updateMessageInMemory(chatId: String, clientMsgId: String, transform: (Message) -> Message)

    /**
     * 创建消息分页器。首次返回最近 [windowSize] 条消息，
     * 调用 [MessagePager.loadMore] 向上加载更老消息。
     *
     * 实现侧通过 LRU 限制同时驻留内存的聊天数（默认 20），
     * 超出时 evict 最旧的聊天窗口（仅清内存，DB 持久化不变）。
     */
    fun pager(chatId: String, windowSize: Int = DEFAULT_MESSAGE_WINDOW): MessagePager

    /**
     * 标记某聊天不再活跃（ViewModel onCleared 时调用）。
     * 释放该聊天的内存消息窗口，下次 [pager] 调用时重新从 DB 加载。
     */
    fun onChatInactive(chatId: String)

    // ── 会话 ──
    fun getConversations(): List<Conversation>
    fun observeConversations(): Flow<List<Conversation>>
    fun upsertConversation(conv: Conversation)
    fun deleteConversation(chatId: String)

    /**
     * 本地清零会话未读数 + 推进 readSeq。
     *
     * 进入聊天页/发送消息后立即调用，不等服务端 CONVERSATION_UPDATED 通知回环
     * （自己发消息不会触发通知，会导致红点不消失）。readSeq 推进到 lastSeq，
     * unreadCount 清零。
     */
    fun markConversationRead(chatId: String, readSeq: Long)

    /** 更新对方已读位置（READ_SYNC 通知触发）。 */
    fun updatePeerReadSeq(chatId: String, peerReadSeq: Long)

    /** 置顶/取消置顶会话。返回更新后的 Conversation。 */
    fun toggleConversationPin(chatId: String, pinned: Boolean): Conversation?

    /**
     * 精确更新单条会话的草稿（null = 明确清除），并原子写入镜像 outbox。
     *
     * 返回本次操作的本地 generation。镜像 RPC 只能条件确认同一 generation，
     * 防止迟到的旧请求把更新的草稿误标为已同步。
     */
    fun setConversationDraft(chatId: String, draft: String?): Long

    /** 返回尚未收到成功 RPC 应答的草稿操作，用于启动/重连重试。 */
    fun getPendingConversationDrafts(): List<PendingConversationDraft>

    /** 仅当 [generation] 仍是该会话最新操作时，标记 RPC 已成功。 */
    fun markConversationDraftMirrored(chatId: String, generation: Long)

    /** Release the platform SQL driver owned by this cache. Test/fake caches may keep the no-op. */
    fun close() = Unit

    companion object {
        /** 单聊消息内存窗口大小（最近 N 条） */
        const val DEFAULT_MESSAGE_WINDOW = 100

        /** 同时驻留内存的最大聊天数（LRU 淘汰） */
        const val MAX_ACTIVE_CHATS = 20
    }
}

/**
 * 消息分页器。观察内存窗口中的消息，支持向上翻页加载更老消息。
 *
 * 生命周期：由 [LocalCache.pager] 创建，ViewModel 持有。
 * ViewModel 销毁时应调用 [LocalCache.onChatInactive] 释放内存。
 */
interface MessagePager {
    /** 当前内存窗口中的消息（按时间倒序，最新在前）。 */
    val messages: Flow<List<Message>>

    /** 是否还有更老的消息可加载。 */
    val hasMore: StateFlow<Boolean>

    /** 向上加载更老的一页消息。同步操作，更新 [messages] 和 [hasMore]。 */
    fun loadMore(pageSize: Int = DEFAULT_PAGE_SIZE)

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
    }
}
