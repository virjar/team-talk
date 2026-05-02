package com.virjar.tk.viewmodel

import com.virjar.tk.protocol.ChannelType
import com.virjar.tk.client.UserContext
import com.virjar.tk.dto.ConversationDto
import com.virjar.tk.util.AppLog
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ConversationListState(
    val conversations: List<ConversationDto> = emptyList(),
    val onlineStatus: Map<String, Boolean> = emptyMap(),
    val avatarMap: Map<String, String> = emptyMap(),
    val isLoading: Boolean = false,
    val error: String = "",
)

class ConversationViewModel(
    private val ctx: UserContext,
) : BaseViewModel() {
    private val conversationRepo = ctx.conversationRepo
    private val userRepo = ctx.userRepo
    private val channelRepo = ctx.channelRepo
    private val myUid = ctx.uid

    private val _state = MutableStateFlow(ConversationListState())
    val state: StateFlow<ConversationListState> = _state.asStateFlow()

    init {
        // 实时订阅 PRESENCE 在线状态更新
        scope.launch {
            ctx.onlineStatus.collect { statusMap ->
                _state.value = _state.value.copy(onlineStatus = statusMap)
            }
        }
    }

    suspend fun refresh() {
        // Phase 1: 先从本地 DB 读取缓存数据并立即展示
        val cached = try {
            conversationRepo.getCachedConversations()
        } catch (e: Exception) {
            AppLog.w("ConvVM", "getCachedConversations failed", e)
            emptyList()
        }
        if (cached.isNotEmpty()) {
            _state.value = _state.value.copy(conversations = cached, isLoading = true, error = "")
        } else {
            _state.value = _state.value.copy(isLoading = true, error = "")
        }

        // Phase 2: 后台网络同步
        try {
            conversationRepo.clearExpiredDrafts()  // 清理 >30 天的过期草稿
            val conversations = conversationRepo.syncConversations()
            // Query online status for personal channel peers
            val peerUids = conversations
                .filter { it.channelType == ChannelType.PERSONAL.code }
                .mapNotNull { conv ->
                    val parts = conv.channelId.split(":")
                    if (parts.size >= 3) {
                        parts.drop(1)
                    } else emptyList()
                }
                .flatten()
                .distinct()
            val onlineStatus = try {
                userRepo.getOnlineStatus(peerUids)
            } catch (e: Exception) {
                AppLog.w("ConvVM", "getOnlineStatus failed", e)
                emptyMap()
            }

            // Preload avatars for conversations
            val avatarMap = mutableMapOf<String, String>()
            try {
                coroutineScope {
                    val deferreds = conversations.map { conv ->
                        async {
                            try {
                                when (conv.channelType) {
                                    ChannelType.PERSONAL.code -> {
                                        // Personal chat: find peer uid (skip "p" prefix)
                                        val parts = conv.channelId.split(":")
                                        if (parts.size >= 3) {
                                            val peerUid = parts.drop(1).first { it != myUid }
                                            val user = userRepo.getUser(peerUid)
                                            if (!user.avatar.isNullOrEmpty()) {
                                                avatarMap[conv.channelId] = user.avatar
                                            }
                                        }
                                    }
                                    ChannelType.GROUP.code -> {
                                        // Group chat
                                        val channel = channelRepo.getChannel(conv.channelId)
                                        if (!channel.avatar.isNullOrEmpty()) {
                                            avatarMap[conv.channelId] = channel.avatar
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                AppLog.w("ConvVM", "Avatar preload failed for ${conv.channelId}", e)
                            }
                        }
                    }
                    deferreds.forEach { it.await() }
                }
            } catch (e: Exception) {
                AppLog.w("ConvVM", "Avatar preload coroutineScope failed", e)
            }

            _state.value = ConversationListState(
                conversations = conversations,
                onlineStatus = onlineStatus,
                avatarMap = avatarMap,
            )
            // 合并 HTTP 初始状态到实时 PRESENCE 数据源
            ctx.mergeOnlineStatus(onlineStatus)
        } catch (e: Exception) {
            AppLog.e("ConvVM", "refresh failed", e)
            if (cached.isNotEmpty()) {
                _state.value = _state.value.copy(isLoading = false)
            } else {
                _state.value = _state.value.copy(isLoading = false, error = e.message ?: "Failed to load")
            }
        }
    }

    suspend fun markRead(channelId: String, readSeq: Long) {
        try {
            conversationRepo.markRead(channelId, readSeq)
            refresh()
        } catch (e: Exception) {
            AppLog.w("ConvVM", "markRead failed: channelId=$channelId", e)
        }
    }

    suspend fun deleteConversation(channelId: String) {
        try {
            conversationRepo.deleteConversation(channelId)
            refresh()
        } catch (e: Exception) {
            AppLog.e("ConvVM", "deleteConversation failed: channelId=$channelId", e)
        }
    }

    suspend fun togglePin(channelId: String, pinned: Boolean) {
        try {
            conversationRepo.updatePin(channelId, pinned)
            refresh()
        } catch (e: Exception) {
            AppLog.e("ConvVM", "togglePin failed: channelId=$channelId", e)
        }
    }

    suspend fun toggleMute(channelId: String, muted: Boolean) {
        try {
            conversationRepo.updateMute(channelId, muted)
            refresh()
        } catch (e: Exception) {
            AppLog.e("ConvVM", "toggleMute failed: channelId=$channelId", e)
        }
    }

    fun updateConversations(conversations: List<ConversationDto>) {
        _state.value = _state.value.copy(conversations = conversations, isLoading = false)
    }

    suspend fun updateDraft(channelId: String, draft: String) {
        try {
            conversationRepo.updateDraft(channelId, draft)
        } catch (e: Exception) {
            AppLog.w("ConvVM", "updateDraft failed: channelId=$channelId", e)
        }
    }

    suspend fun clearDraft(channelId: String) {
        try {
            conversationRepo.updateDraft(channelId, "")
        } catch (e: Exception) {
            AppLog.w("ConvVM", "clearDraft failed: channelId=$channelId", e)
        }
    }
}
