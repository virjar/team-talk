package com.virjar.tk.bot

import com.virjar.tk.model.Chat
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.PresencePayload
import com.virjar.tk.testing.FakeLocalCache
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ImBotBufferingTest {
    @Test
    fun `transient event buffers are bounded observable and presence is conflated`() = runBlocking {
        val buffers = ImBotEventBuffers()
        repeat(ImBotEventBuffers.CONTACT_CAPACITY + 2) { buffers.offerContact() }
        repeat(ImBotEventBuffers.CHAT_CAPACITY + 3) { index ->
            buffers.offerChat(NotifyType.CHAT_UPDATED to Chat(chatId = "chat-$index", chatType = 2))
        }
        buffers.offerPresence(PresencePayload("u1", PresencePayload.STATUS_ONLINE, 1L))
        buffers.offerPresence(PresencePayload("u2", PresencePayload.STATUS_ONLINE, 2L))

        assertEquals(2L, buffers.overflow.value.contactEventsDropped)
        assertEquals(3L, buffers.overflow.value.chatEventsDropped)
        assertEquals("u2", buffers.receivePresence().uid)
        buffers.close()
    }

    @Test
    fun `closing event buffers and disk inbox releases blocked receivers`() = runBlocking {
        val buffers = ImBotEventBuffers()
        buffers.close()
        assertFailsWith<ClosedReceiveChannelException> { buffers.receiveContact() }

        val inbox = ImBotMessageInbox().also { it.bind(FakeLocalCache()) }
        inbox.close()
        assertNull(inbox.receivePendingOrNull())
    }
}
