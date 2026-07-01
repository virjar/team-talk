package com.virjar.tk

import android.os.Bundle
import android.util.Log
import com.virjar.tk.android.BuildConfig
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 全局初始化（日志注入、ServerConfig、异常拦截）已在 TeamTalkApp.onCreate 完成
        setContent {
            AppTheme {
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
    var customServerUrl by remember { mutableStateOf(config.serverUrl) }
    var customTcpHost by remember { mutableStateOf(config.tcpHost) }
    var customTcpPort by remember { mutableIntStateOf(config.tcpPort) }

    if (showRegister) {
        RegisterScreen(
            onRegister = { u, p, n -> onAuthErrorChange(null); scope.launch {
                try { imClient.register(u, p, n, "android-${UUID.randomUUID()}", "Android", customTcpHost, customTcpPort) }
                catch (e: IllegalArgumentException) { onAuthErrorChange(e.message) }
            }},
            onNavigateBack = { showRegister = false; onAuthErrorChange(null) }, error = authError,
        )
    } else {
        LoginScreen(
            onLogin = { u, p -> onAuthErrorChange(null); scope.launch {
                try { imClient.login(u, p, "android-${UUID.randomUUID()}", "Android", customTcpHost, customTcpPort) }
                catch (e: IllegalArgumentException) { onAuthErrorChange(e.message) }
            }},
            onNavigateToRegister = { showRegister = true; onAuthErrorChange(null) }, error = authError,
            allowCustomServer = BuildConfig.ALLOW_CUSTOM_SERVER,  // 编译时常量，false 时 R8 删除整个分支
            serverUrl = customServerUrl,
            onServerUrlChange = { newUrl ->
                customServerUrl = newUrl
                val host = newUrl.removePrefix("https://").removePrefix("http://").substringBefore("/")
                customTcpHost = host
                configureServerConfig(ServerConfig(newUrl, host, customTcpPort, BuildConfig.ALLOW_CUSTOM_SERVER))
            },
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
                onSearchMessages = { navController.navigate(Routes.SEARCH_MESSAGES) },
                onSearchUsers = { navController.navigate(Routes.SEARCH_USERS) },
                onCreateGroup = { navController.navigate(Routes.CREATE_GROUP) },
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
            // 离开聊天页时保存草稿（fire-and-forget，不再阻塞主线程）
            DisposableEffect(chatId) {
                onDispose { dataState.saveDraft(chatId, currentDraft) }
            }
            if (vm != null) { AndroidChatScreen(chatId, chatName, chatType, vm, dataState.userSession.uid,
                serverUrl = defaultServerConfig().serverUrl,
                resolveSender = { uid -> dataState.localCache.getUser(uid) },
                draft = currentDraft,
                onDraftChange = { currentDraft = it },
                onForward = { msg -> navController.navigate(Routes.forward(msg.chatId, msg.serverSeq)) },
                onGroupDetail = { navController.navigate(Routes.groupDetail(chatId)) },
                onBack = { navController.popBackStack() },
            )}
        }
        composable(Routes.SEARCH_MESSAGES) {
            val conversations by dataState.conversationViewModel.conversations.collectAsState()
            SearchMessagesScreen(searchMessages = { q -> dataState.searchMessages(q) },
                onMessageClick = { cid, _ -> val c = conversations.find { it.chatId == cid }; dataState.prepareChat(cid, c?.chatName ?: cid.take(16)); navController.navigate(Routes.chat(cid, c?.chatName ?: cid.take(16))) { popUpTo(Routes.HOME) } },
                onBack = { navController.popBackStack() })
        }
        composable(Routes.SEARCH_USERS) {
            SearchUsersScreen(searchUsers = { q -> dataState.searchUsers(q) },
                onUserClick = { uid -> navController.navigate(Routes.userProfile(uid)) }, onBack = { navController.popBackStack() })
        }
        composable(Routes.CREATE_GROUP) {
            val contacts by dataState.contactViewModel.contacts.collectAsState()
            CreateGroupScreen(contacts = contacts, onCreateGroup = { name, uids ->
                val chatId = dataState.createGroup(name, uids)
                if (chatId != null) {
                    dataState.prepareChat(chatId, name, 2)
                    navController.navigate(Routes.chat(chatId, name, 2)) { popUpTo(Routes.CREATE_GROUP) { inclusive = true } }
                    Result.success(chatId)
                } else Result.failure(Exception("创建失败"))
            }, onBack = { navController.popBackStack() })
        }
        composable(Routes.FRIEND_APPLIES) {
            LaunchedEffect(Unit) { dataState.loadScreenDataByKey(ScreenDataKey.FriendApplies) }
            FriendAppliesScreen(applies = dataState.applies,
                onAccept = { t -> dataState.acceptFriendApply(t) },
                onReject = { t -> dataState.rejectFriendApply(t) },
                onBack = { navController.popBackStack() })
        }
        composable(Routes.USER_PROFILE, arguments = listOf(navArgument("uid"){type=NavType.StringType})) { entry ->
            val uid = entry.arguments?.getString("uid") ?: return@composable
            var hasPendingApply by remember { mutableStateOf(false) }
            LaunchedEffect(uid) { dataState.loadScreenDataByKey(ScreenDataKey.UserProfile(uid)); hasPendingApply = false }
            UserProfileScreen(user = dataState.profileUser, isFriend = dataState.isFriend, hasPendingApply = hasPendingApply,
                onAddFriend = {
                    dataState.contactViewModel.apply(uid)
                    hasPendingApply = true
                    scope.launch {
                        kotlinx.coroutines.delay(800)
                        navController.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = true } }
                    }
                },
                onSendMessage = { scope.launch {
                    val chatId = dataState.startPersonalChat(uid)
                    if (chatId != null) {
                        val n = dataState.profileUser?.name ?: uid.take(12)
                        dataState.prepareChat(chatId, n)
                        navController.navigate(Routes.chat(chatId, n)) { popUpTo(Routes.HOME) }
                    }
                }},
                onDeleteFriend = { dataState.contactViewModel.deleteFriend(uid); navController.popBackStack() },
                onBack = { navController.popBackStack() })
        }
        composable(Routes.EDIT_PROFILE) { EditProfileScreen(currentUser = dataState.currentUser, onSave = { n, p -> dataState.saveProfile(n, p) }, onBack = { navController.popBackStack() }) }
        composable(Routes.CHANGE_PASSWORD) { ChangePasswordScreen(onChangePassword = { o, n -> dataState.changePassword(o, n) }, onBack = { navController.popBackStack() }) }
        composable(Routes.DEVICES) {
            LaunchedEffect(Unit) { dataState.loadScreenDataByKey(ScreenDataKey.Devices) }
            DeviceManagementScreen(devices = dataState.devices.map { DeviceInfo(it.deviceId, it.deviceName ?: "", it.deviceModel ?: "", it.lastLogin) },
                onKick = { d -> dataState.kickDevice(d) },
                onBack = { navController.popBackStack() })
        }
        composable(Routes.BLACKLIST) {
            LaunchedEffect(Unit) { dataState.loadScreenDataByKey(ScreenDataKey.Blacklist) }
            BlacklistScreen(blockedUsers = dataState.blockedContacts.map { BlockedUser(it.friendUid, it.user?.name ?: it.friendUid) },
                onUnblock = { u -> dataState.unblockContact(u) },
                onBack = { navController.popBackStack() })
        }
        composable(Routes.GROUP_DETAIL, arguments = listOf(navArgument("chatId"){type=NavType.StringType})) { entry ->
            val chatId = entry.arguments?.getString("chatId") ?: return@composable
            LaunchedEffect(chatId) { dataState.loadScreenDataByKey(ScreenDataKey.GroupDetail(chatId)) }
            GroupDetailScreen(chat = dataState.groupDetailChat, members = dataState.groupMembers, isOwner = dataState.groupMembers.any { it.uid == dataState.userSession.uid && it.role == 2 },
                myUid = dataState.userSession.uid,
                onMemberClick = { uid -> navController.navigate(Routes.userProfile(uid)) }, onInviteMembers = { navController.navigate(Routes.inviteMembers(chatId)) }, onViewInviteLinks = { navController.navigate(Routes.inviteLinks(chatId)) },
                onLeaveGroup = { dataState.leaveGroup(chatId) { navController.popBackStack(Routes.HOME, inclusive = false) } },
                onEditNotice = { notice -> dataState.updateGroupNotice(chatId, notice) },
                onBack = { navController.popBackStack() },
                onSetAdmin = { uid -> dataState.setMemberRole(chatId, uid, 1) },
                onRemoveAdmin = { uid -> dataState.setMemberRole(chatId, uid, 0) },
                onMuteMember = { uid -> dataState.muteMember(chatId, uid) },
                onUnmuteMember = { uid -> dataState.unmuteMember(chatId, uid) },
                onRemoveMember = { uid -> dataState.removeMember(chatId, uid) },
            )
        }
        composable(Routes.INVITE_MEMBERS, arguments = listOf(navArgument("chatId"){type=NavType.StringType})) { entry ->
            val chatId = entry.arguments?.getString("chatId") ?: return@composable
            val contacts by dataState.contactViewModel.contacts.collectAsState()
            InviteMembersScreen(friendUids = contacts.map { it.friendUid }, friendNames = contacts.associate { it.friendUid to (it.remark ?: it.user?.name ?: it.friendUid) },
                onInvite = { uids -> dataState.inviteMembers(chatId, uids) },
                onBack = { navController.popBackStack() })
        }
        composable(Routes.INVITE_LINKS, arguments = listOf(navArgument("chatId"){type=NavType.StringType})) { entry ->
            val chatId = entry.arguments?.getString("chatId") ?: return@composable
            LaunchedEffect(chatId) { dataState.loadScreenDataByKey(ScreenDataKey.InviteLinks(chatId)) }
            InviteLinksScreen(links = dataState.inviteLinks.map { InviteLink(it.token, it.maxUses, it.useCount, it.revokedAt > 0) },
                onCreateLink = { dataState.createInviteLink(chatId) },
                onRevokeLink = { t -> dataState.revokeInviteLink(chatId, t) },
                onBack = { navController.popBackStack() })
        }
        composable(Routes.FORWARD, arguments = listOf(navArgument("chatId"){type=NavType.StringType}, navArgument("serverSeq"){type=NavType.LongType})) { entry ->
            val chatId = entry.arguments?.getString("chatId") ?: return@composable
            val serverSeq = entry.arguments?.getLong("serverSeq") ?: return@composable
            val conversations by dataState.conversationViewModel.conversations.collectAsState()
            ForwardScreen(conversations = conversations, onForward = { tc -> dataState.forwardMessage(chatId, serverSeq, tc) }, onBack = { navController.popBackStack() })
        }
    }
}
