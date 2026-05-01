package com.virjar.tk.db

import com.virjar.tk.env.ThreadIOGuard
import com.virjar.tk.protocol.PacketType
import com.virjar.tk.protocol.payload.Message
import com.virjar.tk.protocol.payload.MessageBody
import com.virjar.tk.protocol.payload.MessageHeader
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.rocksdb.*
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Serializable
data class MessageMeta(
    val flags: Int = 0,
    val deleted: Boolean = false,
    val pinned: Boolean = false,
    val expireAt: Long? = null,
)

class MessageStore(private val dbPath: String) {
    private val logger = LoggerFactory.getLogger(MessageStore::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private var db: RocksDB? = null
    private val seqCounters = ConcurrentHashMap<String, AtomicLong>()

    private var headerCf: ColumnFamilyHandle? = null
    private var bodyCf: ColumnFamilyHandle? = null
    private var metaCf: ColumnFamilyHandle? = null

    companion object {
        init {
            RocksDB.loadLibrary()
        }

        private val HEADER_CF = ColumnFamilyDescriptor("header".toByteArray())
        private val BODY_CF = ColumnFamilyDescriptor("body".toByteArray())
        private val META_CF = ColumnFamilyDescriptor("meta".toByteArray())
    }

    fun start() {
        val options = DBOptions()
            .setCreateIfMissing(true)
            .setCreateMissingColumnFamilies(true)

        // 收集所有 CF（包括 default）
        val cfDescriptors = listOf(
            ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY),
            HEADER_CF,
            BODY_CF,
            META_CF,
        )

        val cfHandles = mutableListOf<ColumnFamilyHandle>()
        db = RocksDB.open(options, dbPath, cfDescriptors, cfHandles)

        // default CF = index 0, header = 1, body = 2, meta = 3
        headerCf = cfHandles[1]
        bodyCf = cfHandles[2]
        metaCf = cfHandles[3]

        logger.info("RocksDB message store opened at: {} (3 CFs: header, body, meta)", dbPath)
        loadSeqCounters()
    }

    val isRunning: Boolean get() = db != null

    fun stop() {
        headerCf?.close()
        bodyCf?.close()
        metaCf?.close()
        db?.close()
        logger.info("RocksDB message store closed")
    }

    private fun loadSeqCounters() {
        val dbInstance = db ?: return
        val hCf = headerCf ?: return
        val iter = dbInstance.newIterator(hCf)
        iter.seekToFirst()
        while (iter.isValid) {
            val key = iter.key()
            val channelId = extractChannelId(key)
            val seq = extractSeq(key)
            val counter = seqCounters.getOrPut(channelId) { AtomicLong(0) }
            if (seq > counter.get()) counter.set(seq)
            iter.next()
        }
        iter.close()
        logger.info("Loaded {} channel seq counters: {}", seqCounters.size,
            seqCounters.entries.joinToString(", ") { "${it.key}=${it.value.get()}" })
    }

    fun storeMessage(message: Message): Message {
        ThreadIOGuard.check("RocksDB")
        val dbInstance = db ?: error("MessageStore not started")
        val hCf = headerCf ?: error("MessageStore not started")
        val bCf = bodyCf ?: error("MessageStore not started")
        val mCf = metaCf ?: error("MessageStore not started")

        val counter = seqCounters.getOrPut(message.channelId) { AtomicLong(0) }
        val seq = counter.incrementAndGet()

        val stored = Message(
            header = message.header.copy(serverSeq = seq),
            body = message.body,
        )

        val key = buildKey(stored.channelId, seq)

        // header CF: [packetType(1B)][header bytes]
        val headerBuf = Unpooled.buffer()
        try {
            headerBuf.writeByte(stored.packetType.code.toInt())
            stored.header.writeTo(headerBuf)
        } finally {
            // headerBuf 稍后在 WriteBatch write 之后 release
        }

        // body CF: [body bytes]
        val bodyBuf = Unpooled.buffer()
        try {
            stored.body.writeTo(bodyBuf)
        } finally {
            // bodyBuf 稍后在 WriteBatch write 之后 release
        }

        // meta CF: JSON
        val meta = MessageMeta(flags = stored.flags)
        val metaBytes = json.encodeToString(meta).toByteArray(StandardCharsets.UTF_8)

        val headerBytes = toByteArray(headerBuf)
        val bodyBytes = toByteArray(bodyBuf)
        headerBuf.release()
        bodyBuf.release()

        // 原子写入三个 CF
        WriteBatch().use { batch ->
            batch.put(hCf, key, headerBytes)
            batch.put(bCf, key, bodyBytes)
            batch.put(mCf, key, metaBytes)
            dbInstance.write(WriteOptions(), batch)
        }

        return stored
    }

    fun getLatestMessages(channelId: String, limit: Int = 50): List<Message> {
        ThreadIOGuard.check("RocksDB")
        val dbInstance = db ?: return emptyList()
        val hCf = headerCf ?: return emptyList()
        val messages = mutableListOf<Message>()
        val iter = dbInstance.newIterator(hCf)

        val seekKey = buildKey(channelId, Long.MAX_VALUE)
        iter.seek(seekKey)

        if (iter.isValid) {
            val keyChannelId = extractChannelId(iter.key())
            if (keyChannelId != channelId) {
                iter.prev()
            }
        } else {
            iter.seekToLast()
        }

        while (iter.isValid && messages.size < limit) {
            val key = iter.key()
            val keyChannelId = extractChannelId(key)
            if (keyChannelId != channelId) break

            readMessage(dbInstance, key)?.let { messages.add(it) }
            iter.prev()
        }
        iter.close()
        return messages.reversed()
    }

    fun getMessagesAfterSeq(channelId: String, afterSeq: Long, limit: Int = 50): List<Message> {
        ThreadIOGuard.check("RocksDB")
        val dbInstance = db ?: return emptyList()
        val hCf = headerCf ?: return emptyList()
        val messages = mutableListOf<Message>()
        val iter = dbInstance.newIterator(hCf)

        val startKey = buildKey(channelId, afterSeq + 1)
        iter.seek(startKey)

        while (iter.isValid && messages.size < limit) {
            val key = iter.key()
            val keyChannelId = extractChannelId(key)
            if (keyChannelId != channelId) break

            readMessage(dbInstance, key)?.let { messages.add(it) }
            iter.next()
        }
        iter.close()
        return messages
    }

    /**
     * 向前加载历史消息：获取 channelId 中 seq < beforeSeq 的最新 limit 条消息。
     * 返回结果按 seq 升序排列（旧 → 新）。
     */
    fun getMessagesBeforeSeq(channelId: String, beforeSeq: Long, limit: Int = 50): List<Message> {
        ThreadIOGuard.check("RocksDB")
        val dbInstance = db ?: return emptyList()
        val hCf = headerCf ?: return emptyList()
        val messages = mutableListOf<Message>()
        val iter = dbInstance.newIterator(hCf)

        val seekKey = buildKey(channelId, beforeSeq)
        iter.seek(seekKey)

        if (iter.isValid) {
            val keyChannelId = extractChannelId(iter.key())
            val keySeq = extractSeq(iter.key())
            if (keyChannelId == channelId && keySeq >= beforeSeq) {
                iter.prev()
            } else if (keyChannelId != channelId) {
                iter.prev()
            }
        } else {
            iter.seekToLast()
        }

        while (iter.isValid && messages.size < limit) {
            val key = iter.key()
            val keyChannelId = extractChannelId(key)
            if (keyChannelId != channelId) break

            readMessage(dbInstance, key)?.let { messages.add(it) }
            iter.prev()
        }
        iter.close()
        return messages.reversed()
    }

    fun getMessageBySeq(channelId: String, seq: Long): Message? {
        ThreadIOGuard.check("RocksDB")
        val dbInstance = db ?: return null
        val key = buildKey(channelId, seq)
        return readMessage(dbInstance, key)
    }

    fun updateMessage(channelId: String, seq: Long, message: Message): Message? {
        ThreadIOGuard.check("RocksDB")
        val dbInstance = db ?: return null
        val hCf = headerCf ?: return null
        val bCf = bodyCf ?: return null
        val mCf = metaCf ?: return null

        val key = buildKey(channelId, seq)
        // 检查消息存在性
        if (dbInstance.get(hCf, key) == null) return null

        // 重写 header CF
        val headerBuf = Unpooled.buffer()
        try {
            headerBuf.writeByte(message.packetType.code.toInt())
            message.header.writeTo(headerBuf)
        } finally {}

        // 重写 body CF
        val bodyBuf = Unpooled.buffer()
        try {
            message.body.writeTo(bodyBuf)
        } finally {}

        // 重写 meta CF
        val meta = MessageMeta(flags = message.flags)
        val metaBytes = json.encodeToString(meta).toByteArray(StandardCharsets.UTF_8)

        val headerBytes = toByteArray(headerBuf)
        val bodyBytes = toByteArray(bodyBuf)
        headerBuf.release()
        bodyBuf.release()

        // 原子写入三个 CF
        WriteBatch().use { batch ->
            batch.put(hCf, key, headerBytes)
            batch.put(bCf, key, bodyBytes)
            batch.put(mCf, key, metaBytes)
            dbInstance.write(WriteOptions(), batch)
        }

        return message
    }

    fun updateFlags(channelId: String, seq: Long, flags: Int): Message? {
        ThreadIOGuard.check("RocksDB")
        val dbInstance = db ?: return null
        val hCf = headerCf ?: return null
        val bCf = bodyCf ?: return null
        val mCf = metaCf ?: return null

        val key = buildKey(channelId, seq)
        val headerBytes = dbInstance.get(hCf, key) ?: return null
        val bodyBytes = dbInstance.get(bCf, key) ?: return null
        val metaBytes = dbInstance.get(mCf, key) ?: return null

        // 更新 meta
        val meta = json.decodeFromString<MessageMeta>(String(metaBytes, StandardCharsets.UTF_8))
        val updatedMeta = meta.copy(flags = flags)
        dbInstance.put(mCf, key, json.encodeToString(updatedMeta).toByteArray(StandardCharsets.UTF_8))

        return decodeMessage(headerBytes, bodyBytes, flags)
    }

    fun deleteMessage(channelId: String, seq: Long) {
        ThreadIOGuard.check("RocksDB")
        val dbInstance = db ?: return
        val mCf = metaCf ?: return
        val hCf = headerCf ?: return

        val key = buildKey(channelId, seq)
        if (dbInstance.get(hCf, key) == null) return

        val metaBytes = dbInstance.get(mCf, key) ?: return
        val meta = json.decodeFromString<MessageMeta>(String(metaBytes, StandardCharsets.UTF_8))
        val deleted = meta.copy(deleted = true)
        dbInstance.put(mCf, key, json.encodeToString(deleted).toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * 遍历所有非删除消息，用于全量索引重建等场景。
     */
    fun iterateAll(callback: (Message) -> Unit) {
        ThreadIOGuard.check("RocksDB")
        val dbInstance = db ?: return
        val hCf = headerCf ?: return
        val iter = dbInstance.newIterator(hCf)
        iter.seekToFirst()
        while (iter.isValid) {
            val key = iter.key()
            readMessage(dbInstance, key)?.let { callback(it) }
            iter.next()
        }
        iter.close()
    }

    /**
     * 从三个 CF 读取并组装 Message。自动过滤 deleted 消息返回 null。
     */
    private fun readMessage(db: RocksDB, key: ByteArray): Message? {
        val hCf = headerCf ?: return null
        val bCf = bodyCf ?: return null
        val mCf = metaCf ?: return null

        // 读取 meta（检查 deleted）
        val metaBytes = db.get(mCf, key) ?: return null
        val meta = json.decodeFromString<MessageMeta>(String(metaBytes, StandardCharsets.UTF_8))
        if (meta.deleted) return null

        // 读取 header 和 body（二进制）
        val headerBytes = db.get(hCf, key) ?: return null
        val bodyBytes = db.get(bCf, key) ?: return null

        return decodeMessage(headerBytes, bodyBytes, meta.flags)
    }

    private fun decodeMessage(
        headerBytes: ByteArray,
        bodyBytes: ByteArray,
        flags: Int,
    ): Message? {
        val packetType = PacketType.fromCode(headerBytes[0]) ?: return null
        val header = MessageHeader.readFrom(Unpooled.wrappedBuffer(headerBytes, 1, headerBytes.size - 1))
        val bodyCreator = PacketType.bodyCreatorFor<MessageBody>(packetType) ?: return null
        val body = bodyCreator.create(Unpooled.wrappedBuffer(bodyBytes))
        return Message(header.copy(flags = flags), body)
    }

    private fun buildKey(channelId: String, seq: Long): ByteArray {
        val channelIdBytes = channelId.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(2 + channelIdBytes.size + 8)
        buffer.putShort(channelIdBytes.size.toShort())
        buffer.put(channelIdBytes)
        buffer.putLong(seq)
        return buffer.array()
    }

    private fun extractChannelId(key: ByteArray): String {
        val buffer = ByteBuffer.wrap(key)
        val len = buffer.short.toInt()
        val channelIdBytes = ByteArray(len)
        buffer.get(channelIdBytes)
        return String(channelIdBytes, StandardCharsets.UTF_8)
    }

    private fun extractSeq(key: ByteArray): Long {
        val buffer = ByteBuffer.wrap(key)
        val len = buffer.short.toInt()
        buffer.position(2 + len)
        return buffer.long
    }

    private fun toByteArray(buf: ByteBuf): ByteArray {
        val bytes = ByteArray(buf.readableBytes())
        buf.getBytes(buf.readerIndex(), bytes)
        return bytes
    }
}
