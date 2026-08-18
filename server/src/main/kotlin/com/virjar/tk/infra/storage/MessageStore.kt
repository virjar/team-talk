package com.virjar.tk.infra.storage

import com.virjar.tk.model.Message
import com.virjar.tk.domain.message.MessageRepository
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.PacketBuffer
import io.netty.buffer.Unpooled
import org.rocksdb.*
import org.slf4j.LoggerFactory
/**
 * 基于 RocksDB 的消息存储。
 *
 * Key 设计：
 * - chatSeqIndex: [chatId bytes][8B seq BE] → message bytes（按 chat+seq 有序扫描）
 * - clientMsgIdIndex: [0x01][clientMsgId bytes] → chatId + seq（去重）
 * - projectionOutbox: [0x02][chatId bytes][8B seq BE] → message bytes
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
    override fun storeMessage(message: Message): Long {
        val database = db ?: throw IllegalStateException("MessageStore not initialized")
        val seq = message.serverSeq

        // 检查 clientMsgId 去重
        val clientMsgIdKey = buildClientMsgIdKey(message.clientMsgId)
        val existingValue = database.get(clientMsgIdKey)
        if (existingValue != null) {
            return decodeSeq(existingValue.copyOfRange(existingValue.size - 8, existingValue.size))
        }

        val msgBytes = encodeMessage(message)
        val chatSeqKey = buildChatSeqKey(message.chatId, seq)
        val projectionKey = buildProjectionKey(message.chatId, seq)

        // 消息、幂等索引与待投影标记必须同批原子提交。跨库投影完成前
        // outbox 始终保留，进程重启或客户端幂等重试都能继续补偿。
        WriteBatch().use { batch ->
            batch.put(chatSeqKey, msgBytes)
            batch.put(clientMsgIdKey, message.chatId.encodeToByteArray() + encodeSeq(seq))
            batch.put(projectionKey, msgBytes)
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

    /**
     * 通过 clientMsgId 获取 seq（用于快速去重判断，无需反序列化消息）。
     */
    override fun getSeqByClientMsgId(clientMsgId: String): Long? {
        val database = db ?: return null
        val key = buildClientMsgIdKey(clientMsgId)
        val indexValue = database.get(key) ?: return null
        return decodeSeq(indexValue.copyOfRange(indexValue.size - 8, indexValue.size))
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

    override fun updateMessage(chatId: String, seq: Long, message: Message) {
        val database = db ?: return
        val key = buildChatSeqKey(chatId, seq)
        database.put(key, encodeMessage(message))
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

    private fun buildClientMsgIdKey(clientMsgId: String): ByteArray {
        val prefix = byteArrayOf(0x01)
        val idBytes = clientMsgId.encodeToByteArray()
        return prefix + idBytes
    }

    private fun buildProjectionKey(chatId: String, seq: Long): ByteArray =
        PROJECTION_PREFIX + buildChatSeqKey(chatId, seq)

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
        private val PROJECTION_PREFIX = byteArrayOf(0x02)
    }
}
