package com.virjar.tk.server.infra.storage

import com.virjar.tk.protocol.body.AttachmentPolicy
import com.virjar.tk.server.domain.message.MessageArchiveCursor
import com.virjar.tk.server.domain.message.MessageArchiveEntry
import com.virjar.tk.server.domain.message.MessageArchivePage
import com.virjar.tk.server.domain.message.MessageArchiveReader
import com.virjar.tk.server.domain.message.MAX_MESSAGE_ARCHIVE_PAGE_SIZE
import com.virjar.tk.server.domain.message.MessageOperationType
import com.virjar.tk.server.domain.message.MessageProjectionOperation
import com.virjar.tk.server.domain.message.MessageProjectionTarget
import com.virjar.tk.server.domain.message.MessageRepository
import com.virjar.tk.protocol.model.Message
import org.rocksdb.*
import org.slf4j.LoggerFactory
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

internal fun interface MessageStoreDatabaseCloser {
    fun close(database: RocksDB)
}

internal fun closeMessageStoreDatabase(database: RocksDB) {
    // RocksDB.close() 刻意吞掉 RocksDBException。生命周期拥有者必须使用
    // 可抛出的 API，这样失败的原生关闭不会被上报为成功。
    database.closeE()
}

private val directMessageStoreDatabaseCloser = MessageStoreDatabaseCloser(::closeMessageStoreDatabase)

/**
 * 基于 RocksDB 的消息存储。
 *
 * Key 设计：
 * - chatSeqIndex: [0x07][4B chatId length][chatId bytes][8B seq BE] → 消息字节
 * - clientMsgIdIndex: [0x04][chatId][clientMsgId] → sender + seq + 首次内容 SHA-256
 * - operationOutbox: [0x05][chatId part][8B seq][8B revision][operation] → 带版本的操作
 * - messageRevision: [0x06][chatId part][8B seq] → 最新操作 revision
 * - attachmentChatIndex: [0x03][path][0x00][chatId][0x00][8B seq] → 空值
 * - chatSequence: [0x08][chatId part] → 最新已分配的持久消息 seq
 *
 * seq high-water 与新消息、幂等索引和 CREATE outbox 同批写入；PostgreSQL Chat.maxSeq 是投影。
 */
class MessageStore(
    private val dbPath: String,
) : MessageRepository, MessageArchiveReader {
    internal constructor(dbPath: String, beforeDatabaseUse: () -> Unit) : this(dbPath) {
        this.beforeDatabaseUse = beforeDatabaseUse
    }

    internal constructor(
        dbPath: String,
        beforeDatabaseUse: () -> Unit,
        databaseCloser: MessageStoreDatabaseCloser,
    ) : this(dbPath, beforeDatabaseUse) {
        this.databaseCloser = databaseCloser
    }

    private val logger = LoggerFactory.getLogger("MessageStore")
    private val records = MessageStorePersistenceCodec
    private var beforeDatabaseUse: () -> Unit = {}
    private var databaseCloser: MessageStoreDatabaseCloser = directMessageStoreDatabaseCloser

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (this.size < prefix.size) return false
        for (i in prefix.indices) {
            if (this[i] != prefix[i]) return false
        }
        return true
    }
    private val lifecycleLock = ReentrantReadWriteLock(true)

    @Volatile
    private var db: RocksDB? = null
    private var terminalLifecycleFailure: Throwable? = null
    val isRunning: Boolean get() = db != null

    fun init() = lifecycleLock.write {
        terminalLifecycleFailure?.let { throw it }
        if (db == null) {
            RocksDB.loadLibrary()
            db = Options().use { options ->
                options
                    .setCreateIfMissing(true)
                    .setWriteBufferSize(16 * 1024 * 1024)
                RocksDB.open(options, dbPath)
            }
            logger.info("MessageStore initialized at $dbPath")
        }
    }

    fun close() = lifecycleLock.write {
        terminalLifecycleFailure?.let { throw it }
        val opened = db ?: return@write
        db = null
        try {
            databaseCloser.close(opened)
        } catch (failure: Throwable) {
            terminalLifecycleFailure = failure
            throw failure
        }
    }

    /** 分配下一个聊天序号，并原子地发布每条权威消息事实。 */
    @Synchronized
    override fun appendMessage(
        message: Message,
        idempotencyCandidate: Message,
        projectionTarget: MessageProjectionTarget,
    ): Message = withDatabase { database ->
        require(message.serverSeq == 0L) { "新消息的 serverSeq 必须由 MessageStore 分配" }
        require(message.flags and Message.FLAG_REVOKED == 0) {
            "新消息不能以已撤回状态写入权威存储"
        }
        val clientMsgIdKey = records.buildClientMsgIdKey(message.chatId, message.clientMsgId)
        val clientContentHash = records.hashClientContent(idempotencyCandidate)
        findIdempotentMessage(database, message, clientContentHash)?.let { return@withDatabase it }

        val sequenceKey = records.buildChatSequenceKey(message.chatId)
        val previousSeq = database.get(sequenceKey)?.let(records::decodeSeq) ?: 0L
        check(previousSeq < Long.MAX_VALUE) { "消息序号已耗尽: chatId=${message.chatId}" }
        val stored = message.copy(serverSeq = previousSeq + 1L)
        storeNewMessage(
            database = database,
            message = stored,
            projectionTarget = projectionTarget,
            clientMsgIdKey = clientMsgIdKey,
            clientContentHash = clientContentHash,
            sequenceKey = sequenceKey,
            sequenceHighWater = stored.serverSeq,
        )
        stored
    }

    /**
     * 存储与归档测试使用的显式序号写入器。产品消息准入始终
     * 使用 [appendMessage]；保留此狭窄内部缝隙使损坏/游标测试能够
     * 构造非生产的键布局，而不把序号选择暴露给领域层。
     */
    @Synchronized
    internal fun storeMessage(
        message: Message,
        idempotencyCandidate: Message,
        projectionTarget: MessageProjectionTarget,
    ): Long = withDatabase { database ->
        require(message.serverSeq > 0L) { "测试消息 serverSeq 必须为正数" }
        require(message.flags and Message.FLAG_REVOKED == 0) {
            "新消息不能以已撤回状态写入权威存储"
        }
        val clientMsgIdKey = records.buildClientMsgIdKey(message.chatId, message.clientMsgId)
        val clientContentHash = records.hashClientContent(idempotencyCandidate)
        findIdempotentMessage(database, message, clientContentHash)?.let { return@withDatabase it.serverSeq }
        val sequenceKey = records.buildChatSequenceKey(message.chatId)
        val previousSeq = database.get(sequenceKey)?.let(records::decodeSeq) ?: 0L
        storeNewMessage(
            database = database,
            message = message,
            projectionTarget = projectionTarget,
            clientMsgIdKey = clientMsgIdKey,
            clientContentHash = clientContentHash,
            sequenceKey = sequenceKey,
            sequenceHighWater = maxOf(previousSeq, message.serverSeq),
        )
        message.serverSeq
    }

    private fun storeNewMessage(
        database: RocksDB,
        message: Message,
        projectionTarget: MessageProjectionTarget,
        clientMsgIdKey: ByteArray,
        clientContentHash: ByteArray,
        sequenceKey: ByteArray,
        sequenceHighWater: Long,
    ) {
        val seq = message.serverSeq

        val msgBytes = records.encodeMessage(message)
        require(msgBytes.size <= records.MAX_MESSAGE_BYTES) { "消息编码超过持久化上限" }
        val chatSeqKey = records.buildChatSeqKey(message.chatId, seq)
        check(database.get(chatSeqKey) == null) {
            "消息序号已被占用: chatId=${message.chatId}, seq=$seq"
        }
        val operation = records.newProjectionOperation(
            operation = MessageOperationType.CREATE,
            revision = records.INITIAL_REVISION,
            message = message,
            projectionTarget = projectionTarget,
        )

        // 序号、消息、幂等索引与投影可靠发件箱是一个 sync-WAL 批次。
        // 因此进程死亡只会留下"完全没有任何序号"或"一条完整可恢复的
        // 权威消息"两种情况；Conversation/Lucene/事件仍是派生的投影。
        WriteBatch().use { batch ->
            batch.put(sequenceKey, records.encodeSeq(sequenceHighWater))
            batch.put(chatSeqKey, msgBytes)
            batch.put(clientMsgIdKey, records.encodeIdempotencyValue(message.senderUid, seq, clientContentHash))
            batch.put(
                records.buildRevisionKey(message.chatId, seq),
                records.encodeSeq(records.INITIAL_REVISION),
            )
            batch.put(records.buildOperationKey(operation), records.encodeProjectionOperation(operation))
            AttachmentPolicy.attachments(message).forEach { attachment ->
                batch.put(records.buildAttachmentIndexKey(attachment.path, message.chatId, seq), records.EMPTY_VALUE)
            }
            authoritativeRocksWriteOptions().use { options -> database.write(options, batch) }
        }
    }

    override fun getMessage(chatId: String, seq: Long): Message? =
        withDatabaseOrNull { database -> getMessageFrom(database, chatId, seq) }

    override fun findIdempotentMessage(candidate: Message): Message? = withDatabaseOrNull { database ->
        findIdempotentMessage(
            database,
            candidate,
            records.hashClientContent(candidate),
        )
    }

    private fun findIdempotentMessage(
        database: RocksDB,
        candidate: Message,
        clientContentHash: ByteArray,
    ): Message? {
        val key = records.buildClientMsgIdKey(candidate.chatId, candidate.clientMsgId)
        val indexValue = database.get(key) ?: return null
        val existing = records.decodeIdempotencyValue(indexValue)
        require(existing.senderUid == candidate.senderUid) {
            "clientMsgId 已被当前会话的其他发送者使用"
        }
        require(existing.clientContentHash.contentEquals(clientContentHash)) {
            "clientMsgId 已用于不同消息内容"
        }
        val indexedMessage = getMessageFrom(database, candidate.chatId, existing.serverSeq)
            ?: throw IllegalStateException(
                "消息幂等索引损坏: chatId=${candidate.chatId}, seq=${existing.serverSeq}",
            )
        check(indexedMessage.clientMsgId == candidate.clientMsgId && indexedMessage.senderUid == candidate.senderUid) {
            "消息幂等索引身份损坏: chatId=${candidate.chatId}, seq=${existing.serverSeq}"
        }
        return indexedMessage
    }

    override fun getHistory(chatId: String, fromSeq: Long, limit: Int, forward: Boolean): List<Message> =
        withDatabaseOrNull { database ->
            require(fromSeq >= 0L) { "消息历史起始序号不能为负数" }
            require(limit in 1..MAX_HISTORY_PAGE_SIZE) {
                "消息历史分页大小必须在 1..$MAX_HISTORY_PAGE_SIZE 之间"
            }
            val messages = mutableListOf<Message>()
            val messagePrefix = records.buildChatSeqPrefix(chatId)

            // 起点包含在结果中；只有倒序的 fromSeq=0 表示从最新消息开始。
            val startSeq = if (!forward && fromSeq == 0L) Long.MAX_VALUE else fromSeq
            val startKey = records.buildChatSeqKey(chatId, startSeq)
            database.newIterator().use { iterator ->
                if (forward) iterator.seek(startKey) else iterator.seekForPrev(startKey)
                while (iterator.isValid && messages.size < limit) {
                    val key = iterator.key()
                    if (!key.startsWith(messagePrefix)) break
                    require(key.size == messagePrefix.size + records.SEQ_BYTES) {
                        "消息序号索引格式损坏"
                    }
                    val seq = records.decodeSeq(key.copyOfRange(messagePrefix.size, key.size))
                    messages.add(records.decodeStoredMessage(iterator.value(), chatId, seq))
                    if (forward) iterator.next() else iterator.prev()
                }
                iterator.status()
            }

            messages
        }.orEmpty()

    /**
     * 按服务器启动期间使用的不可变 Rocks 键顺序扫描当前消息值。
     * 字节边界在 Message 解码之前由原始 key/value/revision 字节决定；
     * 一个超大的头项仍会进入页面，使每个返回的续游标都能继续推进。
     */
    override fun readArchivePage(
        after: MessageArchiveCursor?,
        limit: Int,
        maxEncodedBytes: Long,
    ): MessageArchivePage = withDatabase { database ->
        require(limit in 1..MAX_MESSAGE_ARCHIVE_PAGE_SIZE) {
            "Message archive page size must be in 1..$MAX_MESSAGE_ARCHIVE_PAGE_SIZE"
        }
        require(maxEncodedBytes > 0L) { "Message archive page byte budget must be positive" }

        val entries = ArrayList<MessageArchiveEntry>(limit)
        var encodedBytes = 0L
        database.newIterator().use { iterator ->
            if (after == null) {
                iterator.seek(records.MESSAGE_PREFIX)
            } else {
                val cursorKey = records.buildChatSeqKey(after.chatId, after.serverSeq)
                iterator.seek(cursorKey)
                iterator.status()
                check(iterator.isValid && iterator.key().contentEquals(cursorKey)) {
                    "Message archive cursor no longer identifies an authoritative record"
                }
                iterator.next()
            }

            while (
                iterator.isValid &&
                entries.size < limit &&
                iterator.key().startsWith(records.MESSAGE_PREFIX)
            ) {
                val key = iterator.key()
                val cursor = records.decodeChatSeqKey(key)
                val messageBytes = iterator.value()
                require(messageBytes.size <= records.MAX_MESSAGE_BYTES) { "消息编码超过持久化上限" }
                val revisionKey = records.buildRevisionKey(cursor.chatId, cursor.serverSeq)
                val revisionBytes = database.get(revisionKey)
                    ?: throw IllegalStateException(
                        "消息 revision 索引损坏: chatId=${cursor.chatId}, seq=${cursor.serverSeq}",
                    )
                val entryBytes = Math.addExact(
                    Math.addExact(
                        Math.addExact(key.size.toLong(), messageBytes.size.toLong()),
                        revisionKey.size.toLong(),
                    ),
                    revisionBytes.size.toLong(),
                )
                if (
                    entries.isNotEmpty() &&
                    (encodedBytes >= maxEncodedBytes || entryBytes > maxEncodedBytes - encodedBytes)
                ) {
                    break
                }

                val revision = records.decodeSeq(revisionBytes)
                require(revision > 0L) { "消息 revision 必须为正数" }
                val message = records.decodeStoredMessage(messageBytes, cursor.chatId, cursor.serverSeq)
                entries += MessageArchiveEntry(message, revision)
                encodedBytes = Math.addExact(encodedBytes, entryBytes)
                iterator.next()
            }
            iterator.status()

            val hasMore = iterator.isValid && iterator.key().startsWith(records.MESSAGE_PREFIX)
            MessageArchivePage(
                entries = entries,
                nextCursor = entries.lastOrNull()?.cursor?.takeIf { hasMore },
                encodedBytes = encodedBytes,
            )
        }
    }

    @Synchronized
    override fun updateMessage(
        chatId: String,
        seq: Long,
        message: Message,
        operation: MessageOperationType,
        projectionTarget: MessageProjectionTarget,
    ): MessageProjectionOperation = withDatabase { database ->
        require(operation != MessageOperationType.CREATE) { "CREATE must use appendMessage" }
        require(seq > 0L) { "消息 serverSeq 必须为正数" }
        require(
            operation != MessageOperationType.REVOKE ||
                message.flags and Message.FLAG_REVOKED != 0,
        ) { "REVOKE operation must persist the revoked message flag" }
        val key = records.buildChatSeqKey(chatId, seq)
        val previous = database.get(key)?.let { records.decodeStoredMessage(it, chatId, seq) }
            ?: throw IllegalArgumentException("消息不存在")
        require(message.chatId == chatId && message.serverSeq == seq) { "消息身份不可修改" }
        require(message.clientMsgId == previous.clientMsgId && message.senderUid == previous.senderUid) {
            "消息发送者或客户端身份不可修改"
        }
        val revisionKey = records.buildRevisionKey(chatId, seq)
        val currentRevision = database.get(revisionKey)?.let(records::decodeSeq)
            ?: throw IllegalStateException("消息 revision 索引损坏: chatId=$chatId, seq=$seq")
        check(currentRevision < Long.MAX_VALUE) { "消息 revision 已耗尽" }
        val nextRevision = currentRevision + 1L
        val projection = records.newProjectionOperation(operation, nextRevision, message, projectionTarget)
        WriteBatch().use { batch ->
            val encodedMessage = records.encodeMessage(message)
            require(encodedMessage.size <= records.MAX_MESSAGE_BYTES) { "消息编码超过持久化上限" }
            batch.put(key, encodedMessage)
            AttachmentPolicy.attachments(previous).forEach { attachment ->
                batch.delete(records.buildAttachmentIndexKey(attachment.path, chatId, seq))
            }
            if (operation != MessageOperationType.REVOKE) {
                AttachmentPolicy.attachments(message).forEach { attachment ->
                    batch.put(records.buildAttachmentIndexKey(attachment.path, chatId, seq), records.EMPTY_VALUE)
                }
            }
            batch.put(revisionKey, records.encodeSeq(nextRevision))
            batch.put(records.buildOperationKey(projection), records.encodeProjectionOperation(projection))
            authoritativeRocksWriteOptions().use { options -> database.write(options, batch) }
        }
        projection
    }

    override fun isProjectionPending(operation: MessageProjectionOperation): Boolean = withDatabase { database ->
        database.get(records.buildOperationKey(operation)) != null
    }

    override fun getPendingProjectionOperations(
        limit: Int,
        maxEncodedBytes: Long,
    ): List<MessageProjectionOperation> = withDatabase { database ->
        readPendingProjectionOperations(database, records.OPERATION_PREFIX, limit, maxEncodedBytes)
    }

    override fun getPendingProjectionOperations(
        chatId: String,
        seq: Long,
        limit: Int,
        maxEncodedBytes: Long,
    ): List<MessageProjectionOperation> = withDatabase { database ->
        readPendingProjectionOperations(
            database,
            records.buildOperationMessagePrefix(chatId, seq),
            limit,
            maxEncodedBytes,
        )
    }

    private fun readPendingProjectionOperations(
        database: RocksDB,
        prefix: ByteArray,
        limit: Int,
        maxEncodedBytes: Long,
    ): List<MessageProjectionOperation> {
        require(limit > 0) { "Projection page size must be positive" }
        require(maxEncodedBytes > 0L) { "Projection page byte budget must be positive" }
        val pending = mutableListOf<MessageProjectionOperation>()
        var encodedBytes = 0L
        database.newIterator().use { iterator ->
            iterator.seek(prefix)
            while (iterator.isValid && pending.size < limit && iterator.key().startsWith(prefix)) {
                val key = iterator.key()
                val encoded = iterator.value()
                val operationBytes = encoded.size.toLong()
                // 在解码之前先检查编码后的 Rocks 值。单个操作可能
                // 大于页面预算；返回该头项是继续推进所必需的。
                if (
                    pending.isNotEmpty() &&
                    (encodedBytes >= maxEncodedBytes || operationBytes > maxEncodedBytes - encodedBytes)
                ) {
                    break
                }
                pending += records.decodeProjectionEntry(key, encoded)
                encodedBytes += operationBytes
                iterator.next()
            }
            iterator.status()
        }
        return pending
    }

    @Synchronized
    override fun markProjectionComplete(operation: MessageProjectionOperation) {
        withDatabase { database ->
            val key = records.buildOperationKey(operation)
            val current = database.get(key) ?: return@withDatabase
            check(current.contentEquals(records.encodeProjectionOperation(operation))) {
                "Message operation changed before exact acknowledgement: " +
                    "${operation.projectionKey}@${operation.revision}"
            }
            database.delete(key)
        }
    }

    override fun getAttachmentChatIds(path: String): Set<String> = withDatabaseOrNull { database ->
        val prefix = records.buildAttachmentPathPrefix(path)
        val chatIds = linkedSetOf<String>()
        database.newIterator().use { iterator ->
            iterator.seek(prefix)
            while (iterator.isValid && iterator.key().startsWith(prefix)) {
                val key = iterator.key()
                // 尾部定界符 + 8 字节 seq 位于 chat id 之外
                val chatEnd = key.size - records.KEY_SEPARATOR_SIZE - records.SEQ_BYTES
                require(chatEnd >= prefix.size && key[chatEnd] == records.KEY_SEPARATOR_BYTE) {
                    "附件反向索引格式损坏"
                }
                val seq = records.decodeSeq(
                    key.copyOfRange(chatEnd + records.KEY_SEPARATOR_SIZE, key.size),
                )
                require(seq > 0L) { "附件反向索引消息序号损坏" }
                chatIds += key.copyOfRange(prefix.size, chatEnd)
                    .decodeToString(throwOnInvalidSequence = true)
                iterator.next()
            }
            iterator.status()
        }
        chatIds
    }.orEmpty()

    override fun isAttachmentReferencedByAny(path: String, chatIds: Set<String>): Boolean {
        if (chatIds.isEmpty()) return false
        return withDatabaseOrNull { database ->
            database.newIterator().use { iterator ->
                chatIds.sorted().any { chatId ->
                    val prefix = records.buildAttachmentChatPrefix(path, chatId)
                    iterator.seek(prefix)
                    iterator.isValid && iterator.key().startsWith(prefix)
                }.also { iterator.status() }
            }
        } ?: false
    }

    override fun getReferencedAttachmentPaths(paths: Set<String>): Set<String> {
        if (paths.isEmpty()) return emptySet()
        return withDatabaseOrNull { database ->
            val referenced = linkedSetOf<String>()
            database.newIterator().use { iterator ->
                paths.sorted().forEach { path ->
                    val prefix = records.buildAttachmentPathPrefix(path)
                    iterator.seek(prefix)
                    if (iterator.isValid && iterator.key().startsWith(prefix)) referenced += path
                }
                iterator.status()
            }
            referenced
        }.orEmpty()
    }

    private inline fun <T> withDatabase(block: (RocksDB) -> T): T = lifecycleLock.read {
        val database = db ?: throw IllegalStateException("MessageStore not initialized")
        beforeDatabaseUse()
        block(database)
    }

    private inline fun <T> withDatabaseOrNull(block: (RocksDB) -> T): T? = lifecycleLock.read {
        val database = db ?: return@read null
        beforeDatabaseUse()
        block(database)
    }

    private fun getMessageFrom(database: RocksDB, chatId: String, seq: Long): Message? {
        if (seq <= 0L) return null
        val bytes = database.get(records.buildChatSeqKey(chatId, seq)) ?: return null
        return records.decodeStoredMessage(bytes, chatId, seq)
    }

    companion object {
        private const val MAX_HISTORY_PAGE_SIZE = 1_000
    }
}
