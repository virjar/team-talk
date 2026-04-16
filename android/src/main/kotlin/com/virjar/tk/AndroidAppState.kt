package com.virjar.tk

import androidx.compose.runtime.*
import com.virjar.tk.client.UserContext
import com.virjar.tk.dto.UserDto
import com.virjar.tk.navigation.NavDestination
import com.virjar.tk.viewmodel.ContactsViewModel
import com.virjar.tk.viewmodel.ConversationViewModel

/**
 * Central state holder for the Android single-window navigation.
 *
 * Replaces the scattered `mutableStateOf` variables and callback lambdas
 * that were previously threaded through every component. Child composables
 * obtain this via [LocalAppState].
 */
internal class AndroidAppState(
    val userContext: UserContext,
    val conversationVm: ConversationViewModel,
    val contactsVm: ContactsViewModel,
    initialThemeMode: ThemeMode,
    private val onThemeChange: (ThemeMode) -> Unit,
) {
    var navDestination by mutableStateOf<NavDestination>(NavDestination.Main())
        private set
    var currentUser by mutableStateOf(userContext.user)
        private set
    var themeMode by mutableStateOf(initialThemeMode)
        private set

    // ── Convenience accessors (reduce userContext.xxx repetition) ──

    val uid: String get() = userContext.uid
    val imageBaseUrl: String get() = userContext.baseUrl
    val userRepo get() = userContext.userRepo
    val contactRepo get() = userContext.contactRepo
    val channelRepo get() = userContext.channelRepo
    val fileRepo get() = userContext.fileRepo

    // ── Navigation actions ──

    fun navigateTo(dest: NavDestination) {
        navDestination = dest
    }

    fun navigateBack(initialTab: Int = 0) {
        navDestination = NavDestination.Main(initialTab)
    }

    fun updateCurrentUser(user: UserDto) {
        currentUser = user
    }

    fun toggleTheme(mode: ThemeMode) {
        themeMode = mode
        onThemeChange(mode)
    }
}

/** CompositionLocal that provides [AndroidAppState] to the Android subtree. */
internal val LocalAppState = compositionLocalOf<AndroidAppState> {
    error("No AndroidAppState provided")
}
