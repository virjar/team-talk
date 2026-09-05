package com.virjar.tk.shared.bot

import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.PresencePayload
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 非权威提示缓冲发生的丢弃计数；消费者收到提示后应从 Repository 重拉详情。 */
data class ImBotEventBufferOverflow(
    val contactEventsDropped: Long = 0L,
    val chatEventsDropped: Long = 0L,
)

/** contact/chat 是有界 wake-up，presence 只保留最新值；权威事实始终在 LocalCache/RPC。 */
internal class ImBotEventBuffers {
    private val contactChannel = Channel<Unit>(CONTACT_CAPACITY)
    private val chatChannel = Channel<Pair<NotifyType, Chat>>(CHAT_CAPACITY)
    private val presenceChannel = Channel<PresencePayload>(Channel.CONFLATED)
    private val _overflow = MutableStateFlow(ImBotEventBufferOverflow())
    val overflow: StateFlow<ImBotEventBufferOverflow> = _overflow.asStateFlow()

    fun offerContact() {
        if (contactChannel.trySend(Unit).isFailure) {
            _overflow.update { it.copy(contactEventsDropped = it.contactEventsDropped + 1L) }
        }
    }

    fun offerChat(event: Pair<NotifyType, Chat>) {
        if (chatChannel.trySend(event).isFailure) {
            _overflow.update { it.copy(chatEventsDropped = it.chatEventsDropped + 1L) }
        }
    }

    fun offerPresence(event: PresencePayload) {
        presenceChannel.trySend(event)
    }

    suspend fun receiveContact(): Unit = contactChannel.receive()
    suspend fun receiveChat(): Pair<NotifyType, Chat> = chatChannel.receive()
    suspend fun receivePresence(): PresencePayload = presenceChannel.receive()

    fun close() {
        contactChannel.close()
        chatChannel.close()
        presenceChannel.close()
    }

    companion object {
        const val CONTACT_CAPACITY = 32
        const val CHAT_CAPACITY = 64
    }
}
