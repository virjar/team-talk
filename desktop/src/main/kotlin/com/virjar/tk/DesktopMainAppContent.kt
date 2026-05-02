package com.virjar.tk

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.virjar.tk.client.*
import com.virjar.tk.navigation.*
import com.virjar.tk.protocol.ChannelType
import com.virjar.tk.protocol.payload.Message
import com.virjar.tk.tray.DesktopNotificationManager
import com.virjar.tk.viewmodel.*
import com.virjar.tk.util.AppLog
import kotlinx.coroutines.launch

/** Sidebar background color — light/dark theme aware. */
internal fun sidebarBgColor(darkTheme: Boolean) =
    if (darkTheme) Color(0xFF2D2D2D) else Color(0xFFE8ECF1)

/** Panel background color — light/dark theme aware. */
internal fun panelBgColor(darkTheme: Boolean) =
    if (darkTheme) Color(0xFF1E1E1E) else Color.White

internal val IconUnselectedColor = Color(0xFF999999)
internal val IconSelectedColor = Color(0xFF444444)
internal val PanelShape: Shape = RoundedCornerShape(10.dp)
internal val SelectedItemBgColor = Color.White

/** Desktop sidebar menu items. Index 0-1 map to real content, 2-5 are placeholders. */
internal data class SidebarMenuItem(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val isFunctional: Boolean,
)

internal val SidebarMenuItems = listOf(
    SidebarMenuItem("Chats", Icons.AutoMirrored.Filled.Chat, Icons.AutoMirrored.Outlined.Chat, true),
    SidebarMenuItem("Contacts", Icons.Filled.Contacts, Icons.Outlined.Contacts, true),
    SidebarMenuItem("Docs", Icons.Filled.Description, Icons.Outlined.Description, false),
    SidebarMenuItem("Meeting", Icons.Filled.MeetingRoom, Icons.Outlined.MeetingRoom, false),
    SidebarMenuItem("Calendar", Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth, false),
    SidebarMenuItem("Todo", Icons.Filled.TaskAlt, Icons.Outlined.TaskAlt, false),
)

/** Data class for a selected chat target in the detail panel. */
data class ChatTarget(
    val channelId: String,
    val channelType: ChannelType,
    val channelName: String,
    val readSeq: Long = 0,
    val scrollToSeq: Long = 0,
    val otherUid: String? = null,
)

/**
 * Desktop-specific three-column layout: Sidebar | List Panel | Detail Panel.
 * Replaces the mobile-style full-screen navigation used by [MainAppContent].
 */
@Composable
fun DesktopMainAppContent(
    userContext: UserContext,
    onLogout: () -> Unit,
    themeMode: ThemeMode,
    onToggleTheme: (ThemeMode) -> Unit,
    onTotalUnreadChange: (Int) -> Unit = {},
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val scope = rememberCoroutineScope()
    val conversationVm = remember(userContext.uid) { ConversationViewModel(userContext) }
    val contactsVm = remember { ContactsViewModel(userContext) }

    val appState = remember {
        DesktopAppState(
            userContext = userContext,
            conversationVm = conversationVm,
            contactsVm = contactsVm,
            initialThemeMode = themeMode,
            onThemeChange = onToggleTheme,
        )
    }

    val notificationManager = remember { DesktopNotificationManager() }

    // TCP message listener - refresh conversation list on any incoming message
    DisposableEffect(userContext) {
        val removeListener = userContext.addMessageListener { incoming ->
            scope.launch {
                try {
                    conversationVm.refresh()
                } catch (e: Exception) {
                    AppLog.e("Desktop", "TCP msg listener refresh failed", e)
                }

                // Notification logic
                try {
                    val conversations = conversationVm.state.value.conversations
                    val conv = conversations.find { it.channelId == incoming.channelId }

                    // Skip notification if viewing this chat
                    val isViewing = appState.selectedChat?.channelId == incoming.channelId

                    // Skip notification if conversation is muted
                    val isMuted = conv?.isMuted == true

                    if (!isViewing && !isMuted) {
                        val channelName = conv?.channelName ?: incoming.channelId
                        val preview = Message.extractPreviewText(incoming.body)
                        notificationManager.onNewMessage(
                            incoming.channelId,
                            incoming.channelType.code,
                            channelName,
                            preview,
                        )
                    }
                } catch (e: Exception) {
                    AppLog.w("Desktop", "Notification logic failed", e)
                }
            }
        }
        onDispose { removeListener() }
    }

    // Monitor conversation list changes and report total unread count
    val conversations by conversationVm.state.collectAsState()
    LaunchedEffect(conversations.conversations) {
        val total = conversations.conversations.sumOf { it.unreadCount }
        onTotalUnreadChange(total)
    }

    // Load data when switching functional tabs
    LaunchedEffect(appState.selectedTab) {
        val item = SidebarMenuItems[appState.selectedTab]
        if (item.isFunctional) {
            when (appState.selectedTab) {
                0 -> { try { conversationVm.refresh() } catch (_: Exception) {} }
                1 -> { try { contactsVm.loadFriends() } catch (_: Exception) {} }
            }
        }
    }

    val connectionState by userContext.connectionState.collectAsState()
    val sideBg = sidebarBgColor(darkTheme)
    val panelBg = panelBgColor(darkTheme)

    CompositionLocalProvider(
        LocalUserContext provides userContext,
        LocalDesktopState provides appState,
    ) {
        // ── Three-column content (no title bar — handled by DecoratedWindow) ──
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(sideBg)
                .padding(8.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        if (appState.overlayDestination != null) {
                            appState.clearOverlay()
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── Column 1: Sidebar ──
            DesktopSidebar()

            // ── Column 2: List panel (320dp) ──
            DesktopListPanel(panelBg = panelBg)

            // ── Column 3: Detail panel (fill remaining) ──
            DesktopDetailPanel(
                modifier = Modifier.weight(1f),
                panelBg = panelBg,
                onLogout = onLogout,
            )
        } // Row
    }
}

/** Truncate payload for notification preview. */
private fun truncatePayload(payload: String, maxLen: Int): String {
    if (payload.length <= maxLen) return payload
    return payload.take(maxLen) + "..."
}
