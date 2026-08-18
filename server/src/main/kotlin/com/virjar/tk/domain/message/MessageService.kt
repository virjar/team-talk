package com.virjar.tk.domain.message

import com.virjar.tk.domain.attachment.AttachmentService
import com.virjar.tk.domain.chat.ChatStore
import com.virjar.tk.domain.conversation.ConversationService
import com.virjar.tk.infra.search.MessageTextExtractor
import com.virjar.tk.infra.search.SearchIndex
import com.virjar.tk.infra.storage.MessageStore
import com.virjar.tk.infra.sync.SyncEventService
import com.virjar.tk.model.Message
import com.virjar.tk.protocol.NotifyType

class MessageService(
    private val messageStore: MessageStore,
    private val chatStore: ChatStore,
    private val syncEventService: SyncEventService,
    private val conversationService: ConversationService,
    private val searchIndex: SearchIndex,
    private val attachmentService: AttachmentService,
) {

    suspend fun sendMessage(senderUid: String, message: Message): Long {
        val chatId = message.chatId

        if (!chatStore.isMember(chatId, senderUid)) {
            throw IllegalArgumentException("不是聊天成员")
        }

        if (chatStore.isMuted(chatId, senderUid)) {
            throw IllegalArgumentException("你已被禁言")
        }

        // 全员禁言检查（管理员豁免）
        if (chatStore.isMutedAll(chatId)) {
            val member = chatStore.getMember(chatId, senderUid)
            if (member == null || member.role < 1) {
                throw IllegalArgumentException("群聊已开启全员禁言")
            }
        }

        // 幂等：如果 clientMsgId 已存在，直接返回已有 seq
        val existingSeq = messageStore.getSeqByClientMsgId(message.clientMsgId)
        if (existingSeq != null) {
            return existingSeq
        }

        // 消息契约：发送成功 = 引用的附件真实存在（文件只走本服务端文件存储，
        // 不存在三方文件服务；完整 http URL 只是客户端/外部 SDK 对接形态）。
        // 断链消息在服务端拒绝，不能等对端点击才发现打不开。
        val canonicalMessage = attachmentService.resolve(message)

        // 非阻塞递增 maxSeq
        val serverSeq = chatStore.incrementMaxSeq(chatId)

        val storedMessage = canonicalMessage.copy(serverSeq = serverSeq, senderUid = senderUid)
        messageStore.storeMessage(storedMessage)

        // 索引到 Lucene
        val text = MessageTextExtractor.extract(storedMessage, storedMessage.body)
        searchIndex.indexMessage(storedMessage, text)

        val memberUids = chatStore.getMemberUids(chatId)
        syncEventService.emitEvents(memberUids, NotifyType.MESSAGE_RECV, storedMessage)

        val chat = chatStore.getChat(chatId)
        if (chat != null) {
            // 传入消息预览文本和类型，让会话列表能显示 lastMessage
            val previewText = MessageTextExtractor.extract(storedMessage, storedMessage.body)
            conversationService.onMessageReceived(chatId, chat.chatType, serverSeq, storedMessage.messageType, previewText, memberUids)
        }

        return serverSeq
    }

    fun getHistory(uid: String, chatId: String, fromSeq: Long, limit: Int): List<Message> {
        if (!chatStore.isMember(chatId, uid)) {
            throw IllegalArgumentException("不是聊天成员")
        }
        return messageStore.getHistory(chatId, fromSeq, limit, forward = false)
    }

    suspend fun revokeMessage(uid: String, chatId: String, serverSeq: Long) {
        val message = messageStore.getMessage(chatId, serverSeq)
            ?: throw IllegalArgumentException("消息不存在")

        if (message.senderUid != uid) {
            val member = chatStore.getMember(chatId, uid)
                ?: throw IllegalArgumentException("不是聊天成员")
            if (member.role < 1) throw IllegalArgumentException("需要管理员权限")
        }
        doRevoke(message)
    }

    /** 管理员撤回：免权限检查，广播链路复用。 */
    suspend fun adminRevoke(chatId: String, serverSeq: Long) {
        val message = messageStore.getMessage(chatId, serverSeq)
            ?: throw IllegalArgumentException("消息不存在")
        doRevoke(message)
    }

    private suspend fun doRevoke(message: Message) {
        val revoked = message.copy(flags = message.flags or 1)
        messageStore.updateMessage(message.chatId, message.serverSeq, revoked)

        val memberUids = chatStore.getMemberUids(message.chatId)
        syncEventService.emitEvents(memberUids, NotifyType.MESSAGE_RECV, revoked)
    }

    suspend fun editMessage(uid: String, chatId: String, serverSeq: Long, newMessage: Message) {
        val message = messageStore.getMessage(chatId, serverSeq)
            ?: throw IllegalArgumentException("消息不存在")

        if (message.senderUid != uid) {
            throw IllegalArgumentException("只能编辑自己的消息")
        }

        val canonicalNewMessage = attachmentService.resolve(newMessage)
        val edited = canonicalNewMessage.copy(
            serverSeq = serverSeq,
            senderUid = uid,
            flags = message.flags or 2,
        )
        messageStore.updateMessage(chatId, serverSeq, edited)

        val text = MessageTextExtractor.extract(edited, edited.body)
        searchIndex.indexMessage(edited, text)

        val memberUids = chatStore.getMemberUids(chatId)
        syncEventService.emitEvents(memberUids, NotifyType.MESSAGE_RECV, edited)
    }

    suspend fun forwardMessage(uid: String, srcChatId: String, srcSeq: Long, targetChatId: String): Message {
        if (!chatStore.isMember(srcChatId, uid)) throw IllegalArgumentException("不是源聊天成员")
        if (!chatStore.isMember(targetChatId, uid)) throw IllegalArgumentException("不是目标聊天成员")

        val srcMsg = messageStore.getMessage(srcChatId, srcSeq)
            ?: throw IllegalArgumentException("原消息不存在")
        val canonicalSource = attachmentService.resolve(srcMsg)

        val serverSeq = chatStore.incrementMaxSeq(targetChatId)

        val forwardMsg = canonicalSource.copy(
            chatId = targetChatId,
            clientMsgId = java.util.UUID.randomUUID().toString(),
            serverSeq = serverSeq,
            senderUid = uid,
            flags = canonicalSource.flags or 4,
            timestamp = System.currentTimeMillis(),
        )

        messageStore.storeMessage(forwardMsg)

        val text = MessageTextExtractor.extract(forwardMsg, forwardMsg.body)
        searchIndex.indexMessage(forwardMsg, text)

        val memberUids = chatStore.getMemberUids(targetChatId)
        syncEventService.emitEvents(memberUids, NotifyType.MESSAGE_RECV, forwardMsg)

        val chat = chatStore.getChat(targetChatId)
        if (chat != null) {
            val fwdPreviewText = MessageTextExtractor.extract(forwardMsg, forwardMsg.body)
            conversationService.onMessageReceived(targetChatId, chat.chatType, serverSeq, forwardMsg.messageType, fwdPreviewText, memberUids)
        }

        return forwardMsg
    }

    fun searchMessages(uid: String, chatId: String, keyword: String, limit: Int): List<Message> {
        if (!chatStore.isMember(chatId, uid)) throw IllegalArgumentException("不是聊天成员")
        val (_, results) = searchIndex.search(keyword, chatIds = setOf(chatId), limit = limit)
        return results.mapNotNull { messageStore.getMessage(it.chatId, it.seq) }
    }
}
