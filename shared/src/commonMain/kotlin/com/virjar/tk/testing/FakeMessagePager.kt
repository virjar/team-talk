package com.virjar.tk.testing

import com.virjar.tk.client.CacheUseGate
import com.virjar.tk.client.MessagePager
import com.virjar.tk.client.MessagePageLoadResult
import com.virjar.tk.model.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

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

/** Simplified pager which directly mirrors [FakeLocalCache]. */
internal class SimpleMessagePager(
    private val chatId: String,
    private val cache: FakeLocalCache,
    private val cacheUseGate: CacheUseGate,
    private val windowSize: Int,
) : MessagePager {
    override val messages: Flow<List<Message>> get() =
        cache.observeMessages(chatId).map { fakeInitialMessages(it, windowSize) }
    override val hasMore: StateFlow<Boolean> = MutableStateFlow(false)
    override fun loadMore(pageSize: Int): MessagePageLoadResult = cacheUseGate.use {
        MessagePageLoadResult.Exhausted
    }
}
