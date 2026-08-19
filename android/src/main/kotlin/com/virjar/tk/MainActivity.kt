package com.virjar.tk

import android.os.Bundle
import android.util.Log
import com.virjar.tk.android.BuildConfig
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.navigation.navArgument
import com.virjar.tk.client.*
import com.virjar.tk.model.User
import com.virjar.tk.navigation.AppDataState
import com.virjar.tk.navigation.ScreenDataKey
import com.virjar.tk.ui.AppTheme
import com.virjar.tk.ui.screen.*
import com.virjar.tk.ui.theme.initThemeStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 全局初始化（日志注入、ServerConfig、异常拦截）已在 TeamTalkApp.onCreate 完成
        // 主题持久化：需在首次组合（TkTheme 读取）前就绪
        initThemeStore(applicationContext)
        setContent {
            AppTheme(touchDensity = true) {
                TestTagEnabler {
                val config = remember { defaultServerConfig() }
                val tokenStore = remember { TokenStore(applicationContext) }
                val scope = rememberCoroutineScope()
                val auth = rememberAuthController(
                    tokenStore = tokenStore,
                    tcpHost = config.tcpHost,
                    tcpPort = config.tcpPort,
                    deviceId = "android-device",
                    deviceName = "Android",
                    createCache = { uid -> createAndroidLocalCache(applicationContext, uid) },
                    onAuthenticated = { session ->
                        // 注册/登录后缓存可能还没写入，回退用 UserSession 内存字段构建
                        val us = session.userSession
                        us.username?.let { username ->
                            session.localCache.upsertUser(User(uid = us.uid, username = username, name = us.name ?: username))
                        }
                    },
                )
                if (!auth.isLoggedIn) {
                    if (auth.autoLoggingIn) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else {
                        AuthFlow(auth.imClient, config, scope, auth.authError) { auth.clearError() }
                    }
                } else {
                    AndroidMainApp(
                        dataState = remember { AppDataState(auth.session!!) },
                        onLogout = auth.onLogout,
                    )
                }
            }
                }
        }
    }
}

@Composable
private fun AuthFlow(imClient: ImClient, config: ServerConfig, scope: kotlinx.coroutines.CoroutineScope, authError: String?, onAuthErrorChange: (String?) -> Unit) {
    var showRegister by remember { mutableStateOf(false) }
    if (showRegister) {
        RegisterScreen(
            onRegister = { u, p, n -> onAuthErrorChange(null); scope.launch {
                try { imClient.register(u, p, n, "android-${UUID.randomUUID()}", "Android", config.tcpHost, config.tcpPort) }
                catch (e: IllegalArgumentException) { onAuthErrorChange(e.message) }
            }},
            onNavigateBack = { showRegister = false; onAuthErrorChange(null) }, error = authError,
        )
    } else {
        LoginScreen(
            onLogin = { u, p -> onAuthErrorChange(null); scope.launch {
                try { imClient.login(u, p, "android-${UUID.randomUUID()}", "Android", config.tcpHost, config.tcpPort) }
                catch (e: IllegalArgumentException) { onAuthErrorChange(e.message) }
            }},
            onNavigateToRegister = { showRegister = true; onAuthErrorChange(null) }, error = authError,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AndroidMainApp(dataState: AppDataState, onLogout: () -> Unit) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { dataState.destroy() } }
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, animationSpec = tween(300)) },
        exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, animationSpec = tween(300)) },
        popEnterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, animationSpec = tween(300)) },
        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, animationSpec = tween(300)) },
    ) {
        composable(Routes.HOME) {
            HomeScreen(dataState = dataState, onLogout = onLogout,
                onConversationClick = { cid, n, t -> dataState.prepareChat(cid, n, t); navController.navigate(Routes.chat(cid, n, t)) },
                onGlobalSearch = { navController.navigate(Routes.SEARCH_MESSAGES) },
                onFriendApplies = { navController.navigate(Routes.FRIEND_APPLIES) },
                onUserProfile = { uid -> navController.navigate(Routes.userProfile(uid)) },
                onEditProfile = { navController.navigate(Routes.EDIT_PROFILE) },
                onChangePassword = { navController.navigate(Routes.CHANGE_PASSWORD) },
                onDevices = { navController.navigate(Routes.DEVICES) },
                onBlacklist = { navController.navigate(Routes.BLACKLIST) },
            )
        }
        composable(Routes.CHAT, arguments = listOf(navArgument("chatId"){type=NavType.StringType}, navArgument("name"){type=NavType.StringType;defaultValue=""}, navArgument("type"){type=NavType.IntType;defaultValue=1})) { entry ->
            val chatId = entry.arguments?.getString("chatId") ?: return@composable
            val chatName = entry.arguments?.getString("name") ?: ""
            val chatType = entry.arguments?.getInt("type") ?: 1
            val vm = dataState.chatViewModel
            val conversations by dataState.conversationViewModel.conversations.collectAsState()
            val draft = remember(chatId) { conversations.find { it.chatId == chatId }?.draft }
            var currentDraft by remember { mutableStateOf(draft) }
            // @ 补全候选：群聊拉成员，私聊用好友
            var mentionCandidates by remember { mutableStateOf<List<com.virjar.tk.model.User>>(emptyList()) }
            LaunchedEffect(chatId, chatType) {
                mentionCandidates = try {
                    if (chatType == com.virjar.tk.model.ChatType.GROUP.code) {
                        dataState.chatRepo.getMembers(chatId).getOrNull()?.mapNotNull { it.user } ?: emptyList()
                    } else {
                        dataState.contactViewModel.contacts.value.mapNotNull { it.user }
                    }
                } catch (_: Exception) { emptyList() }
            }
            // 离开聊天页时保存草稿（fire-and-forget，不再阻塞主线程）
            DisposableEffect(chatId) {
                onDispose { dataState.saveDraft(chatId, currentDraft) }
            }
            if (vm != null) { AndroidChatScreen(chatId, chatName, chatType, vm, dataState.userSession.uid,
                serverUrl = defaultServerConfig().serverUrl,
                accessToken = dataState.userSession.accessToken,
                resolveSender = { uid ->
                    mentionCandidates.firstOrNull { it.uid == uid } ?: dataState.localCache.getUser(uid)
                },
                mentionCandidates = mentionCandidates,
                draft = currentDraft,
                onDraftChange = { currentDraft = it },
                onForward = { msg -> navController.navigate(Routes.forward(msg.chatId, msg.serverSeq)) },
                onGroupDetail = { navController.navigate(Routes.groupDetail(chatId)) },
                onBack = { navController.popBackStack() },
            )}
        }
        composable(Routes.SEARCH_MESSAGES) {
            val conversations by dataState.conversationViewModel.conversations.collectAsState()
            val contacts by dataState.contactViewModel.contacts.collectAsState()
            var query by rememberSaveable { mutableStateOf("") }
            GlobalSearchScreen(
                query = query,
                onQueryChange = { query = it },
                conversations = conversations,
                contacts = contacts,
                searchMessages = { q -> dataState.discovery.searchMessages(q) },
                searchUsers = { q -> dataState.discovery.searchUsers(q) },
                onConversationClick = { conversation ->
                    dataState.prepareChat(conversation.chatId, conversation.chatName ?: conversation.chatId.take(16), conversation.chatType)
                    navController.navigate(Routes.chat(conversation.chatId, conversation.chatName ?: conversation.chatId.take(16), conversation.chatType)) {
                        popUpTo(Routes.HOME)
                    }
                },
                onMessageClick = { message ->
                    val conversation = conversations.find { it.chatId == message.chatId }
                    val name = conversation?.chatName ?: message.chatId.take(16)
                    val type = conversation?.chatType ?: 1
                    dataState.prepareChat(message.chatId, name, type)
                    navController.navigate(Routes.chat(message.chatId, name, type)) { popUpTo(Routes.HOME) }
                },
                onUserClick = { user -> navController.navigate(Routes.userProfile(user.uid)) },
                excludedUserUid = dataState.userSession.uid,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SEARCH_USERS) {
            SearchUsersScreen(searchUsers = { q -> dataState.discovery.searchUsers(q) },
                onUserClick = { uid -> navController.navigate(Routes.userProfile(uid)) }, onBack = { navController.popBackStack() })
        }
        composable(
            Routes.CREATE_GROUP,
            arguments = listOf(navArgument("seedUid") { type = NavType.StringType; defaultValue = "" }),
        ) { entry ->
            val contacts by dataState.contactViewModel.contacts.collectAsState()
            val seedUid = entry.arguments?.getString("seedUid").orEmpty()
            CreateGroupScreen(contacts = contacts, onCreateGroup = { name, uids ->
                val chatId = dataState.groups.create(name, uids)
                if (chatId != null) {
                    dataState.prepareChat(chatId, name, 2)
                    navController.navigate(Routes.chat(chatId, name, 2)) { popUpTo(Routes.CREATE_GROUP) { inclusive = true } }
                    Result.success(chatId)
                } else Result.failure(Exception("创建失败"))
            }, onBack = { navController.popBackStack() }, initialSelectedUids = setOfNotNull(seedUid.takeIf { it.isNotBlank() }))
        }
        composable(Routes.FRIEND_APPLIES) {
            LaunchedEffect(Unit) { dataState.loadScreenDataByKey(ScreenDataKey.FriendApplies) }
            FriendAppliesScreen(applies = dataState.account.applies,
                onAccept = { t -> dataState.account.acceptFriendApply(t) },
                onReject = { t -> dataState.account.rejectFriendApply(t) },
                onBack = { navController.popBackStack() })
        }
        composable(Routes.USER_PROFILE, arguments = listOf(navArgument("uid"){type=NavType.StringType})) { entry ->
            val uid = entry.arguments?.getString("uid") ?: return@composable
            var hasPendingApply by remember { mutableStateOf(false) }
            LaunchedEffect(uid) { dataState.loadScreenDataByKey(ScreenDataKey.UserProfile(uid)); hasPendingApply = false }
            UserProfileScreen(user = dataState.account.profileUser, isFriend = dataState.account.isFriend, hasPendingApply = hasPendingApply,
                onAddFriend = {
                    dataState.contactViewModel.apply(uid)
                    hasPendingApply = true
                    scope.launch {
                        kotlinx.coroutines.delay(800)
                        navController.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = true } }
                    }
                },
                onSendMessage = { scope.launch {
                    val chatId = dataState.discovery.startPersonalChat(uid)
                    if (chatId != null) {
                        val n = dataState.account.profileUser?.name ?: uid.take(12)
                        dataState.prepareChat(chatId, n)
                        navController.navigate(Routes.chat(chatId, n)) { popUpTo(Routes.HOME) }
                    }
                }},
                onCreateGroup = if (dataState.account.isFriend) {
                    { navController.navigate(Routes.createGroup(uid)) }
                } else null,
                onDeleteFriend = { dataState.contactViewModel.deleteFriend(uid); navController.popBackStack() },
                onBack = { navController.popBackStack() })
        }
        composable(Routes.EDIT_PROFILE) { EditProfileScreen(currentUser = dataState.account.currentUser, onSave = { n, p -> dataState.account.saveProfile(n, p) }, onBack = { navController.popBackStack() }) }
        composable(Routes.CHANGE_PASSWORD) { ChangePasswordScreen(onChangePassword = { o, n -> dataState.account.changePassword(o, n) }, onBack = { navController.popBackStack() }) }
        composable(Routes.DEVICES) {
            LaunchedEffect(Unit) { dataState.loadScreenDataByKey(ScreenDataKey.Devices) }
            DeviceManagementScreen(devices = dataState.account.devices.map { DeviceInfo(it.deviceId, it.deviceName ?: "", it.deviceModel ?: "", it.lastLogin) },
                onKick = { d -> dataState.account.kickDevice(d) },
                onBack = { navController.popBackStack() })
        }
        composable(Routes.BLACKLIST) {
            LaunchedEffect(Unit) { dataState.loadScreenDataByKey(ScreenDataKey.Blacklist) }
            BlacklistScreen(blockedUsers = dataState.account.blockedContacts.map { BlockedUser(it.friendUid, it.user?.name ?: it.friendUid) },
                onUnblock = { u -> dataState.account.unblockContact(u) },
                onBack = { navController.popBackStack() })
        }
        composable(Routes.GROUP_DETAIL, arguments = listOf(navArgument("chatId"){type=NavType.StringType})) { entry ->
            val chatId = entry.arguments?.getString("chatId") ?: return@composable
            LaunchedEffect(chatId) { dataState.loadScreenDataByKey(ScreenDataKey.GroupDetail(chatId)) }
            GroupDetailScreen(chat = dataState.groups.detailChat, members = dataState.groups.members, isOwner = dataState.groups.members.any { it.uid == dataState.userSession.uid && it.role == 2 },
                myUid = dataState.userSession.uid,
                onMemberClick = { uid -> navController.navigate(Routes.userProfile(uid)) }, onInviteMembers = { navController.navigate(Routes.inviteMembers(chatId)) }, onViewInviteLinks = { navController.navigate(Routes.inviteLinks(chatId)) },
                onGroupFiles = { navController.navigate(Routes.groupFiles(chatId)) },
                onLeaveGroup = {
                    val isOwner = dataState.groups.members.any {
                        it.uid == dataState.userSession.uid && it.role == 2
                    }
                    dataState.groups.exit(chatId, dissolve = isOwner) {
                        navController.popBackStack(Routes.HOME, inclusive = false)
                    }
                },
                onEditNotice = { notice -> dataState.groups.updateNotice(chatId, notice) },
                onBack = { navController.popBackStack() },
                onSetAdmin = { uid -> dataState.groups.setMemberRole(chatId, uid, 1) },
                onRemoveAdmin = { uid -> dataState.groups.setMemberRole(chatId, uid, 0) },
                onMuteMember = { uid -> dataState.groups.muteMember(chatId, uid) },
                onUnmuteMember = { uid -> dataState.groups.unmuteMember(chatId, uid) },
                onRemoveMember = { uid -> dataState.groups.removeMember(chatId, uid) },
            )
        }
        composable(Routes.GROUP_FILES, arguments = listOf(navArgument("chatId"){type=NavType.StringType})) { entry ->
            val chatId = entry.arguments?.getString("chatId") ?: return@composable
            val context = LocalContext.current
            val config = remember { defaultServerConfig() }
            var uploading by remember { mutableStateOf(false) }
            var versionTarget by remember { mutableStateOf<com.virjar.tk.model.GroupFileEntry?>(null) }
            val downloads = remember(context, config.serverUrl, dataState.userSession.accessToken) {
                AndroidFileDownloadController(context, config.serverUrl, dataState.userSession.accessToken)
            }
            DisposableEffect(downloads) { onDispose { downloads.close() } }
            LaunchedEffect(chatId) { dataState.loadScreenDataByKey(ScreenDataKey.GroupFiles(chatId)) }

            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) scope.launch {
                    uploading = true
                    try {
                        val bytes = withContext(Dispatchers.IO) { MediaHelper.readBytes(context, uri) }
                        val name = MediaHelper.getFileName(context, uri)
                        val type = MediaHelper.getMimeType(context, uri)
                        val attachment = com.virjar.tk.repository.FileRepository(
                            config.serverUrl,
                            dataState.userSession.accessToken,
                        ).upload(bytes, name, type).getOrThrow()
                        val target = versionTarget
                        if (target == null) dataState.groupFiles.publish(name, attachment)
                        else dataState.groupFiles.addVersion(target, attachment)
                    } catch (e: Exception) {
                        Log.e("GroupFiles", "upload failed", e)
                        dataState.groupFiles.reportUploadError(e)
                    } finally {
                        versionTarget = null
                        uploading = false
                    }
                } else {
                    versionTarget = null
                }
            }

            GroupFilesScreen(
                entries = dataState.groupFiles.entries,
                path = dataState.groupFiles.path,
                selectedFile = dataState.groupFiles.selectedFile,
                versions = dataState.groupFiles.versions,
                loading = dataState.groupFiles.loading,
                uploading = uploading,
                onRefresh = { scope.launch { dataState.groupFiles.refresh() } },
                onEnter = dataState.groupFiles::enter,
                onUp = dataState.groupFiles::up,
                onCreateFolder = dataState.groupFiles::createFolder,
                onUpload = { versionTarget = null; picker.launch(arrayOf("*/*")) },
                onOpenFile = downloads::openOrDownload,
                onShowVersions = dataState.groupFiles::showVersions,
                onUploadVersion = { target -> versionTarget = target; picker.launch(arrayOf("*/*")) },
                onRename = dataState.groupFiles::rename,
                onDelete = dataState.groupFiles::delete,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.INVITE_MEMBERS, arguments = listOf(navArgument("chatId"){type=NavType.StringType})) { entry ->
            val chatId = entry.arguments?.getString("chatId") ?: return@composable
            val contacts by dataState.contactViewModel.contacts.collectAsState()
            InviteMembersScreen(friendUids = contacts.map { it.friendUid }, friendNames = contacts.associate { it.friendUid to (it.remark ?: it.user?.name ?: it.friendUid) },
                onInvite = { uids -> dataState.groups.inviteMembers(chatId, uids) },
                onBack = { navController.popBackStack() })
        }
        composable(Routes.INVITE_LINKS, arguments = listOf(navArgument("chatId"){type=NavType.StringType})) { entry ->
            val chatId = entry.arguments?.getString("chatId") ?: return@composable
            LaunchedEffect(chatId) { dataState.loadScreenDataByKey(ScreenDataKey.InviteLinks(chatId)) }
            InviteLinksScreen(links = dataState.groups.inviteLinks.map { InviteLink(it.token, it.maxUses, it.useCount, it.revokedAt > 0) },
                onCreateLink = { dataState.groups.createInviteLink(chatId) },
                onRevokeLink = { t -> dataState.groups.revokeInviteLink(chatId, t) },
                onBack = { navController.popBackStack() })
        }
        composable(Routes.FORWARD, arguments = listOf(navArgument("chatId"){type=NavType.StringType}, navArgument("serverSeq"){type=NavType.LongType})) { entry ->
            val chatId = entry.arguments?.getString("chatId") ?: return@composable
            val serverSeq = entry.arguments?.getLong("serverSeq") ?: return@composable
            val conversations by dataState.conversationViewModel.conversations.collectAsState()
            ForwardScreen(conversations = conversations, onForward = { tc -> dataState.discovery.forwardMessage(chatId, serverSeq, tc) }, onBack = { navController.popBackStack() })
        }
    }
}
