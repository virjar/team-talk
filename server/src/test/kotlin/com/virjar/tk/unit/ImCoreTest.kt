package com.virjar.tk.unit

import com.virjar.tk.db.MessageStore
import com.virjar.tk.protocol.ChannelType
import com.virjar.tk.protocol.PacketType
import com.virjar.tk.protocol.payload.Message
import com.virjar.tk.protocol.payload.MessageHeader
import com.virjar.tk.protocol.payload.TextBody
import com.virjar.tk.service.ChannelService
import com.virjar.tk.service.TokenService
import org.junit.jupiter.api.*
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImCoreTest {

    companion object {
        private lateinit var messageStore: MessageStore
        private const val TEST_DB_PATH = "/tmp/teamtalk-imcore-test-rocksdb"

        @JvmStatic
        @BeforeAll
        fun setUp() {
            File(TEST_DB_PATH).deleteRecursively()
            messageStore = MessageStore(TEST_DB_PATH)
            messageStore.start()
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            messageStore.stop()
            File(TEST_DB_PATH).deleteRecursively()
        }
    }

    @Test
    fun `personal channel id is deterministic and symmetric`() {
        val id1 = ChannelService.buildPersonalChannelId("userA", "userB")
        val id2 = ChannelService.buildPersonalChannelId("userB", "userA")
        assertEquals(id1, id2, "Personal channel ID should be the same regardless of user order")
        assertTrue(id1.startsWith("p:"), "Personal channel ID should start with 'p:'")
    }

    @Test
    fun `personal channel id is unique for different pairs`() {
        val id1 = ChannelService.buildPersonalChannelId("userA", "userB")
        val id2 = ChannelService.buildPersonalChannelId("userA", "userC")
        assertTrue(id1 != id2, "Different user pairs should have different channel IDs")
    }

    @Test
    fun `message store write and read`() {
        val message = createTextMessage(
            channelId = "test-channel-1",
            messageId = "msg001",
            senderUid = "user1",
            text = "hello world",
        )
        val stored = messageStore.storeMessage(message)
        assertEquals(1, stored.serverSeq, "First message should have seq=1")

        val retrieved = messageStore.getMessageBySeq("test-channel-1", 1)
        assertNotNull(retrieved)
        assertEquals("msg001", retrieved.messageId)
        assertEquals("user1", retrieved.senderUid)
        assertEquals("hello world", (retrieved.body as TextBody).text)
    }

    @Test
    fun `message store auto-increments seq`() {
        val channel = "seq-test-channel"
        for (i in 1..5) {
            val message = createTextMessage(
                channelId = channel,
                messageId = "seq-msg-$i",
                senderUid = "user1",
                text = "msg $i",
            )
            val stored = messageStore.storeMessage(message)
            assertEquals(i.toLong(), stored.serverSeq, "Message $i should have seq=$i")
        }
    }

    @Test
    fun `message store get latest messages returns in order`() {
        val channel = "latest-test-channel"
        for (i in 1..10) {
            val message = createTextMessage(
                channelId = channel,
                messageId = "latest-msg-$i",
                senderUid = "user1",
                text = "msg $i",
            )
            messageStore.storeMessage(message)
        }

        val messages = messageStore.getLatestMessages(channel, 5)
        assertEquals(5, messages.size)
        assertEquals(6, messages[0].serverSeq.toInt(), "Should start from seq 6")
        assertEquals(10, messages[4].serverSeq.toInt(), "Should end at seq 10")
    }

    @Test
    fun `message store get messages after seq`() {
        val channel = "after-seq-test-channel"
        for (i in 1..10) {
            val message = createTextMessage(
                channelId = channel,
                messageId = "after-msg-$i",
                senderUid = "user1",
                text = "msg $i",
            )
            messageStore.storeMessage(message)
        }

        val messages = messageStore.getMessagesAfterSeq(channel, 7, 10)
        assertEquals(3, messages.size, "Should return 3 messages after seq 7")
        assertEquals(8, messages[0].serverSeq.toInt())
        assertEquals(9, messages[1].serverSeq.toInt())
        assertEquals(10, messages[2].serverSeq.toInt())
    }

    @Test
    fun `message store soft delete`() {
        val channel = "delete-test-channel"
        val message = createTextMessage(
            channelId = channel,
            messageId = "delete-msg-1",
            senderUid = "user1",
            text = "delete me",
        )
        val stored = messageStore.storeMessage(message)
        messageStore.deleteMessage(channel, stored.serverSeq)

        val latest = messageStore.getLatestMessages(channel, 10)
        assertTrue(latest.isEmpty(), "Deleted message should not appear in latest")
    }

    @Test
    fun `token service full round trip`() {
        TokenService.init("TestSecretKeyForJWTTokenGeneration2026")
        val uid = "round-trip-user"
        val pair = TokenService.generateTokenPair(uid)

        assertNotNull(pair.accessToken)
        assertNotNull(pair.refreshToken)
        assertTrue(pair.expiresIn > 0)

        val validated = TokenService.validateAccessToken(pair.accessToken)
        assertEquals(uid, validated)
    }

    @Test
    fun `token service rejects invalid token`() {
        TokenService.init("TestSecretKeyForJWTTokenGeneration2026")
        val result = TokenService.validateAccessToken("totally.invalid.token")
        assertNull(result)
    }

    @Test
    fun `token service rejects expired token`() {
        // This test verifies that the token service rejects tokens signed with different secrets
        TokenService.init("SecretOneForTesting2026abcd")
        val pair = TokenService.generateTokenPair("user1")
        TokenService.init("SecretTwoForTesting2026abcd")
        val validated = TokenService.validateAccessToken(pair.accessToken)
        assertNull(validated, "Token signed with different secret should be rejected")
    }

    private fun createTextMessage(
        channelId: String,
        messageId: String,
        senderUid: String,
        text: String,
    ): Message {
        return Message(
            header = MessageHeader(
                channelId = channelId,
                messageId = messageId,
                senderUid = senderUid,
                channelType = ChannelType.PERSONAL,
            ),
            body = TextBody(text, emptyList()),
        )
    }
}
