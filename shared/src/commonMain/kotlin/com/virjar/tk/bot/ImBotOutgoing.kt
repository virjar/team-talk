package com.virjar.tk.bot

import com.virjar.tk.body.MessageBody
import com.virjar.tk.body.MessageBodyPolicy
import com.virjar.tk.client.ClientSession
import com.virjar.tk.client.OutgoingMessage
import com.virjar.tk.client.OutgoingMessageConflictException
import com.virjar.tk.client.OutgoingMessageState
import com.virjar.tk.client.canonicalizeOutboundMessage
import com.virjar.tk.model.Message
import com.virjar.tk.protocol.payload.MessageAckPayload
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Durable outgoing admission, stable-key serialization and legacy ACK waiting for [ImBot]. */
internal class ImBotOutgoing(
    private val session: ClientSession,
    private val senderUid: () -> String,
) {
    private val locks = Array(LOCK_STRIPES) { Mutex() }

    fun receipt(chatId: String, clientMsgId: String): OutgoingMessage? =
        session.outgoingReceipt(chatId, clientMsgId)

    fun receipt(
        chatId: String,
        clientMsgId: String,
        requestFingerprint: ByteArray,
    ): OutgoingMessage? = session.outgoingReceipt(chatId, clientMsgId, requestFingerprint)

    suspend fun send(chatId: String, body: MessageBody): MessageAckPayload {
        val clientMsgId = UUID.randomUUID().toString()
        enqueue(chatId, clientMsgId, body)
        return withTimeoutOrNull(LEGACY_SEND_WAIT_MILLIS) {
            while (true) {
                val current = checkNotNull(receipt(chatId, clientMsgId))
                when (current.state) {
                    OutgoingMessageState.SUCCESS -> return@withTimeoutOrNull MessageAckPayload(
                        clientMsgId,
                        requireNotNull(current.serverSeq),
                        0,
                        null,
                    )
                    OutgoingMessageState.TERMINAL_FAILED -> return@withTimeoutOrNull MessageAckPayload(
                        clientMsgId,
                        0L,
                        current.terminalCode ?: 400,
                        current.lastError,
                    )
                    OutgoingMessageState.PENDING,
                    OutgoingMessageState.IN_FLIGHT,
                    OutgoingMessageState.RETRY_WAIT -> delay(25L)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        } ?: MessageAckPayload(clientMsgId, 0L, -1, "message remains durably queued")
    }

    suspend fun enqueue(
        chatId: String,
        clientMsgId: String,
        body: MessageBody,
        requestFingerprint: ByteArray? = null,
    ): OutgoingMessage = lock(chatId, clientMsgId).withLock {
        session.outgoingReceipt(chatId, clientMsgId, requestFingerprint)?.let { existing ->
            if (requestFingerprint == null) existing.requireSameBody(body)
            return@withLock existing
        }
        val messageType = MessageBodyPolicy.typeOf(body)
        val message = MessageBodyPolicy.canonicalize(
            Message(
                chatId = chatId,
                clientMsgId = clientMsgId,
                senderUid = senderUid(),
                messageType = messageType.code,
                timestamp = System.currentTimeMillis(),
                body = body,
            ),
        )
        session.enqueueOutgoing(message, requestFingerprint)
    }

    private fun OutgoingMessage.requireSameBody(requestedBody: MessageBody) {
        val requestedType = MessageBodyPolicy.typeOf(requestedBody).code
        val candidate = canonicalizeOutboundMessage(
            message.copy(messageType = requestedType, body = requestedBody),
        )
        if (candidate != message) {
            throw OutgoingMessageConflictException(
                "clientMsgId already names a different immutable outgoing payload",
            )
        }
    }

    private fun lock(chatId: String, clientMsgId: String): Mutex {
        val hash = 31 * chatId.hashCode() + clientMsgId.hashCode()
        return locks[(hash and Int.MAX_VALUE) % locks.size]
    }

    private companion object {
        const val LEGACY_SEND_WAIT_MILLIS = 35_000L
        const val LOCK_STRIPES = 64
    }
}
