package com.virjar.tk.app.viewmodel

import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.protocol.model.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 拥有一个 chat 的瞬时 typing 的可见呈现租约和事件订阅。 */
internal class ChatTypingPresentationOwner(
    chatId: String,
    myUid: String,
    connectionState: StateFlow<ConnectionState>,
    private val typingEvents: Flow<Pair<String, String>>,
    private val scope: CoroutineScope,
    trySendTyping: (chatId: String) -> Boolean,
    monotonicNowMillis: () -> Long,
) {
    private val state = ChatTypingState(
        chatId = chatId,
        myUid = myUid,
        connectionState = connectionState,
        scope = scope,
        trySendTyping = trySendTyping,
        monotonicNowMillis = monotonicNowMillis,
    )
    val typingUid: StateFlow<String?> = state.typingUid

    private val lock = Any()
    private var eventsJob: Job? = null
    private var open = true
    private var presentationActive = false

    fun onUserTextChanged(chatForegroundActive: Boolean) =
        state.onUserTextChanged(chatForegroundActive)

    fun onConnectionStateChanged(connectionState: ConnectionState) =
        state.onConnectionStateChanged(connectionState)

    fun onMessagesChanged(messages: List<Message>) = state.onMessagesChanged(messages)

    fun onPresentationActiveChanged(active: Boolean) = synchronized(lock) {
        if (!open || presentationActive == active) return@synchronized
        presentationActive = active
        eventsJob?.cancel()
        eventsJob = null
        val presentationLease = state.onPresentationActiveChanged(active) ?: return@synchronized
        // 在返回之前建立 SharedFlow 订阅。隐藏的发射没有活动的订阅者，
        // 而已取消的订阅者如果其回调被排队，则保留它的旧租约。
        eventsJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            typingEvents.collect { (chatId, uid) ->
                state.onEvent(presentationLease, chatId, uid)
            }
        }
    }

    fun close() = synchronized(lock) {
        if (!open) return@synchronized
        open = false
        presentationActive = false
        eventsJob?.cancel()
        eventsJob = null
        state.close()
    }
}
