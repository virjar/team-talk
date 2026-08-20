package com.virjar.tk.domain.message

import com.virjar.tk.body.AttachmentPolicy
import com.virjar.tk.body.CardBody
import com.virjar.tk.body.GenericPayload
import com.virjar.tk.body.MessageBodyPolicy
import com.virjar.tk.body.ReplyBody
import com.virjar.tk.body.RichTextBody
import com.virjar.tk.body.buildRichTextBody
import com.virjar.tk.domain.attachment.AttachmentService
import com.virjar.tk.domain.chat.ChatAccess
import com.virjar.tk.domain.chat.ChatLifecycleGate
import com.virjar.tk.domain.chat.ChatStore
import com.virjar.tk.domain.contact.ContactStore
import com.virjar.tk.domain.transaction.PgUnitOfWork
import com.virjar.tk.domain.user.UserStore
import com.virjar.tk.model.Message
import com.virjar.tk.protocol.ExtensionType
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ProtoCodec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

class MessageService(
    private val messages: MessageRepository,
    private val chatStore: ChatStore,
    private val access: ChatAccess,
    private val projectionRepository: MessageProjectionRepository,
    private val unitOfWork: PgUnitOfWork,
    private val projectionReadiness: MessageProjectionReadiness,
    private val search: MessageSearch,
    private val attachmentService: AttachmentService,
    private val users: UserStore,
    private val contacts: ContactStore,
    private val lifecycleGate: ChatLifecycleGate,
    private val projectionHooks: MessageProjectionHooks = MessageProjectionHooks.None,
) {
    /** 固定条带避免按消息创建锁导致无界缓存，同时串行化同一 chat+seq 的 outbox 投影。 */
    private val projectionLocks = Array(PROJECTION_LOCK_STRIPES) { Mutex() }
    private val recoveryMutex = Mutex()

    suspend fun sendMessage(senderUid: String, message: Message): Long {
        recoverIfBlocked()
        return lifecycleGate.withChat(message.chatId) { sendMessageLocked(senderUid, message) }
    }

    /** Caller holds [lifecycleGate] for this chat through durable storage and projection. */
    private suspend fun sendMessageLocked(senderUid: String, message: Message): Long {
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
        requireRegisteredGenericExtension(clientDeclaredMessage)

        // 幂等重试不只返回 seq；如果上次在跨库投影中途失败，先补齐搜索、
        // 离线事件和会话投影，才能满足“发送成功即可用”的契约。
        val existing = messages.findIdempotentMessage(clientDeclaredMessage)
        if (existing != null) {
            drainPendingForMessageLocked(chatId, existing.serverSeq)
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
        val committedSeq = messages.storeMessage(
            storedMessage,
            clientDeclaredMessage,
            projectionTarget(chatId),
        )
        if (committedSeq != serverSeq) {
            // 并发的同 clientMsgId 已先行提交，当前分配的 seq 保留为合法空洞。
            drainPendingForMessageLocked(chatId, committedSeq)
            return committedSeq
        }
        drainPendingForMessageLocked(chatId, serverSeq)

        return serverSeq
    }

    fun getHistory(uid: String, chatId: String, fromSeq: Long, limit: Int): List<Message> {
        requireQueryPageLimit(limit)
        access.requireMember(uid, chatId)
        return messages.getHistory(chatId, fromSeq, limit, forward = false)
    }

    suspend fun revokeMessage(uid: String, chatId: String, serverSeq: Long) {
        recoverIfBlocked()
        lifecycleGate.withChat(chatId) { revokeMessageLocked(uid, chatId, serverSeq) }
    }

    private suspend fun revokeMessageLocked(uid: String, chatId: String, serverSeq: Long) {
        val actor = access.requireMember(uid, chatId)
        val message = messages.getMessage(chatId, serverSeq)
            ?: throw IllegalArgumentException("消息不存在")

        if (message.senderUid != uid) {
            if (actor.role < 1) throw IllegalArgumentException("需要管理员权限")
        }
        doRevoke(message)
    }

    /** 管理员撤回：免权限检查，广播链路复用。 */
    suspend fun adminRevoke(chatId: String, serverSeq: Long) {
        recoverIfBlocked()
        lifecycleGate.withChat(chatId) {
            val message = messages.getMessage(chatId, serverSeq)
                ?: throw IllegalArgumentException("消息不存在")
            doRevoke(message)
        }
    }

    private suspend fun doRevoke(message: Message) {
        if (message.flags and Message.FLAG_REVOKED != 0) {
            drainPendingForMessageLocked(message.chatId, message.serverSeq)
            return
        }
        val revoked = message.copy(flags = message.flags or Message.FLAG_REVOKED)
        messages.updateMessage(
            message.chatId,
            message.serverSeq,
            revoked,
            MessageOperationType.REVOKE,
            projectionTarget(message.chatId),
        )
        drainPendingForMessageLocked(message.chatId, message.serverSeq)
    }

    suspend fun editMessage(uid: String, chatId: String, serverSeq: Long, newMessage: Message) {
        recoverIfBlocked()
        lifecycleGate.withChat(chatId) { editMessageLocked(uid, chatId, serverSeq, newMessage) }
    }

    private suspend fun editMessageLocked(uid: String, chatId: String, serverSeq: Long, newMessage: Message) {
        access.requireMember(uid, chatId)
        val message = messages.getMessage(chatId, serverSeq)
            ?: throw IllegalArgumentException("消息不存在")

        if (message.senderUid != uid) {
            throw IllegalArgumentException("只能编辑自己的消息")
        }
        require(message.flags and Message.FLAG_REVOKED == 0) { "已撤回消息不能编辑" }
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
            flags = message.flags or Message.FLAG_EDITED,
        )
        if (ProtoCodec.encode(edited).contentEquals(ProtoCodec.encode(message))) {
            drainPendingForMessageLocked(chatId, serverSeq)
            return
        }
        messages.updateMessage(
            chatId,
            serverSeq,
            edited,
            MessageOperationType.EDIT,
            projectionTarget(chatId),
        )
        drainPendingForMessageLocked(chatId, serverSeq)
    }

    suspend fun forwardMessage(uid: String, srcChatId: String, srcSeq: Long, targetChatId: String): Message {
        recoverIfBlocked()
        return lifecycleGate.withChats(srcChatId, targetChatId) {
            forwardMessageLocked(uid, srcChatId, srcSeq, targetChatId)
        }
    }

    private suspend fun forwardMessageLocked(
        uid: String,
        srcChatId: String,
        srcSeq: Long,
        targetChatId: String,
    ): Message {
        // Source membership is a current authorization fact too. Keep the check under the same
        // two-chat lifecycle boundary as the source read and target commit, otherwise a kick,
        // leave, or dissolve can revoke access while a forward is waiting on the target chat.
        access.requireMember(uid, srcChatId, "不是源聊天成员")

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

        messages.storeMessage(forwardMsg, forwardMsg, projectionTarget(targetChatId))
        drainPendingForMessageLocked(targetChatId, serverSeq)

        return forwardMsg
    }

    /**
     * 新消息进入目标聊天前的实时权限边界。
     *
     * 普通发送的幂等命中在调用本方法之前返回，保证 ACK 丢失后的重试不被后来发生的
     * 拉黑/禁言反向改写；转发没有客户端幂等身份，因此每次都必须先通过此检查。
     */
    private fun requireCanCreateMessage(senderUid: String, chatId: String) {
        access.requireMember(senderUid, chatId)

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
            access.requireMember(uid, chatId)
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

    /**
     * Replays the complete durable operation outbox, not merely one startup page. Readiness is
     * cleared only after two global empty observations and only if no concurrent failure changed
     * the generation between those observations.
     */
    suspend fun recoverPendingProjections(limit: Int = 1_000): Int {
        require(limit > 0) { "Projection page size must be positive" }
        return recoveryMutex.withLock {
            var recovered = 0
            var emptyScans = 0
            var observedGeneration = projectionReadiness.generation()
            while (true) {
                val pending = messages.getPendingProjectionOperations(limit)
                if (pending.isEmpty()) {
                    emptyScans += 1
                    if (emptyScans < REQUIRED_EMPTY_SCANS) continue
                    if (projectionReadiness.markReadyIfUnchanged(observedGeneration)) return@withLock recovered
                    observedGeneration = projectionReadiness.generation()
                    emptyScans = 0
                    continue
                }
                emptyScans = 0
                for (operation in pending) {
                    projectOperation(operation)
                    recovered += 1
                }
            }
            @Suppress("UNREACHABLE_CODE")
            recovered
        }
    }

    private suspend fun recoverIfBlocked() {
        if (projectionReadiness.currentFailure() != null) recoverPendingProjections()
    }

    /** Caller holds [lifecycleGate] for this chat. */
    private suspend fun drainPendingForMessageLocked(chatId: String, serverSeq: Long) {
        while (true) {
            val pending = messages.getPendingProjectionOperations(chatId, serverSeq, PROJECTION_MESSAGE_PAGE_SIZE)
            if (pending.isEmpty()) return
            pending.forEach { projectOperationLocked(it) }
        }
    }

    private suspend fun projectOperation(operation: MessageProjectionOperation) {
        lifecycleGate.withChat(operation.message.chatId) { projectOperationLocked(operation) }
    }

    /** Must run under the chat lifecycle gate; the striped lock deduplicates one message identity. */
    private suspend fun projectOperationLocked(operation: MessageProjectionOperation) {
        val message = operation.message
        projectionLocks[projectionLockIndex(message.chatId, message.serverSeq)].withLock {
            // Concurrent command retry and global recovery can both observe one immutable operation.
            if (!messages.isProjectionPending(operation)) return
            try {
                val preview = when (operation.operation) {
                    MessageOperationType.REVOKE -> ""
                    MessageOperationType.CREATE,
                    MessageOperationType.EDIT,
                    -> MessageTextExtractor.extract(message, message.body)
                }
                val searchText = if (operation.operation == MessageOperationType.REVOKE) null else preview
                search.applyProjection(operation, searchText)
                projectionHooks.hit(MessageProjectionStage.AFTER_LUCENE_BEFORE_POSTGRES, operation)

                unitOfWork.write {
                    val applied = projectionRepository.apply(transaction, operation, preview)
                    if (applied.applied) {
                        for (recipient in applied.recipients) {
                            appendEvent(
                                recipient.uid,
                                NotifyType.MESSAGE_RECV,
                                message,
                                projectionEventDedupeKey(operation, recipient.uid, "message"),
                            )
                            recipient.conversation?.let { conversation ->
                                appendEvent(
                                    recipient.uid,
                                    NotifyType.CONVERSATION_UPDATED,
                                    conversation,
                                    projectionEventDedupeKey(operation, recipient.uid, "conversation"),
                                )
                            }
                        }
                    }
                }
                projectionHooks.hit(MessageProjectionStage.AFTER_POSTGRES_BEFORE_ROCKS_ACK, operation)
                messages.markProjectionComplete(operation)
            } catch (error: Throwable) {
                projectionReadiness.block("${operation.projectionKey}@${operation.revision}", error)
                throw error
            }
        }
    }

    private fun projectionTarget(chatId: String): MessageProjectionTarget {
        val chat = chatStore.getChat(chatId) ?: throw IllegalArgumentException("聊天不存在")
        val recipients = chatStore.getMemberUids(chatId).distinct().sorted()
        require(recipients.isNotEmpty()) { "聊天没有活动成员" }
        return MessageProjectionTarget(chat.chatType, recipients)
    }

    private fun projectionEventDedupeKey(
        operation: MessageProjectionOperation,
        uid: String,
        kind: String,
    ): String {
        val identity = "${operation.projectionKey}\u0000${operation.revision}\u0000$uid\u0000$kind"
        val hash = MessageDigest.getInstance("SHA-256").digest(identity.encodeToByteArray())
        return "message-projection:${hash.joinToString("") { byte ->
            val value = byte.toInt() and 0xFF
            "${HEX[value ushr 4]}${HEX[value and 0x0F]}"
        }}"
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

    /**
     * GENERIC is a receive-compatible escape hatch, not permission for clients to invent an
     * extension number. The protocol decoder preserves unknown opaque messages from a newer
     * server, while this authoritative create boundary accepts only explicitly allocated codes.
     */
    private fun requireRegisteredGenericExtension(message: Message) {
        val generic = message.body as? GenericPayload ?: return
        require(ExtensionType.fromCode(generic.extensionType) != null) {
            "未登记的消息扩展类型: ${generic.extensionType}"
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
        private const val PROJECTION_MESSAGE_PAGE_SIZE = 100
        private const val REQUIRED_EMPTY_SCANS = 2
        private const val HEX = "0123456789abcdef"

        /** 操作型消息只能走权限明确的 RPC，不能伪装成普通 MESSAGE 帧落库。 */
        private val CREATABLE_MESSAGE_TYPES = setOf(
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
            // GENERIC enters canonicalization so the server can produce an explicit
            // "unregistered extension" rejection instead of treating code 99 as unknown.
            MessageType.GENERIC,
        )

        private val EDITABLE_MESSAGE_TYPES = setOf(MessageType.RICH_TEXT)
    }
}
