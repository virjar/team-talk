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
) : BaseViewModel() {

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
            try { conversationRepo.listConversations() }
            catch (e: Exception) { setError("刷新会话失败: ${e.message}") }
        }
    }

    fun setPin(chatId: String, pinned: Boolean) {
        scope.launch {
            try { conversationRepo.setPin(chatId, pinned) }
            catch (e: Exception) { setError("设置置顶失败: ${e.message}") }
        }
    }

    fun setDraft(chatId: String, draft: String?) {
        scope.launch {
            try { conversationRepo.setDraft(chatId, draft) }
            catch (e: Exception) { setError("保存草稿失败: ${e.message}") }
        }
    }

    fun setMute(chatId: String, muted: Boolean) {
        scope.launch {
            try { conversationRepo.setMute(chatId, muted) }
            catch (e: Exception) { setError("设置静音失败: ${e.message}") }
        }
    }

    fun deleteConversation(chatId: String) {
        scope.launch {
            try { conversationRepo.deleteConversation(chatId) }
            catch (e: Exception) { setError("删除会话失败: ${e.message}") }
        }
    }
}
