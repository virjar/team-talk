package com.virjar.tk.client

import com.virjar.tk.model.Message
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class SendQueueTest {
    @Test
    fun `SDK validation failure is reported instead of disguised as timeout`() = runTest {
        val failure = CompletableDeferred<String>()
        val queue = SendQueue(
            connectionState = MutableStateFlow(ConnectionState.AUTHENTICATED),
            sender = MessageSender { throw IllegalArgumentException("附件路径非法") },
            scope = backgroundScope,
            onFailed = { _, reason -> failure.complete(reason) },
        )

        queue.enqueue(
            Message(
                chatId = "chat-1",
                clientMsgId = "client-1",
                senderUid = "user-1",
                messageType = 1,
                timestamp = 1,
            ),
        )

        assertEquals("附件路径非法", withTimeout(1_000) { failure.await() })
        queue.close()
    }
}
