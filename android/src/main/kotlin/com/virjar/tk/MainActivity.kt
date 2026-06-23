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
import com.virjar.tk.AppError
import com.virjar.tk.client.*
import com.virjar.tk.model.Message
import com.virjar.tk.model.User
import com.virjar.tk.navigation.AppDataState
import com.virjar.tk.navigation.ScreenDataKey
import com.virjar.tk.ui.AppTheme
import com.virjar.tk.ui.screen.*
import kotlinx.coroutines.flow.first
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
            // Save draft on dispose (covers back button, back gesture, and navigation away)
            DisposableEffect(chatId) {
                onDispose {
                    if (currentDraft != null && currentDraft != "") {
                        kotlinx.coroutines.runBlocking { dataState.conversationRepo.setDraft(chatId, currentDraft) }
                    }
                }
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
            SearchMessagesScreen(searchMessages = { q -> try { dataState.messageRepo.searchMessages("", q).getOrThrow() } catch (e: AppError) { dataState.handleError(e, "搜索失败"); emptyList() } },
                onMessageClick = { cid, _ -> val c = conversations.find { it.chatId == cid }; dataState.prepareChat(cid, c?.chatName ?: cid.take(16)); navController.navigate(Routes.chat(cid, c?.chatName ?: cid.take(16))) { popUpTo(Routes.HOME) } },
                onBack = { navController.popBackStack() })
        }
        composable(Routes.SEARCH_USERS) {
            SearchUsersScreen(searchUsers = { q -> try { dataState.userRepo.search(q).getOrThrow() } catch (e: AppError) { dataState.handleError(e, "搜索失败"); emptyList() } },
                onUserClick = { uid -> navController.navigate(Routes.userProfile(uid)) }, onBack = { navController.popBackStack() })
        }
        composable(Routes.CREATE_GROUP) {
            val contacts by dataState.contactViewModel.contacts.collectAsState()
            CreateGroupScreen(contacts = contacts, onCreateGroup = { name, uids ->
                try { val chat = dataState.chatRepo.createGroup(name, memberUids = uids).getOrThrow(); dataState.prepareChat(chat.chatId, name, 2); scope.launch { dataState.conversationRepo.listConversations() }; navController.navigate(Routes.chat(chat.chatId, name, 2)) { popUpTo(Routes.CREATE_GROUP) { inclusive = true } }; Result.success(chat.chatId) }
                catch (e: AppError) { dataState.handleError(e, "创建群组失败"); Result.failure(e) }
            }, onBack = { navController.popBackStack() })
        }
        composable(Routes.FRIEND_APPLIES) {
            LaunchedEffect(Unit) { dataState.loadScreenDataByKey(ScreenDataKey.FriendApplies) }
            FriendAppliesScreen(applies = dataState.applies,
                onAccept = { t -> try { dataState.contactRepo.accept(t).getOrThrow() } catch (e: AppError) { dataState.handleError(e, "接受申请失败") }; scope.launch { dataState.loadScreenDataByKey(ScreenDataKey.FriendApplies) } },
                onReject = { t -> try { dataState.contactRepo.reject(t).getOrThrow() } catch (e: AppError) { dataState.handleError(e, "拒绝申请失败") }; scope.launch { dataState.loadScreenDataByKey(ScreenDataKey.FriendApplies) } },
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
                    // 添加好友是临时操作，完成后自动返回主界面并清理搜索导航栈
                    scope.launch {
                        kotlinx.coroutines.delay(800)
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.HOME) { inclusive = true }
                        }
                    }
                },
                onSendMessage = { scope.launch { try { val chat = dataState.chatRepo.createPersonalChat(uid).getOrThrow(); dataState.prepareChat(chat.chatId, dataState.profileUser?.name ?: uid.take(12)); navController.navigate(Routes.chat(chat.chatId, dataState.profileUser?.name ?: uid.take(12))) { popUpTo(Routes.HOME) } } catch (e: AppError) { dataState.handleError(e, "创建聊天失败") } } },
                onDeleteFriend = { dataState.contactViewModel.deleteFriend(uid); navController.popBackStack() },
                onBack = { navController.popBackStack() })
        }
        composable(Routes.EDIT_PROFILE) { EditProfileScreen(currentUser = dataState.currentUser, onSave = { n, p -> try { dataState.userRepo.updateProfile(name = n, phone = p).getOrThrow(); true } catch (e: AppError) { dataState.handleError(e, "保存失败"); false } }, onBack = { navController.popBackStack() }) }
        composable(Routes.CHANGE_PASSWORD) { ChangePasswordScreen(onChangePassword = { o, n -> try { dataState.userRepo.changePassword(o, n).getOrThrow() } catch (e: AppError) { dataState.handleError(e, "修改密码失败"); false } }, onBack = { navController.popBackStack() }) }
        composable(Routes.DEVICES) {
            LaunchedEffect(Unit) { dataState.loadScreenDataByKey(ScreenDataKey.Devices) }
            DeviceManagementScreen(devices = dataState.devices.map { DeviceInfo(it.deviceId, it.deviceName ?: "", it.deviceModel ?: "", it.lastLogin) },
                onKick = { d -> scope.launch { try { dataState.deviceRepo.kickDevice(d).getOrThrow() } catch (e: AppError) { dataState.handleError(e, "踢出设备失败") }; dataState.loadScreenDataByKey(ScreenDataKey.Devices) } },
                onBack = { navController.popBackStack() })
        }
        composable(Routes.BLACKLIST) {
            LaunchedEffect(Unit) { dataState.loadScreenDataByKey(ScreenDataKey.Blacklist) }
            BlacklistScreen(blockedUsers = dataState.blockedContacts.map { BlockedUser(it.friendUid, it.user?.name ?: it.friendUid) },
                onUnblock = { u -> scope.launch { try { dataState.contactRepo.removeFromBlacklist(u).getOrThrow() } catch (e: AppError) { dataState.handleError(e, "移出黑名单失败") }; dataState.loadScreenDataByKey(ScreenDataKey.Blacklist) } },
                onBack = { navController.popBackStack() })
        }
        composable(Routes.GROUP_DETAIL, arguments = listOf(navArgument("chatId"){type=NavType.StringType})) { entry ->
            val chatId = entry.arguments?.getString("chatId") ?: return@composable
            LaunchedEffect(chatId) { dataState.loadScreenDataByKey(ScreenDataKey.GroupDetail(chatId)) }
            GroupDetailScreen(chat = dataState.groupDetailChat, members = dataState.groupMembers, isOwner = dataState.groupMembers.any { it.uid == dataState.userSession.uid && it.role == 2 },
                myUid = dataState.userSession.uid,
                onMemberClick = { uid -> navController.navigate(Routes.userProfile(uid)) }, onInviteMembers = { navController.navigate(Routes.inviteMembers(chatId)) }, onViewInviteLinks = { navController.navigate(Routes.inviteLinks(chatId)) },
                onLeaveGroup = { scope.launch { try { dataState.chatRepo.deleteChat(chatId) } catch (e: Exception) { Log.w("MainActivity", "Failed to delete chat on leave group", e) }; navController.popBackStack(Routes.HOME, inclusive = false) } },
                onEditNotice = { notice -> scope.launch { dataState.chatRepo.updateGroup(chatId, notice = notice); dataState.loadScreenDataByKey(ScreenDataKey.GroupDetail(chatId)) } },
                onBack = { navController.popBackStack() },
                onSetAdmin = { uid -> scope.launch { dataState.chatRepo.setMemberRole(chatId, uid, 1) } },
                onRemoveAdmin = { uid -> scope.launch { dataState.chatRepo.setMemberRole(chatId, uid, 0) } },
                onMuteMember = { uid -> scope.launch { dataState.chatRepo.muteMember(chatId, uid, 3600) } },
                onUnmuteMember = { uid -> scope.launch { dataState.chatRepo.unmuteMember(chatId, uid) } },
                onRemoveMember = { uid -> scope.launch { dataState.chatRepo.removeMember(chatId, uid) } },
            )
        }
        composable(Routes.INVITE_MEMBERS, arguments = listOf(navArgument("chatId"){type=NavType.StringType})) { entry ->
            val chatId = entry.arguments?.getString("chatId") ?: return@composable
            val contacts by dataState.contactViewModel.contacts.collectAsState()
            InviteMembersScreen(friendUids = contacts.map { it.friendUid }, friendNames = contacts.associate { it.friendUid to (it.remark ?: it.user?.name ?: it.friendUid) },
                onInvite = { uids -> try { dataState.chatRepo.addMembers(chatId, uids).getOrThrow(); true } catch (e: AppError) { dataState.handleError(e, "邀请成员失败"); false } },
                onBack = { navController.popBackStack() })
        }
        composable(Routes.INVITE_LINKS, arguments = listOf(navArgument("chatId"){type=NavType.StringType})) { entry ->
            val chatId = entry.arguments?.getString("chatId") ?: return@composable
            LaunchedEffect(chatId) { dataState.loadScreenDataByKey(ScreenDataKey.InviteLinks(chatId)) }
            InviteLinksScreen(links = dataState.inviteLinks.map { InviteLink(it.token, it.maxUses, it.useCount, it.revokedAt > 0) },
                onCreateLink = { try { val t = dataState.chatRepo.createInviteLink(chatId).getOrThrow(); scope.launch { dataState.loadScreenDataByKey(ScreenDataKey.InviteLinks(chatId)) }; t } catch (e: AppError) { dataState.handleError(e, "创建链接失败"); null } },
                onRevokeLink = { t -> scope.launch { try { dataState.chatRepo.revokeInviteLink(t).getOrThrow() } catch (e: AppError) { dataState.handleError(e, "撤销链接失败") }; dataState.loadScreenDataByKey(ScreenDataKey.InviteLinks(chatId)) } },
                onBack = { navController.popBackStack() })
        }
        composable(Routes.FORWARD, arguments = listOf(navArgument("chatId"){type=NavType.StringType}, navArgument("serverSeq"){type=NavType.LongType})) { entry ->
            val chatId = entry.arguments?.getString("chatId") ?: return@composable
            val serverSeq = entry.arguments?.getLong("serverSeq") ?: return@composable
            val conversations by dataState.conversationViewModel.conversations.collectAsState()
            ForwardScreen(conversations = conversations, onForward = { tc -> try { dataState.messageRepo.forwardMessage(chatId, serverSeq, tc).getOrThrow(); true } catch (e: AppError) { dataState.handleError(e, "转发失败"); false } }, onBack = { navController.popBackStack() })
        }
    }
}
