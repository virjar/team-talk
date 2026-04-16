package com.virjar.tk

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.virjar.tk.client.*
import com.virjar.tk.dto.DeviceDto
import com.virjar.tk.protocol.ChannelType
import com.virjar.tk.navigation.*
import com.virjar.tk.ui.screen.*
import com.virjar.tk.ui.theme.TeamTalkTheme
import com.virjar.tk.util.AppLog
import com.virjar.tk.viewmodel.*
import com.virjar.tk.viewmodel.SearchViewModel
import com.virjar.tk.audio.VoicePlayer
import kotlinx.coroutines.launch

/**
 * Logged-in app content shared by Android (single window) and Desktop (main window).
 * Handles all navigation after login: Main, Chat, SearchUsers, etc.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppContent(
    userContext: UserContext,
    onLogout: () -> Unit,
) {
    var themeMode by remember { mutableStateOf(ThemeMode.SYSTEM) }
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    TeamTalkTheme(darkTheme = darkTheme) {
        val scope = rememberCoroutineScope()

        // ViewModels
        val conversationVm = remember(userContext.uid) {
            ConversationViewModel(userContext)
        }
        val contactsVm = remember { ContactsViewModel(userContext) }

        val appState = remember {
            AndroidAppState(userContext, conversationVm, contactsVm, themeMode) { themeMode = it }
        }

        // TCP message listener - refresh conversation list on any incoming message
        DisposableEffect(userContext) {
            val removeListener = userContext.addMessageListener { incoming ->
                scope.launch {
                    try { conversationVm.refresh() } catch (e: Exception) {
                        AppLog.e("App", "TCP msg listener refresh failed", e)
                    }
                }
            }
            onDispose { removeListener() }
        }

        CompositionLocalProvider(
            LocalUserContext provides userContext,
            LocalAppState provides appState,
        ) {
            when (val dest = appState.navDestination) {
                is NavDestination.Main -> MainScreen(
                    onLogout = onLogout,
                    initialTab = dest.initialTab,
                )

                is NavDestination.Chat -> {
                    val draft = conversationVm.state.value.conversations
                        .find { it.channelId == dest.channelId }?.draft ?: ""
                    val voicePlayer = remember { VoicePlayer() }
                    val chatVm = remember(dest.channelId) {
                        ChatViewModel(
                            ctx = userContext,
                            channelId = dest.channelId,
                            channelType = dest.channelType,
                            initialReadSeq = dest.readSeq,
                            initialScrollToSeq = dest.scrollToSeq,
                        )
                    }
                    LaunchedEffect(dest.channelId) {
                        chatVm.loadMessages()
                        try {
                            val state = chatVm.state.value
                            if (state.messages.isNotEmpty()) {
                                val maxSeq = state.messages.maxOf { it.serverSeq }
                                conversationVm.markRead(dest.channelId, maxSeq)
                            }
                        } catch (e: Exception) {
                            AppLog.w("App", "markRead on chat enter failed", e)
                        }
                    }
                    DisposableEffect(dest.channelId) {
                        val removeListener = userContext.addMessageListener { incoming ->
                            if (incoming.channelId == dest.channelId) {
                                scope.launch {
                                    chatVm.loadMessages()
                                    val state = chatVm.state.value
                                    if (state.messages.isNotEmpty()) {
                                        val maxSeq = state.messages.maxOf { it.serverSeq }
                                        conversationVm.markRead(dest.channelId, maxSeq)
                                    }
                                }
                            }
                        }
                        val removeTypingListener = userContext.addTypingListener { channelId, senderUid, _ ->
                            chatVm.onTypingReceived(channelId, senderUid)
                        }
                        val removeEditListener = userContext.addEditListener { channelId, targetMessageId, newPayload, editedAt ->
                            chatVm.onEditReceived(channelId, targetMessageId, newPayload, editedAt)
                        }
                        onDispose {
                            removeListener()
                            removeTypingListener()
                            removeEditListener()
                        }
                    }
                    ChatScreen(
                        channelId = dest.channelId,
                        channelName = dest.channelName,
                        myUid = appState.uid,
                        channelType = dest.channelType,
                        state = chatVm.state.collectAsState().value,
                        onSendMessage = { text -> scope.launch { chatVm.sendMessage(text) } },
                        onRevokeMessage = { seq -> scope.launch { chatVm.revokeMessage(seq) } },
                        onDeleteMessage = { msgId, seq -> scope.launch { chatVm.deleteMessage(msgId, seq) } },
                        onLoadMoreHistory = { scope.launch { chatVm.loadMoreHistory() } },
                        onBack = { currentInputText ->
                            if (currentInputText.isBlank()) {
                                scope.launch {
                                    try { conversationVm.clearDraft(dest.channelId) } catch (e: Exception) {
                                        AppLog.w("App", "clearDraft failed", e)
                                    }
                                }
                            }
                            // 非空草稿已由防抖 LaunchedEffect 保存，无需重复保存
                            appState.navigateBack()
                            scope.launch { try { conversationVm.refresh() } catch (e: Exception) {
                                AppLog.e("App", "refresh on chat back failed", e)
                            } }
                        },
                        onSendImage = { bytes, width, height ->
                            scope.launch { chatVm.sendImageMessage(bytes, width, height) }
                        },
                        onSendFile = { bytes, fileName ->
                            scope.launch { chatVm.sendFileMessage(bytes, fileName) }
                        },
                        onSendVideo = { bytes, fileName ->
                            scope.launch { chatVm.sendVideoMessage(bytes, fileName) }
                        },
                        onReplyMessage = { text, replyToMsgId, replyToUid, replyToName, replyToType ->
                            scope.launch {
                                chatVm.sendReplyMessage(text, replyToMsgId, replyToUid, replyToName, replyToType)
                            }
                        },
                        onClearReply = { chatVm.setReplyingTo(null) },
                        onSetReply = { msg -> chatVm.setReplyingTo(msg) },
                        onForward = { msg ->
                            appState.navigateTo(NavDestination.Forward(msg))
                        },
                        onGroupDetail = {
                            appState.navigateTo(NavDestination.GroupDetail(dest.channelId, dest.channelType))
                        },
                        onUserProfile = {
                            dest.otherUid?.let { uid ->
                                appState.navigateTo(NavDestination.UserProfile(uid, backTo = dest))
                            }
                        },
                        onFileDownload = { payload ->
                            scope.launch { chatVm.downloadFile(payload) }
                        },
                        imageBaseUrl = appState.imageBaseUrl,
                        initialDraft = draft,
                        onDraftChanged = { newDraft ->
                            scope.launch {
                                try {
                                    if (newDraft.isBlank()) {
                                        conversationVm.clearDraft(dest.channelId)
                                    } else {
                                        conversationVm.updateDraft(dest.channelId, newDraft)
                                    }
                                } catch (e: Exception) {
                                    AppLog.w("App", "debounced updateDraft failed", e)
                                }
                            }
                        },
                        onTyping = { userContext.sendTyping(dest.channelId, dest.channelType) },
                        onSetEdit = { msg -> chatVm.setEditingMessage(msg) },
                        onEditMessage = { text -> scope.launch { chatVm.editMessage(text) } },
                        onClearEdit = { chatVm.setEditingMessage(null) },
                        voicePlayer = voicePlayer,
                        onStartRecording = { chatVm.startRecording() },
                        onStopAndSendRecording = { chatVm.stopAndSendRecording() },
                        onCancelRecording = { chatVm.cancelRecording() },
                        onClearScrollTarget = { chatVm.clearScrollToSeq() },
                    )
                }

                is NavDestination.SearchUsers -> SearchUsersScreen(
                    contactsVm = contactsVm,
                    onUserClick = { uid -> appState.navigateTo(NavDestination.UserProfile(uid)) },
                    onBack = { appState.navigateBack(initialTab = 1) },
                    onSendApply = { uid -> scope.launch { contactsVm.applyFriend(uid) } },
                )

                is NavDestination.SearchMessages -> {
                    val searchVm = remember { SearchViewModel(userContext) }
                    SearchMessagesScreen(
                        searchVm = searchVm,
                        channelId = dest.channelId,
                        channelName = dest.channelName,
                        onBack = { appState.navigateBack() },
                        onResultClick = { cid, ctype, cname, seq ->
                            appState.navigateTo(NavDestination.Chat(cid, ChannelType.fromCode(ctype), cname, scrollToSeq = seq))
                        },
                    )
                }

                is NavDestination.UserProfile -> UserProfileScreen(
                    uid = dest.uid,
                    userRepo = appState.userRepo,
                    contactRepo = appState.contactRepo,
                    myUid = appState.uid,
                    onBack = { appState.navigateTo(dest.backTo ?: NavDestination.Main(initialTab = 1)) },
                    onStartChat = { channelId, channelType, channelName ->
                        scope.launch {
                            try {
                                val channel = appState.channelRepo.ensurePersonalChannel(dest.uid)
                                val name = channel.name.ifEmpty { channelName }
                                appState.navigateTo(NavDestination.Chat(channel.channelId, ChannelType.fromCode(channel.channelType), name))
                            } catch (e: Exception) {
                                AppLog.e("App", "ensurePersonalChannel failed", e)
                                appState.navigateTo(NavDestination.Chat(channelId, ChannelType.fromCode(channelType), channelName))
                            }
                        }
                    },
                    onSendApply = { uid -> scope.launch { contactsVm.applyFriend(uid) } },
                    onDeleteFriend = { uid ->
                        scope.launch {
                            try {
                                contactsVm.deleteFriend(uid)
                                appState.navigateBack(initialTab = 1)
                            } catch (e: Exception) {
                                AppLog.e("App", "deleteFriend failed", e)
                            }
                        }
                    },
                    onUpdateRemark = { uid, remark ->
                        scope.launch {
                            try { contactsVm.updateRemark(uid, remark) } catch (e: Exception) {
                                AppLog.e("App", "updateRemark failed", e)
                            }
                        }
                    },
                    onAddBlacklist = { uid ->
                        scope.launch {
                            try {
                                appState.contactRepo.addBlacklist(uid)
                            } catch (e: Exception) {
                                AppLog.e("App", "addBlacklist failed", e)
                            }
                        }
                    },
                    onRemoveBlacklist = { uid ->
                        scope.launch {
                            try {
                                appState.contactRepo.removeBlacklist(uid)
                            } catch (e: Exception) {
                                AppLog.e("App", "removeBlacklist failed", e)
                            }
                        }
                    },
                )

                is NavDestination.FriendApplies -> FriendAppliesScreen(
                    contactsVm = contactsVm,
                    onBack = { appState.navigateBack(initialTab = 1) },
                    onAccept = { token -> scope.launch { contactsVm.acceptApply(token) } },
                )

                is NavDestination.CreateGroup -> CreateGroupScreen(
                    contactsVm = contactsVm,
                    channelRepo = appState.channelRepo,
                    onBack = { appState.navigateBack() },
                    onGroupCreated = { channelId ->
                        appState.navigateTo(NavDestination.Chat(channelId, ChannelType.GROUP, ""))
                    },
                )

                is NavDestination.GroupDetail -> GroupDetailScreen(
                    channelId = dest.channelId,
                    channelType = dest.channelType,
                    channelRepo = appState.channelRepo,
                    myUid = appState.uid,
                    onBack = { appState.navigateBack() },
                    onViewMembers = { appState.navigateTo(NavDestination.GroupMembers(dest.channelId)) },
                    onInviteMembers = { appState.navigateTo(NavDestination.InviteMembers(dest.channelId)) },
                    onInviteLinks = { appState.navigateTo(NavDestination.InviteLinks(dest.channelId)) },
                    onLeaveGroup = {
                        scope.launch {
                            try {
                                appState.channelRepo.removeMembers(dest.channelId, listOf(appState.uid))
                                appState.navigateBack()
                                conversationVm.refresh()
                            } catch (e: Exception) {
                                AppLog.e("App", "leaveGroup failed", e)
                            }
                        }
                    },
                    onDeleteGroup = {
                        scope.launch {
                            try {
                                appState.channelRepo.deleteChannel(dest.channelId)
                                appState.navigateBack()
                                conversationVm.refresh()
                            } catch (e: Exception) {
                                AppLog.e("App", "deleteGroup failed", e)
                            }
                        }
                    },
                    imageBaseUrl = appState.imageBaseUrl,
                    fileRepo = appState.fileRepo,
                )

                is NavDestination.GroupMembers -> GroupMembersScreen(
                    channelId = dest.channelId,
                    channelRepo = appState.channelRepo,
                    myUid = appState.uid,
                    onBack = { appState.navigateBack() },
                    imageBaseUrl = appState.imageBaseUrl,
                )

                is NavDestination.InviteMembers -> InviteMembersScreen(
                    channelId = dest.channelId,
                    contactsVm = contactsVm,
                    channelRepo = appState.channelRepo,
                    onBack = { appState.navigateTo(NavDestination.GroupDetail(dest.channelId, ChannelType.GROUP)) },
                )

                is NavDestination.InviteLinks -> InviteLinksScreen(
                    channelId = dest.channelId,
                    channelRepo = appState.channelRepo,
                    onBack = { appState.navigateTo(NavDestination.GroupDetail(dest.channelId, ChannelType.GROUP)) },
                )

                is NavDestination.JoinByLink -> {
                    var result by remember { mutableStateOf<String?>(null) }
                    var isLoading by remember { mutableStateOf(true) }
                    var error by remember { mutableStateOf("") }

                    LaunchedEffect(dest.token) {
                        try {
                            val res = appState.channelRepo.joinByInviteLink(dest.token)
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
                                    IconButton(onClick = { appState.navigateBack() }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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

                is NavDestination.EditProfile -> EditProfileScreen(
                    userDto = appState.currentUser,
                    userRepo = appState.userRepo,
                    fileRepo = appState.fileRepo,
                    imageBaseUrl = appState.imageBaseUrl,
                    onBack = { appState.navigateBack(initialTab = 2) },
                    onProfileUpdated = { updated -> appState.updateCurrentUser(updated) },
                )

                is NavDestination.ChangePassword -> ChangePasswordScreen(
                    userRepo = appState.userRepo,
                    onBack = { appState.navigateBack(initialTab = 2) },
                    onSuccess = { appState.navigateBack(initialTab = 2) },
                )

                is NavDestination.Blacklist -> BlacklistScreen(
                    contactRepo = appState.contactRepo,
                    onBack = { appState.navigateBack(initialTab = 2) },
                )

                is NavDestination.DeviceManagement -> {
                    var devices by remember { mutableStateOf<List<DeviceDto>>(emptyList()) }
                    var isLoading by remember { mutableStateOf(true) }
                    val currentDeviceId = "cmp-android" // Android 固定标识

                    suspend fun loadDevices() {
                        isLoading = true
                        try {
                            devices = userContext.getDevices()
                        } catch (e: Exception) {
                            AppLog.e("App", "loadDevices failed", e)
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
                                    AppLog.e("App", "kickDevice failed", e)
                                }
                            }
                        },
                        onRefresh = { scope.launch { loadDevices() } },
                        onBack = { appState.navigateBack(initialTab = 2) },
                    )
                }

                is NavDestination.Forward -> {
                    var forwardResult by remember { mutableStateOf<String?>(null) }
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
                                val success = forwardVm.sendForwardMessage(targetChannelId, ct, dest.payload)
                                forwardResult = if (success) "Message forwarded" else "Forward failed"
                            }
                        },
                        onBack = { appState.navigateBack() },
                    )
                    forwardResult?.let { msg ->
                        Snackbar(
                            modifier = Modifier.padding(16.dp),
                            action = { TextButton(onClick = { forwardResult = null }) { Text("OK") } }
                        ) { Text(msg) }
                    }
                }

                // Login/Register/Me handled by caller, should not reach here
                is NavDestination.Login -> {}
                is NavDestination.Register -> {}
                is NavDestination.Me -> {}
            }
        }
    }
}
