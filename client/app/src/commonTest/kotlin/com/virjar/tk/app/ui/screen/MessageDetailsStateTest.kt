package com.virjar.tk.app.ui.screen

import com.virjar.tk.app.navigation.UiLocalDataBoundary
import com.virjar.tk.app.viewmodel.messageDetailsProjection
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.protocol.model.Message
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MessageDetailsStateTest {
    @Test
    fun windowEvictionKeepsThePageAndCommittedEditsAndRevokesRefreshItsBody() = runTest {
        val original = message("long-message", "正文".repeat(1_000))
        val window = MutableStateFlow(listOf(original))
        val events = MutableStateFlow(0L)
        var cached: Message? = original
        var localReads = 0
        val localDispatcher = StandardTestDispatcher(testScheduler, "local-data")
        val projection = messageDetailsProjection("chat", original.clientMsgId, window, events,
            UiLocalDataBoundary(localDispatcher)) {
            assertSame(localDispatcher, currentCoroutineContext()[ContinuationInterceptor])
            localReads++
            cached
        }
        val state = MessageDetailsState().apply { open(original) }
        val observation = backgroundScope.launch { state.collect(checkNotNull(state.selection), projection) }
        runCurrent()
        assertEquals(0, localReads, "a resident snapshot needs no additional SQLite read")

        window.value = emptyList()
        runCurrent()
        assertTrue(state.isOpen)
        assertEquals(original, state.message, "losing a pager row must not dismiss its full text")

        cached = original.copy(body = RichTextBody("编辑后的正文", plainText = "编辑后的正文"))
        events.value = 1L
        runCurrent()
        assertEquals(cached, state.message, "committed events refresh a message outside the pager")
        cached = cached!!.copy(flags = Message.FLAG_REVOKED)
        events.value = 2L
        runCurrent()
        assertEquals(Message.FLAG_REVOKED, state.message?.flags)
        assertTrue(state.isOpen)

        cached = null
        events.value = 3L
        runCurrent()
        assertTrue(state.isOpen, "an explicitly unavailable message still has a back action")
        assertFalse(state.loading)
        assertNull(state.message)
        observation.cancel()
    }

    @Test
    fun closeAndReopenOfTheSameIdentityRejectsAnOldRead() = runTest {
        val original = message("same-id", "原正文")
        val late = MutableStateFlow<Message?>(original)
        val state = MessageDetailsState().apply { open(original) }
        val oldSelection = checkNotNull(state.selection)
        val observation = backgroundScope.launch { state.collect(oldSelection, late) }
        runCurrent()
        state.close()
        late.value = original.copy(flags = Message.FLAG_REVOKED)
        runCurrent()
        assertFalse(state.isOpen)
        assertNull(state.message)

        state.open(original)
        late.value = original.copy(body = RichTextBody("迟到读取", plainText = "迟到读取"))
        runCurrent()
        assertEquals(original, state.message)
        observation.cancel()
    }

    @Test
    fun restorationLoadsByIdentityWithoutPuttingTheBodyInSavedState() = runTest {
        val original = message("restored", "完整正文")
        val state = MessageDetailsState(original.clientMsgId)
        assertTrue(state.isOpen)
        assertTrue(state.loading)
        assertNull(state.message)
        val projection = messageDetailsProjection("chat", original.clientMsgId,
            MutableStateFlow(emptyList()), MutableStateFlow(0L),
            UiLocalDataBoundary(StandardTestDispatcher(testScheduler))) { original }
        val observation = backgroundScope.launch { state.collect(checkNotNull(state.selection), projection) }
        runCurrent()
        assertEquals(original, state.message)
        assertFalse(state.loading)
        observation.cancel()
    }

    private fun message(id: String, text: String) = Message(
        chatId = "chat", clientMsgId = id, serverSeq = 12L, senderUid = "sender",
        messageType = 1, timestamp = 1L, body = RichTextBody(text, plainText = text),
    )
}
