package com.virjar.tk.protocol

import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.Contact
import com.virjar.tk.protocol.model.Conversation
import com.virjar.tk.protocol.model.ConversationPage
import com.virjar.tk.protocol.model.SyncCheckpointChatPage
import com.virjar.tk.protocol.model.SyncCheckpointContactPage
import com.virjar.tk.protocol.model.SyncCheckpointHeader
import com.virjar.tk.protocol.model.SyncCheckpointPageRequest
import com.virjar.tk.protocol.model.User
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.ProtocolCorruptionException
import com.virjar.tk.protocol.payload.ResponsePayload
import com.virjar.tk.protocol.rpc.RpcInvoker
import com.virjar.tk.protocol.rpc.gen.SyncRpcContract
import com.virjar.tk.protocol.rpc.gen.SyncRpcProxy
import com.virjar.tk.protocol.rpc.gen.SyncRpcStub
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SyncCheckpointRpcContractTest {

    @Test
    fun `generated sync stub decodes every request and encodes every typed response`() {
        val stub = RecordingSyncStub()

        val header = ProtoCodec.decode(
            SyncCheckpointHeader,
            runCheckpointSuspendImmediate {
                stub.dispatch(
                    SyncRpcContract.M_BEGIN_CHECKPOINT,
                    SyncRpcContract.encodeBeginCheckpoint(DATASET_ID),
                )
            },
        )
        val contactPage = ProtoCodec.decode(
            SyncCheckpointContactPage,
            runCheckpointSuspendImmediate {
                stub.dispatch(
                    SyncRpcContract.M_LIST_CHECKPOINT_CONTACTS,
                    SyncRpcContract.encodeListCheckpointContacts(FIRST_PAGE),
                )
            },
        )
        val chatPage = ProtoCodec.decode(
            SyncCheckpointChatPage,
            runCheckpointSuspendImmediate {
                stub.dispatch(
                    SyncRpcContract.M_LIST_CHECKPOINT_CHATS,
                    SyncRpcContract.encodeListCheckpointChats(NEXT_PAGE),
                )
            },
        )
        val conversationPage = ProtoCodec.decode(
            ConversationPage,
            runCheckpointSuspendImmediate {
                stub.dispatch(
                    SyncRpcContract.M_LIST_CHECKPOINT_CONVERSATIONS,
                    SyncRpcContract.encodeListCheckpointConversations(FIRST_PAGE),
                )
            },
        )

        assertEquals(HEADER, header)
        assertEquals(CONTACT_PAGE, contactPage)
        assertEquals(CHAT_PAGE, chatPage)
        assertEquals(CONVERSATION_PAGE, conversationPage)
        assertEquals(
            listOf(
                "begin:$DATASET_ID",
                "contacts:$FIRST_PAGE",
                "chats:$NEXT_PAGE",
                "conversations:$FIRST_PAGE",
            ),
            stub.calls,
        )
    }

    @Test
    fun `generated sync stub rejects trailing request bytes before business code`() {
        val stub = RecordingSyncStub()
        val payload = SyncRpcContract.encodeListCheckpointContacts(FIRST_PAGE) + byteArrayOf(0x7f)

        assertFailsWith<ProtocolCorruptionException> {
            runCheckpointSuspendImmediate {
                stub.dispatch(SyncRpcContract.M_LIST_CHECKPOINT_CONTACTS, payload)
            }
        }
        assertEquals(emptyList(), stub.calls)
    }

    @Test
    fun `generated sync proxy routes and strictly decodes every typed response`() {
        val methodIds = mutableListOf<Int>()
        val proxy = SyncRpcProxy(
            object : RpcInvoker {
                override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?): ResponsePayload {
                    assertEquals(SyncRpcContract.SERVICE, service)
                    methodIds += methodId
                    val result = when (methodId) {
                        SyncRpcContract.M_BEGIN_CHECKPOINT -> ProtoCodec.encode(HEADER)
                        SyncRpcContract.M_LIST_CHECKPOINT_CONTACTS -> ProtoCodec.encode(CONTACT_PAGE)
                        SyncRpcContract.M_LIST_CHECKPOINT_CHATS -> ProtoCodec.encode(CHAT_PAGE)
                        SyncRpcContract.M_LIST_CHECKPOINT_CONVERSATIONS -> ProtoCodec.encode(CONVERSATION_PAGE)
                        else -> error("unexpected sync method $methodId")
                    }
                    return ResponsePayload(requestId = methodId, status = 0, payload = result)
                }
            },
        )

        assertEquals(HEADER, runCheckpointSuspendImmediate { proxy.beginCheckpoint(DATASET_ID) })
        assertEquals(CONTACT_PAGE, runCheckpointSuspendImmediate { proxy.listCheckpointContacts(FIRST_PAGE) })
        assertEquals(CHAT_PAGE, runCheckpointSuspendImmediate { proxy.listCheckpointChats(NEXT_PAGE) })
        assertEquals(
            CONVERSATION_PAGE,
            runCheckpointSuspendImmediate { proxy.listCheckpointConversations(FIRST_PAGE) },
        )
        assertEquals(listOf(1, 2, 3, 4), methodIds)
    }

    @Test
    fun `generated sync proxy rejects trailing response bytes`() {
        val proxy = SyncRpcProxy(
            object : RpcInvoker {
                override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?) =
                    ResponsePayload(
                        requestId = methodId,
                        status = 0,
                        payload = ProtoCodec.encode(HEADER) + byteArrayOf(0x7f),
                    )
            },
        )

        assertFailsWith<ProtocolCorruptionException> {
            runCheckpointSuspendImmediate { proxy.beginCheckpoint(DATASET_ID) }
        }
    }

    private class RecordingSyncStub : SyncRpcStub(CURRENT_USER.uid) {
        val calls = mutableListOf<String>()

        override suspend fun beginCheckpoint(datasetId: String): SyncCheckpointHeader {
            calls += "begin:$datasetId"
            return HEADER
        }

        override suspend fun listCheckpointContacts(
            request: SyncCheckpointPageRequest,
        ): SyncCheckpointContactPage {
            calls += "contacts:$request"
            return CONTACT_PAGE
        }

        override suspend fun listCheckpointChats(request: SyncCheckpointPageRequest): SyncCheckpointChatPage {
            calls += "chats:$request"
            return CHAT_PAGE
        }

        override suspend fun listCheckpointConversations(request: SyncCheckpointPageRequest): ConversationPage {
            calls += "conversations:$request"
            return CONVERSATION_PAGE
        }
    }

    private companion object {
        const val DATASET_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val CHECKPOINT_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val CURRENT_USER = User(
            uid = "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
            username = "checkpoint-user",
            name = "Checkpoint User",
        )
        val HEADER = SyncCheckpointHeader(DATASET_ID, CHECKPOINT_ID, 41L, CURRENT_USER)
        val FIRST_PAGE = SyncCheckpointPageRequest(CHECKPOINT_ID)
        val NEXT_PAGE = SyncCheckpointPageRequest(CHECKPOINT_ID, "next_cursor-1")
        val CONTACT_PAGE = SyncCheckpointContactPage(
            items = listOf(
                Contact(
                    uid = CURRENT_USER.uid,
                    friendUid = "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                ),
            ),
            nextCursor = null,
        )
        val CHAT_PAGE = SyncCheckpointChatPage(
            items = listOf(
                Chat(
                    chatId = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
                    chatType = 1,
                    name = "Checkpoint Chat",
                ),
            ),
            nextCursor = null,
        )
        val CONVERSATION_PAGE = ConversationPage(
            items = listOf(
                Conversation(
                    chatId = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
                    chatType = 1,
                    peerUid = "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                    peerRevision = 1,
                    chatName = "Checkpoint Chat",
                ),
            ),
            nextCursor = null,
        )
    }
}

private fun <T> runCheckpointSuspendImmediate(block: suspend () -> T): T {
    var completed = false
    var value: T? = null
    var failure: Throwable? = null
    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {
                completed = true
                result.fold(
                    onSuccess = { value = it },
                    onFailure = { failure = it },
                )
            }
        },
    )
    check(completed) { "checkpoint test invocation suspended asynchronously" }
    failure?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return value as T
}
