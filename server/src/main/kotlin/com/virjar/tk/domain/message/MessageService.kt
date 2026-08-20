package com.virjar.tk.domain.message

import com.virjar.tk.body.AttachmentPolicy
import com.virjar.tk.body.CardBody
import com.virjar.tk.body.MessageBodyPolicy
import com.virjar.tk.body.ReplyBody
import com.virjar.tk.body.RichTextBody
import com.virjar.tk.body.buildRichTextBody
import com.virjar.tk.domain.attachment.AttachmentService
import com.virjar.tk.domain.chat.ChatStore
import com.virjar.tk.domain.conversation.ConversationService
import com.virjar.tk.domain.contact.ContactStore
import com.virjar.tk.domain.event.EventPublisher
import com.virjar.tk.domain.user.UserStore
import com.virjar.tk.model.Message
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.NotifyType
import kotlinx.coroutines.sync.Mutex

class MessageService(
    private val messages: MessageRepository,
    private val chatStore: ChatStore,
    private val events: EventPublisher,
    private val conversationService: ConversationService,
    private val search: MessageSearch,
    private val attachmentService: AttachmentService,
    private val users: UserStore,
    private val contacts: ContactStore,
) {
    /** 固定条带避免按消息创建锁导致无界缓存，同时串行化同一 chat+seq 的 outbox 投影。 */
    private val projectionLocks = Array(PROJECTION_LOCK_STRIPES) { Mutex() }

    suspend fun sendMessage(senderUid: String, message: Message): Long {
        val chatId = message.chatId
        require(chatId.isNotBlank() && chatId.length <= MessageBodyPolicy.MAX_CHAT_ID_LENGTH) { "chatId 非法" }
        require(
            message.clientMsgId.isNotBlank() &&
                message.clientMsgId.length <= MessageBodyPolicy.MAX_CLIENT_MESSAGE_ID_LENGTH,
        ) {
            "clientMsgId 非法"
        }
        val messageType = MessageType.fromCode(message.messageType)
            ?: throw IllegalArgumentException("未知消息类型: ${message.messageType}")
        require(messageType in CREATABLE_MESSAGE_TYPES) {
            "消息类型 $messageType 不能通过新建消息入口发送"
        }

        // 即便绕过 SDK，服务端也在 ACK 前执行同一份结构、正文预算和附件路径规则。
        // 先做不依赖 FileStore 当前状态的规范化，使已成功文件消息在文件生命周期变化后仍可幂等重试。
        val clientDeclaredMessage = MessageBodyPolicy.canonicalize(AttachmentPolicy.canonicalize(message))
            .copy(senderUid = senderUid)

        // 幂等重试不只返回 seq；如果上次在跨库投影中途失败，先补齐搜索、
        // 离线事件和会话投影，才能满足“发送成功即可用”的契约。
        val existing = messages.findIdempotentMessage(clientDeclaredMessage)
        if (existing != null) {
            if (messages.isProjectionPending(chatId, existing.serverSeq)) {
                projectNewMessage(existing)
            }
            return existing.serverSeq
        }

        // 成员和禁言都是会随时间变化的当前权限，只约束尚未接受的新消息。若原 ACK 丢失，
        // 已持久化请求必须先按 sender/hash 命中上面的幂等记录；否则发送者随后离群或被禁言
        // 会把一条收件方已经收到的成功消息反向显示为发送失败。
        requireCanCreateMessage(senderUid, chatId)

        // 成员校验只限制尚未接受的新消息。已成功消息延迟重试时，
        // 被 mention 的成员可能已离群，但幂等 ACK 仍必须返回原 serverSeq。
        validateMentionMembership(clientDeclaredMessage)

        val declaredMessage = rebuildAuthoritativeReferences(clientDeclaredMessage)

        // 消息契约：发送成功 = 引用的附件真实存在（文件只走本服务端文件存储，
        // 不存在三方文件服务；完整 http URL 只是客户端/外部 SDK 对接形态）。
        // 断链消息在服务端拒绝，不能等对端点击才发现打不开。
        val canonicalMessage = attachmentService.resolve(declaredMessage, senderUid)

        // 非阻塞递增 maxSeq
        val serverSeq = chatStore.incrementMaxSeq(chatId)

        // 客户端只声明 chatId/clientMsgId/type/body；消息身份、时间和状态位全部由服务端重建。
        val storedMessage = canonicalMessage.copy(
            serverSeq = serverSeq,
            senderUid = senderUid,
            timestamp = System.currentTimeMillis(),
            flags = 0,
            sendStatus = Message.SEND_STATUS_SENT,
            uploadProgress = 0f,
        )
        // 幂等摘要保存首次被接受的客户端声明，而不是服务端补齐后的可变展示侧信道；
        // 因此目标消息后续编辑、文件元数据刷新都不会让同一请求的延迟重试变成冲突。
        val committedSeq = messages.storeMessage(storedMessage, clientDeclaredMessage)
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
        requireQueryPageLimit(limit)
        if (!chatStore.isMember(chatId, uid)) {
            throw IllegalArgumentException("不是聊天成员")
        }
        return messages.getHistory(chatId, fromSeq, limit, forward = false)
    }

    suspend fun revokeMessage(uid: String, chatId: String, serverSeq: Long) {
        val actor = chatStore.getMember(chatId, uid)
            ?: throw IllegalArgumentException("不是聊天成员")
        val message = messages.getMessage(chatId, serverSeq)
            ?: throw IllegalArgumentException("消息不存在")

        if (message.senderUid != uid) {
            if (actor.role < 1) throw IllegalArgumentException("需要管理员权限")
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
        if (!chatStore.isMember(chatId, uid)) {
            throw IllegalArgumentException("不是聊天成员")
        }
        val message = messages.getMessage(chatId, serverSeq)
            ?: throw IllegalArgumentException("消息不存在")

        if (message.senderUid != uid) {
            throw IllegalArgumentException("只能编辑自己的消息")
        }
        val originalType = MessageType.fromCode(message.messageType)
            ?: throw IllegalArgumentException("原消息类型非法")
        val editedType = MessageType.fromCode(newMessage.messageType)
            ?: throw IllegalArgumentException("编辑后的消息类型非法")
        require(originalType in EDITABLE_MESSAGE_TYPES && editedType in EDITABLE_MESSAGE_TYPES) {
            "仅文本消息允许编辑，且编辑不能改变为操作型或附件消息"
        }

        // 编辑只接收新的 type/body；原消息身份与创建时间不可由客户端改写。
        val editCandidate = newMessage.copy(
            chatId = message.chatId,
            clientMsgId = message.clientMsgId,
            serverSeq = message.serverSeq,
            senderUid = message.senderUid,
            timestamp = message.timestamp,
            flags = message.flags,
            sendStatus = Message.SEND_STATUS_SENT,
            uploadProgress = 0f,
        )
        val canonicalNewMessage = attachmentService.resolve(editCandidate, uid)
        validateMentionMembership(canonicalNewMessage)
        val edited = canonicalNewMessage.copy(
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
        // 转发会在目标会话创建并广播一条全新的消息，必须服从与 sendMessage 相同的
        // 当前权限事实；否则黑名单、单成员禁言和全员禁言都能被转发入口绕过。
        requireCanCreateMessage(uid, targetChatId)

        val srcMsg = messages.getMessage(srcChatId, srcSeq)
            ?: throw IllegalArgumentException("原消息不存在")
        val canonicalSource = attachmentService.resolve(srcMsg, uid)

        val serverSeq = chatStore.incrementMaxSeq(targetChatId)

        val forwardMsg = canonicalSource.copy(
            chatId = targetChatId,
            clientMsgId = java.util.UUID.randomUUID().toString(),
            serverSeq = serverSeq,
            senderUid = uid,
            flags = canonicalSource.flags or 4,
            timestamp = System.currentTimeMillis(),
        )
        validateMentionMembership(forwardMsg)

        messages.storeMessage(forwardMsg)
        projectNewMessage(forwardMsg)

        return forwardMsg
    }

    /**
     * 新消息进入目标聊天前的实时权限边界。
     *
     * 普通发送的幂等命中在调用本方法之前返回，保证 ACK 丢失后的重试不被后来发生的
     * 拉黑/禁言反向改写；转发没有客户端幂等身份，因此每次都必须先通过此检查。
     */
    private fun requireCanCreateMessage(senderUid: String, chatId: String) {
        if (!chatStore.isMember(chatId, senderUid)) {
            throw IllegalArgumentException("不是聊天成员")
        }

        val chat = chatStore.getChat(chatId) ?: throw IllegalArgumentException("聊天不存在")
        if (chat.chatType == 1) {
            val peerUid = chatStore.getMemberUids(chatId).firstOrNull { it != senderUid }
                ?: throw IllegalArgumentException("私聊成员不完整")
            require(!contacts.isBlockedEither(senderUid, peerUid)) { "黑名单关系下不能发送私聊消息" }
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
    }

    fun searchMessages(uid: String, chatId: String, keyword: String, limit: Int): List<Message> {
        requireQueryPageLimit(limit)
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

    private fun requireQueryPageLimit(limit: Int) {
        require(limit in 1..MAX_QUERY_PAGE_SIZE) {
            "消息查询分页大小必须在 1..$MAX_QUERY_PAGE_SIZE 之间"
        }
    }

    /** Replays durable projection outbox entries left by an interrupted process. */
    suspend fun recoverPendingProjections(limit: Int = 1_000): Int {
        val pending = messages.getPendingProjections(limit)
        for (message in pending) projectNewMessage(message)
        return pending.size
    }

    private suspend fun projectNewMessage(message: Message) {
        val lock = projectionLocks[projectionLockIndex(message.chatId, message.serverSeq)]
        lock.lock()
        try {
            // 两个并发重试都可能观察到 pending；获得单飞锁后必须重查，
            // 否则会重复产生 MESSAGE_RECV / CONVERSATION_UPDATED durable events。
            if (!messages.isProjectionPending(message.chatId, message.serverSeq)) return

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
        } finally {
            lock.unlock()
        }
    }

    private fun projectionLockIndex(chatId: String, serverSeq: Long): Int {
        val seqHash = (serverSeq xor (serverSeq ushr 32)).toInt()
        val hash = 31 * chatId.hashCode() + seqHash
        return (hash and Int.MAX_VALUE) % projectionLocks.size
    }

    /** Reply 与联系人名片的展示侧信道必须由服务端权威数据重建。 */
    private fun rebuildAuthoritativeReferences(message: Message): Message {
        return when (val body = message.body) {
            is ReplyBody -> rebuildReply(message, body)
            is CardBody -> rebuildContactCard(message, body)
            else -> message
        }
    }

    /**
     * mention 会驱动通知与成员定位，因此不能把非会话成员的 uid 带入已成功消息。
     * RichTextBody 使用 canonical mentions；ReplyBody 的 content 仍是 Markdown 事实源，
     * 在服务端现场解析，不信任任何客户端侧信道。
     */
    private fun validateMentionMembership(message: Message) {
        val mentionedUids = when (val body = message.body) {
            is RichTextBody -> body.mentions.map { it.uid }
            is ReplyBody -> buildRichTextBody(body.content).mentions.map { it.uid }
            else -> emptyList()
        }
        if (mentionedUids.isEmpty()) return

        val memberUids = chatStore.getMemberUids(message.chatId).toHashSet()
        require(mentionedUids.all(memberUids::contains)) {
            "mention 目标必须是当前聊天成员"
        }
    }

    private fun rebuildReply(message: Message, reply: ReplyBody): Message {
        val targetSeq = reply.replyToMsgId.toLongOrNull()
            ?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("回复目标必须是已落库消息的 serverSeq")
        val target = messages.getMessage(message.chatId, targetSeq)
            ?: throw IllegalArgumentException("回复目标不存在或不属于当前会话")
        // ChatStore's hot member projection intentionally contains only uid/role. Resolve the
        // display snapshot from the global user directory after the target has been bound to this
        // exact chat; using the client-declared uid/name here would permit cross-chat spoofing.
        val targetUser = users.findByUid(target.senderUid)
        val displayName = sequenceOf(
            targetUser?.name,
            targetUser?.username,
            target.senderUid,
        ).filterNotNull().firstOrNull { it.isNotBlank() }
            ?.take(MessageBodyPolicy.MAX_DISPLAY_NAME_LENGTH)
            ?: "未知成员"
        val snippet = authoritativeReplySnippet(target)
            .take(MessageBodyPolicy.MAX_SHORT_TEXT_LENGTH)

        return MessageBodyPolicy.canonicalize(message.copy(
            body = reply.copy(
                replyToMsgId = target.serverSeq.toString(),
                replyToSenderUid = target.senderUid,
                replyToSenderName = displayName,
                replySnippet = snippet,
            ),
        ))
    }

    /** 联系人名片只携带 targetUid；姓名和头像是用户目录的权威快照。 */
    private fun rebuildContactCard(message: Message, card: CardBody): Message {
        val target = users.findByUid(card.targetUid)
            ?: throw IllegalArgumentException("名片目标用户不存在")
        return MessageBodyPolicy.canonicalize(message.copy(
            body = card.copy(
                targetName = target.name,
                targetAvatar = target.avatar,
            ),
        ))
    }

    private fun authoritativeReplySnippet(target: Message): String {
        if (target.flags and Message.FLAG_REVOKED != 0) return "撤回了一条消息"
        return MessageTextExtractor.extract(target, target.body)?.takeIf(String::isNotBlank)
            ?: when (MessageType.fromCode(target.messageType)) {
                MessageType.IMAGE -> "[图片]"
                MessageType.VOICE -> "[语音]"
                MessageType.VIDEO -> "[视频]"
                MessageType.STICKER -> "[表情]"
                else -> "[消息]"
            }
    }

    companion object {
        /**
         * 单条权威 RichText 最坏会在线上同时携带 Markdown、plainText 和 mention 侧信道。
         * 十条上限为 16 MiB 帧保留数 MiB 信封余量，也阻止任意 limit 驱动存储/索引巨量扫描。
         */
        const val MAX_QUERY_PAGE_SIZE = 10
        private const val PROJECTION_LOCK_STRIPES = 256

        /** 操作型消息只能走权限明确的 RPC，不能伪装成普通 MESSAGE 帧落库。 */
        private val CREATABLE_MESSAGE_TYPES = setOf(
            MessageType.TEXT,
            MessageType.RICH_TEXT,
            MessageType.INTERACTIVE_CARD,
            MessageType.IMAGE,
            MessageType.VOICE,
            MessageType.VIDEO,
            MessageType.FILE,
            MessageType.LOCATION,
            MessageType.CARD,
            MessageType.REPLY,
            MessageType.MERGE_FORWARD,
            MessageType.STICKER,
        )

        private val EDITABLE_MESSAGE_TYPES = setOf(MessageType.TEXT, MessageType.RICH_TEXT)
    }
}
