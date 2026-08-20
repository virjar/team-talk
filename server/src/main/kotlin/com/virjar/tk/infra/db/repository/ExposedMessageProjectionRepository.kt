package com.virjar.tk.infra.db.repository

import com.virjar.tk.domain.message.MessageOperationType
import com.virjar.tk.domain.message.MessageProjectionApplyResult
import com.virjar.tk.domain.message.MessageProjectionOperation
import com.virjar.tk.domain.message.MessageProjectionRecipient
import com.virjar.tk.domain.message.MessageProjectionRepository
import com.virjar.tk.domain.transaction.PgTransactionContext
import com.virjar.tk.infra.db.Chats
import com.virjar.tk.infra.db.Conversations
import com.virjar.tk.infra.db.ExternalProjectionReceipts
import com.virjar.tk.infra.db.GroupChats
import com.virjar.tk.infra.db.GroupMembers
import com.virjar.tk.infra.db.Users
import com.virjar.tk.infra.db.requireExposedTransaction
import com.virjar.tk.model.Conversation
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.ProtoCodec
import org.jetbrains.exposed.sql.*
import java.nio.ByteBuffer
import java.security.MessageDigest

class ExposedMessageProjectionRepository : MessageProjectionRepository {
    override fun apply(
        transaction: PgTransactionContext,
        operation: MessageProjectionOperation,
        lastMessagePreview: String?,
    ): MessageProjectionApplyResult {
        transaction.requireExposedTransaction()
        val payloadHash = operation.payloadHash(lastMessagePreview)
        val inserted = ExternalProjectionReceipts.insertIgnore {
            it[projectionKey] = operation.projectionKey
            it[revision] = operation.revision
            it[operationType] = operation.operation.code
            it[chatId] = operation.message.chatId
            it[serverSeq] = operation.message.serverSeq
            it[ExternalProjectionReceipts.payloadHash] = payloadHash
            it[appliedAt] = System.currentTimeMillis()
        }
        if (inserted.insertedCount == 0) {
            validateExistingReceipt(operation, payloadHash)
            return MessageProjectionApplyResult(applied = false, recipients = emptyList())
        }

        val chat = Chats.selectAll().where {
            (Chats.chatId eq operation.message.chatId) and (Chats.status eq 1)
        }.forUpdate().singleOrNull()
            ?: return MessageProjectionApplyResult(applied = true, recipients = emptyList())
        val chatType = chat[Chats.chatType]
        require(operation.target.chatType == chatType) {
            "Message projection chat type changed"
        }

        val requestedRecipients = operation.target.recipientUids
        require(requestedRecipients.isNotEmpty()) { "Message projection recipient snapshot is empty" }
        val activeRecipients = GroupMembers.selectAll().where {
            (GroupMembers.chatId eq operation.message.chatId) and
                (GroupMembers.uid inList requestedRecipients) and
                (GroupMembers.status eq 1)
        }.orderBy(GroupMembers.uid to SortOrder.ASC)
            .forUpdate()
            .map { it[GroupMembers.uid] }

        val now = System.currentTimeMillis()
        val projected = activeRecipients.map { uid ->
            val conversationChanged = when (operation.operation) {
                MessageOperationType.CREATE -> {
                    projectCreate(uid, chatType, operation, lastMessagePreview, now)
                    true
                }
                MessageOperationType.EDIT,
                MessageOperationType.REVOKE,
                -> projectChange(uid, operation, lastMessagePreview, now)
            }
            MessageProjectionRecipient(
                uid = uid,
                conversation = if (conversationChanged) {
                    loadConversation(uid, chatType, operation.message.chatId)
                } else {
                    null
                },
            )
        }
        return MessageProjectionApplyResult(applied = true, recipients = projected)
    }

    private fun projectCreate(
        uid: String,
        chatType: Int,
        operation: MessageProjectionOperation,
        preview: String?,
        now: Long,
    ) {
        val message = operation.message
        val existing = Conversations.selectAll().where {
            (Conversations.uid eq uid) and (Conversations.chatId eq message.chatId)
        }.singleOrNull()
        if (existing == null) {
            val senderRead = uid == message.senderUid
            Conversations.insert {
                it[Conversations.uid] = uid
                it[Conversations.chatId] = message.chatId
                it[Conversations.chatType] = chatType
                it[Conversations.lastMsgSeq] = message.serverSeq
                it[Conversations.lastMessage] = preview
                it[Conversations.lastMessageType] = message.messageType
                it[Conversations.readSeq] = if (senderRead) message.serverSeq else 0L
                it[Conversations.version] = if (senderRead) 2L else 1L
                it[Conversations.updatedAt] = now
            }
            return
        }

        var nextVersion = existing[Conversations.version]
        val advancesLast = message.serverSeq > existing[Conversations.lastMsgSeq]
        val advancesRead = uid == message.senderUid && message.serverSeq > existing[Conversations.readSeq]
        if (!advancesLast && !advancesRead) return
        if (advancesLast) nextVersion += 1L
        if (advancesRead) nextVersion += 1L
        Conversations.update({
            (Conversations.uid eq uid) and (Conversations.chatId eq message.chatId)
        }) {
            if (advancesLast) {
                it[Conversations.lastMsgSeq] = message.serverSeq
                it[Conversations.lastMessage] = preview
                it[Conversations.lastMessageType] = message.messageType
            }
            if (advancesRead) it[Conversations.readSeq] = message.serverSeq
            it[Conversations.version] = nextVersion
            it[Conversations.updatedAt] = now
        }
    }

    private fun projectChange(
        uid: String,
        operation: MessageProjectionOperation,
        preview: String?,
        now: Long,
    ): Boolean {
        val message = operation.message
        val existing = Conversations.selectAll().where {
            (Conversations.uid eq uid) and (Conversations.chatId eq message.chatId)
        }.singleOrNull() ?: return false
        if (existing[Conversations.lastMsgSeq] != message.serverSeq) return false

        Conversations.update({
            (Conversations.uid eq uid) and (Conversations.chatId eq message.chatId)
        }) {
            it[Conversations.lastMessage] = preview
            it[Conversations.lastMessageType] = if (operation.operation == MessageOperationType.REVOKE) {
                MessageType.REVOKE.code
            } else {
                message.messageType
            }
            it[Conversations.version] = existing[Conversations.version] + 1L
            it[Conversations.updatedAt] = now
        }
        return true
    }

    private fun loadConversation(uid: String, chatType: Int, chatId: String): Conversation? {
        val row = Conversations.selectAll().where {
            (Conversations.uid eq uid) and (Conversations.chatId eq chatId)
        }.singleOrNull() ?: return null
        val (chatName, chatAvatar) = if (chatType == 2) {
            val group = GroupChats.selectAll().where { GroupChats.chatId eq chatId }.singleOrNull()
            group?.get(GroupChats.name) to group?.get(GroupChats.avatar)
        } else {
            val otherUid = GroupMembers.selectAll().where {
                (GroupMembers.chatId eq chatId) and
                    (GroupMembers.uid neq uid) and
                    (GroupMembers.status eq 1)
            }.orderBy(GroupMembers.uid to SortOrder.ASC).limit(1)
                .singleOrNull()
                ?.get(GroupMembers.uid)
            val other = otherUid?.let { peerUid ->
                Users.selectAll().where { Users.uid eq peerUid }.singleOrNull()
            }
            (other?.get(Users.name) ?: otherUid) to other?.get(Users.avatar)
        }
        val lastSeq = row[Conversations.lastMsgSeq]
        val readSeq = row[Conversations.readSeq]
        return Conversation(
            chatId = chatId,
            chatType = chatType,
            chatName = chatName,
            chatAvatar = chatAvatar,
            lastMessage = row[Conversations.lastMessage],
            lastMessageType = row[Conversations.lastMessageType],
            lastSeq = lastSeq,
            readSeq = readSeq,
            unreadCount = (lastSeq - readSeq).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            isPinned = row[Conversations.isPinned],
            isMuted = row[Conversations.isMuted],
            peerReadSeq = row[Conversations.peerReadSeq],
            draft = row[Conversations.draft],
        )
    }

    private fun validateExistingReceipt(operation: MessageProjectionOperation, payloadHash: ByteArray) {
        val receipt = ExternalProjectionReceipts.selectAll().where {
            (ExternalProjectionReceipts.projectionKey eq operation.projectionKey) and
                (ExternalProjectionReceipts.revision eq operation.revision)
        }.single()
        check(receipt[ExternalProjectionReceipts.operationType] == operation.operation.code)
        check(receipt[ExternalProjectionReceipts.chatId] == operation.message.chatId)
        check(receipt[ExternalProjectionReceipts.serverSeq] == operation.message.serverSeq)
        check(receipt[ExternalProjectionReceipts.payloadHash].contentEquals(payloadHash)) {
            "Projection receipt payload mismatch for ${operation.projectionKey}@${operation.revision}"
        }
    }

    private fun MessageProjectionOperation.payloadHash(lastMessagePreview: String?): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(operation.code.toByte())
        digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(revision).array())
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(target.chatType).array())
        target.recipientUids.forEach { uid ->
            val encoded = uid.encodeToByteArray()
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(encoded.size).array())
            digest.update(encoded)
        }
        digest.update(ProtoCodec.encode(message))
        val encodedPreview = lastMessagePreview?.encodeToByteArray()
        digest.update((if (encodedPreview == null) 0 else 1).toByte())
        if (encodedPreview != null) {
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(encodedPreview.size).array())
            digest.update(encodedPreview)
        }
        return digest.digest()
    }
}
