package com.virjar.tk.infra.storage

import com.virjar.tk.body.AttachmentPolicy
import com.virjar.tk.model.Message
import com.virjar.tk.domain.message.MessageRepository
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.PacketBuffer
import io.netty.buffer.Unpooled
import org.rocksdb.*
import org.slf4j.LoggerFactory
import java.security.MessageDigest
/**
 * 基于 RocksDB 的消息存储。
 *
 * Key 设计：
 * - chatSeqIndex: [chatId bytes][8B seq BE] → message bytes（按 chat+seq 有序扫描）
 * - clientMsgIdIndex: [0x04][chatId][clientMsgId] → sender + seq + 首次内容 SHA-256
 * - projectionOutbox: [0x02][chatId bytes][8B seq BE] → message bytes
 * - attachmentChatIndex: [0x03][path][0x00][chatId][0x00][8B seq] → empty
 *
 * 注意：seq 由 ChatStore 统一分配，本类不自增 seq。
 */
class MessageStore(
    private val dbPath: String,
) : MessageRepository {
    private val logger = LoggerFactory.getLogger("MessageStore")

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (this.size < prefix.size) return false
        for (i in prefix.indices) {
            if (this[i] != prefix[i]) return false
        }
        return true
    }
    val isRunning: Boolean get() = db != null
    private var db: RocksDB? = null

    fun init() {
        RocksDB.loadLibrary()
        val options = Options().setCreateIfMissing(true)
            .setWriteBufferSize(16 * 1024 * 1024)
        db = RocksDB.open(options, dbPath)
        logger.info("MessageStore initialized at $dbPath")
    }

    fun close() {
        db?.close()
    }

    /**
     * 存储消息。使用 message 中已有的 serverSeq（由 ChatStore 分配）。
     * 返回存储的 serverSeq。如果 clientMsgId 已存在则返回已有 seq（幂等）。
     */
    @Synchronized
    override fun storeMessage(message: Message, idempotencyCandidate: Message): Long {
        val database = db ?: throw IllegalStateException("MessageStore not initialized")
        val seq = message.serverSeq

        // clientMsgId 由客户端生成，但在整个 chat 内代表唯一消息身份。
        // sender 放在 value 中一起持久化，防止另一个成员复用同一身份。
        val clientMsgIdKey = buildClientMsgIdKey(message.chatId, message.clientMsgId)
        val clientContentHash = hashClientContent(idempotencyCandidate)
        val existingValue = database.get(clientMsgIdKey)
        if (existingValue != null) {
            val existing = decodeIdempotencyValue(existingValue)
            require(existing.senderUid == message.senderUid) {
                "clientMsgId 已被当前会话的其他发送者使用"
            }
            require(existing.clientContentHash.contentEquals(clientContentHash)) {
                "clientMsgId 已用于不同消息内容"
            }
            getMessage(message.chatId, existing.serverSeq)
                ?: throw IllegalStateException(
                    "消息幂等索引损坏: chatId=${message.chatId}, seq=${existing.serverSeq}",
                )
            return existing.serverSeq
        }

        val msgBytes = encodeMessage(message)
        val chatSeqKey = buildChatSeqKey(message.chatId, seq)
        val projectionKey = buildProjectionKey(message.chatId, seq)

        // 消息、幂等索引与待投影标记必须同批原子提交。跨库投影完成前
        // outbox 始终保留，进程重启或客户端幂等重试都能继续补偿。
        WriteBatch().use { batch ->
            batch.put(chatSeqKey, msgBytes)
            batch.put(clientMsgIdKey, encodeIdempotencyValue(message.senderUid, seq, clientContentHash))
            batch.put(projectionKey, msgBytes)
            AttachmentPolicy.attachments(message).forEach { attachment ->
                batch.put(buildAttachmentIndexKey(attachment.path, message.chatId, seq), EMPTY_VALUE)
            }
            WriteOptions().use { options -> database.write(options, batch) }
        }

        return seq
    }

    override fun getMessage(chatId: String, seq: Long): Message? {
        val database = db ?: return null
        val key = buildChatSeqKey(chatId, seq)
        val bytes = database.get(key) ?: return null
        return decodeMessage(bytes)
    }

    override fun findIdempotentMessage(candidate: Message): Message? {
        val database = db ?: return null
        val key = buildClientMsgIdKey(candidate.chatId, candidate.clientMsgId)
        val indexValue = database.get(key) ?: return null
        val existing = decodeIdempotencyValue(indexValue)
        require(existing.senderUid == candidate.senderUid) {
            "clientMsgId 已被当前会话的其他发送者使用"
        }
        require(existing.clientContentHash.contentEquals(hashClientContent(candidate))) {
            "clientMsgId 已用于不同消息内容"
        }
        return getMessage(candidate.chatId, existing.serverSeq)
    }

    override fun getHistory(chatId: String, fromSeq: Long, limit: Int, forward: Boolean): List<Message> {
        val database = db ?: return emptyList()
        val messages = mutableListOf<Message>()
        val chatIdBytes = chatId.encodeToByteArray()

        if (forward) {
            // 从 fromSeq 开始向后
            val startKey = buildChatSeqKey(chatId, fromSeq)
            val iterator = database.newIterator()
            iterator.seek(startKey)
            while (iterator.isValid && messages.size < limit) {
                val key = iterator.key()
                if (!key.startsWith(chatIdBytes)) break
                messages.add(decodeMessage(iterator.value()))
                iterator.next()
            }
            iterator.close()
        } else {
            // 从 fromSeq 开始向前（更早的消息）
            // fromSeq=0 表示获取最新消息，使用 MAX_VALUE 作为起点
            val effectiveSeq = if (fromSeq == 0L) Long.MAX_VALUE else fromSeq
            val startKey = buildChatSeqKey(chatId, effectiveSeq)
            val iterator = database.newIterator()
            iterator.seekForPrev(startKey)
            while (iterator.isValid && messages.size < limit) {
                val key = iterator.key()
                if (!key.startsWith(chatIdBytes)) break
                messages.add(decodeMessage(iterator.value()))
                iterator.prev()
            }
            iterator.close()
        }

        return messages
    }

    @Synchronized
    override fun updateMessage(chatId: String, seq: Long, message: Message) {
        val database = db ?: return
        val key = buildChatSeqKey(chatId, seq)
        val previous = database.get(key)?.let(::decodeMessage)
        WriteBatch().use { batch ->
            batch.put(key, encodeMessage(message))
            previous?.let { old ->
                AttachmentPolicy.attachments(old).forEach { attachment ->
                    batch.delete(buildAttachmentIndexKey(attachment.path, chatId, seq))
                }
            }
            AttachmentPolicy.attachments(message).forEach { attachment ->
                batch.put(buildAttachmentIndexKey(attachment.path, chatId, seq), EMPTY_VALUE)
            }
            WriteOptions().use { options -> database.write(options, batch) }
        }
    }

    override fun isProjectionPending(chatId: String, seq: Long): Boolean {
        val database = db ?: return false
        return database.get(buildProjectionKey(chatId, seq)) != null
    }

    override fun getPendingProjections(limit: Int): List<Message> {
        val database = db ?: return emptyList()
        val pending = mutableListOf<Message>()
        database.newIterator().use { iterator ->
            iterator.seek(PROJECTION_PREFIX)
            while (iterator.isValid && pending.size < limit && iterator.key().startsWith(PROJECTION_PREFIX)) {
                pending += decodeMessage(iterator.value())
                iterator.next()
            }
        }
        return pending
    }

    override fun markProjectionComplete(chatId: String, seq: Long) {
        db?.delete(buildProjectionKey(chatId, seq))
    }

    override fun getAttachmentChatIds(path: String): Set<String> {
        val database = db ?: return emptySet()
        val prefix = buildAttachmentPathPrefix(path)
        val chatIds = linkedSetOf<String>()
        database.newIterator().use { iterator ->
            iterator.seek(prefix)
            while (iterator.isValid && iterator.key().startsWith(prefix)) {
                val key = iterator.key()
                // trailing delimiter + 8-byte seq are outside the chat id
                val chatEnd = key.size - 9
                if (chatEnd >= prefix.size) {
                    chatIds += key.copyOfRange(prefix.size, chatEnd).decodeToString()
                }
                iterator.next()
            }
        }
        return chatIds
    }

    private fun buildChatSeqKey(chatId: String, seq: Long): ByteArray {
        val chatIdBytes = chatId.encodeToByteArray()
        val key = ByteArray(chatIdBytes.size + 8)
        System.arraycopy(chatIdBytes, 0, key, 0, chatIdBytes.size)
        // Big-endian seq for correct lexicographic ordering
        key[chatIdBytes.size] = (seq ushr 56).toByte()
        key[chatIdBytes.size + 1] = (seq ushr 48).toByte()
        key[chatIdBytes.size + 2] = (seq ushr 40).toByte()
        key[chatIdBytes.size + 3] = (seq ushr 32).toByte()
        key[chatIdBytes.size + 4] = (seq ushr 24).toByte()
        key[chatIdBytes.size + 5] = (seq ushr 16).toByte()
        key[chatIdBytes.size + 6] = (seq ushr 8).toByte()
        key[chatIdBytes.size + 7] = seq.toByte()
        return key
    }

    private fun buildClientMsgIdKey(chatId: String, clientMsgId: String): ByteArray =
        IDEMPOTENCY_PREFIX + encodeKeyPart(chatId) + encodeKeyPart(clientMsgId)

    private fun encodeIdempotencyValue(senderUid: String, seq: Long, clientContentHash: ByteArray): ByteArray {
        require(clientContentHash.size == SHA_256_LENGTH)
        return encodeKeyPart(senderUid) + encodeSeq(seq) + clientContentHash
    }

    private fun decodeIdempotencyValue(value: ByteArray): IdempotencyRecord {
        require(value.size >= KEY_PART_HEADER_LENGTH + 8 + SHA_256_LENGTH) { "消息幂等索引格式损坏" }
        val senderLength = decodeKeyPartLength(value)
        require(senderLength <= value.size - KEY_PART_HEADER_LENGTH - 8 - SHA_256_LENGTH) {
            "消息幂等索引格式损坏"
        }
        val seqOffset = KEY_PART_HEADER_LENGTH + senderLength
        require(seqOffset + 8 + SHA_256_LENGTH == value.size) { "消息幂等索引格式损坏" }
        return IdempotencyRecord(
            senderUid = value.copyOfRange(KEY_PART_HEADER_LENGTH, seqOffset).decodeToString(),
            serverSeq = decodeSeq(value.copyOfRange(seqOffset, seqOffset + 8)),
            clientContentHash = value.copyOfRange(seqOffset + 8, value.size),
        )
    }

    private fun decodeKeyPartLength(value: ByteArray): Int {
        val length = ((value[0].toLong() and 0xFF) shl 24) or
            ((value[1].toLong() and 0xFF) shl 16) or
            ((value[2].toLong() and 0xFF) shl 8) or
            (value[3].toLong() and 0xFF)
        require(length <= Int.MAX_VALUE) { "消息幂等索引格式损坏" }
        return length.toInt()
    }

    private data class IdempotencyRecord(
        val senderUid: String,
        val serverSeq: Long,
        val clientContentHash: ByteArray,
    )

    private fun encodeKeyPart(value: String): ByteArray {
        val bytes = value.encodeToByteArray()
        val size = bytes.size
        return byteArrayOf(
            (size ushr 24).toByte(),
            (size ushr 16).toByte(),
            (size ushr 8).toByte(),
            size.toByte(),
        ) + bytes
    }

    private fun buildProjectionKey(chatId: String, seq: Long): ByteArray =
        PROJECTION_PREFIX + buildChatSeqKey(chatId, seq)

    private fun buildAttachmentPathPrefix(path: String): ByteArray =
        ATTACHMENT_PREFIX + path.encodeToByteArray() + KEY_SEPARATOR

    private fun buildAttachmentIndexKey(path: String, chatId: String, seq: Long): ByteArray =
        buildAttachmentPathPrefix(path) + chatId.encodeToByteArray() + KEY_SEPARATOR + encodeSeq(seq)

    private fun decodeSeqFromKey(key: ByteArray, offset: Int): Long {
        return ((key[offset].toLong() and 0xFF) shl 56) or
                ((key[offset + 1].toLong() and 0xFF) shl 48) or
                ((key[offset + 2].toLong() and 0xFF) shl 40) or
                ((key[offset + 3].toLong() and 0xFF) shl 32) or
                ((key[offset + 4].toLong() and 0xFF) shl 24) or
                ((key[offset + 5].toLong() and 0xFF) shl 16) or
                ((key[offset + 6].toLong() and 0xFF) shl 8) or
                (key[offset + 7].toLong() and 0xFF)
    }

    private fun encodeSeq(seq: Long): ByteArray {
        val bytes = ByteArray(8)
        bytes[0] = (seq ushr 56).toByte()
        bytes[1] = (seq ushr 48).toByte()
        bytes[2] = (seq ushr 40).toByte()
        bytes[3] = (seq ushr 32).toByte()
        bytes[4] = (seq ushr 24).toByte()
        bytes[5] = (seq ushr 16).toByte()
        bytes[6] = (seq ushr 8).toByte()
        bytes[7] = seq.toByte()
        return bytes
    }

    private fun decodeSeq(bytes: ByteArray): Long {
        return ((bytes[0].toLong() and 0xFF) shl 56) or
                ((bytes[1].toLong() and 0xFF) shl 48) or
                ((bytes[2].toLong() and 0xFF) shl 40) or
                ((bytes[3].toLong() and 0xFF) shl 32) or
                ((bytes[4].toLong() and 0xFF) shl 24) or
                ((bytes[5].toLong() and 0xFF) shl 16) or
                ((bytes[6].toLong() and 0xFF) shl 8) or
                (bytes[7].toLong() and 0xFF)
    }

    /** 服务端分配字段不参与首次请求摘要；编辑消息不会改变这里保存的摘要。 */
    private fun hashClientContent(message: Message): ByteArray = MessageDigest.getInstance("SHA-256").digest(
        encodeMessage(
            message.copy(
                serverSeq = 0,
                timestamp = 0,
                flags = 0,
                sendStatus = Message.SEND_STATUS_SENT,
                uploadProgress = 0f,
            ),
        ),
    )

    private fun encodeMessage(message: Message): ByteArray {
        val byteBuf = Unpooled.buffer()
        val buf = PacketBuffer(byteBuf)
        message.writeTo(buf)
        val bytes = ByteArray(byteBuf.readableBytes())
        byteBuf.readBytes(bytes)
        byteBuf.release()
        return bytes
    }

    private fun decodeMessage(bytes: ByteArray): Message {
        val byteBuf = Unpooled.wrappedBuffer(bytes)
        val buf = PacketBuffer(byteBuf)
        return Message.readFrom(buf)
    }

    companion object {
        // 0x01 是旧的 sender-scoped 索引。新 prefix 避免与旧 key 空间混用；
        // 当前尚未发布，测试数据可在此破坏性身份语义升级时清理。
        private val IDEMPOTENCY_PREFIX = byteArrayOf(0x04)
        private val PROJECTION_PREFIX = byteArrayOf(0x02)
        private val ATTACHMENT_PREFIX = byteArrayOf(0x03)
        private val KEY_SEPARATOR = byteArrayOf(0x00)
        private val EMPTY_VALUE = byteArrayOf()
        private const val KEY_PART_HEADER_LENGTH = 4
        private const val SHA_256_LENGTH = 32
    }
}
