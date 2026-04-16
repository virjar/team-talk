package com.virjar.tk.viewmodel

import androidx.compose.runtime.Immutable
import com.virjar.tk.client.UserContext
import com.virjar.tk.dto.MessageSearchResult
import com.virjar.tk.util.toUserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Immutable
data class MessageSearchState(
    val query: String = "",
    val results: List<MessageSearchResult> = emptyList(),
    val total: Int = 0,
    val isSearching: Boolean = false,
    val error: String = "",
    val senderUid: String = "",
    val startTimestamp: Long? = null,
    val endTimestamp: Long? = null,
)

class SearchViewModel(private val ctx: UserContext) {
    private val chatRepo = ctx.chatRepo

    private val _state = MutableStateFlow(MessageSearchState())
    val state: StateFlow<MessageSearchState> = _state.asStateFlow()

    suspend fun search(
        query: String,
        channelId: String? = null,
        senderUid: String? = null,
        startTimestamp: Long? = null,
        endTimestamp: Long? = null,
    ) {
        _state.value = MessageSearchState(
            query = query,
            isSearching = true,
            senderUid = senderUid ?: "",
            startTimestamp = startTimestamp,
            endTimestamp = endTimestamp,
        )
        try {
            val response = chatRepo.searchMessages(
                query, channelId, senderUid, startTimestamp, endTimestamp
            )
            _state.value = _state.value.copy(
                results = response.results,
                total = response.total,
                isSearching = false,
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(isSearching = false, error = e.toUserMessage())
        }
    }

    suspend fun loadMore(channelId: String? = null) {
        val current = _state.value
        if (current.isSearching || current.results.size >= current.total) return
        _state.value = current.copy(isSearching = true)
        try {
            val senderUid = current.senderUid.ifBlank { null }
            val response = chatRepo.searchMessages(
                current.query, channelId, senderUid,
                current.startTimestamp, current.endTimestamp,
                offset = current.results.size,
            )
            _state.value = current.copy(
                results = current.results + response.results,
                isSearching = false,
            )
        } catch (e: Exception) {
            _state.value = current.copy(isSearching = false, error = e.toUserMessage())
        }
    }

    fun clear() {
        _state.value = MessageSearchState()
    }
}
