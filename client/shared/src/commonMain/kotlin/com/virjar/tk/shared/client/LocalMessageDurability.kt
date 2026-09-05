package com.virjar.tk.shared.client

import com.virjar.tk.shared.database.AppDatabaseQueries
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.ProtoCodec

/** 持久消息投影的 SQL 序列化。调用方拥有缓存事务。 */
internal class LocalMessageProjectionPersistence(
    private val queries: AppDatabaseQueries,
) {
    fun persist(message: Message) {
        if (message.serverSeq == 0L) {
            val existing = queries.selectMessageById(message.chatId, message.clientMsgId).executeAsOneOrNull()
            if ((existing?.server_seq ?: 0L) > 0L) {
                throw OutgoingMessageConflictException(
                    "local message cannot replace an authoritative server projection",
                )
            }
        }
        val bodyBytes = message.body?.let { ProtoCodec.encode(it) }
        queries.insertMessage(
            message.chatId,
            message.clientMsgId,
            message.serverSeq,
            message.senderUid,
            message.messageType.toLong(),
            message.timestamp,
            message.flags.toLong(),
            bodyBytes,
            message.sendStatus.toLong(),
        )
    }

    /** 调用方在同一恢复事务中选定了这个精确缺失 key；无点读。 */
    fun persistMissingOutgoing(message: Message) {
        check(message.serverSeq == 0L) { "Recovered outgoing projection must remain optimistic" }
        val bodyBytes = message.body?.let { ProtoCodec.encode(it) }
        queries.insertMessageIfAbsent(
            message.chatId,
            message.clientMsgId,
            message.serverSeq,
            message.senderUid,
            message.messageType.toLong(),
            message.timestamp,
            message.flags.toLong(),
            bodyBytes,
            message.sendStatus.toLong(),
        )
    }
}

/** 对可以再次拉取的服务器权威消息的有界回收。 */
internal class LocalAuthoritativeMessageRetention(
    private val queries: AppDatabaseQueries,
    private val limits: LocalMessageRetentionLimits,
    /** 在 prune 事务提交之后运行；刷新与已回收行绑定的投影。 */
    private val onPruned: (chatId: String) -> Unit = {},
) {
    /** 构造时没有租约，且只访问一个有界 chat 集合。 */
    fun catchUp() {
        val candidates = queries.selectAuthoritativeMessageRetentionCandidates(
            retainedCount = limits.retainedCount.toLong(),
            retainedBytes = limits.retainedBytes,
            limit = limits.catchUpChats.toLong(),
        ).executeAsList()
        candidates.forEach(::prune)
    }

    /** 选择与其有界删除一起提交或一起回滚。 */
    fun prune(chatId: String): Boolean {
        var pruned = false
        queries.transaction {
            val metadata = queries.selectAuthoritativeMessageRetentionMetadata(
                chatId = chatId,
                limit = limits.retainedCount.toLong() + 1L,
            ).executeAsList()
            var retainedBytes = 0L
            var retainedCount = 0
            for (row in metadata) {
                val estimatedBytes = row.estimated_bytes
                check(estimatedBytes >= 0L) { "message retention byte estimate must be non-negative" }
                if (
                    retainedCount >= limits.retainedCount ||
                    estimatedBytes > limits.retainedBytes - retainedBytes
                ) {
                    break
                }
                retainedBytes += estimatedBytes
                retainedCount += 1
            }
            if (retainedCount == metadata.size) return@transaction

            val deleteLimit = limits.deleteBatchSize.toLong()
            if (retainedCount == 0) {
                queries.deleteAllRetainableAuthoritativeMessagesBatch(chatId, deleteLimit)
            } else {
                val oldestRetained = metadata[retainedCount - 1]
                queries.deleteOlderRetainableAuthoritativeMessagesBatch(
                    chatId = chatId,
                    oldestRetainedServerSeq = oldestRetained.server_seq,
                    oldestRetainedClientMsgId = oldestRetained.client_msg_id,
                    limit = deleteLimit,
                )
            }
            // 回应是消息投影的附属行：本批删除后立即回收失去消息行的回应，
            // 批删未清空的窗口由下一次 prune 的同一条 orphan 查询收敛。
            queries.deleteOrphanMessageReactions(chatId)
            pruned = true
        }
        if (pruned) onPruned(chatId)
        return pruned
    }
}
