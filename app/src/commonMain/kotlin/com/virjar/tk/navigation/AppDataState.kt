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
import com.virjar.tk.navigation.feature.GroupDocumentsFeature
import com.virjar.tk.viewmodel.ChatViewModel
import com.virjar.tk.viewmodel.ContactViewModel
import com.virjar.tk.viewmodel.ConversationViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
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
open class AppDataState(val session: ClientSession) {
    val imClient get() = session.imClient
    val userSession get() = session.userSession
    val localCache get() = session.localCache
    val chatRepo get() = session.chatRepo
    val contactRepo get() = session.contactRepo
    val messageRepo get() = session.messageRepo
    val deviceRepo get() = session.deviceRepo
    val userRepo get() = session.userRepo
    val conversationRepo get() = session.conversationRepo
    val organizationRepo get() = session.organizationRepo
    val groupFileRepo get() = session.groupFileRepo
    val documentRepo get() = session.documentRepo

    private val actionScope = CoroutineScope(
        Dispatchers.Main + SupervisorJob() +
            CoroutineExceptionHandler { _, throwable -> logUnhandledError("AppDataState", throwable) },
    )

    val conversationViewModel = ConversationViewModel(localCache, conversationRepo)
    val contactViewModel = ContactViewModel(localCache, contactRepo, userSession.uid).also { viewModel ->
        session.eventProcessor.onContactChanged = { viewModel.refreshPendingApplyCount() }
    }
    var chatViewModel by mutableStateOf<ChatViewModel?>(null)
        private set

    val account = AccountFeature(session, contactViewModel, actionScope, ::handleError)
    val groups = GroupFeature(session, actionScope, ::handleError)
    val discovery = DiscoveryFeature(session, ::handleError)
    val organization = OrganizationFeature(session, ::handleError)
    val groupFiles = GroupFilesFeature(session, actionScope, ::handleError)
    val groupDocuments = GroupDocumentsFeature(session, actionScope, ::handleError)

    var error by mutableStateOf<String?>(null)
        private set

    fun destroy() {
        conversationViewModel.destroy()
        contactViewModel.destroy()
        chatViewModel?.destroy()
        actionScope.cancel()
        session.eventProcessor.onContactChanged = null
    }

    fun prepareChat(chatId: String, chatName: String, chatType: Int = ChatType.PERSONAL.code) {
        chatViewModel?.destroy()
        chatViewModel = ChatViewModel(
            chatId,
            localCache,
            messageRepo,
            session.eventProcessor,
            userSession.uid,
            session.sendQueue,
        ).apply {
            onAuthExpired = { session.close() }
        }
    }

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
            is ScreenDataKey.GroupDocuments -> groupDocuments.open(key.chatId)
        }
    }

    fun saveDraft(chatId: String, draft: String?) = actionScope.launch {
        try {
            conversationRepo.setDraft(chatId, draft?.takeIf { it.isNotBlank() })
        } catch (_: Exception) {
            // Draft persistence is best-effort and must not interrupt conversation flow.
        }
    }

    private fun handleError(throwable: Throwable, fallbackMessage: String) {
        when (throwable) {
            is AppError.AuthExpired -> {
                error = "认证失效，请重新登录"
                session.close()
            }

            is AppError.FatalCodec -> {
                error = "⚠️ 数据协议错误，请联系开发者：${throwable.message}"
            }

            is AppError -> error = throwable.message ?: fallbackMessage
            else -> error = fallbackMessage
        }
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
    data class GroupDocuments(val chatId: String) : ScreenDataKey()
}
