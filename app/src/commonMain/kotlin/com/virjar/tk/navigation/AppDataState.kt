package com.virjar.tk.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.virjar.tk.AppError
import com.virjar.tk.client.ClientSession
import com.virjar.tk.client.logUnhandledError
import com.virjar.tk.model.ChatType
import com.virjar.tk.navigation.feature.AccountFeature
import com.virjar.tk.navigation.feature.DiscoveryFeature
import com.virjar.tk.navigation.feature.GroupFeature
import com.virjar.tk.navigation.feature.OrganizationFeature
import com.virjar.tk.navigation.feature.GroupFilesFeature
import com.virjar.tk.navigation.feature.DocumentDraftStore
import com.virjar.tk.navigation.feature.DocumentWorkspaceFeature
import com.virjar.tk.ui.screen.ChatComposerContextStore
import com.virjar.tk.viewmodel.ChatViewModel
import com.virjar.tk.viewmodel.ContactViewModel
import com.virjar.tk.viewmodel.ConversationViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Session-scoped client composition root.
 *
 * This object owns shared ViewModels, feature controllers and their coroutine
 * lifetime. Platform navigation remains in Android/Desktop shells; feature
 * state and actions live in [account], [groups] and [discovery].
 */
open class AppDataState(
    private val session: ClientSession,
    val chatComposerContexts: ChatComposerContextStore = ChatComposerContextStore(),
    val documentDrafts: DocumentDraftStore = DocumentDraftStore(),
    private val onAuthExpired: () -> Unit = { session.close() },
) {
    val userSession get() = session.userSession

    /** Narrow synchronous lookup for platform renderers; the cache itself remains session-owned. */
    fun cachedUser(uid: String) = session.localCache.getUser(uid)

    private val activeChat = ActiveChatBinding()

    private val actionScope = CoroutineScope(
        Dispatchers.Main + SupervisorJob() +
            CoroutineExceptionHandler { _, throwable -> logUnhandledError("AppDataState", throwable) },
    )

    val conversationViewModel = ConversationViewModel(session.localCache, session.conversationRepo)
    val contactViewModel = ContactViewModel(
        localCache = session.localCache,
        contactRepo = session.contactRepo,
        myUid = userSession.uid,
        contactEvents = session.eventProcessor.contactEvents,
    )
    var chatViewModel by mutableStateOf<ChatViewModel?>(null)
        private set

    val account = AccountFeature(session, contactViewModel, actionScope, ::handleError)
    val groups = GroupFeature(session, actionScope, ::handleError)
    val discovery = DiscoveryFeature(session, ::handleError)
    val organization = OrganizationFeature(session, ::handleError)
    val groupFiles = GroupFilesFeature(session, actionScope, ::handleError)
    val documents = DocumentWorkspaceFeature(session, actionScope, ::handleError, documentDrafts)

    var error by mutableStateOf<String?>(null)
        private set

    fun destroy(
        clearComposerContexts: Boolean = true,
        clearDocumentDrafts: Boolean = clearComposerContexts,
    ) {
        conversationViewModel.destroy()
        contactViewModel.destroy()
        chatViewModel?.destroy()
        chatViewModel = null
        activeChat.clear()
        if (clearComposerContexts) chatComposerContexts.clear()
        // Platform persistence owns its async durability queue. Retaining drafts requires no
        // blocking work here; Android captures the editor and schedules a barrier before destroy.
        if (clearDocumentDrafts) documentDrafts.clearAndRetire(userSession.uid)
        actionScope.cancel()
    }

    fun prepareChat(chatId: String, chatName: String, chatType: Int = ChatType.PERSONAL.code) {
        ensureChat(chatId, chatName, chatType)
    }

    /**
     * Ensure the session-scoped chat ViewModel belongs to the route being rendered.
     *
     * Android can restore a CHAT back-stack entry without replaying the click that originally
     * navigated there. Keeping this operation idempotent lets the destination own preparation,
     * while preserving an already-live ViewModel (and its loaded message window) on normal entry.
     */
    fun ensureChat(chatId: String, chatName: String, chatType: Int = ChatType.PERSONAL.code) {
        if (!activeChat.needsPreparation(chatId, chatViewModel != null)) return
        chatViewModel?.destroy()
        chatViewModel = ChatViewModel(
            chatId,
            session.localCache,
            session.messageRepo,
            session.eventProcessor,
            userSession.uid,
            session.sendQueue,
        ).apply {
            onAuthExpired = { this@AppDataState.onAuthExpired() }
        }
        activeChat.markPrepared(chatId)
    }

    /** Never expose another route's ViewModel during navigation/back-stack transitions. */
    fun chatViewModelFor(chatId: String): ChatViewModel? =
        chatViewModel.takeIf { activeChat.matches(chatId, it != null) }

    fun clearError() {
        error = null
    }

    suspend fun loadScreenDataByKey(key: ScreenDataKey) {
        when (key) {
            ScreenDataKey.Devices -> account.loadDevices()
            ScreenDataKey.Blacklist -> account.loadBlacklist()
            ScreenDataKey.FriendApplies -> account.loadFriendApplies()
            is ScreenDataKey.GroupDetail -> groups.loadDetail(key.chatId)
            is ScreenDataKey.UserProfile -> account.loadProfile(key.uid)
            is ScreenDataKey.InviteLinks -> groups.loadInviteLinks(key.chatId)
            is ScreenDataKey.GroupFiles -> groupFiles.open(key.chatId)
            is ScreenDataKey.GroupBots -> groups.loadGroupBots(key.chatId)
            ScreenDataKey.Documents -> documents.open()
        }
    }

    fun saveDraft(chatId: String, draft: String?) {
        val normalized = draft?.takeIf { it.isNotBlank() }
        // 本地缓存同步落盘，保证立即返回/断网也不丢；Repository 会把远端镜像严格串行，
        // 已发出的旧 RPC 完成后才会发送清空请求，避免服务端乱序复活旧草稿。
        val generation = session.conversationRepo.setDraftLocal(chatId, normalized)
        actionScope.launch {
            try {
                session.conversationRepo.mirrorDraft(chatId, generation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Draft mirroring is best-effort and must not interrupt conversation flow.
            }
        }
    }

    /** Persist an explicit conversation-list "mark read" action on every device. */
    fun markConversationRead(chatId: String, readSeq: Long) {
        if (readSeq <= 0L) return
        actionScope.launch {
            try {
                session.messageRepo.markRead(chatId, readSeq).getOrThrow()
                session.localCache.markConversationRead(chatId, readSeq)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                handleError(throwable, "标记已读失败")
            }
        }
    }

    private fun handleError(throwable: Throwable, fallbackMessage: String) {
        if (throwable is CancellationException) throw throwable
        when (throwable) {
            is AppError.AuthExpired -> {
                error = "认证失效，请重新登录"
                onAuthExpired()
            }

            is AppError.FatalCodec -> {
                error = "⚠️ 数据协议错误，请联系开发者：${throwable.message}"
            }

            is AppError -> error = throwable.message ?: fallbackMessage
            else -> error = fallbackMessage
        }
    }
}

/** Pure route-to-owner binding kept separate so lifecycle/idempotency rules are unit-testable. */
internal class ActiveChatBinding {
    private var preparedChatId: String? = null

    fun needsPreparation(routeChatId: String, hasViewModel: Boolean): Boolean =
        !hasViewModel || preparedChatId != routeChatId

    fun matches(routeChatId: String, hasViewModel: Boolean): Boolean =
        hasViewModel && preparedChatId == routeChatId

    fun markPrepared(chatId: String) {
        preparedChatId = chatId
    }

    fun clear() {
        preparedChatId = null
    }
}

sealed class ScreenDataKey {
    data object Devices : ScreenDataKey()
    data object Blacklist : ScreenDataKey()
    data object FriendApplies : ScreenDataKey()
    data class GroupDetail(val chatId: String) : ScreenDataKey()
    data class UserProfile(val uid: String) : ScreenDataKey()
    data class InviteLinks(val chatId: String) : ScreenDataKey()
    data class GroupFiles(val chatId: String) : ScreenDataKey()
    data class GroupBots(val chatId: String) : ScreenDataKey()
    data object Documents : ScreenDataKey()
}
