package com.virjar.tk.viewmodel

import com.virjar.tk.client.LocalCache
import com.virjar.tk.model.Conversation
import com.virjar.tk.repository.ConversationRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * 会话列表 ViewModel。
 */
class ConversationViewModel(
    private val localCache: LocalCache,
    private val conversationRepo: ConversationRepository,
    dispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
) : BaseViewModel(dispatcher) {

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    init {
        scope.launch {
            localCache.observeConversations().collect { _conversations.value = it }
        }
        _conversations.value = localCache.getConversations()
        refresh()
    }

    fun refresh() {
        scope.launch {
            runViewModelAction("刷新会话失败") {
                conversationRepo.listConversations().getOrThrow()
            }
        }
    }

    fun deleteConversation(chatId: String) {
        scope.launch {
            runViewModelAction("删除会话失败") {
                conversationRepo.deleteConversation(chatId).getOrThrow()
            }
        }
    }

    /**
     * Persist pin state through the authoritative conversation service. The local projection is
     * updated only by CONVERSATION_UPDATED, so a rejected or cancelled RPC cannot show fake state.
     */
    fun setPinned(chatId: String, pinned: Boolean) {
        scope.launch {
            runViewModelAction("会话置顶失败") {
                conversationRepo.setPin(chatId, pinned).getOrThrow()
            }
        }
    }
}
