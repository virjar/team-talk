package com.virjar.tk

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.virjar.tk.protocol.ChannelType
import com.virjar.tk.navigation.NavDestination
import com.virjar.tk.ui.screen.ContactsScreen
import com.virjar.tk.ui.screen.ConversationListScreen
import com.virjar.tk.util.AppLog
import kotlinx.coroutines.launch

// ──────────────────────────── List Panel ────────────────────────────

@Composable
internal fun DesktopListPanel(
    panelBg: Color,
) {
    val appState = LocalDesktopState.current
    val scope = rememberCoroutineScope()
    val item = SidebarMenuItems[appState.selectedTab]

    Box(
        modifier = Modifier
            .width(320.dp)
            .fillMaxHeight()
            .clip(PanelShape)
            .background(panelBg),
    ) {
        if (!item.isFunctional) {
            // Placeholder for unimplemented features
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = item.selectedIcon,
                        contentDescription = item.label,
                        modifier = Modifier.size(48.dp),
                        tint = IconUnselectedColor.copy(alpha = 0.5f),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "${item.label} — Coming Soon",
                        style = MaterialTheme.typography.bodyLarge,
                        color = IconUnselectedColor,
                    )
                }
            }
        } else {
            when (appState.selectedTab) {
                0 -> {
                    val connectionState = appState.userContext.connectionState.collectAsState().value
                    ConversationListScreen(
                        state = appState.conversationVm.state.collectAsState().value,
                        isTcpConnected = connectionState == com.virjar.tk.client.UserContext.ConnectionState.CONNECTED,
                        imageBaseUrl = appState.userContext.baseUrl,
                        onSelectConversation = { channelId, channelType, channelName ->
                            appState.selectChatFromConversation(channelId, channelType, channelName)
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
                        onRefresh = { scope.launch { appState.conversationVm.refresh() } },
                        onLogout = null,
                        onSearchMessages = { appState.navigateOverlayClearChat(NavDestination.SearchMessages()) },
                    )
                }

                1 -> ContactsScreen(
                    state = appState.contactsVm.contactsState.collectAsState().value,
                    imageBaseUrl = appState.userContext.baseUrl,
                    onSearchClick = { appState.navigateOverlayClearChat(NavDestination.SearchUsers) },
                    onFriendAppliesClick = { appState.navigateOverlayClearChat(NavDestination.FriendApplies) },
                    onFriendClick = { uid, friendName ->
                        scope.launch {
                            try {
                                val channel = appState.userContext.channelRepo.ensurePersonalChannel(uid)
                                val name = channel.name.ifEmpty { friendName }
                                appState.selectChat(
                                    ChatTarget(
                                        channelId = channel.channelId,
                                        channelType = ChannelType.fromCode(channel.channelType),
                                        channelName = name,
                                        otherUid = uid,
                                    )
                                )
                            } catch (e: Exception) {
                                AppLog.e("Desktop", "ensurePersonalChannel failed", e)
                            }
                        }
                    },
                    onCreateGroupClick = { appState.navigateOverlayClearChat(NavDestination.CreateGroup) },
                )
            }
        }
    }
}
