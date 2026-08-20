package com.virjar.tk.testing

import com.virjar.tk.client.LocalCache
import com.virjar.tk.client.MessagePager
import com.virjar.tk.client.PendingConversationDraft
import com.virjar.tk.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * Fake [LocalCache]，纯内存实现。
 *
 * 消息方法重点实现（支持 Repository/ViewModel 测试）：
 * - [insertMessage] / [updateMessage] / [deleteMessage] 操作内存列表
 * - [getMessages] / [observeMessages] 读取内存列表
 * - [pager] 返回 [SimpleMessagePager]
 *
 * 其他实体（用户/联系人/聊天/成员/会话）用 MutableStateFlow 模拟，可手动设置。
 */
class FakeLocalCache : LocalCache {
    // 消息存储：chatId → 按时间倒序的消息列表（最新在前）
    private val messagesMap = mutableMapOf<String, MutableList<Message>>()
    private val messagesFlows = mutableMapOf<String, MutableStateFlow<List<Message>>>()

    // 其他实体存储
    private val usersFlow = MutableStateFlow<List<User>>(emptyList())
    private val contactsFlow = MutableStateFlow<List<Contact>>(emptyList())
    private val chatsFlow = MutableStateFlow<List<Chat>>(emptyList())
    private val membersMap = mutableMapOf<String, MutableList<Member>>()
    private val conversationsFlow = MutableStateFlow<List<Conversation>>(emptyList())
    private data class DraftObservation(val draft: String?)
    private data class DraftOverride(
        val draft: String?,
        val generation: Long,
        val mirrored: Boolean,
        val observedAuthority: DraftObservation? = null,
    )
    private val draftOverrides = mutableMapOf<String, DraftOverride>()
    private val draftGenerationHighWatermarks = mutableMapOf<String, Long>()

    // 记录 onChatInactive 调用（测试断言用）
    val inactiveChats = mutableListOf<String>()

    private fun messagesFlow(chatId: String): MutableStateFlow<List<Message>> =
        messagesFlows.getOrPut(chatId) { MutableStateFlow(messagesMap[chatId]?.toList() ?: emptyList()) }

    private fun syncFlow(chatId: String) {
        messagesFlow(chatId).value = messagesMap[chatId]?.toList() ?: emptyList()
    }

    // ── 消息 ──

    override fun getMessages(chatId: String, limit: Int): List<Message> =
        (messagesMap[chatId] ?: emptyList()).take(limit)

    override fun observeMessages(chatId: String): Flow<List<Message>> = messagesFlow(chatId)

    override fun insertMessage(message: Message) {
        val list = messagesMap.getOrPut(message.chatId) { mutableListOf() }
        val idx = list.indexOfFirst { it.clientMsgId == message.clientMsgId }
        if (idx >= 0) list[idx] = message else list.add(message)
        list.sortWith(
            compareByDescending<Message> { if (it.serverSeq > 0L) it.serverSeq else Long.MAX_VALUE }
                .thenByDescending { it.timestamp },
        )
        syncFlow(message.chatId)
    }

    override fun updateMessage(chatId: String, clientMsgId: String, serverSeq: Long) {
        val list = messagesMap[chatId] ?: return
        val idx = list.indexOfFirst { it.clientMsgId == clientMsgId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(serverSeq = serverSeq, sendStatus = Message.SEND_STATUS_SENT)
            syncFlow(chatId)
        }
    }

    override fun updateMessageStatus(chatId: String, clientMsgId: String, sendStatus: Int) {
        val list = messagesMap[chatId] ?: return
        val idx = list.indexOfFirst { it.clientMsgId == clientMsgId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(sendStatus = sendStatus)
            syncFlow(chatId)
        }
    }

    override fun pager(chatId: String, windowSize: Int): MessagePager =
        SimpleMessagePager(chatId, this)

    override fun onChatInactive(chatId: String) {
        inactiveChats += chatId
    }

    // ── 用户 ──

    override fun getUser(uid: String): User? = usersFlow.value.find { it.uid == uid }
    override fun upsertUser(user: User) {
        val list = usersFlow.value.toMutableList()
        val idx = list.indexOfFirst { it.uid == user.uid }
        if (idx >= 0) list[idx] = user else list.add(user)
        usersFlow.value = list
    }
    // ── 联系人 ──

    override fun getContacts(): List<Contact> = contactsFlow.value
    override fun observeContacts(): Flow<List<Contact>> = contactsFlow
    override fun upsertContact(contact: Contact) {
        val list = contactsFlow.value.toMutableList()
        val idx = list.indexOfFirst { it.friendUid == contact.friendUid }
        if (idx >= 0) list[idx] = contact else list.add(contact)
        contactsFlow.value = list
    }
    override fun deleteContact(friendUid: String) {
        contactsFlow.value = contactsFlow.value.filter { it.friendUid != friendUid }
    }

    // ── 聊天 ──

    override fun getChat(chatId: String): Chat? = chatsFlow.value.find { it.chatId == chatId }
    override fun upsertChat(chat: Chat) {
        val list = chatsFlow.value.toMutableList()
        val idx = list.indexOfFirst { it.chatId == chat.chatId }
        if (idx >= 0) list[idx] = chat else list.add(chat)
        chatsFlow.value = list
    }
    override fun deleteChat(chatId: String) {
        chatsFlow.value = chatsFlow.value.filter { it.chatId != chatId }
    }

    // ── 成员 ──

    override fun getMembers(chatId: String): List<Member> = membersMap[chatId] ?: emptyList()
    override fun observeMembers(chatId: String): Flow<List<Member>> =
        MutableStateFlow(membersMap[chatId] ?: emptyList())
    override fun upsertMember(member: Member) {
        val list = membersMap.getOrPut(member.chatId) { mutableListOf() }
        val idx = list.indexOfFirst { it.uid == member.uid }
        if (idx >= 0) list[idx] = member else list.add(member)
    }
    override fun removeMember(chatId: String, uid: String) {
        membersMap[chatId]?.removeAll { it.uid == uid }
    }

    // ── 会话 ──

    override fun getConversations(): List<Conversation> = conversationsFlow.value
    override fun observeConversations(): Flow<List<Conversation>> = conversationsFlow
    override fun upsertConversation(conv: Conversation) {
        val currentOverride = draftOverrides[conv.chatId]
        val observedOverride = currentOverride?.let { override ->
            if (!override.mirrored) {
                override.copy(observedAuthority = DraftObservation(conv.draft))
            } else {
                override
            }
        }
        val clearOverride = observedOverride?.let { it.mirrored && it.draft == conv.draft } == true
        if (clearOverride) draftOverrides.remove(conv.chatId)
        else if (observedOverride != null) draftOverrides[conv.chatId] = observedOverride
        val effectiveOverride = observedOverride.takeUnless { clearOverride }
        val incoming = conv.copy(
            draft = if (effectiveOverride != null) effectiveOverride.draft else conv.draft,
        )
        val list = conversationsFlow.value.toMutableList()
        val idx = list.indexOfFirst { it.chatId == incoming.chatId }
        if (idx >= 0) {
            // 合并策略与 LocalCacheImpl 一致（简化版）
            val local = list[idx]
            val mergedReadSeq = maxOf(local.readSeq, incoming.readSeq)
            val latestMessage = if (incoming.lastSeq >= local.lastSeq) incoming else local
            val mergedUnread = (latestMessage.lastSeq - mergedReadSeq)
                .coerceIn(0L, Int.MAX_VALUE.toLong())
                .toInt()
            list[idx] = incoming.copy(
                lastMessage = latestMessage.lastMessage,
                lastMessageType = latestMessage.lastMessageType,
                lastMsgTimestamp = latestMessage.lastMsgTimestamp,
                lastSeq = latestMessage.lastSeq,
                readSeq = mergedReadSeq,
                unreadCount = mergedUnread,
            )
        } else {
            list.add(incoming)
        }
        conversationsFlow.value = list
    }
    override fun setConversationDraft(chatId: String, draft: String?): Long {
        val generation = (draftGenerationHighWatermarks[chatId] ?: 0L) + 1L
        draftGenerationHighWatermarks[chatId] = generation
        draftOverrides[chatId] = DraftOverride(draft, generation, mirrored = false)
        conversationsFlow.value = conversationsFlow.value.map {
            if (it.chatId == chatId) it.copy(draft = draft) else it
        }
        return generation
    }
    override fun getPendingConversationDrafts(): List<PendingConversationDraft> =
        draftOverrides.mapNotNull { (chatId, override) ->
            if (!override.mirrored) PendingConversationDraft(chatId, override.draft, override.generation) else null
        }
    override fun markConversationDraftMirrored(chatId: String, generation: Long) {
        val current = draftOverrides[chatId] ?: return
        if (current.generation == generation && !current.mirrored) {
            if (current.observedAuthority?.draft == current.draft && current.observedAuthority != null) {
                draftOverrides.remove(chatId)
            } else {
                draftOverrides[chatId] = current.copy(mirrored = true)
            }
        }
    }
    override fun deleteConversation(chatId: String) {
        draftOverrides.remove(chatId)
        conversationsFlow.value = conversationsFlow.value.filter { it.chatId != chatId }
    }
    override fun updatePeerReadSeq(chatId: String, peerReadSeq: Long) {
        conversationsFlow.value = conversationsFlow.value.map {
            if (it.chatId == chatId) it.copy(peerReadSeq = peerReadSeq) else it
        }
    }
    override fun toggleConversationPin(chatId: String, pinned: Boolean): Conversation? {
        var result: Conversation? = null
        conversationsFlow.value = conversationsFlow.value.map {
            if (it.chatId == chatId) {
                result = it.copy(isPinned = pinned); result!!
            } else it
        }
        return result
    }
    override fun updateMessageInMemory(chatId: String, clientMsgId: String, transform: (Message) -> Message) {
        // 测试桩：无窗口概念，直接忽略（进度动画不影响测试语义）
    }

    override fun markConversationRead(chatId: String, readSeq: Long) {
        conversationsFlow.value = conversationsFlow.value.map {
            if (it.chatId != chatId) return@map it
            val mergedReadSeq = maxOf(it.readSeq, readSeq)
            it.copy(
                unreadCount = (it.lastSeq - mergedReadSeq)
                    .coerceIn(0L, Int.MAX_VALUE.toLong())
                    .toInt(),
                readSeq = mergedReadSeq,
            )
        }
    }
}

/** 简化版 MessagePager，直接镜像 FakeLocalCache 的消息列表。 */
private class SimpleMessagePager(
    private val chatId: String,
    private val cache: FakeLocalCache,
) : MessagePager {
    override val messages: Flow<List<Message>> get() = cache.observeMessages(chatId)
    override val hasMore: StateFlow<Boolean> = MutableStateFlow(false)
    override fun loadMore(pageSize: Int) { /* Fake 不分页 */ }
}
