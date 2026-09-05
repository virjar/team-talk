package com.virjar.tk.server.infra.storage

import com.virjar.tk.server.domain.message.MessageArchiveCursor
import com.virjar.tk.server.domain.message.MessageOperationType
import com.virjar.tk.server.domain.message.MessageProjectionOperation
import com.virjar.tk.server.domain.message.MessageProjectionTarget
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.ProtoCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/**
 * 拥有 [MessageStore] 持久化的二进制键与值格式。
 *
 * 让此无状态编解码器与 RocksDB 生命周期及查询编排分离，
 * 使磁盘契约显式化，而无需引入第二个数据库拥有者。
 */
internal object MessageStorePersistenceCodec {
    fun buildChatSeqPrefix(chatId: String): ByteArray =
        MESSAGE_PREFIX + encodeKeyPart(chatId, MAX_CHAT_ID_BYTES, "chatId")

    fun buildChatSeqKey(chatId: String, seq: Long): ByteArray =
        buildChatSeqPrefix(chatId) + encodeSeq(seq)

    fun buildChatSequenceKey(chatId: String): ByteArray =
        CHAT_SEQUENCE_PREFIX + encodeKeyPart(chatId, MAX_CHAT_ID_BYTES, "chatId")

    fun decodeChatSeqKey(key: ByteArray): MessageArchiveCursor {
        require(key.startsWith(MESSAGE_PREFIX)) { "消息索引前缀损坏" }
        require(key.size >= MESSAGE_PREFIX.size + KEY_PART_HEADER_LENGTH + 1 + SEQ_BYTES) {
            "消息序号索引格式损坏"
        }
        val lengthOffset = MESSAGE_PREFIX.size
        val chatLength = decodeKeyPartLength(key, lengthOffset)
        require(chatLength in 1..MAX_CHAT_ID_BYTES) { "消息 chatId 编码长度非法" }
        val chatStart = lengthOffset + KEY_PART_HEADER_LENGTH
        val seqOffset = Math.addExact(chatStart, chatLength)
        require(key.size == seqOffset + SEQ_BYTES) { "消息序号索引格式损坏" }
        val chatId = key.copyOfRange(chatStart, seqOffset)
            .decodeToString(throwOnInvalidSequence = true)
        val seq = decodeSeq(key.copyOfRange(seqOffset, key.size))
        require(seq > 0L) { "消息序号索引必须为正数" }
        return MessageArchiveCursor(chatId, seq)
    }

    fun buildClientMsgIdKey(chatId: String, clientMsgId: String): ByteArray =
        IDEMPOTENCY_PREFIX +
            encodeKeyPart(chatId, MAX_CHAT_ID_BYTES, "chatId") +
            encodeKeyPart(clientMsgId, MAX_CLIENT_MESSAGE_ID_BYTES, "clientMsgId")

    fun encodeIdempotencyValue(senderUid: String, seq: Long, clientContentHash: ByteArray): ByteArray {
        require(clientContentHash.size == SHA_256_LENGTH)
        return encodeKeyPart(senderUid, MAX_UID_BYTES, "senderUid") + encodeSeq(seq) + clientContentHash
    }

    fun decodeIdempotencyValue(value: ByteArray): IdempotencyRecord {
        require(value.size >= KEY_PART_HEADER_LENGTH + SEQ_BYTES + SHA_256_LENGTH) {
            "消息幂等索引格式损坏"
        }
        val senderLength = decodeKeyPartLength(value)
        require(senderLength <= value.size - KEY_PART_HEADER_LENGTH - SEQ_BYTES - SHA_256_LENGTH) {
            "消息幂等索引格式损坏"
        }
        val seqOffset = KEY_PART_HEADER_LENGTH + senderLength
        require(seqOffset + SEQ_BYTES + SHA_256_LENGTH == value.size) { "消息幂等索引格式损坏" }
        return IdempotencyRecord(
            senderUid = value.copyOfRange(KEY_PART_HEADER_LENGTH, seqOffset)
                .decodeToString(throwOnInvalidSequence = true),
            serverSeq = decodeSeq(value.copyOfRange(seqOffset, seqOffset + SEQ_BYTES)),
            clientContentHash = value.copyOfRange(seqOffset + SEQ_BYTES, value.size),
        )
    }

    fun buildRevisionKey(chatId: String, seq: Long): ByteArray =
        REVISION_PREFIX + encodeKeyPart(chatId, MAX_CHAT_ID_BYTES, "chatId") + encodeSeq(seq)

    fun buildOperationMessagePrefix(chatId: String, seq: Long): ByteArray =
        OPERATION_PREFIX + encodeKeyPart(chatId, MAX_CHAT_ID_BYTES, "chatId") + encodeSeq(seq)

    fun buildOperationKey(operation: MessageProjectionOperation): ByteArray =
        buildOperationMessagePrefix(operation.message.chatId, operation.message.serverSeq) +
            encodeSeq(operation.revision) + byteArrayOf(operation.operation.code.toByte())

    fun newProjectionOperation(
        operation: MessageOperationType,
        revision: Long,
        message: Message,
        projectionTarget: MessageProjectionTarget,
    ): MessageProjectionOperation = MessageProjectionOperation(
        projectionKey = MessageProjectionOperation.stableKey(message.chatId, message.serverSeq),
        operation = operation,
        revision = revision,
        message = message,
        target = projectionTarget.canonical(),
    )

    fun encodeProjectionOperation(operation: MessageProjectionOperation): ByteArray {
        val messageBytes = encodeMessage(operation.message)
        require(messageBytes.size <= MAX_MESSAGE_BYTES) { "Message operation payload is too large" }
        require(operation.target.recipientUids.size in 1..MAX_PROJECTION_RECIPIENTS) {
            "Message projection recipient count is out of bounds"
        }
        validateOperationRevision(operation.operation, operation.revision)
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(OPERATION_FORMAT_VERSION)
                output.writeByte(operation.operation.code)
                output.writeLong(operation.revision)
                output.writeSizedString(
                    operation.projectionKey,
                    MAX_PROJECTION_KEY_BYTES,
                    "projection key",
                )
                output.writeInt(operation.target.chatType)
                output.writeInt(operation.target.recipientUids.size)
                operation.target.recipientUids.forEach { uid ->
                    output.writeSizedString(uid, MAX_UID_BYTES, "projection recipient uid")
                }
                output.writeSizedBytes(messageBytes, MAX_MESSAGE_BYTES, "message")
            }
            bytes.toByteArray().also { encoded ->
                require(encoded.size <= MAX_OPERATION_BYTES) { "Message operation is too large" }
            }
        }
    }

    fun decodeProjectionEntry(key: ByteArray, value: ByteArray): MessageProjectionOperation {
        val operation = decodeProjectionOperation(value)
        require(key.contentEquals(buildOperationKey(operation))) {
            "Message operation outbox key/value identity mismatch"
        }
        return operation
    }

    fun buildAttachmentPathPrefix(path: String): ByteArray =
        ATTACHMENT_PREFIX + encodeAttachmentPath(path) + KEY_SEPARATOR

    fun buildAttachmentChatPrefix(path: String, chatId: String): ByteArray =
        buildAttachmentPathPrefix(path) +
            chatId.encodeToByteArray(throwOnInvalidSequence = true).also { bytes ->
                require(bytes.size in 1..MAX_CHAT_ID_BYTES && KEY_SEPARATOR.single() !in bytes) {
                    "chatId encoded length or delimiter is invalid"
                }
            } + KEY_SEPARATOR

    fun buildAttachmentIndexKey(path: String, chatId: String, seq: Long): ByteArray =
        buildAttachmentChatPrefix(path, chatId) + encodeSeq(seq)

    fun encodeSeq(seq: Long): ByteArray {
        val bytes = ByteArray(SEQ_BYTES)
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

    fun decodeSeq(bytes: ByteArray): Long {
        require(bytes.size == SEQ_BYTES) { "Invalid encoded sequence length: ${bytes.size}" }
        return ((bytes[0].toLong() and 0xFF) shl 56) or
            ((bytes[1].toLong() and 0xFF) shl 48) or
            ((bytes[2].toLong() and 0xFF) shl 40) or
            ((bytes[3].toLong() and 0xFF) shl 32) or
            ((bytes[4].toLong() and 0xFF) shl 24) or
            ((bytes[5].toLong() and 0xFF) shl 16) or
            ((bytes[6].toLong() and 0xFF) shl 8) or
            (bytes[7].toLong() and 0xFF)
    }

    fun decodeStoredMessage(bytes: ByteArray, expectedChatId: String, expectedSeq: Long): Message {
        require(bytes.size <= MAX_MESSAGE_BYTES) { "消息编码超过持久化上限" }
        return decodeMessage(bytes).also { message ->
            check(message.chatId == expectedChatId && message.serverSeq == expectedSeq) {
                "消息 key/value 身份不一致: chatId=$expectedChatId, seq=$expectedSeq"
            }
        }
    }

    /** 服务端分配字段不参与首次请求摘要；编辑消息不会改变这里保存的摘要。 */
    fun hashClientContent(message: Message): ByteArray = MessageDigest.getInstance("SHA-256").digest(
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

    fun encodeMessage(message: Message): ByteArray = ProtoCodec.encode(message)

    private fun decodeProjectionOperation(encoded: ByteArray): MessageProjectionOperation {
        require(encoded.size <= MAX_OPERATION_BYTES) { "Message operation is too large" }
        return DataInputStream(ByteArrayInputStream(encoded)).use { input ->
            val version = input.readInt()
            require(version == OPERATION_FORMAT_VERSION) {
                "Unsupported message operation outbox version: $version"
            }
            val operation = MessageOperationType.fromCode(input.readUnsignedByte())
            val revision = input.readLong()
            require(revision > 0L) { "Invalid message operation revision: $revision" }
            validateOperationRevision(operation, revision)
            val projectionKey = input.readSizedString(MAX_PROJECTION_KEY_BYTES)
            val chatType = input.readInt()
            val recipientCount = input.readInt()
            require(recipientCount in 1..MAX_PROJECTION_RECIPIENTS) {
                "Invalid message projection recipient count: $recipientCount"
            }
            val recipients = List(recipientCount) { input.readSizedString(MAX_UID_BYTES) }
            require(recipients == recipients.distinct().sorted()) {
                "Message projection recipients are not canonical"
            }
            val message = decodeMessage(input.readSizedBytes(MAX_MESSAGE_BYTES))
            require(input.available() == 0) { "Message operation outbox has trailing bytes" }
            MessageProjectionOperation(
                projectionKey = projectionKey,
                operation = operation,
                revision = revision,
                message = message,
                target = MessageProjectionTarget(chatType, recipients),
            )
        }
    }

    private fun validateOperationRevision(operation: MessageOperationType, revision: Long) {
        require((operation == MessageOperationType.CREATE) == (revision == INITIAL_REVISION)) {
            "CREATE must be revision 1 and later revisions must be EDIT or REVOKE"
        }
    }

    private fun encodeKeyPart(value: String, maxBytes: Int, field: String): ByteArray {
        val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
        require(bytes.size in 1..maxBytes) { "$field 编码长度非法" }
        val size = bytes.size
        return byteArrayOf(
            (size ushr 24).toByte(),
            (size ushr 16).toByte(),
            (size ushr 8).toByte(),
            size.toByte(),
        ) + bytes
    }

    private fun decodeKeyPartLength(value: ByteArray, offset: Int = 0): Int {
        require(offset >= 0 && offset <= value.size - KEY_PART_HEADER_LENGTH) {
            "消息索引长度字段损坏"
        }
        val length = ((value[offset].toLong() and 0xFF) shl 24) or
            ((value[offset + 1].toLong() and 0xFF) shl 16) or
            ((value[offset + 2].toLong() and 0xFF) shl 8) or
            (value[offset + 3].toLong() and 0xFF)
        require(length <= Int.MAX_VALUE) { "消息幂等索引格式损坏" }
        return length.toInt()
    }

    private fun encodeAttachmentPath(path: String): ByteArray =
        path.encodeToByteArray(throwOnInvalidSequence = true).also { bytes ->
            require(bytes.size in 1..MAX_ATTACHMENT_PATH_BYTES && KEY_SEPARATOR.single() !in bytes) {
                "附件路径编码长度或分隔符非法"
            }
        }

    private fun decodeMessage(bytes: ByteArray): Message = ProtoCodec.decode(Message, bytes)

    private fun DataOutputStream.writeSizedString(value: String, maxBytes: Int, field: String) =
        writeSizedBytes(value.encodeToByteArray(throwOnInvalidSequence = true), maxBytes, field)

    private fun DataOutputStream.writeSizedBytes(value: ByteArray, maxBytes: Int, field: String) {
        require(value.size <= maxBytes) { "$field is too large" }
        writeInt(value.size)
        write(value)
    }

    private fun DataInputStream.readSizedString(maxBytes: Int): String =
        readSizedBytes(maxBytes).decodeToString(throwOnInvalidSequence = true)

    private fun DataInputStream.readSizedBytes(maxBytes: Int): ByteArray {
        val size = readInt()
        require(size in 0..maxBytes && size <= available()) { "Invalid outbox field size: $size" }
        return ByteArray(size).also(::readFully)
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (index in prefix.indices) {
            if (this[index] != prefix[index]) return false
        }
        return true
    }

    internal data class IdempotencyRecord(
        val senderUid: String,
        val serverSeq: Long,
        val clientContentHash: ByteArray,
    )

    // 0x01 是旧的 sender-scoped 索引。新 prefix 避免与旧 key 空间混用；
    // 当前尚未发布，测试数据可在此破坏性身份语义升级时清理。
    private val IDEMPOTENCY_PREFIX = byteArrayOf(0x04)
    private val ATTACHMENT_PREFIX = byteArrayOf(0x03)
    val OPERATION_PREFIX: ByteArray = byteArrayOf(0x05)
    private val REVISION_PREFIX = byteArrayOf(0x06)
    val MESSAGE_PREFIX: ByteArray = byteArrayOf(0x07)
    private val CHAT_SEQUENCE_PREFIX = byteArrayOf(0x08)
    const val KEY_SEPARATOR_SIZE = 1
    const val KEY_SEPARATOR_BYTE: Byte = 0
    private val KEY_SEPARATOR = byteArrayOf(KEY_SEPARATOR_BYTE)
    val EMPTY_VALUE: ByteArray = byteArrayOf()
    private const val OPERATION_FORMAT_VERSION = 1
    const val INITIAL_REVISION = 1L
    private const val MAX_PROJECTION_KEY_BYTES = 2_048
    private const val MAX_PROJECTION_RECIPIENTS = 100_000
    private const val MAX_UID_BYTES = 1_024
    private const val MAX_CHAT_ID_BYTES = 1_024
    private const val MAX_CLIENT_MESSAGE_ID_BYTES = 1_024
    private const val MAX_ATTACHMENT_PATH_BYTES = 16_384
    const val MAX_MESSAGE_BYTES = 16 * 1024 * 1024
    private const val MAX_OPERATION_BYTES = 32 * 1024 * 1024
    private const val KEY_PART_HEADER_LENGTH = 4
    const val SEQ_BYTES = 8
    private const val SHA_256_LENGTH = 32
}
