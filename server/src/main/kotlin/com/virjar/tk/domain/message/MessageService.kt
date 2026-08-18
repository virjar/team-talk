package com.virjar.tk.domain.message

import com.virjar.tk.domain.attachment.AttachmentService
import com.virjar.tk.domain.chat.ChatStore
import com.virjar.tk.domain.conversation.ConversationService
import com.virjar.tk.domain.event.EventPublisher
import com.virjar.tk.model.Message
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.NotifyType

class MessageService(
    private val messages: MessageRepository,
    private val chatStore: ChatStore,
    private val events: EventPublisher,
    private val conversationService: ConversationService,
    private val search: MessageSearch,
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

        // 幂等重试不只返回 seq；如果上次在跨库投影中途失败，先补齐搜索、
        // 离线事件和会话投影，才能满足“发送成功即可用”的契约。
        val existingSeq = messages.getSeqByClientMsgId(message.clientMsgId)
        if (existingSeq != null) {
            val existing = messages.getMessage(chatId, existingSeq)
            if (existing != null && messages.isProjectionPending(chatId, existingSeq)) {
                projectNewMessage(existing)
            }
            return existingSeq
        }

        // 消息契约：发送成功 = 引用的附件真实存在（文件只走本服务端文件存储，
        // 不存在三方文件服务；完整 http URL 只是客户端/外部 SDK 对接形态）。
        // 断链消息在服务端拒绝，不能等对端点击才发现打不开。
        val canonicalMessage = attachmentService.resolve(message)

        // 非阻塞递增 maxSeq
        val serverSeq = chatStore.incrementMaxSeq(chatId)

        val storedMessage = canonicalMessage.copy(serverSeq = serverSeq, senderUid = senderUid)
        val committedSeq = messages.storeMessage(storedMessage)
        if (committedSeq != serverSeq) {
            // 并发的同 clientMsgId 已先行提交，当前分配的 seq 保留为合法空洞。
            messages.getMessage(chatId, committedSeq)?.let { committed ->
                if (messages.isProjectionPending(chatId, committedSeq)) projectNewMessage(committed)
            }
            return committedSeq
        }
        projectNewMessage(storedMessage)

        return serverSeq
    }

    fun getHistory(uid: String, chatId: String, fromSeq: Long, limit: Int): List<Message> {
        if (!chatStore.isMember(chatId, uid)) {
            throw IllegalArgumentException("不是聊天成员")
        }
        return messages.getHistory(chatId, fromSeq, limit, forward = false)
    }

    suspend fun revokeMessage(uid: String, chatId: String, serverSeq: Long) {
        val message = messages.getMessage(chatId, serverSeq)
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
        val message = messages.getMessage(chatId, serverSeq)
            ?: throw IllegalArgumentException("消息不存在")
        doRevoke(message)
    }

    private suspend fun doRevoke(message: Message) {
        val revoked = message.copy(flags = message.flags or 1)
        messages.updateMessage(message.chatId, message.serverSeq, revoked)

        val memberUids = chatStore.getMemberUids(message.chatId)
        events.emitEvents(memberUids, NotifyType.MESSAGE_RECV, revoked)
        conversationService.onMessageChanged(
            // ConversationList 以 lastMessage 非 null 作为是否渲染摘要的开关；
            // REVOKE 类型不读取正文，但仍需非 null 占位才能显示“撤回了一条消息”。
            message.chatId, message.serverSeq, MessageType.REVOKE.code, "", memberUids
        )
    }

    suspend fun editMessage(uid: String, chatId: String, serverSeq: Long, newMessage: Message) {
        val message = messages.getMessage(chatId, serverSeq)
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
        messages.updateMessage(chatId, serverSeq, edited)

        val text = MessageTextExtractor.extract(edited, edited.body)
        search.indexMessage(edited, text)

        val memberUids = chatStore.getMemberUids(chatId)
        events.emitEvents(memberUids, NotifyType.MESSAGE_RECV, edited)
        conversationService.onMessageChanged(chatId, serverSeq, edited.messageType, text, memberUids)
    }

    suspend fun forwardMessage(uid: String, srcChatId: String, srcSeq: Long, targetChatId: String): Message {
        if (!chatStore.isMember(srcChatId, uid)) throw IllegalArgumentException("不是源聊天成员")
        if (!chatStore.isMember(targetChatId, uid)) throw IllegalArgumentException("不是目标聊天成员")

        val srcMsg = messages.getMessage(srcChatId, srcSeq)
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

        messages.storeMessage(forwardMsg)
        projectNewMessage(forwardMsg)

        return forwardMsg
    }

    fun searchMessages(uid: String, chatId: String, keyword: String, limit: Int): List<Message> {
        val allowedChatIds = if (chatId.isBlank()) {
            // 空 chatId 是客户端“搜索全部消息”的明确契约。权限集合必须由服务端
            // 根据当前用户会话计算，不能信任客户端上传任意 chatId 列表。
            chatStore.listUserChatIds(uid)
        } else {
            if (!chatStore.isMember(chatId, uid)) throw IllegalArgumentException("不是聊天成员")
            setOf(chatId)
        }
        if (allowedChatIds.isEmpty()) return emptyList()
        val results = search.search(keyword, chatIds = allowedChatIds, limit = limit)
        return results.hits.mapNotNull { messages.getMessage(it.chatId, it.seq) }
    }

    /** Replays durable projection outbox entries left by an interrupted process. */
    suspend fun recoverPendingProjections(limit: Int = 1_000): Int {
        val pending = messages.getPendingProjections(limit)
        for (message in pending) projectNewMessage(message)
        return pending.size
    }

    private suspend fun projectNewMessage(message: Message) {
        val text = MessageTextExtractor.extract(message, message.body)
        search.indexMessage(message, text)

        val memberUids = chatStore.getMemberUids(message.chatId)
        events.emitEvents(memberUids, NotifyType.MESSAGE_RECV, message)

        chatStore.getChat(message.chatId)?.let { chat ->
            conversationService.onMessageReceived(
                message.chatId,
                chat.chatType,
                message.serverSeq,
                message.messageType,
                text,
                memberUids,
                message.senderUid,
            )
        }
        messages.markProjectionComplete(message.chatId, message.serverSeq)
    }
}
