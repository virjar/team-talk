package com.virjar.tk.shared.testkit

import com.virjar.tk.shared.client.MessagePager
import com.virjar.tk.shared.client.MessagePageLoadResult
import com.virjar.tk.protocol.model.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile

internal val fakeMessageOrder = compareByDescending<Message> {
    if (it.serverSeq > 0L) it.serverSeq else Long.MAX_VALUE
}.thenByDescending { it.timestamp }

internal fun fakeInitialMessages(messages: List<Message>, limit: Int): List<Message> {
    require(limit > 0)
    val authority = messages.filter { it.serverSeq > 0L }
    val optimisticLimit = limit - if (authority.isEmpty()) 0 else 1
    val optimistic = messages.asSequence().filter { it.serverSeq == 0L }
        .take(optimisticLimit.coerceAtLeast(0)).toList()
    return optimistic + authority.take((limit - optimistic.size).coerceAtLeast(0))
}

/** 直接镜像 [FakeLocalCache] 的简化分页器。 */
internal class SimpleMessagePager(
    private val chatId: String,
    cache: FakeLocalCache,
    private val cacheUseGate: FakeCacheUseGate,
    private val windowSize: Int,
    onClose: (SimpleMessagePager) -> Unit,
) : MessagePager {
    private val ownerLock = Any()
    private val retired = MutableStateFlow(false)
    private var open = true
    private var closeCallback: ((SimpleMessagePager) -> Unit)? = onClose
    private var activeMessageCollectors = 0
    internal var closeOverlappedMessageCollector = false
        private set

    init {
        require(chatId.isNotBlank()) { "chatId must not be blank" }
        require(windowSize in 1..MessagePager.MAX_WINDOW_SIZE) {
            "windowSize must be between 1 and ${MessagePager.MAX_WINDOW_SIZE}"
        }
    }

    private val projectedMessages: Flow<List<Message>> = combine(
        cache.messageFlowForPager(chatId),
        retired,
    ) { messages, isRetired -> FakePagerPublication(messages, isRetired) }
        .takeWhile { publication -> !publication.retired }
        .map { publication -> fakeInitialMessages(publication.messages, windowSize) }

    override val messages: Flow<List<Message>> = flow {
        synchronized(ownerLock) { activeMessageCollectors++ }
        try {
            emitAll(projectedMessages)
        } finally {
            synchronized(ownerLock) { activeMessageCollectors-- }
        }
    }

    override val hasMore: StateFlow<Boolean> = MutableStateFlow(false)
    override fun loadMore(pageSize: Int): MessagePageLoadResult = cacheUseGate.use {
        synchronized(ownerLock) {
            check(open) { "MessagePager is closed" }
            require(pageSize in 1..MessagePager.MAX_PAGE_SIZE) {
                "pageSize must be between 1 and ${MessagePager.MAX_PAGE_SIZE}"
            }
            MessagePageLoadResult.Exhausted
        }
    }

    override fun close() {
        retire()?.invoke(this)
    }

    internal fun retireFromCache() {
        retire()
    }

    private fun retire(): ((SimpleMessagePager) -> Unit)? = synchronized(ownerLock) {
        if (!open) return@synchronized null
        open = false
        if (activeMessageCollectors > 0) closeOverlappedMessageCollector = true
        retired.value = true
        closeCallback.also { closeCallback = null }
    }
}

private data class FakePagerPublication(
    val messages: List<Message>,
    val retired: Boolean,
)
