package com.virjar.tk.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.virjar.tk.protocol.ChannelType
import com.virjar.tk.LocalAppState
import com.virjar.tk.client.UserContext
import com.virjar.tk.ui.screen.ContactsScreen
import com.virjar.tk.ui.screen.ConversationListScreen
import com.virjar.tk.ui.screen.MeScreen
import com.virjar.tk.util.AppLog
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onLogout: () -> Unit,
    initialTab: Int = 0,
) {
    val appState = LocalAppState.current
    var selectedTab by remember { mutableIntStateOf(initialTab) }
    val scope = rememberCoroutineScope()

    // Load data when switching tabs
    LaunchedEffect(selectedTab) {
        when (MainTab.entries[selectedTab]) {
            MainTab.Conversations -> {
                try { appState.conversationVm.refresh() } catch (_: Exception) {}
            }
            MainTab.Contacts -> {
                try { appState.contactsVm.loadFriends() } catch (_: Exception) {}
            }
            MainTab.Me -> {}
        }
    }

    val connectionState by appState.userContext.connectionState.collectAsState()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        icon = {
                            when (tab) {
                                MainTab.Conversations -> Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = tab.label)
                                MainTab.Contacts -> Icon(Icons.Default.Contacts, contentDescription = tab.label)
                                MainTab.Me -> Icon(Icons.Default.Person, contentDescription = tab.label)
                            }
                        },
                        label = { Text(tab.label) },
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                    )
                }
            }
        }
    ) { padding ->
        when (MainTab.entries[selectedTab]) {
            MainTab.Conversations -> ConversationListScreen(
                state = appState.conversationVm.state.collectAsState().value,
                isTcpConnected = connectionState == UserContext.ConnectionState.CONNECTED,
                imageBaseUrl = appState.imageBaseUrl,
                onSelectConversation = { channelId, channelType, channelName ->
                    val readSeq = appState.conversationVm.state.value.conversations
                        .find { it.channelId == channelId }?.readSeq ?: 0
                    appState.navigateTo(NavDestination.Chat(channelId, ChannelType.fromCode(channelType), channelName, readSeq))
                },
                onDeleteConversation = { channelId ->
                    scope.launch { appState.conversationVm.deleteConversation(channelId) }
                },
                onTogglePin = { channelId, pinned ->
                    scope.launch { appState.conversationVm.togglePin(channelId, pinned) }
                },
                onToggleMute = { channelId, muted ->
                    scope.launch { appState.conversationVm.toggleMute(channelId, muted) }
                },
                onSearchMessages = { appState.navigateTo(NavDestination.SearchMessages()) },
                modifier = Modifier.padding(padding),
            )

            MainTab.Contacts -> ContactsScreen(
                state = appState.contactsVm.contactsState.collectAsState().value,
                imageBaseUrl = appState.imageBaseUrl,
                onSearchClick = { appState.navigateTo(NavDestination.SearchUsers) },
                onFriendAppliesClick = { appState.navigateTo(NavDestination.FriendApplies) },
                onFriendClick = { uid, friendName ->
                    scope.launch {
                        try {
                            val channel = appState.channelRepo.ensurePersonalChannel(uid)
                            val name = channel.name.ifEmpty { friendName }
                            appState.navigateTo(NavDestination.Chat(
                                channelId = channel.channelId,
                                channelType = ChannelType.fromCode(channel.channelType),
                                channelName = name,
                                otherUid = uid,
                            ))
                        } catch (e: Exception) {
                            AppLog.e("MainScreen", "ensurePersonalChannel failed", e)
                        }
                    }
                },
                onCreateGroupClick = { appState.navigateTo(NavDestination.CreateGroup) },
                modifier = Modifier.padding(padding),
            )

            MainTab.Me -> MeScreen(
                currentUser = appState.currentUser,
                imageBaseUrl = appState.imageBaseUrl,
                themeMode = appState.themeMode,
                onToggleTheme = { appState.toggleTheme(it) },
                onEditProfile = { appState.navigateTo(NavDestination.EditProfile) },
                onChangePassword = { appState.navigateTo(NavDestination.ChangePassword) },
                onBlacklist = { appState.navigateTo(NavDestination.Blacklist) },
                onDeviceManagement = { appState.navigateTo(NavDestination.DeviceManagement) },
                onLogout = onLogout,
                modifier = Modifier.padding(padding),
            )
        }
    }
}
