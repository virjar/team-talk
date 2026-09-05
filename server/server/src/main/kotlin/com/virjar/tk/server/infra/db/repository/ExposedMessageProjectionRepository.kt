package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.domain.message.MessageOperationType
import com.virjar.tk.server.domain.message.MessageProjectionApplyResult
import com.virjar.tk.server.domain.message.MessageProjectionOperation
import com.virjar.tk.server.domain.message.MessageProjectionRecipient
import com.virjar.tk.server.domain.message.MessageProjectionRepository
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.infra.db.Chats
import com.virjar.tk.server.infra.db.Conversations
import com.virjar.tk.server.infra.db.ExternalProjectionReceipts
import com.virjar.tk.server.infra.db.GroupChats
import com.virjar.tk.server.infra.db.GroupMembers
import com.virjar.tk.server.infra.db.Users
import com.virjar.tk.server.infra.db.toUserAvatar
import com.virjar.tk.server.infra.db.execRawSql
import com.virjar.tk.server.infra.db.requireExposedTransaction
import com.virjar.tk.protocol.model.Conversation
import com.virjar.tk.protocol.model.GroupPolicy
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.ProtoCodec
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.statements.StatementType
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.sql.ResultSet

class ExposedMessageProjectionRepository : MessageProjectionRepository {
    override fun apply(
        transaction: PgWriteTransactionContext,
        operation: MessageProjectionOperation,
        lastMessagePreview: String?,
    ): MessageProjectionApplyResult {
        val exposedTransaction = transaction.requireExposedTransaction()
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
        val now = System.currentTimeMillis()
        if (operation.operation == MessageOperationType.CREATE) {
            val expectedSeq = Math.addExact(chat[Chats.maxSeq], 1L)
            check(operation.message.serverSeq == expectedSeq) {
                "Message projection sequence is not contiguous: chatId=${operation.message.chatId}, " +
                    "expected=$expectedSeq, actual=${operation.message.serverSeq}"
            }
            check(Chats.update({ Chats.chatId eq operation.message.chatId }) {
                it[Chats.maxSeq] = operation.message.serverSeq
                it[Chats.updatedAt] = now
            } == 1) { "Message projection could not advance Chat maxSeq" }
        }

        val requestedRecipients = operation.target.recipientUids
        require(requestedRecipients.isNotEmpty()) { "Message projection recipient snapshot is empty" }
        val activeRecipients = lockActiveRecipients(
            exposedTransaction,
            operation.message.chatId,
            requestedRecipients,
        )
        val conversationEventUids = when (operation.operation) {
            MessageOperationType.CREATE -> {
                val usage = ConversationUsageLedger.lockBatched(activeRecipients)
                val existingConversationUids = loadExistingConversationUids(
                    operation.message.chatId,
                    activeRecipients,
                )
                ConversationUsageLedger.applyBatched(
                    usage,
                    activeRecipients.asSequence()
                        .filterNot(existingConversationUids::contains)
                        .associateWith { conversationUsageDeltaForInsert() },
                )
                projectCreateBatched(
                    exposedTransaction,
                    activeRecipients,
                    chatType,
                    operation,
                    lastMessagePreview,
                    now,
                )
                // 每次全新 CREATE 都是连续的，并发出当前可见的 Conversation。
                // 回执重放已在上面返回，因此此分支绝不会重新发布重复项。
                activeRecipients.toSet()
            }
            MessageOperationType.EDIT,
            MessageOperationType.REVOKE,
            -> projectChangeBatched(
                exposedTransaction,
                activeRecipients,
                operation,
                lastMessagePreview,
                now,
            )
        }
        val conversationRows = loadVisibleConversationRows(
            operation.message.chatId,
            conversationEventUids,
        )
        val conversations = buildConversations(
            chatType,
            operation.message.chatId,
            conversationRows,
        )
        return MessageProjectionApplyResult(
            applied = true,
            recipients = activeRecipients.map { uid ->
                MessageProjectionRecipient(
                    uid = uid,
                    conversation = if (uid in conversationEventUids) {
                        conversations[uid]
                    } else {
                        null
                    },
                )
            },
        )
    }

    private fun lockActiveRecipients(
        transaction: Transaction,
        chatId: String,
        requestedRecipients: List<String>,
    ): List<String> {
        val activeRecipients = mutableListOf<String>()
        requestedRecipients.chunked(PROJECTION_SQL_BATCH_SIZE).forEach { uidBatch ->
            val candidateRows = uidBatch.joinToString(", ") { "(?::varchar, ?::integer)" }
            val args = buildList<Pair<IColumnType<*>, Any?>> {
                uidBatch.forEachIndexed { lockOrder, uid ->
                    add(GroupMembers.uid.columnType to uid)
                    add(GroupMembers.status.columnType to lockOrder)
                }
                add(GroupMembers.chatId.columnType to chatId)
                add(GroupMembers.status.columnType to 1)
            }
            val lockedRecipients: List<String> = transaction.execRawSql(
                stmt = """
                    WITH candidates(uid, lock_order) AS (VALUES $candidateRows)
                    SELECT member_row.uid
                    FROM candidates candidate
                    JOIN group_members member_row ON member_row.uid = candidate.uid
                    WHERE member_row.chat_id = ?::varchar
                      AND member_row.status = ?::integer
                    ORDER BY candidate.lock_order
                    FOR UPDATE OF member_row
                """.trimIndent(),
                args = args,
                explicitStatementType = StatementType.SELECT,
            ) { resultSet: ResultSet ->
                buildList<String> {
                    while (resultSet.next()) add(resultSet.getString("uid"))
                }
            } ?: error("Message projection member lock returned no result set")
            activeRecipients += lockedRecipients
        }
        check(activeRecipients == activeRecipients.distinct().sorted()) {
            "Message projection member locks are not globally ordered"
        }
        GroupPolicy.requireFinalMemberCount(activeRecipients.size)
        return activeRecipients
    }

    private fun loadExistingConversationUids(chatId: String, uids: List<String>): Set<String> {
        val existing = linkedSetOf<String>()
        uids.chunked(PROJECTION_SQL_BATCH_SIZE).forEach { uidBatch ->
            Conversations.select(Conversations.uid).where {
                (Conversations.chatId eq chatId) and (Conversations.uid inList uidBatch)
            }.orderBy(Conversations.uid to SortOrder.ASC)
                .forEach { row -> existing += row[Conversations.uid] }
        }
        return existing
    }

    private fun projectCreateBatched(
        transaction: Transaction,
        uids: List<String>,
        chatType: Int,
        operation: MessageProjectionOperation,
        preview: String?,
        now: Long,
    ) {
        val message = operation.message
        uids.chunked(PROJECTION_SQL_BATCH_SIZE).forEach { uidBatch ->
            val recipientRows = uidBatch.joinToString(", ") { "(?::varchar)" }
            val args = buildList<Pair<IColumnType<*>, Any?>> {
                uidBatch.forEach { uid -> add(Conversations.uid.columnType to uid) }
                add(Conversations.chatId.columnType to message.chatId)
                add(Conversations.chatType.columnType to chatType)
                add(Conversations.lastMsgSeq.columnType to message.serverSeq)
                add(Conversations.lastMessage.columnType to preview)
                add(Conversations.lastMessageType.columnType to message.messageType)
                add(Conversations.lastMsgTimestamp.columnType to message.timestamp)
                add(Conversations.uid.columnType to message.senderUid)
                add(Conversations.updatedAt.columnType to now)
            }
            transaction.execRawSql(
                stmt = """
                    WITH recipients(uid) AS (VALUES $recipientRows),
                    projection(chat_id, chat_type, server_seq, preview, message_type, message_timestamp, sender_uid, now_ms) AS (
                        VALUES (
                            ?::varchar,
                            ?::integer,
                            ?::bigint,
                            ?::varchar,
                            ?::integer,
                            ?::bigint,
                            ?::varchar,
                            ?::bigint
                        )
                    )
                    INSERT INTO conversations (
                        uid,
                        chat_id,
                        chat_type,
                        last_msg_seq,
                        last_message,
                        last_message_type,
                        last_msg_timestamp,
                        read_seq,
                        version,
                        updated_at
                    )
                    SELECT recipient.uid,
                           projection.chat_id,
                           projection.chat_type,
                           projection.server_seq,
                           projection.preview,
                           projection.message_type,
                           projection.message_timestamp,
                           CASE WHEN recipient.uid = projection.sender_uid
                               THEN projection.server_seq ELSE 0 END,
                           CASE WHEN recipient.uid = projection.sender_uid THEN 2 ELSE 1 END,
                           projection.now_ms
                    FROM recipients recipient
                    CROSS JOIN projection
                    ORDER BY recipient.uid
                    ON CONFLICT (uid, chat_id) DO UPDATE
                    SET last_msg_seq = CASE
                            WHEN EXCLUDED.last_msg_seq > conversations.last_msg_seq
                                THEN EXCLUDED.last_msg_seq
                            ELSE conversations.last_msg_seq
                        END,
                        last_message = CASE
                            WHEN EXCLUDED.last_msg_seq > conversations.last_msg_seq
                                THEN EXCLUDED.last_message
                            ELSE conversations.last_message
                        END,
                        last_message_type = CASE
                            WHEN EXCLUDED.last_msg_seq > conversations.last_msg_seq
                                THEN EXCLUDED.last_message_type
                            ELSE conversations.last_message_type
                        END,
                        last_msg_timestamp = CASE
                            WHEN EXCLUDED.last_msg_seq > conversations.last_msg_seq
                                THEN EXCLUDED.last_msg_timestamp
                            ELSE conversations.last_msg_timestamp
                        END,
                        read_seq = GREATEST(conversations.read_seq, EXCLUDED.read_seq),
                        is_hidden = CASE
                            WHEN EXCLUDED.last_msg_seq > conversations.last_msg_seq
                                THEN FALSE
                            ELSE conversations.is_hidden
                        END,
                        version = conversations.version
                            + CASE WHEN EXCLUDED.last_msg_seq > conversations.last_msg_seq THEN 1 ELSE 0 END
                            + CASE WHEN EXCLUDED.read_seq > conversations.read_seq THEN 1 ELSE 0 END,
                        updated_at = EXCLUDED.updated_at
                    WHERE EXCLUDED.last_msg_seq > conversations.last_msg_seq
                       OR EXCLUDED.read_seq > conversations.read_seq
                """.trimIndent(),
                args = args,
                explicitStatementType = StatementType.INSERT,
            )
        }
    }

    private fun projectChangeBatched(
        transaction: Transaction,
        uids: List<String>,
        operation: MessageProjectionOperation,
        preview: String?,
        now: Long,
    ): Set<String> {
        val message = operation.message
        val lastMessageType = if (operation.operation == MessageOperationType.REVOKE) {
            MessageType.REVOKE.code
        } else {
            message.messageType
        }
        val changed = linkedSetOf<String>()
        uids.chunked(PROJECTION_SQL_BATCH_SIZE).forEach { uidBatch ->
            val recipientRows = uidBatch.joinToString(", ") { "(?::varchar)" }
            val args = buildList<Pair<IColumnType<*>, Any?>> {
                uidBatch.forEach { uid -> add(Conversations.uid.columnType to uid) }
                add(Conversations.lastMessage.columnType to preview)
                add(Conversations.lastMessageType.columnType to lastMessageType)
                add(Conversations.updatedAt.columnType to now)
                add(Conversations.chatId.columnType to message.chatId)
                add(Conversations.lastMsgSeq.columnType to message.serverSeq)
            }
            val updatedUids: List<String> = transaction.execRawSql(
                stmt = """
                    WITH recipients(uid) AS (VALUES $recipientRows),
                    updated AS (
                        UPDATE conversations conversation
                        SET last_message = ?::varchar,
                            last_message_type = ?::integer,
                            version = conversation.version + 1,
                            updated_at = ?::bigint
                        FROM recipients recipient
                        WHERE conversation.uid = recipient.uid
                          AND conversation.chat_id = ?::varchar
                          AND conversation.last_msg_seq = ?::bigint
                        RETURNING conversation.uid
                    )
                    SELECT uid FROM updated ORDER BY uid
                """.trimIndent(),
                args = args,
                explicitStatementType = StatementType.SELECT,
            ) { resultSet: ResultSet ->
                buildList<String> {
                    while (resultSet.next()) add(resultSet.getString("uid"))
                }
            } ?: error("Message projection conversation update returned no result set")
            changed += updatedUids
        }
        return changed
    }

    private fun loadVisibleConversationRows(
        chatId: String,
        uids: Set<String>,
    ): Map<String, ResultRow> {
        val result = linkedMapOf<String, ResultRow>()
        uids.toList().sorted().chunked(PROJECTION_SQL_BATCH_SIZE).forEach { uidBatch ->
            Conversations.selectAll().where {
                (Conversations.chatId eq chatId) and
                    (Conversations.uid inList uidBatch) and
                    (Conversations.isHidden eq false)
            }.orderBy(Conversations.uid to SortOrder.ASC)
                .forEach { row ->
                    check(result.put(row[Conversations.uid], row) == null) {
                        "Message projection returned a duplicate Conversation"
                    }
                }
        }
        return result
    }

    /** 展示信息继续按当前会话批量查询，完整字段映射统一走 ResultRow.toConversation。 */
    private fun buildConversations(
        chatType: Int,
        chatId: String,
        rowsByUid: Map<String, ResultRow>,
    ): Map<String, Conversation> {
        if (rowsByUid.isEmpty()) return emptyMap()
        if (chatType == 3) {
            // "保存的消息"私有会话：服务端固定展示身份，没有对端用户。
            return rowsByUid.mapValues { (_, row) ->
                row.toConversation(chatType = chatType, chatName = SAVED_CHAT_DISPLAY_NAME)
            }
        }
        if (chatType == 2) {
            val group = GroupChats.selectAll().where { GroupChats.chatId eq chatId }.singleOrNull()
            return rowsByUid.mapValues { (_, row) ->
                row.toConversation(chatType = chatType, chatName = group?.get(GroupChats.name))
            }
        }

        val memberUids = GroupMembers.select(GroupMembers.uid).where {
            (GroupMembers.chatId eq chatId) and (GroupMembers.status eq 1)
        }.orderBy(GroupMembers.uid to SortOrder.ASC)
            .map { row -> row[GroupMembers.uid] }
        val usersByUid = if (memberUids.isEmpty()) {
            emptyMap()
        } else {
            Users.selectAll().where { Users.uid inList memberUids }
                .associateBy { row -> row[Users.uid] }
        }
        return rowsByUid.mapValues { (uid, row) ->
            val peerUid = checkNotNull(memberUids.firstOrNull { candidate -> candidate != uid }) {
                "Active personal chat has no opposite member"
            }
            val peer = checkNotNull(usersByUid[peerUid]) { "Active personal chat peer has no User row" }
            row.toConversation(
                chatType = chatType,
                peerUid = peerUid,
                peerRevision = peer[Users.revision],
                chatName = peer[Users.name],
                chatAvatar = peer.toUserAvatar(),
            )
        }
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

private const val PROJECTION_SQL_BATCH_SIZE = 512
