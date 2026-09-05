package com.virjar.tk.server.e2e

import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.protocol.model.ContactApplyRecord
import com.virjar.tk.protocol.model.ConversationPageRequest
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.rpc.gen.ChatRpcProxy
import com.virjar.tk.protocol.rpc.gen.ContactRpcProxy
import com.virjar.tk.protocol.rpc.gen.ConversationRpcProxy
import com.virjar.tk.protocol.rpc.gen.DeviceRpcProxy
import com.virjar.tk.protocol.rpc.gen.MessageRpcProxy
import com.virjar.tk.protocol.rpc.gen.UserRpcContract
import com.virjar.tk.protocol.rpc.gen.UserRpcProxy
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * 针对已部署服务器的跨端 RPC 契约测试。
 *
 * 每个正向调用都经由 KSP 生成的 Proxy。因此 service id、method id、请求编码与响应解码
 * 都由生成的 Contract 拥有。只有被路由的方法返回了被断言的业务投影，测试才算通过；
 * 任意 HTTP 风格的状态码都不会被视为 codec 成功。原始的未知方法调用被有意保留为
 * 负向对照，避免缺失/生成的路由伪装成被接受的 400 响应。
 *
 * 运行方式：
 * `./gradlew :server:test --tests "*RpcCodecConsistencyTest" -Dtk.e2e.remote=true`
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "tk.e2e.remote", matches = "true")
class RpcCodecConsistencyTest {

    private lateinit var user1: RemoteAcceptanceSupport.Session
    private lateinit var user2: RemoteAcceptanceSupport.Session
    private lateinit var chatId: String

    @BeforeAll
    fun setup() = runBlocking {
        println(
            "[RpcCodecConsistency] target = " +
                "${RemoteAcceptanceSupport.host}:${RemoteAcceptanceSupport.port}",
        )
        user1 = RemoteAcceptanceSupport.registerUser("codec1")
        user2 = RemoteAcceptanceSupport.registerUser("codec2")

        val user1Contacts = ContactRpcProxy(user1.rpc)
        val user2Contacts = ContactRpcProxy(user2.rpc)
        val application = user1Contacts.apply(user2.uid, "codec setup")
        assertEquals(user1.uid, application.fromUid)
        assertEquals(user2.uid, application.toUid)
        assertNull(application.token, "sender must not receive the contact-processing token")
        val incoming = user2Contacts.listPendingApplies()
            .single { it.fromUid == user1.uid && it.status == ContactApplyRecord.STATUS_PENDING }
        val accepted = user2Contacts.accept(
            UUID.randomUUID().toString(),
            System.currentTimeMillis(),
            requireNotNull(incoming.token),
        )
        assertEquals(ContactApplyRecord.STATUS_ACCEPTED, accepted.status)

        val chat = ChatRpcProxy(user1.rpc).createPersonal(user2.uid)
        chatId = chat.chatId
        assertEquals(1, chat.chatType)
        println("[RpcCodecConsistency] setup complete: chatId=$chatId")
    }

    @AfterAll
    fun teardown() {
        if (::user1.isInitialized) user1.close()
        if (::user2.isInitialized) user2.close()
    }

    @Test
    fun `message contract routes and decodes each exercised method`() = runBlocking {
        val rpc = MessageRpcProxy(user1.rpc)

        val initialHistory = rpc.getHistory(chatId, 0, Message.MAX_QUERY_PAGE_SIZE)
        assertTrue(initialHistory.size <= Message.MAX_QUERY_PAGE_SIZE)
        assertTrue(initialHistory.all { it.chatId == chatId })

        val searchToken = "codecsearch${UUID.randomUUID().toString().replace("-", "")}"
        val searchableSeq = sendRichText(searchToken)
        val searchResult = rpc.search(chatId, searchToken, Message.MAX_QUERY_PAGE_SIZE)
        assertTrue(
            searchResult.any { it.serverSeq == searchableSeq && it.chatId == chatId },
            "generated search request and response codecs must preserve the routed message",
        )

        val revokeToken = "codecrevoke${UUID.randomUUID().toString().replace("-", "")}"
        val revokedSeq = sendRichText(revokeToken)
        rpc.revoke(chatId, revokedSeq)
        val revoked = rpc.getHistory(chatId, 0, Message.MAX_QUERY_PAGE_SIZE)
            .single { it.serverSeq == revokedSeq }
        assertTrue(revoked.flags and Message.FLAG_REVOKED != 0)

        rpc.markRead(chatId, revokedSeq)
        val conversation = ConversationRpcProxy(user1.rpc)
            .listPage(ConversationPageRequest())
            .items
            .single { it.chatId == chatId }
        assertTrue(conversation.readSeq >= revokedSeq)
    }

    @Test
    fun `contact contract routes and decodes each exercised method`() = runBlocking {
        val rpc = ContactRpcProxy(user1.rpc)
        assertTrue(rpc.list().any { it.friendUid == user2.uid })

        val applicant = RemoteAcceptanceSupport.registerUser("codec-pending")
        val blocked = RemoteAcceptanceSupport.registerUser("codec-blocked")
        try {
            val application = ContactRpcProxy(applicant.rpc).apply(user1.uid, "codec pending")
            assertEquals(applicant.uid, application.fromUid)
            assertEquals(user1.uid, application.toUid)
            assertNull(application.token)

            val incoming = rpc.listPendingApplies().single { it.fromUid == applicant.uid }
            assertEquals(ContactApplyRecord.STATUS_PENDING, incoming.status)
            assertNotNull(incoming.token)

            val lookup = rpc.getPendingApply(applicant.uid).record
            assertNotNull(lookup)
            assertEquals(incoming.id, lookup?.id)
            assertEquals(ContactApplyRecord.DIRECTION_INCOMING, lookup?.direction)

            val records = rpc.listApplyRecords(beforeId = 0, limit = 20)
            assertTrue(records.any { it.id == incoming.id && it.status == ContactApplyRecord.STATUS_PENDING })

            rpc.blacklist(blocked.uid)
            assertTrue(rpc.listBlacklist().any { it.friendUid == blocked.uid })
        } finally {
            applicant.close()
            blocked.close()
        }
    }

    @Test
    fun `chat contract routes and decodes each exercised method`() = runBlocking {
        val rpc = ChatRpcProxy(user1.rpc)
        assertEquals(chatId, rpc.createPersonal(user2.uid).chatId)

        val member = RemoteAcceptanceSupport.registerUser("codec-member")
        try {
            val groupName = "CodecGroup-${UUID.randomUUID().toString().take(8)}"
            val group = rpc.createGroup(UUID.randomUUID().toString(), groupName, null, emptyList())
            assertEquals(2, group.chatType)
            assertEquals(groupName, group.name)
            val loadedGroup = rpc.get(group.chatId)
            assertEquals(group.chatId, loadedGroup.chatId)
            assertEquals(groupName, loadedGroup.name)

            rpc.addMembers(group.chatId, listOf(member.uid))
            val addedMembers = rpc.getMembers(group.chatId)
            assertTrue(addedMembers.any { it.uid == user1.uid && it.role == 2 })
            assertTrue(addedMembers.any { it.uid == member.uid })

            rpc.removeMembers(group.chatId, member.uid)
            assertFalse(rpc.getMembers(group.chatId).any { it.uid == member.uid })
        } finally {
            member.close()
        }
    }

    @Test
    fun `conversation contract routes and decodes each exercised method`() = runBlocking {
        val rpc = ConversationRpcProxy(user1.rpc)
        assertTrue(rpc.listPage(ConversationPageRequest()).items.any { it.chatId == chatId })

        val draft = "codec-draft-${UUID.randomUUID()}"
        rpc.setDraft(chatId, draft)
        rpc.setPin(chatId, true)
        rpc.setMute(chatId, true)

        val updated = rpc.listPage(ConversationPageRequest()).items.single { it.chatId == chatId }
        assertEquals(draft, updated.draft)
        assertTrue(updated.isPinned)
        assertTrue(updated.isMuted)
    }

    @Test
    fun `user contract routes and decodes each exercised method`() = runBlocking {
        val rpc = UserRpcProxy(user1.rpc)
        assertEquals(user1.uid, rpc.getProfile(null).uid)
        val username = requireNotNull(user1.registeredUsername)
        assertTrue(rpc.search(username).any { it.uid == user1.uid && it.username == username })
    }

    @Test
    fun `device contract routes and decodes list`() = runBlocking {
        val devices = DeviceRpcProxy(user1.rpc).listDevices()
        assertTrue(devices.any { it.deviceId == "e2e-device" && it.deviceName == "E2E" })
    }

    @Test
    fun `unknown method is an explicit negative control`() = runBlocking {
        val unknownMethodId = Int.MAX_VALUE
        val response = user1.invoke(UserRpcContract.SERVICE, unknownMethodId)
        assertEquals(400, response.status)
        assertEquals(
            "Unknown method $unknownMethodId for service ${UserRpcContract.SERVICE}",
            response.payload?.decodeToString(),
        )
    }

    private suspend fun sendRichText(markdown: String): Long {
        val message = Message(
            chatId = chatId,
            clientMsgId = UUID.randomUUID().toString(),
            senderUid = user1.uid,
            messageType = MessageType.RICH_TEXT.code,
            timestamp = System.currentTimeMillis(),
            body = buildRichTextBody(markdown),
        )
        val ack = user1.imClient.sendAndWaitAck(message)
        assertEquals(0, ack.code, ack.reason)
        assertTrue(ack.serverSeq > 0)
        return ack.serverSeq
    }
}
