package com.virjar.tk.shared.client

import com.virjar.tk.protocol.body.MessageBodyRegistry
import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.Contact
import com.virjar.tk.protocol.model.Conversation
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.OrganizationMember
import com.virjar.tk.protocol.model.OrganizationUnit
import com.virjar.tk.protocol.model.User
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.ProtoCodec

/** 服务器派生本地投影的一个原子权威元组。 */
data class ServerProjectionSyncState(
    val datasetId: String,
    val cursor: Long,
) {
    init {
        com.virjar.tk.protocol.payload.SyncDatasetIdPolicy.requireValid(datasetId)
        require(cursor >= 0L) { "sync cursor must be non-negative" }
    }
}

/**
 * sync 携带的紧凑服务器投影的、已完全收集的替代品。其页可能来自不同的服务器快照；[baseEventId]
 * 锚定在该替代品被原子安装之后收敛并发变化的尾部。
 *
 * 本地可靠事实刻意缺席：outgoing 消息、命令、草稿、已读、bot 投递历史、组织与文档仍归其现有
 * 本地存储所有。
 */
data class ServerProjectionCheckpoint(
    val datasetId: String,
    val baseEventId: Long,
    val currentUser: User,
    val contacts: List<Contact>,
    val chats: List<Chat>,
    val conversations: List<Conversation>,
) {
    init {
        com.virjar.tk.protocol.payload.SyncDatasetIdPolicy.requireValid(datasetId)
        require(baseEventId >= 0L) { "checkpoint baseEventId must be non-negative" }
        requireUniqueCheckpointKeys(contacts, Contact::friendUid, "contact friendUid")
        requireUniqueCheckpointKeys(chats, Chat::chatId, "chat id")
        requireUniqueCheckpointKeys(conversations, Conversation::chatId, "conversation chat id")
        contacts.forEach { contact ->
            require(contact.uid == currentUser.uid) {
                "checkpoint contact belongs to a different account"
            }
            val embedded = requireNotNull(contact.user) {
                "checkpoint contact is missing its authoritative user"
            }
            require(embedded.uid == contact.friendUid) {
                "checkpoint contact embedded user identity mismatch"
            }
        }
        val chatIds = chats.mapTo(HashSet(chats.size), Chat::chatId)
        require(conversations.all { it.chatId in chatIds }) {
            "checkpoint conversation has no chat projection"
        }
    }
}

private fun <T> requireUniqueCheckpointKeys(
    values: List<T>,
    key: (T) -> String,
    label: String,
) {
    val seen = HashSet<String>(values.size)
    values.forEach { value ->
        require(seen.add(key(value))) { "checkpoint contains duplicate $label" }
    }
}

/** 正的服务器 sequence 使服务器投影成为唯一展示权威。 */
internal fun Message.asAuthoritativeProjection(): Message =
    if (serverSeq > 0L && sendStatus != Message.SEND_STATUS_SENT) {
        copy(sendStatus = Message.SEND_STATUS_SENT)
    } else {
        this
    }

internal fun com.virjar.tk.shared.database.User.toLocalModel() = User(
    uid = uid,
    username = username,
    name = name,
    avatar = storedAttachment(
        avatar_path,
        avatar_name,
        avatar_content_type,
        avatar_size,
        "Cached user $uid avatar",
    ),
    phone = phone,
    sex = sex?.toInt() ?: 0,
    role = role?.toInt() ?: 0,
    status = status?.toInt() ?: 1,
    revision = revision,
)

internal fun com.virjar.tk.shared.database.Chat.toLocalModel() = Chat(
    chatId = chat_id,
    chatType = chat_type.toInt(),
    name = name,
    avatar = avatar,
    creator = creator,
    memberCount = member_count?.toInt() ?: 0,
    maxSeq = max_seq ?: 0L,
    notice = notice,
    mutedAll = muted_all != 0L,
)

internal fun com.virjar.tk.shared.database.Organization_unit.toLocalModel() = OrganizationUnit(
    unitId = unit_id,
    parentId = parent_id,
    name = name,
    leaderUid = leader_uid,
    sortOrder = sort_order.toInt(),
    groupChatId = group_chat_id,
    status = status.toInt(),
    directMemberCount = direct_member_count.toInt(),
)

internal fun com.virjar.tk.shared.database.Organization_member.toLocalModel() = OrganizationMember(
    unitId = unit_id,
    uid = uid,
    title = title,
    primary = is_primary != 0L,
    joinedAt = joined_at,
    user = when {
        user_username == null && user_name == null -> null
        else -> User(
            uid = uid,
            username = requireNotNull(user_username) { "Cached organization user has no username" },
            name = requireNotNull(user_name) { "Cached organization user has no name" },
            avatar = storedAttachment(
                user_avatar_path,
                user_avatar_name,
                user_avatar_content_type,
                user_avatar_size,
                "Cached organization user $uid avatar",
            ),
            phone = user_phone,
            sex = user_sex?.toInt() ?: 0,
            role = user_role?.toInt() ?: 0,
            status = user_status?.toInt() ?: 1,
            revision = requireNotNull(user_revision) {
                "Cached organization user $uid has no revision"
            },
        )
    },
)

internal fun com.virjar.tk.shared.database.Message.toLocalModel(): Message {
    val bodyBytes = body
    val decodedBody = if (bodyBytes != null) {
        try {
            val msgType = requireNotNull(MessageType.fromCode(message_type.toInt())) {
                "Unknown cached message type: $message_type"
            }
            val buffer = PacketBuffer(bodyBytes)
            val decoded = requireNotNull(MessageBodyRegistry.decode(msgType, buffer)) {
                "Message type $msgType has no body reader"
            }
            require(buffer.readableBytes() == 0) { "Cached message body has trailing bytes" }
            decoded
        } catch (e: Exception) {
            val failure = IllegalStateException(
                "Corrupt cached message body chatId=$chat_id msgId=$client_msg_id type=$message_type",
                e,
            )
            // 无头会话可能刻意不拥有进程级 AppLog 槽。
            com.virjar.tk.shared.log.platformLog(
                "fault",
                "LocalCache",
                failure.message ?: "Corrupt cached message body",
                failure,
            )
            throw failure
        }
    } else {
        null
    }
    return Message(
        chatId = chat_id,
        clientMsgId = client_msg_id,
        serverSeq = server_seq ?: 0L,
        senderUid = sender_uid,
        messageType = message_type.toInt(),
        timestamp = timestamp,
        flags = flags?.toInt() ?: 0,
        body = decodedBody,
        sendStatus = send_status?.toInt() ?: 0,
    )
}

internal fun com.virjar.tk.shared.database.Outgoing_message.toLocalModel() = OutgoingMessage(
    localOrdinal = local_ordinal,
    message = decodeCanonicalPayload(),
    state = OutgoingMessageState.fromCode(state),
    attemptCount = attempt_count,
    nextAttemptAt = next_attempt_at,
    createdAt = created_at,
    updatedAt = updated_at,
    serverSeq = server_seq,
    terminalCode = terminal_code?.toInt(),
    completedAt = completed_at,
    failureCode = failure_code?.let(OutgoingFailureCode::fromStorageCode),
)

private fun com.virjar.tk.shared.database.Outgoing_message.projectionSendStatus(): Int = when (
    OutgoingMessageState.fromCode(state)
) {
    OutgoingMessageState.IN_FLIGHT -> Message.SEND_STATUS_SENDING
    OutgoingMessageState.TERMINAL_FAILED -> Message.SEND_STATUS_FAILED
    OutgoingMessageState.SUCCESS -> Message.SEND_STATUS_SENT
    OutgoingMessageState.PENDING,
    OutgoingMessageState.RETRY_WAIT -> Message.SEND_STATUS_QUEUED
}

internal fun com.virjar.tk.shared.database.Outgoing_message.toProjectionMessage(): Message =
    decodeCanonicalPayload().copy(
        serverSeq = if (state == OutgoingMessageState.SUCCESS.code) {
            requireNotNull(server_seq) { "SUCCESS outgoing receipt has no serverSeq" }
        } else {
            0L
        },
        sendStatus = projectionSendStatus(),
    )

private fun com.virjar.tk.shared.database.Outgoing_message.decodeCanonicalPayload(): Message =
    ProtoCodec.decode(Message, payload).also { decoded ->
        check(
            decoded.chatId == chat_id &&
                decoded.clientMsgId == client_msg_id &&
                decoded.senderUid == sender_uid &&
                decoded.serverSeq == 0L
        ) {
            "Outgoing payload identity does not match its durable outbox key"
        }
    }

internal fun com.virjar.tk.shared.database.Outgoing_message.requireSameOutgoingRequest(
    candidatePayload: ByteArray,
    candidateFingerprint: ByteArray?,
) {
    if (request_fingerprint != null || candidateFingerprint != null) {
        if (
            request_fingerprint == null || candidateFingerprint == null ||
            !request_fingerprint.contentEquals(candidateFingerprint)
        ) {
            throw OutgoingMessageConflictException(
                "clientMsgId already names a different durable outgoing request",
            )
        }
        // 指纹标识一个稳定的高层请求。保留原始线格式字节。
        return
    }
    if (!payload.contentEquals(candidatePayload)) {
        throw OutgoingMessageConflictException(
            "clientMsgId already names a different immutable outgoing payload",
        )
    }
}

internal fun com.virjar.tk.shared.database.Outgoing_message.requireRequestFingerprint(expected: ByteArray) {
    if (request_fingerprint == null || !request_fingerprint.contentEquals(expected)) {
        throw OutgoingMessageConflictException(
            "clientMsgId already names a different durable outgoing request",
        )
    }
}

internal fun com.virjar.tk.shared.database.Conversation.toLocalModel() = Conversation(
    chatId = chat_id,
    chatType = chat_type.toInt(),
    peerUid = peer_uid,
    peerRevision = peer_revision,
    chatName = chat_name,
    chatAvatar = storedAttachment(
        chat_avatar_path,
        chat_avatar_name,
        chat_avatar_content_type,
        chat_avatar_size,
        "Cached conversation $chat_id avatar",
    ),
    lastMessage = last_message,
    lastMessageType = last_message_type?.toInt(),
    lastMsgTimestamp = last_msg_timestamp,
    lastSeq = last_seq ?: 0L,
    readSeq = read_seq ?: 0L,
    peerReadSeq = peer_read_seq ?: 0L,
    unreadCount = unread_count?.toInt() ?: 0,
    isPinned = is_pinned == 1L,
    isMuted = is_muted == 1L,
    draft = draft,
)
