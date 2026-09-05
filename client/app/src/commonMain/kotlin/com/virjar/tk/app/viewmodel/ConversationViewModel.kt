package com.virjar.tk.app.viewmodel

import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.shared.client.LocalCache
import com.virjar.tk.protocol.model.Conversation
import com.virjar.tk.protocol.model.User
import com.virjar.tk.app.navigation.UiLocalDataBoundary
import com.virjar.tk.shared.repository.ConversationRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * 会话列表 ViewModel。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConversationViewModel(
    private val localCache: LocalCache,
    private val conversationRepo: ConversationRepository,
    private val connectionState: StateFlow<ConnectionState>,
    dispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
    onAuthExpired: () -> Unit = {},
    private val localData: UiLocalDataBoundary = UiLocalDataBoundary(dispatcher),
) : BaseViewModel(dispatcher, onAuthExpired) {

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()
    private val _peerUsers = MutableStateFlow<Map<String, User>>(emptyMap())
    /** 个人行的存活规范化用户；Conversation 字段保持冷启动快照。 */
    val peerUsers: StateFlow<Map<String, User>> = _peerUsers.asStateFlow()

    init {
        scope.launch {
            localData.projection(localCache::observeConversations).collect { _conversations.value = it }
        }
        scope.launch {
            conversations
                .map { items -> items.mapNotNull(Conversation::peerUid).distinct() }
                .distinctUntilChanged()
                .flatMapLatest(::observePeerUsers)
                .collect { _peerUsers.value = it }
        }
        scope.launch {
            connectionState.collectLatest { state ->
                if (state == ConnectionState.AUTHENTICATED) refreshFromServer()
            }
        }
    }

    private fun observePeerUsers(peerUids: List<String>): Flow<Map<String, User>> {
        if (peerUids.isEmpty()) return flowOf(emptyMap())
        val peers = peerUids.map { uid ->
            localData.projection { localCache.observeUser(uid) }
                .map { user -> uid to user }
        }
        return combine(peers) { values ->
            buildMap {
                values.forEach { (uid, user) -> if (user != null) put(uid, user) }
            }
        }
    }

    fun refresh() {
        if (connectionState.value != ConnectionState.AUTHENTICATED) return
        scope.launch { refreshFromServer() }
    }

    private suspend fun refreshFromServer() = runViewModelAction("刷新会话失败") {
        localData.run { conversationRepo.listConversations().getOrThrow() }
    }

    fun deleteConversation(chatId: String) {
        scope.launch {
            runViewModelAction("删除会话失败") {
                localData.run { conversationRepo.deleteConversation(chatId).getOrThrow() }
            }
        }
    }

    /**
     * 通过权威会话服务持久化置顶状态。本地投影只由 CONVERSATION_UPDATED 更新，
     * 因此被拒绝或取消的 RPC 不可能显示假状态。
     */
    fun setPinned(chatId: String, pinned: Boolean) {
        scope.launch {
            runViewModelAction("会话置顶失败") {
                localData.run { conversationRepo.setPin(chatId, pinned).getOrThrow() }
            }
        }
    }

    /**
     * 通过会话服务持久化免打扰状态。CONVERSATION_UPDATED 仍然是本地投影的唯一
     * 事实来源，与上面的置顶流程一致。
     */
    fun setMuted(chatId: String, muted: Boolean) {
        scope.launch {
            runViewModelAction("会话免打扰设置失败") {
                localData.run { conversationRepo.setMute(chatId, muted).getOrThrow() }
            }
        }
    }
}
