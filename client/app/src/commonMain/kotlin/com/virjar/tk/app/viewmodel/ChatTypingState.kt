package com.virjar.tk.app.viewmodel

import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.protocol.model.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 拥有一个 chat 的仅存活 typing 发送节流、接收 TTL 和退役边界。 */
internal class ChatTypingState(
    private val chatId: String,
    private val myUid: String,
    private val connectionState: StateFlow<ConnectionState>,
    private val scope: CoroutineScope,
    private val trySendTyping: (chatId: String) -> Boolean,
    private val monotonicNowMillis: () -> Long,
) {
    private val _typingUid = MutableStateFlow<String?>(null)
    val typingUid: StateFlow<String?> = _typingUid.asStateFlow()

    private val lock = Any()
    private val throttle = TypingLeadingThrottle()
    private var generation = 0L
    private var expiryJob: Job? = null
    private var open = true
    private var presentationActive = false
    private var presentationGeneration = 0L
    private var residentMessageIds: Set<Pair<String, String>>? = null

    /** 只在这个 chat 的 composer 中真正的用户输入文本修改时才调用。 */
    fun onUserTextChanged(chatForegroundActive: Boolean) = synchronized(lock) {
        if (
            !open ||
            !presentationActive ||
            !chatForegroundActive ||
            connectionState.value != ConnectionState.AUTHENTICATED
        ) return@synchronized
        throttle.trySend(monotonicNowMillis()) {
            if (
                !open ||
                !presentationActive ||
                connectionState.value != ConnectionState.AUTHENTICATED
            ) return@trySend false
            trySendTyping(chatId)
        }
    }

    /**
     * 把瞬时 typing 接收绑定到实际的 chat 呈现。隐藏的事件被丢弃，
     * 而退役一个呈现会立即清除它的 TTL，这样保留的 ViewModel 就不能复活它。
     */
    fun onPresentationActiveChanged(active: Boolean): Long? = synchronized(lock) {
        if (!open) return@synchronized null
        if (presentationActive != active) {
            presentationActive = active
            presentationGeneration += 1L
            if (!active) clearLocked()
        }
        presentationGeneration.takeIf { active }
    }

    /** 在续期有界 TTL 之前，把瞬时事件过滤到确切的 chat/账号。 */
    fun onEvent(presentationLease: Long, eventChatId: String, uid: String) {
        if (
            eventChatId == chatId &&
            uid != myUid &&
            connectionState.value == ConnectionState.AUTHENTICATED
        ) {
            publish(presentationLease, uid)
        }
    }

    /** 任何非认证边界都会立即移除仅存活的呈现状态。 */
    fun onConnectionStateChanged(state: ConnectionState) {
        if (state != ConnectionState.AUTHENTICATED) clear()
    }

    /** 来自活动对端的一条新驻留消息取代该对端的 typing 指示器。 */
    fun onMessagesChanged(projectedMessages: List<Message>) {
        val currentIds = projectedMessages.mapTo(linkedSetOf()) { it.chatId to it.clientMsgId }
        val previousIds = residentMessageIds
        residentMessageIds = currentIds
        if (previousIds == null) return
        val activeTypingUid = _typingUid.value ?: return
        if (projectedMessages.any { message ->
                message.senderUid == activeTypingUid &&
                    message.senderUid != myUid &&
                    (message.chatId to message.clientMsgId) !in previousIds
            }
        ) {
            clear(activeTypingUid)
        }
    }

    private fun publish(presentationLease: Long, uid: String) = synchronized(lock) {
        if (
            !open ||
            !presentationActive ||
            presentationGeneration != presentationLease ||
            connectionState.value != ConnectionState.AUTHENTICATED
        ) return@synchronized
        generation += 1L
        val expiryGeneration = generation
        expiryJob?.cancel()
        _typingUid.value = uid
        expiryJob = scope.launch {
            delay(TYPING_TTL_MILLIS)
            synchronized(lock) {
                if (open && generation == expiryGeneration && _typingUid.value == uid) {
                    expiryJob = null
                    _typingUid.value = null
                }
            }
        }
    }

    private fun clear(expectedUid: String? = null) = synchronized(lock) {
        if (expectedUid != null && _typingUid.value != expectedUid) return@synchronized
        clearLocked()
    }

    private fun clearLocked() {
        generation += 1L
        expiryJob?.cancel()
        expiryJob = null
        _typingUid.value = null
    }

    fun close() = synchronized(lock) {
        open = false
        presentationActive = false
        presentationGeneration += 1L
        clearLocked()
        residentMessageIds = null
    }

    private companion object {
        const val TYPING_TTL_MILLIS = 3_000L
    }
}
