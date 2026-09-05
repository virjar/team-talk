package com.virjar.tk.server.domain.message

import com.virjar.tk.protocol.body.AttachmentPolicy
import com.virjar.tk.protocol.body.CardBody
import com.virjar.tk.protocol.body.MessageBodyPolicy
import com.virjar.tk.protocol.body.OfficeRefBody
import com.virjar.tk.protocol.body.ReplyBody
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.server.domain.attachment.AttachmentService
import com.virjar.tk.server.domain.attachment.AttachmentLifecycleGate
import com.virjar.tk.server.domain.chat.ChatAccess
import com.virjar.tk.server.domain.chat.ManagedChatPolicy
import com.virjar.tk.server.domain.chat.MessageAdmission
import com.virjar.tk.server.domain.chat.ChatService
import com.virjar.tk.server.domain.chat.ChatStore
import com.virjar.tk.server.domain.chat.UnmanagedChatPolicy
import com.virjar.tk.server.domain.contact.ContactRepository
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.domain.transaction.PgUnitOfWork
import com.virjar.tk.server.domain.user.UserRepository
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.Member
import com.virjar.tk.protocol.model.ConversationWirePolicy
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.ProtoCodec

/**
 * 消息业务入口：校验命令、读写消息归档，并等待 [MessageProjector] 完成本次投影。
 *
 * [MessageRepository] 分配聊天内序号并持久化消息；[MessageProjector] 更新搜索、会话和同步事件。
 * 本类返回后，TCP 入口才发送成功 ACK。连接状态和网络投递不属于本类。
 */
class MessageService(
    private val messages: MessageRepository,
    private val chatStore: ChatStore,
    private val access: ChatAccess,
    private val chatService: ChatService,
    private val officeRefs: OfficeRefResolver,
    private val projector: MessageProjector,
    private val unitOfWork: PgUnitOfWork,
    private val search: MessageSearch,
    private val attachmentService: AttachmentService,
    private val users: UserRepository,
    private val contacts: ContactRepository,
    private val managedChats: ManagedChatPolicy = UnmanagedChatPolicy,
    private val attachmentLifecycle: AttachmentLifecycleGate = AttachmentLifecycleGate(),
) {
    suspend fun sendMessage(senderUid: String, message: Message): Long {
        return sendMessage(senderUid, message, authorizeAfterChatLock = null)
    }

    /** 机器人可附加凭据校验；回调在聊天锁内同步执行，普通用户传 null。 */
    internal suspend fun sendMessage(
        senderUid: String,
        message: Message,
        authorizeAfterChatLock: ((PgWriteTransactionContext) -> Unit)?,
    ): Long {
        return projector.withProjectionReadyChats(message.chatId) {
            sendMessageLocked(senderUid, message, authorizeAfterChatLock)
        }
    }

    /** 调用方经 [MessageProjector.withProjectionReadyChats] 串行执行此聊天的写入与投影。 */
    private suspend fun sendMessageLocked(
        senderUid: String,
        message: Message,
        authorizeAfterChatLock: ((PgWriteTransactionContext) -> Unit)?,
    ): Long {
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
            if (authorizeAfterChatLock != null) {
                authorizeIdempotentBotRetry(chatId, authorizeAfterChatLock)
            }
            projector.drainPendingForMessageLocked(chatId, existing.serverSeq)
            return existing.serverSeq
        }

        // 人类成员/禁言状态只约束新消息；上面的机器人专用路径仍会在返回幂等 ACK 之前
        // 重新校验其当前凭证与授权。若原 ACK 丢失，
        // 已持久化请求必须先按 sender/hash 命中上面的幂等记录；否则发送者随后离群或被禁言
        // 会把一条收件方已经收到的成功消息反向显示为发送失败。
        // 成员校验只限制尚未接受的新消息。已成功消息延迟重试时，
        // 被 mention 的成员可能已离群，但幂等 ACK 仍必须返回原 serverSeq。

        val declaredMessage = rebuildAuthoritativeReferences(clientDeclaredMessage)

        // 消息契约：发送成功 = 引用的附件真实存在（文件只走本服务端文件存储，
        // 不存在三方文件服务；完整 http URL 只是客户端/外部 SDK 对接形态）。
        // 断链消息在服务端拒绝，不能等对端点击才发现打不开。
        val committedMessage = withAttachmentReferenceMutation(declaredMessage) {
            val canonicalMessage = attachmentService.resolve(declaredMessage, senderUid)
            attachmentService.markReferenced(canonicalMessage)
            val admission = admitNewMessage(
                senderUid,
                chatId,
                canonicalMessage,
                authorizeAfterChatLock,
            )

            // 客户端只声明 chatId/clientMsgId/type/body；消息身份、时间和状态位全部由服务端重建。
            val candidate = canonicalMessage.copy(
                serverSeq = 0L,
                senderUid = senderUid,
                timestamp = System.currentTimeMillis(),
                flags = 0,
                sendStatus = Message.SEND_STATUS_SENT,
                uploadProgress = 0f,
            )
            // 幂等摘要保存首次被接受的客户端声明，而不是服务端补齐后的可变展示侧信道；
            // 因此目标消息后续编辑、文件元数据刷新都不会让同一请求的延迟重试变成冲突。
            messages.appendMessage(
                candidate,
                clientDeclaredMessage,
                MessageProjectionTarget(admission.chatType, admission.recipientUids),
            )
        }
        projector.drainPendingForMessageLocked(chatId, committedMessage.serverSeq)

        return committedMessage.serverSeq
    }

    suspend fun getHistory(uid: String, chatId: String, fromSeq: Long, limit: Int): List<Message> {
        requireQueryPageLimit(limit)
        return access.readAsMember(uid, chatId) { _, _ ->
            messages.getHistory(chatId, fromSeq, limit, forward = false)
        }
    }

    suspend fun revokeMessage(uid: String, chatId: String, serverSeq: Long) {
        projector.withProjectionReadyChats(chatId) { revokeMessageLocked(uid, chatId, serverSeq) }
    }

    private suspend fun revokeMessageLocked(uid: String, chatId: String, serverSeq: Long) {
        access.readMembersFor(uid, chatId) { chat, members ->
            val actor = members.first { it.uid == uid }
            val message = messages.getMessage(chatId, serverSeq)
                ?: throw IllegalArgumentException("消息不存在")
            if (message.senderUid != uid && actor.role < 1) {
                throw IllegalArgumentException("需要管理员权限")
            }
            revokeUnderSnapshot(message, projectionTarget(chat, members))
        }
        projector.drainPendingForMessageLocked(chatId, serverSeq)
    }

    /** 管理员撤回：免权限检查，广播链路复用。 */
    suspend fun adminRevoke(chatId: String, serverSeq: Long) {
        projector.withProjectionReadyChats(chatId) {
            access.readChatMembers(chatId) { chat, members ->
                val message = messages.getMessage(chatId, serverSeq)
                    ?: throw IllegalArgumentException("消息不存在")
                revokeUnderSnapshot(message, projectionTarget(chat, members))
            }
            projector.drainPendingForMessageLocked(chatId, serverSeq)
        }
    }

    private fun revokeUnderSnapshot(message: Message, target: MessageProjectionTarget) {
        if (message.flags and Message.FLAG_REVOKED != 0) return
        val revoked = message.copy(flags = message.flags or Message.FLAG_REVOKED)
        messages.updateMessage(
            message.chatId,
            message.serverSeq,
            revoked,
            MessageOperationType.REVOKE,
            target,
        )
    }

    suspend fun editMessage(uid: String, chatId: String, serverSeq: Long, newMessage: Message) {
        projector.withProjectionReadyChats(chatId) { editMessageLocked(uid, chatId, serverSeq, newMessage) }
    }

    private suspend fun editMessageLocked(uid: String, chatId: String, serverSeq: Long, newMessage: Message) {
        val message = access.readAsMember(uid, chatId) { _, _ ->
            messages.getMessage(chatId, serverSeq)
                ?: throw IllegalArgumentException("消息不存在")
        }

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
        val declaredEditCandidate = MessageBodyPolicy.canonicalize(
            AttachmentPolicy.canonicalize(editCandidate),
        )
        // 锁定旧/新并集：移除与引入是一次引用索引变更，且保留工作线程绝不能在这段
        // 间隙中退役一个新引入的暂存对象。
        withAttachmentReferenceMutation(message, declaredEditCandidate) {
            val canonicalNewMessage = attachmentService.resolve(declaredEditCandidate, uid)
            attachmentService.markReferenced(canonicalNewMessage)
            val edited = canonicalNewMessage.copy(
                flags = message.flags or Message.FLAG_EDITED,
            )
            access.readMembersFor(uid, chatId) { chat, members ->
                validateMentionMembership(edited, members.map(Member::uid))
                if (!ProtoCodec.encode(edited).contentEquals(ProtoCodec.encode(message))) {
                    messages.updateMessage(
                        chatId,
                        serverSeq,
                        edited,
                        MessageOperationType.EDIT,
                        projectionTarget(chat, members),
                    )
                }
            }
        }
        // 重复编辑也可能需要补齐上次未完成的投影。
        projector.drainPendingForMessageLocked(chatId, serverSeq)
    }

    /**
     * CLIENT-08：把一条已确认消息复制保存到当前用户的"保存的消息"私有会话。
     *
     * 复用 forward 的复制语义（附件 resolve/markReferenced、sender=保存者、FORWARDED 标记），
     * 目标固定为 saved chat，clientMsgId 使用调用方的稳定 [operationId]：消息存储的
     * chatId+clientMsgId+内容 hash 幂等让丢响应重放返回原副本。副本使用首次保存的服务端时间；
     * 时间与状态位不参与内容摘要，源消息撤回后的重试仍取回原副本及其时间。
     * 副本是独立消息，源消息随后撤回/删除不影响已保存内容。
     */
    suspend fun saveMessage(uid: String, srcChatId: String, srcSeq: Long, operationId: String): Message {
        require(operationId.isNotBlank() && operationId.length <= MessageBodyPolicy.MAX_CLIENT_MESSAGE_ID_LENGTH) {
            "保存命令 operationId 非法"
        }
        val savedChat = chatService.getOrCreateSavedChat(uid)
        return projector.withProjectionReadyChats(srcChatId, savedChat.chatId) {
            val srcMsg = access.readAsMember(uid, srcChatId, "不是源聊天成员") { _, _ ->
                messages.getMessage(srcChatId, srcSeq) ?: throw IllegalArgumentException("原消息不存在")
            }
            val canonicalSource = attachmentService.resolve(srcMsg, uid)
            val stableCopy = canonicalSource.copy(
                chatId = savedChat.chatId,
                clientMsgId = operationId,
                serverSeq = 0L,
                senderUid = uid,
                flags = (canonicalSource.flags and Message.FLAG_REVOKED.inv()) or Message.FLAG_FORWARDED,
                timestamp = srcMsg.timestamp,
            )
            messages.findIdempotentMessage(stableCopy)?.let { saved -> return@withProjectionReadyChats saved }
            require(srcMsg.flags and Message.FLAG_REVOKED == 0) { "原消息已撤回，不能保存" }

            val savedMessage = withAttachmentReferenceMutation(srcMsg) {
                attachmentService.markReferenced(canonicalSource)
                access.requireMember(uid, srcChatId, "不是源聊天成员")
                val admission = admitNewMessage(uid, savedChat.chatId, canonicalSource)
                messages.appendMessage(
                    stableCopy.copy(timestamp = System.currentTimeMillis()),
                    stableCopy,
                    MessageProjectionTarget(admission.chatType, admission.recipientUids),
                )
            }
            projector.drainPendingForMessageLocked(savedChat.chatId, savedMessage.serverSeq)
            savedMessage
        }
    }

    suspend fun forwardMessage(uid: String, srcChatId: String, srcSeq: Long, targetChatId: String): Message {
        return projector.withProjectionReadyChats(srcChatId, targetChatId) {
            forwardMessageLocked(uid, srcChatId, srcSeq, targetChatId)
        }
    }

    private suspend fun forwardMessageLocked(
        uid: String,
        srcChatId: String,
        srcSeq: Long,
        targetChatId: String,
    ): Message {
        // 源成员关系也是一个当前的授权事实。把检查放在与源读取和目标提交相同的双聊天
        // 生命周期边界下，否则一次踢出、退群或解散可能在转发等待目标聊天时撤回访问权。
        val srcMsg = access.readAsMember(uid, srcChatId, "不是源聊天成员") { _, _ ->
            messages.getMessage(srcChatId, srcSeq)
                ?: throw IllegalArgumentException("原消息不存在")
        }
        val forwarded = withAttachmentReferenceMutation(srcMsg) {
            val canonicalSource = attachmentService.resolve(srcMsg, uid)
            attachmentService.markReferenced(canonicalSource)

            // 文件元数据/引用校验可能在源快照之后挂起。在准许目标写入之前，
            // 于最终源边界重新检查。
            access.requireMember(uid, srcChatId, "不是源聊天成员")
            val admission = admitNewMessage(uid, targetChatId, canonicalSource)

            val forwardMsg = canonicalSource.copy(
                chatId = targetChatId,
                clientMsgId = java.util.UUID.randomUUID().toString(),
                serverSeq = 0L,
                senderUid = uid,
                flags = canonicalSource.flags or Message.FLAG_FORWARDED,
                timestamp = System.currentTimeMillis(),
            )
            messages.appendMessage(
                forwardMsg,
                forwardMsg,
                MessageProjectionTarget(admission.chatType, admission.recipientUids),
            )
        }
        projector.drainPendingForMessageLocked(targetChatId, forwarded.serverSeq)

        return forwarded
    }

    private suspend fun <T> withAttachmentReferenceMutation(
        vararg messages: Message,
        block: suspend () -> T,
    ): T {
        val attachments = messages.flatMap(AttachmentPolicy::attachments)
        return if (attachments.isEmpty()) {
            block()
        } else {
            attachmentLifecycle.withReferenceMutation(
                paths = attachments.map { it.path },
                block = block,
            )
        }
    }

    /**
     * 新消息进入目标聊天前的实时权限边界。
     *
     * 普通发送的幂等命中在调用本方法之前返回，保证 ACK 丢失后的重试不被后来发生的
     * 拉黑/禁言反向改写；转发没有客户端幂等身份，因此每次都必须先通过此检查。
     */
    private suspend fun admitNewMessage(
        senderUid: String,
        chatId: String,
        messageForMentions: Message,
        authorizeAfterChatLock: ((PgWriteTransactionContext) -> Unit)? = null,
    ): MessageAdmission =
        unitOfWork.write {
            val authority = managedChats.lockAuthority(transaction, listOf(chatId)).getValue(chatId)
            require(authority.ready) { "受管群投影尚未收敛" }
            val admission = chatStore.admitMessage(
                transaction,
                chatId,
                senderUid,
                System.currentTimeMillis(),
                afterChatLocked = { authorizeAfterChatLock?.invoke(transaction) },
            ) { facts ->
                val sender = facts.sender ?: throw IllegalArgumentException("不是聊天成员")
                if (facts.chat.chatType == 1) {
                    val peerUid = facts.activeMemberUids.firstOrNull { it != senderUid }
                        ?: throw IllegalArgumentException("私聊成员不完整")
                    require(!contacts.isBlockedEither(senderUid, peerUid)) {
                        "黑名单关系下不能发送私聊消息"
                    }
                }
                if (facts.senderMuted) throw IllegalArgumentException("你已被禁言")
                if (facts.chat.mutedAll && sender.role < 1) {
                    throw IllegalArgumentException("群聊已开启全员禁言")
                }
                validateMentionMembership(messageForMentions, facts.activeMemberUids)
            }
            admission
        }

    /** 当前机器人凭证仍然把守幂等 ACK，而不改变普通用户的重试语义。 */
    private suspend fun authorizeIdempotentBotRetry(
        chatId: String,
        authorizeAfterChatLock: (PgWriteTransactionContext) -> Unit,
    ) {
        unitOfWork.write {
            val authority = managedChats.lockAuthority(transaction, listOf(chatId)).getValue(chatId)
            require(authority.ready) { "受管群投影尚未收敛" }
            chatStore.lockChats(transaction, listOf(chatId), requireActive = true)
            authorizeAfterChatLock(transaction)
        }
    }

    suspend fun searchMessages(uid: String, chatId: String, keyword: String, limit: Int): List<Message> {
        requireQueryPageLimit(limit)
        requireValidMessageSearchQuery(keyword)
        require(
            chatId.isEmpty() ||
                (
                    chatId.length <= ConversationWirePolicy.MAX_CHAT_ID_LENGTH &&
                        chatId.none { character -> character.isISOControl() || character.isWhitespace() }
                    )
        ) { "消息搜索 chatId 非法" }
        return if (chatId.isEmpty()) {
            // 空 chatId 是客户端“搜索全部消息”的明确契约。权限集合必须由服务端
            // 根据当前用户会话计算，不能信任客户端上传任意 chatId 列表。
            access.readAccessibleChatIds(uid) { allowedChatIds ->
                searchAuthorized(keyword, allowedChatIds, limit)
            }
        } else {
            access.readAsMember(uid, chatId) { _, _ ->
                searchAuthorized(keyword, setOf(chatId), limit)
            }
        }
    }

    private fun searchAuthorized(keyword: String, allowedChatIds: Set<String>, limit: Int): List<Message> {
        if (allowedChatIds.isEmpty()) return emptyList()
        val results = search.search(keyword, chatIds = allowedChatIds, limit = limit)
        return results.hits.mapNotNull { hit ->
            // 授权是 PostgreSQL 事实，而 Lucene 是可重建的派生数据。即使旧的/损坏的文档
            // 携带了额外的 chatId 词项，也要保持默认拒绝（fail-closed）边界。
            if (hit.chatId !in allowedChatIds) return@mapNotNull null
            messages.getMessage(hit.chatId, hit.seq)
        }
    }

    private fun requireQueryPageLimit(limit: Int) {
        require(limit in 1..Message.MAX_QUERY_PAGE_SIZE) {
            "消息查询分页大小必须在 1..${Message.MAX_QUERY_PAGE_SIZE} 之间"
        }
    }

    private fun projectionTarget(chat: com.virjar.tk.protocol.model.Chat, members: List<Member>): MessageProjectionTarget {
        val recipients = members.map(Member::uid).distinct().sorted()
        require(recipients.isNotEmpty()) { "聊天没有活动成员" }
        return MessageProjectionTarget(chat.chatType, recipients)
    }

    /** 回复、联系人名片和办公引用的展示字段由服务端权威数据重建。 */
    private suspend fun rebuildAuthoritativeReferences(message: Message): Message {
        return when (val body = message.body) {
            is ReplyBody -> rebuildReply(message, body)
            is CardBody -> rebuildContactCard(message, body)
            is OfficeRefBody -> MessageBodyPolicy.canonicalize(
                message.copy(body = officeRefs.resolve(message.senderUid, body)),
            )
            else -> message
        }
    }

    /**
     * mention 会驱动通知与成员定位，因此不能把非会话成员的 uid 带入已成功消息。
     * RichTextBody 使用 canonical mentions；ReplyBody 的 content 仍是 Markdown 事实源，
     * 在服务端现场解析，不信任任何客户端侧信道。
     */
    private fun validateMentionMembership(message: Message, activeMemberUids: Collection<String>) {
        val mentionedUids = when (val body = message.body) {
            is RichTextBody -> body.mentions.map { it.uid }
            is ReplyBody -> buildRichTextBody(body.content, body.assets).mentions.map { it.uid }
            else -> emptyList()
        }
        if (mentionedUids.isEmpty()) return

        val memberUids = activeMemberUids.toHashSet()
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
        // ChatStore 的热成员投影刻意只包含 uid/role。在目标已绑定到这个精确聊天之后，
        // 从全局用户目录解析展示快照；此处使用客户端声明的 uid/name 会允许跨聊天伪造。
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
        return MessageTextExtractor.extractConversationPreview(target, target.body)?.takeIf(String::isNotBlank)
            ?: when (MessageType.fromCode(target.messageType)) {
                MessageType.IMAGE -> "[图片]"
                MessageType.VOICE -> "[语音]"
                MessageType.VIDEO -> "[视频]"
                MessageType.STICKER -> "[表情]"
                else -> "[消息]"
            }
    }

    companion object {
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
            MessageType.OFFICE_REF,
        )

        private val EDITABLE_MESSAGE_TYPES = setOf(MessageType.RICH_TEXT)
    }
}
