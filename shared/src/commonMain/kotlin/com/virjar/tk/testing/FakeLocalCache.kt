package com.virjar.tk.testing

import com.virjar.tk.client.LocalCache
import com.virjar.tk.client.MessagePager
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
        if (idx >= 0) list[idx] = message else list.add(0, message)
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
        val list = conversationsFlow.value.toMutableList()
        val idx = list.indexOfFirst { it.chatId == conv.chatId }
        if (idx >= 0) {
            // 合并策略与 LocalCacheImpl 一致（简化版）
            val local = list[idx]
            val mergedReadSeq = maxOf(local.readSeq, conv.readSeq)
            val mergedUnread = if (local.readSeq >= conv.readSeq && local.unreadCount == 0) 0 else conv.unreadCount
            list[idx] = conv.copy(readSeq = mergedReadSeq, unreadCount = mergedUnread, draft = local.draft ?: conv.draft)
        } else {
            list.add(conv)
        }
        conversationsFlow.value = list
    }
    override fun deleteConversation(chatId: String) {
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
    override fun markConversationRead(chatId: String, readSeq: Long) {
        conversationsFlow.value = conversationsFlow.value.map {
            if (it.chatId == chatId) it.copy(unreadCount = 0, readSeq = readSeq) else it
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
