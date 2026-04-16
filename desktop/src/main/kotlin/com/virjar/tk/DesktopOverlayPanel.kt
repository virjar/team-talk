package com.virjar.tk

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.virjar.tk.dto.DeviceDto
import com.virjar.tk.navigation.NavDestination
import com.virjar.tk.protocol.ChannelType
import com.virjar.tk.protocol.payload.Message
import com.virjar.tk.ui.screen.*
import com.virjar.tk.util.AppLog
import com.virjar.tk.viewmodel.ChatViewModel
import com.virjar.tk.viewmodel.SearchViewModel
import kotlinx.coroutines.launch

// ──────────────────────────── Overlay Panel ────────────────────────────

/**
 * @param chatTarget when non-null, this overlay is in a chat context
 *   (e.g. GroupDetail opened from within a chat). The panel will route
 *   chat-context destinations such as GroupMembers, InviteMembers, etc.
 * @param onLogout called when the user logs out from the Me screen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DesktopOverlayPanel(
    destination: NavDestination,
    chatTarget: ChatTarget? = null,
    onLogout: () -> Unit,
) {
    val appState = LocalDesktopState.current
    val userContext = appState.userContext
    val conversationVm = appState.conversationVm
    val contactsVm = appState.contactsVm
    val scope = rememberCoroutineScope()

    // Forward result snackbar state
    var forwardResult by remember { mutableStateOf<String?>(null) }

    when (destination) {
        // ── Chat-context overlays (only when chatTarget != null) ──

        is NavDestination.GroupDetail -> GroupDetailScreen(
            channelId = destination.channelId,
            channelType = destination.channelType,
            channelRepo = userContext.channelRepo,
            myUid = userContext.uid,
            onBack = { appState.clearOverlay() },
            onViewMembers = { appState.navigateOverlay(NavDestination.GroupMembers(destination.channelId)) },
            onInviteMembers = { appState.navigateOverlay(NavDestination.InviteMembers(destination.channelId)) },
            onInviteLinks = { appState.navigateOverlay(NavDestination.InviteLinks(destination.channelId)) },
            onLeaveGroup = {
                scope.launch {
                    try {
                        userContext.channelRepo.removeMembers(destination.channelId, listOf(userContext.uid))
                        appState.clearOverlay()
                        conversationVm.refresh()
                    } catch (e: Exception) {
                        AppLog.e("Desktop", "leaveGroup failed", e)
                    }
                }
            },
            onDeleteGroup = {
                scope.launch {
                    try {
                        userContext.channelRepo.deleteChannel(destination.channelId)
                        appState.clearOverlay()
                        conversationVm.refresh()
                    } catch (e: Exception) {
                        AppLog.e("Desktop", "deleteGroup failed", e)
                    }
                }
            },
            imageBaseUrl = userContext.baseUrl,
            fileRepo = userContext.fileRepo,
        )

        is NavDestination.GroupMembers -> GroupMembersScreen(
            channelId = destination.channelId,
            channelRepo = userContext.channelRepo,
            myUid = userContext.uid,
            onBack = { appState.navigateOverlay(NavDestination.GroupDetail(destination.channelId, ChannelType.GROUP)) },
            imageBaseUrl = userContext.baseUrl,
        )

        is NavDestination.InviteMembers -> InviteMembersScreen(
            channelId = destination.channelId,
            contactsVm = contactsVm,
            channelRepo = userContext.channelRepo,
            onBack = { appState.navigateOverlay(NavDestination.GroupDetail(destination.channelId, ChannelType.GROUP)) },
        )

        is NavDestination.UserProfile -> DesktopUserProfileScreen(
            uid = destination.uid,
            onNavigateToChat = { target -> appState.selectChat(target) },
            onDismiss = { appState.clearOverlay() },
        )

        is NavDestination.Forward -> {
            ForwardScreen(
                conversationVm = conversationVm,
                contactsVm = contactsVm,
                onForward = { targetChannelId, targetChannelType ->
                    scope.launch {
                        val ct = ChannelType.fromCode(targetChannelType)
                        val forwardVm = ChatViewModel(
                            ctx = userContext,
                            channelId = targetChannelId,
                            channelType = ct,
                        )
                        val success = forwardVm.sendForwardMessage(
                            targetChannelId, ct, destination.payload
                        )
                        forwardResult = if (success) "Message forwarded" else "Forward failed"
                    }
                },
                onBack = { appState.clearOverlay() },
            )
            forwardResult?.let { msg ->
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    action = { TextButton(onClick = { forwardResult = null }) { Text("OK") } }
                ) { Text(msg) }
            }
        }

        // ── Main (non-chat-context) overlays ──

        is NavDestination.SearchMessages -> {
            val searchVm = remember { SearchViewModel(userContext) }
            SearchMessagesScreen(
                searchVm = searchVm,
                channelId = destination.channelId,
                channelName = destination.channelName,
                onBack = { appState.clearOverlay() },
                onResultClick = { cid, ctype, cname, seq ->
                    appState.selectChat(ChatTarget(cid, ChannelType.fromCode(ctype), cname, scrollToSeq = seq))
                },
            )
        }

        is NavDestination.SearchUsers -> SearchUsersScreen(
            contactsVm = contactsVm,
            onUserClick = { uid -> appState.navigateOverlay(NavDestination.UserProfile(uid)) },
            onBack = { appState.clearOverlay() },
            onSendApply = { uid -> scope.launch { contactsVm.applyFriend(uid) } },
        )

        is NavDestination.FriendApplies -> FriendAppliesScreen(
            contactsVm = contactsVm,
            onBack = { appState.clearOverlay() },
            onAccept = { token -> scope.launch { contactsVm.acceptApply(token) } },
        )

        is NavDestination.CreateGroup -> CreateGroupScreen(
            contactsVm = contactsVm,
            channelRepo = userContext.channelRepo,
            onBack = { appState.clearOverlay() },
            onGroupCreated = { channelId ->
                appState.selectChat(ChatTarget(channelId, ChannelType.GROUP, ""))
            },
        )

        is NavDestination.EditProfile -> EditProfileScreen(
            userDto = appState.currentUser,
            userRepo = userContext.userRepo,
            fileRepo = userContext.fileRepo,
            imageBaseUrl = userContext.baseUrl,
            onBack = { appState.navigateOverlay(NavDestination.Me) },
            onProfileUpdated = { updated -> appState.updateCurrentUser(updated) },
        )

        is NavDestination.ChangePassword -> ChangePasswordScreen(
            userRepo = userContext.userRepo,
            onBack = { appState.navigateOverlay(NavDestination.Me) },
            onSuccess = { appState.navigateOverlay(NavDestination.Me) },
        )

        is NavDestination.Blacklist -> BlacklistScreen(
            contactRepo = userContext.contactRepo,
            onBack = { appState.navigateOverlay(NavDestination.Me) },
        )

        is NavDestination.DeviceManagement -> {
            var devices by remember { mutableStateOf<List<DeviceDto>>(emptyList()) }
            var isLoading by remember { mutableStateOf(true) }
            val currentDeviceId = "cmp-desktop" // Desktop 固定标识

            suspend fun loadDevices() {
                isLoading = true
                try {
                    devices = userContext.getDevices()
                } catch (e: Exception) {
                    AppLog.e("Desktop", "loadDevices failed", e)
                } finally {
                    isLoading = false
                }
            }

            LaunchedEffect(Unit) { loadDevices() }

            DeviceManagementScreen(
                devices = devices,
                currentDeviceId = currentDeviceId,
                isLoading = isLoading,
                onKick = { deviceId ->
                    scope.launch {
                        try {
                            userContext.kickDevice(deviceId)
                            loadDevices()
                        } catch (e: Exception) {
                            AppLog.e("Desktop", "kickDevice failed", e)
                        }
                    }
                },
                onRefresh = { scope.launch { loadDevices() } },
                onBack = { appState.navigateOverlay(NavDestination.Me) },
            )
        }

        is NavDestination.Me -> MeScreen(
            currentUser = appState.currentUser,
            imageBaseUrl = userContext.baseUrl,
            themeMode = appState.themeMode,
            onToggleTheme = { mode -> appState.toggleTheme(mode) },
            onEditProfile = { appState.navigateOverlay(NavDestination.EditProfile) },
            onChangePassword = { appState.navigateOverlay(NavDestination.ChangePassword) },
            onBlacklist = { appState.navigateOverlay(NavDestination.Blacklist) },
            onDeviceManagement = { appState.navigateOverlay(NavDestination.DeviceManagement) },
            onLogout = onLogout,
        )

        is NavDestination.InviteLinks -> InviteLinksScreen(
            channelId = destination.channelId,
            channelRepo = userContext.channelRepo,
            onBack = { appState.navigateOverlay(NavDestination.GroupDetail(destination.channelId, ChannelType.GROUP)) },
        )

        is NavDestination.JoinByLink -> {
            // Desktop: show join-by-link result inline
            var result by remember { mutableStateOf<String?>(null) }
            var isLoading by remember { mutableStateOf(true) }
            var error by remember { mutableStateOf("") }

            LaunchedEffect(destination.token) {
                try {
                    val res = userContext.channelRepo.joinByInviteLink(destination.token)
                    result = if (res.joined) "Joined ${res.channelName}" else "Already a member"
                } catch (e: Exception) {
                    error = e.message ?: "Failed to join"
                } finally {
                    isLoading = false
                }
            }

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Join Group") },
                        navigationIcon = {
                            IconButton(onClick = { appState.clearOverlay() }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Close"
                                )
                            }
                        },
                    )
                }
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    when {
                        isLoading -> CircularProgressIndicator()
                        error.isNotEmpty() -> Text(error, color = MaterialTheme.colorScheme.error)
                        result != null -> Text(result!!, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }

        else -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Select a conversation to start chatting",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
