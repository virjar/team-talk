package com.virjar.tk.protocol

import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.Document
import com.virjar.tk.protocol.model.InviteLink
import com.virjar.tk.protocol.model.Member
import com.virjar.tk.protocol.payload.ResponsePayload
import com.virjar.tk.protocol.rpc.RpcInvoker
import com.virjar.tk.protocol.rpc.gen.ChatRpcContract
import com.virjar.tk.protocol.rpc.gen.ChatRpcProxy
import com.virjar.tk.protocol.rpc.gen.ChatRpcStub
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RpcCollectionBoundsTest {

    @Test
    fun `generic list encoder rejects impossible count before touching an element`() {
        var elementRead = false
        val impossible = object : AbstractList<IProto>() {
            override val size: Int = PacketBuffer.MAX_COLLECTION_ENTRIES + 1
            override fun get(index: Int): IProto {
                elementRead = true
                error("element must not be read")
            }
        }

        assertFailsWith<ProtocolEncodingException> { ProtoCodec.encodeList(impossible) }
        assertFalse(elementRead)
    }

    @Test
    fun `generated chat request stubs reject extreme list counts from tiny payloads`() {
        val cases = listOf(
            ChatRpcContract.M_CREATE_GROUP to ProtoCodec.encodePayload {
                writeString("00000000-0000-4000-8000-000000000031")
                writeString("group")
                writeString(null)
                writeVarInt(Int.MAX_VALUE)
            },
            ChatRpcContract.M_ADD_MEMBERS to ProtoCodec.encodePayload {
                writeString("chat")
                writeVarInt(Int.MAX_VALUE)
            },
        )

        cases.forEach { (methodId, payload) ->
            val stub = GuardedChatStub()
            val failure = assertFailsWith<ProtocolCorruptionException> {
                runSuspendImmediate { stub.dispatch(methodId, payload) }
            }
            assertTrue(failure.message.orEmpty().contains("count"))
            assertFalse(stub.invoked, "畸形集合必须在进入 RPC 业务实现前失败")
        }
    }

    @Test
    fun `generated chat request stubs reject list counts that cannot fit remaining bytes`() {
        val payload = ProtoCodec.encodePayload {
            writeString("chat")
            writeVarInt(2)
        }
        val stub = GuardedChatStub()

        val failure = assertFailsWith<ProtocolCorruptionException> {
            runSuspendImmediate { stub.dispatch(ChatRpcContract.M_ADD_MEMBERS, payload) }
        }

        assertTrue(failure.message.orEmpty().contains("remaining payload"))
        assertFalse(stub.invoked, "集合最小 wire 大小不成立时不得进入 RPC 业务实现")
    }

    @Test
    fun `generated rpc proxies bound collection result counts before allocating`() {
        val impossibleResult = ProtoCodec.encodePayload { writeVarInt(Int.MAX_VALUE) }
        val proxy = ChatRpcProxy(
            object : RpcInvoker {
                override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?) =
                    ResponsePayload(requestId = 1, status = 0, payload = impossibleResult)
            },
        )

        assertFailsWith<ProtocolCorruptionException> {
            runSuspendImmediate { proxy.getMembers("chat") }
        }
    }

    @Test
    fun `handwritten document collection checks remaining bytes before allocating`() {
        val payload = ProtoCodec.encodePayload {
            writeString("document")
            writeString("space")
            writeString(null)
            writeString("title")
            writeString("markdown")
            writeVarLong(1)
            writeString("creator")
            writeVarLong(2)
            writeString("updater")
            writeVarLong(3)
            writeVarInt(1)
        }

        assertFailsWith<ProtocolCorruptionException> {
            Document.readFrom(PacketBuffer(payload))
        }
    }

    private class GuardedChatStub : ChatRpcStub("test-user") {
        var invoked = false
            private set

        private fun unexpected(): Nothing {
            invoked = true
            error("RPC implementation must not be reached")
        }

        override suspend fun createPersonal(targetUid: String): Chat = unexpected()
        override suspend fun getOrCreateSavedChat(): Chat = unexpected()
        override suspend fun createGroup(
            operationId: String,
            name: String,
            avatar: String?,
            memberUids: List<String>,
        ): Chat = unexpected()
        override suspend fun get(chatId: String): Chat = unexpected()
        override suspend fun update(chatId: String, name: String?, avatar: String?, notice: String?) = unexpected()
        override suspend fun delete(chatId: String) = unexpected()
        override suspend fun addMembers(chatId: String, uids: List<String>) = unexpected()
        override suspend fun removeMembers(chatId: String, targetUid: String) = unexpected()
        override suspend fun getMembers(chatId: String): List<Member> = unexpected()
        override suspend fun transferOwner(chatId: String, newOwnerUid: String) = unexpected()
        override suspend fun setRole(chatId: String, targetUid: String, role: Int) = unexpected()
        override suspend fun muteMember(chatId: String, targetUid: String, durationSeconds: Int) = unexpected()
        override suspend fun unmuteMember(chatId: String, targetUid: String) = unexpected()
        override suspend fun muteAll(chatId: String) = unexpected()
        override suspend fun unmuteAll(chatId: String) = unexpected()
        override suspend fun createInviteLink(
            operationId: String,
            issuedAt: Long,
            chatId: String,
            name: String,
            maxUses: Int,
            expiresAt: Long,
        ): String =
            unexpected()
        override suspend fun listInviteLinks(chatId: String): List<InviteLink> = unexpected()
        override suspend fun revokeInviteLink(token: String) = unexpected()
        override suspend fun joinByInvite(token: String): Chat = unexpected()
        override suspend fun getInviteInfo(token: String): InviteLink = unexpected()
        override suspend fun leaveGroup(chatId: String) = unexpected()
    }
}

private fun <T> runSuspendImmediate(block: suspend () -> T): T {
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
    check(completed) { "测试调用发生了异步挂起" }
    failure?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return value as T
}
