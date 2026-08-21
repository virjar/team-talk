package com.virjar.tk.agent

import com.virjar.tk.client.OutgoingMessage
import com.virjar.tk.client.OutgoingMessageConflictException
import com.virjar.tk.model.Attachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.security.MessageDigest

/** A caller-controlled path failed before upload; never expose the original path or cause. */
internal class AgentFileRequestException : IllegalArgumentException("file path is not allowed")

/**
 * Fixed-size concurrency boundary for retry-safe `send-file`.
 *
 * The first receipt lookup happens before staging/upload. We still stage a retry which already has
 * a receipt so its content fingerprint can be checked fail-closed, but never upload it again.
 * A crash after upload and before SQLite admission may leave one remote orphan; it cannot create a
 * duplicate message because no wire send happens before durable enqueue.
 */
internal class AgentFileSendCoordinator(
    private val findReceipt: (String, String, ByteArray?) -> OutgoingMessage?,
    private val stage: (String) -> AgentPreparedUpload,
    private val upload: suspend (AgentPreparedUpload, String) -> Attachment,
    private val enqueue: suspend (String, String, Attachment, ByteArray) -> OutgoingMessage,
    stripeCount: Int = DEFAULT_STRIPES,
) {
    private val stripes = Array(stripeCount) { Mutex() }

    init {
        require(stripeCount > 0) { "stripeCount must be positive" }
    }

    suspend fun enqueueFile(
        chatId: String,
        clientMsgId: String,
        rawPath: String,
        contentType: String = DEFAULT_CONTENT_TYPE,
    ): OutgoingMessage = stripe(chatId, clientMsgId).withLock {
        val preexisting = findReceipt(chatId, clientMsgId, null)
        val prepared = try {
            stage(rawPath)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw AgentFileRequestException()
        }
        try {
            val fingerprint = fileRequestFingerprint(prepared, contentType)
            if (preexisting != null) {
                return@withLock findReceipt(chatId, clientMsgId, fingerprint)
                    ?: throw OutgoingMessageConflictException(
                        "clientMsgId receipt expired while the file snapshot was validated",
                    )
            }
            findReceipt(chatId, clientMsgId, fingerprint)?.let { return@withLock it }
            val attachment = upload(prepared, contentType)
            enqueue(chatId, clientMsgId, attachment, fingerprint)
        } finally {
            // Do not expose a caller path through logs or replace an admitted remote result with a
            // local cleanup failure. The staging root has its own startup cleanup boundary.
            runCatching { prepared.close() }
        }
    }

    private fun stripe(chatId: String, clientMsgId: String): Mutex {
        val hash = 31 * chatId.hashCode() + clientMsgId.hashCode()
        return stripes[(hash and Int.MAX_VALUE) % stripes.size]
    }

    private fun fileRequestFingerprint(prepared: AgentPreparedUpload, contentType: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("teamtalk-agent-file-v1\u0000".encodeToByteArray())
        digest.update(prepared.contentSha256)
        digest.update(0.toByte())
        digest.update(prepared.originalFileName.encodeToByteArray())
        digest.update(0.toByte())
        digest.update(contentType.encodeToByteArray())
        digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(prepared.source.contentLength).array())
        return digest.digest()
    }

    private companion object {
        const val DEFAULT_STRIPES = 64
        const val DEFAULT_CONTENT_TYPE = "application/octet-stream"
    }
}
