package com.virjar.tk

import androidx.compose.runtime.*
import com.virjar.tk.protocol.ChannelType
import com.virjar.tk.client.UserContext
import com.virjar.tk.dto.UserDto
import com.virjar.tk.navigation.NavDestination
import com.virjar.tk.viewmodel.ContactsViewModel
import com.virjar.tk.viewmodel.ConversationViewModel

/**
 * Central state holder for the Desktop three-column layout.
 *
 * Replaces the scattered `mutableStateOf` variables and callback lambdas
 * that were previously threaded through every component. Child composables
 * obtain this via [LocalDesktopState].
 */
internal class DesktopAppState(
    val userContext: UserContext,
    val conversationVm: ConversationViewModel,
    val contactsVm: ContactsViewModel,
    initialThemeMode: ThemeMode,
    private val onThemeChange: (ThemeMode) -> Unit,
) {
    var currentUser by mutableStateOf(userContext.user)
        private set
    var selectedTab by mutableIntStateOf(0)
        private set
    var selectedChat by mutableStateOf<ChatTarget?>(null)
        private set
    var overlayDestination by mutableStateOf<NavDestination?>(null)
        private set
    var themeMode by mutableStateOf(initialThemeMode)
        private set

    // ── Navigation actions ──

    fun selectTab(tab: Int) {
        selectedTab = tab
        selectedChat = null
        overlayDestination = null
    }

    fun selectChat(target: ChatTarget) {
        selectedChat = target
        overlayDestination = null
    }

    fun selectChatFromConversation(channelId: String, channelType: Int, channelName: String) {
        val readSeq = conversationVm.state.value.conversations
            .find { it.channelId == channelId }?.readSeq ?: 0
        selectChat(ChatTarget(channelId, ChannelType.fromCode(channelType), channelName, readSeq))
    }

    fun navigateOverlay(dest: NavDestination) {
        overlayDestination = dest
    }

    fun navigateOverlayClearChat(dest: NavDestination) {
        selectedChat = null
        overlayDestination = dest
    }

    fun clearOverlay() {
        overlayDestination = null
    }

    fun updateCurrentUser(user: UserDto) {
        currentUser = user
    }

    fun toggleTheme(mode: ThemeMode) {
        themeMode = mode
        onThemeChange(mode)
    }

    fun onAvatarClick() {
        selectedChat = null
        overlayDestination = NavDestination.Me
    }
}

/** CompositionLocal that provides [DesktopAppState] to the Desktop subtree. */
internal val LocalDesktopState = compositionLocalOf<DesktopAppState> {
    error("No DesktopAppState provided")
}
