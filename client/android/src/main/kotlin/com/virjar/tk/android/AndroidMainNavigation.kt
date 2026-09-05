package com.virjar.tk.android

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.shared.client.ProtocolCompatibility
import com.virjar.tk.app.ui.component.ProtocolUpgradeBanner
import com.virjar.tk.app.navigation.AppDataState
import com.virjar.tk.app.navigation.ScreenDataKey
import com.virjar.tk.app.navigation.MainTab
import com.virjar.tk.app.ui.UiActionAdmission
import com.virjar.tk.app.ui.screen.BlacklistScreen
import com.virjar.tk.app.ui.screen.BlockedUser
import com.virjar.tk.app.ui.screen.ChangePasswordScreen
import com.virjar.tk.app.ui.screen.conversationIdentityPresentation
import com.virjar.tk.app.ui.screen.CreateGroupScreen
import com.virjar.tk.app.ui.screen.DeviceInfo
import com.virjar.tk.app.ui.screen.DeviceManagementScreen
import com.virjar.tk.app.ui.screen.ForwardScreen
import com.virjar.tk.app.ui.screen.FriendAppliesScreen
import com.virjar.tk.app.ui.screen.GlobalSearchScreen
import com.virjar.tk.app.ui.screen.GroupBotsScreen
import com.virjar.tk.app.ui.screen.GroupDetailScreen
import com.virjar.tk.app.ui.screen.InviteLink
import com.virjar.tk.app.ui.screen.InviteLinksScreen
import com.virjar.tk.app.ui.screen.InviteMembersScreen
import com.virjar.tk.app.ui.screen.SearchUsersScreen
import com.virjar.tk.app.ui.screen.UserProfileScreen
import com.virjar.tk.app.telemetry.ClientUiAction
import com.virjar.tk.app.telemetry.ClientUiPage
import com.virjar.tk.app.telemetry.ClientActionOutcome
import com.virjar.tk.app.viewmodel.MessageFocusTarget
import kotlinx.coroutines.flow.MutableStateFlow
import com.virjar.tk.protocol.body.OfficeRefBody
import kotlinx.coroutines.launch

/**
 * 已认证状态下的 Android 主导航外壳。
 * 路由项按 destination 函数分组，在各私有 NavGraphBuilder 扩展中注册。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AndroidMainAppContent(
    dataState: AppDataState,
    resourceOwner: AndroidAuthenticatedResourceOwner,
    connectionState: ConnectionState,
    protocolCompatibility: ProtocolCompatibility?,
    notificationNavigation: AndroidNotificationNavigation,
    onLogout: () -> Unit,
) {
    if (!dataState.acceptsRendering) return
    val navController = rememberNavController()
    val requestedDocument = remember { MutableStateFlow<OfficeRefBody?>(null) }
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    var homeTelemetryPage by remember { mutableStateOf(ClientUiPage.CONVERSATIONS) }
    val currentTelemetryPage = if (currentBackStackEntry?.destination?.route == Routes.HOME) {
        homeTelemetryPage
    } else {
        androidTelemetryPage(currentBackStackEntry?.destination?.route)
    }
    AndroidTelemetryLifecycle(
        telemetry = dataState.telemetry,
        connectionState = connectionState,
        currentPage = currentTelemetryPage,
        acceptsRendering = { dataState.acceptsRendering },
    )
    val actionAdmission = dataState.uiActionAdmission
    val notificationTarget by notificationNavigation.target.collectAsState()
    LaunchedEffect(notificationTarget, dataState, currentBackStackEntry) {
        val target = notificationTarget ?: return@LaunchedEffect
        val owner = dataState.documentDraftOwnerKey
        if (!target.belongsTo(owner.deploymentFingerprint, owner.datasetId, owner.uid)) {
            notificationNavigation.consume(target)
            return@LaunchedEffect
        }
        // 冷启动点击先等待认证与 NavHost 首帧；intent 的账号字段只用于比对当前所有者。
        val currentEntry = currentBackStackEntry ?: return@LaunchedEffect
        actionAdmission.runIfOpen {
            val alreadyOpen = currentEntry.destination.route == Routes.CHAT &&
                currentEntry.arguments?.getString("chatId") == target.chatId
            if (!alreadyOpen && dataState.prepareChat(target.chatId)) {
                navController.navigate(Routes.chat(Uri.encode(target.chatId))) {
                    popUpTo(Routes.HOME)
                    launchSingleTop = true
                }
            }
        }
        notificationNavigation.consume(target)
    }
    suspend fun <T> admittedAction(
        onClosed: () -> T,
        action: suspend () -> T,
    ): T = dataState.runAdmittedUiAction(actionAdmission, onClosed, action)
    fun launchAdmittedAction(action: suspend () -> Unit): Boolean =
        dataState.launchAdmittedUiAction(actionAdmission, action)
    val chatEmbeddedAssets = rememberAndroidChatEmbeddedAssetImportHost(dataState, resourceOwner)
        ?: return
    val chatEmbeddedAssetImports = chatEmbeddedAssets.gateway
    val chatEmbeddedAssetSelector = chatEmbeddedAssets.selector
    val snackbarHostState = rememberAndroidSessionFeedback(dataState, currentTelemetryPage)
    Box(Modifier.fillMaxSize()) {
        // 已认证外壳同时拥有系统栏内边距与连接反馈。把横幅放在 NavHost 上方，
        // 让同样的离线真值在每个目标页都可见，又不会遮挡路由的应用栏或底部导航。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .statusBarsPadding(),
        ) {
            AndroidConnectionStatusBanner(connectionState)
            ProtocolUpgradeBanner(protocolCompatibility)
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                enterTransition = {
                    slideIntoContainer(
                        AnimatedContentTransitionScope.SlideDirection.Start,
                        animationSpec = tween(300),
                    )
                },
                exitTransition = {
                    slideOutOfContainer(
                        AnimatedContentTransitionScope.SlideDirection.Start,
                        animationSpec = tween(300),
                    )
                },
                popEnterTransition = {
                    slideIntoContainer(
                        AnimatedContentTransitionScope.SlideDirection.End,
                        animationSpec = tween(300),
                    )
                },
                popExitTransition = {
                    slideOutOfContainer(
                        AnimatedContentTransitionScope.SlideDirection.End,
                        animationSpec = tween(300),
                    )
                },
            ) {
                homeDestination(
                    navController = navController,
                    dataState = dataState,
                    resourceOwner = resourceOwner,
                    actionAdmission = actionAdmission,
                    launchAdmittedAction = ::launchAdmittedAction,
                    requestedDocument = requestedDocument,
                    onHomeTelemetryPageChange = { homeTelemetryPage = it },
                    onLogout = onLogout,
                )
                chatDestination(
                    navController = navController,
                    dataState = dataState,
                    resourceOwner = resourceOwner,
                    actionAdmission = actionAdmission,
                    requestedDocument = requestedDocument,
                    chatEmbeddedAssetImports = chatEmbeddedAssetImports,
                    chatEmbeddedAssetSelector = chatEmbeddedAssetSelector,
                )
                androidTextAttachmentPreviewRoute(
                    navController = navController,
                    dataState = dataState,
                    resourceOwner = resourceOwner,
                )
                searchDestination(
                    navController = navController,
                    dataState = dataState,
                    actionAdmission = actionAdmission,
                )
                contactsDestination(
                    navController = navController,
                    dataState = dataState,
                    resourceOwner = resourceOwner,
                    actionAdmission = actionAdmission,
                    launchAdmittedAction = ::launchAdmittedAction,
                )
                accountSecurityDestination(
                    navController = navController,
                    dataState = dataState,
                    actionAdmission = actionAdmission,
                )
                groupAdminDestination(
                    navController = navController,
                    dataState = dataState,
                    actionAdmission = actionAdmission,
                    launchAdmittedAction = ::launchAdmittedAction,
                )
                androidGroupFilesRoute(
                    navController = navController,
                    dataState = dataState,
                    resourceOwner = resourceOwner,
                )
                inviteDestination(
                    navController = navController,
                    dataState = dataState,
                    actionAdmission = actionAdmission,
                )
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 72.dp)
                .testTag("main.error.snackbar"),
        )
    }
}

/** 主页路由：多标签外壳、首页遥测页签与各入口导航。 */
private fun NavGraphBuilder.homeDestination(
    navController: NavHostController,
    dataState: AppDataState,
    resourceOwner: AndroidAuthenticatedResourceOwner,
    actionAdmission: UiActionAdmission,
    launchAdmittedAction: (suspend () -> Unit) -> Boolean,
    requestedDocument: MutableStateFlow<OfficeRefBody?>,
    onHomeTelemetryPageChange: (ClientUiPage) -> Unit,
    onLogout: () -> Unit,
) {
    composable(Routes.HOME) {
        HomeScreen(
            dataState = dataState,
            resourceOwner = resourceOwner,
            launchAdmittedAction = launchAdmittedAction,
            requestedDocument = requestedDocument,
            onSelectedTabChanged = { tab ->
                onHomeTelemetryPageChange(androidHomeTabTelemetryPage(tab))
            },
            onLogout = actionAdmission.guard {
                dataState.telemetry.recordAction(
                    ClientUiPage.SETTINGS,
                    ClientUiAction.LOGOUT,
                    ClientActionOutcome.STARTED,
                )
                onLogout()
            },
            onConversationClick = { cid ->
                actionAdmission.runIfOpen {
                    if (dataState.prepareChat(cid)) {
                        navController.navigate(Routes.chat(cid))
                    }
                }
            },
            onGlobalSearch = actionAdmission.guard {
                navController.navigate(Routes.SEARCH_MESSAGES)
            },
            onFriendApplies = actionAdmission.guard {
                navController.navigate(Routes.FRIEND_APPLIES)
            },
            onUserProfile = actionAdmission.guard { uid: String ->
                navController.navigate(Routes.userProfile(uid))
            },
            onEditProfile = actionAdmission.guard {
                navController.navigate(Routes.EDIT_PROFILE)
            },
            onChangePassword = actionAdmission.guard {
                navController.navigate(Routes.CHANGE_PASSWORD)
            },
            onDevices = actionAdmission.guard { navController.navigate(Routes.DEVICES) },
            onBlacklist = actionAdmission.guard { navController.navigate(Routes.BLACKLIST) },
        )
    }
}

/** 搜索路由：全局消息搜索与独立的用户搜索。 */
private fun NavGraphBuilder.searchDestination(
    navController: NavHostController,
    dataState: AppDataState,
    actionAdmission: UiActionAdmission,
) {
    suspend fun <T> admittedAction(onClosed: () -> T, action: suspend () -> T): T =
        dataState.runAdmittedUiAction(actionAdmission, onClosed, action)
    composable(Routes.SEARCH_MESSAGES) {
        val conversations by dataState.conversationViewModel.conversations.collectAsState()
        val peerUsers by dataState.conversationViewModel.peerUsers.collectAsState()
        val searchUsers by dataState.globalSearchUserViewModel.users.collectAsState()
        val contacts by dataState.contactViewModel.contacts.collectAsState()
        var query by rememberSaveable { mutableStateOf("") }
        GlobalSearchScreen(
            query = query,
            onQueryChange = { query = it },
            conversations = conversations,
            contacts = contacts,
            conversationPeerUsers = peerUsers,
            canonicalSearchUsers = searchUsers,
            onDisplayedSearchUserUidsChange =
                dataState.globalSearchUserViewModel::bindDisplayedUserUids,
            searchMessages = { queryText ->
                admittedAction(onClosed = { emptyList() }) {
                    dataState.discovery.searchMessages(queryText)
                }
            },
            searchUsers = { queryText ->
                admittedAction(onClosed = { emptyList() }) {
                    dataState.discovery.searchUsers(queryText)
                }
            },
            onConversationClick = actionAdmission.guard { conversation: com.virjar.tk.protocol.model.Conversation ->
                if (dataState.prepareChat(conversation.chatId)) {
                    navController.navigate(
                        Routes.chat(conversation.chatId),
                    ) {
                        popUpTo(Routes.HOME)
                    }
                }
            },
            onMessageClick = actionAdmission.guard { message: com.virjar.tk.protocol.model.Message ->
                if (dataState.prepareChat(message.chatId)) {
                    navController.navigate(Routes.chat(message.chatId, message.serverSeq)) {
                        popUpTo(Routes.HOME)
                    }
                }
            },
            onUserClick = actionAdmission.guard { user: com.virjar.tk.protocol.model.User ->
                navController.navigate(Routes.userProfile(user.uid))
            },
            excludedUserUid = dataState.userSession.uid,
            onBack = actionAdmission.guard { navController.popBackStack() },
        )
    }
    composable(Routes.SEARCH_USERS) {
        SearchUsersScreen(
            searchUsers = { query ->
                admittedAction(onClosed = { emptyList() }) {
                    dataState.discovery.searchUsers(query)
                }
            },
            onUserClick = actionAdmission.guard { uid: String ->
                navController.navigate(Routes.userProfile(uid))
            },
            onBack = actionAdmission.guard { navController.popBackStack() },
        )
    }
}

/** 联系人与资料路由：建群、好友申请、用户资料与资料编辑。 */
private fun NavGraphBuilder.contactsDestination(
    navController: NavHostController,
    dataState: AppDataState,
    resourceOwner: AndroidAuthenticatedResourceOwner,
    actionAdmission: UiActionAdmission,
    launchAdmittedAction: (suspend () -> Unit) -> Boolean,
) {
    suspend fun <T> admittedAction(onClosed: () -> T, action: suspend () -> T): T =
        dataState.runAdmittedUiAction(actionAdmission, onClosed, action)
    composable(
        Routes.CREATE_GROUP,
        arguments = listOf(
            navArgument("seedUid") {
                type = NavType.StringType
                defaultValue = ""
            },
        ),
    ) { entry ->
        val contacts by dataState.contactViewModel.contacts.collectAsState()
        val seedUid = entry.arguments?.getString("seedUid").orEmpty()
        CreateGroupScreen(
            contacts = contacts,
            pendingGroupCreation = dataState.groups.pendingGroupCreation,
            groupCreationDraftLoaded = dataState.groups.groupCreationDraftLoaded,
            groupCreationDraftError = dataState.groups.groupCreationDraftError,
            onCreateGroup = { name, uids ->
                admittedAction(
                    onClosed = {
                        Result.failure(IllegalStateException("登录会话已结束"))
                    },
                ) {
                    val chatId = dataState.groups.create(name, uids)
                    if (chatId != null) {
                        val navigated = actionAdmission.runIfOpen {
                            if (dataState.prepareChat(chatId)) {
                                navController.navigate(Routes.chat(chatId)) {
                                    popUpTo(Routes.CREATE_GROUP) { inclusive = true }
                                }
                            }
                        }
                        if (navigated) Result.success(chatId) else {
                            Result.failure(IllegalStateException("登录会话已结束"))
                        }
                    } else {
                        Result.failure(Exception("创建失败"))
                    }
                }
            },
            onDiscardPendingGroupCreation = {
                admittedAction(onClosed = { false }) {
                    dataState.groups.discardPendingCreation()
                }
            },
            onBack = actionAdmission.guard { navController.popBackStack() },
            initialSelectedUids = setOfNotNull(seedUid.takeIf { it.isNotBlank() }),
        )
    }
    composable(Routes.FRIEND_APPLIES) {
        LaunchedEffect(Unit) {
            admittedAction(onClosed = {}) {
                dataState.loadScreenDataByKey(ScreenDataKey.FriendApplies)
            }
        }
        FriendAppliesScreen(
            records = dataState.account.friendApplyRecords,
            loading = dataState.account.friendApplyRecordsLoading,
            hasMore = dataState.account.friendApplyRecordsHasMore,
            onLoadMore = actionAdmission.guard(dataState.account::loadMoreFriendApplies),
            onAccept = { token ->
                admittedAction(onClosed = {}) {
                    dataState.account.acceptFriendApply(token)
                }
            },
            onReject = { token ->
                admittedAction(onClosed = {}) {
                    dataState.account.rejectFriendApply(token)
                }
            },
            onBack = actionAdmission.guard { navController.popBackStack() },
        )
    }
    composable(
        Routes.USER_PROFILE,
        arguments = listOf(navArgument("uid") { type = NavType.StringType }),
    ) { entry ->
        val uid = entry.arguments?.getString("uid") ?: return@composable
        LaunchedEffect(uid) {
            admittedAction(onClosed = {}) {
                dataState.loadScreenDataByKey(ScreenDataKey.UserProfile(uid))
            }
        }
        UserProfileScreen(
            user = dataState.account.profileUser?.takeIf { it.uid == uid },
            myUid = dataState.userSession.uid,
            isFriend = dataState.account.isFriend,
            hasPendingApply = dataState.account.hasOutgoingFriendApply(uid),
            hasIncomingApply = dataState.account.hasIncomingFriendApply(uid),
            isApplyingFriend = dataState.account.isApplyingFriend(uid),
            onAddFriend = actionAdmission.guard { dataState.account.applyFriend(uid) },
            onViewFriendApplies = actionAdmission.guard {
                navController.navigate(Routes.FRIEND_APPLIES)
            },
            onSendMessage = {
                launchAdmittedAction {
                    val chatId = dataState.discovery.startPersonalChat(uid)
                    if (chatId != null) {
                        actionAdmission.runIfOpen {
                            if (dataState.prepareChat(chatId)) {
                                navController.navigate(Routes.chat(chatId)) {
                                    popUpTo(Routes.HOME)
                                }
                            }
                        }
                    }
                }
            },
            onCreateGroup = if (dataState.account.isFriend) {
                actionAdmission.guard { navController.navigate(Routes.createGroup(uid)) }
            } else {
                null
            },
            onBlockUser = if (uid != dataState.userSession.uid) {
                actionAdmission.guard {
                    dataState.account.blockContact(uid) {
                        actionAdmission.runIfOpen { navController.popBackStack() }
                    }
                }
            } else {
                null
            },
            onDeleteFriend = actionAdmission.guard {
                dataState.contactViewModel.deleteFriend(uid)
                navController.popBackStack()
            },
            onBack = actionAdmission.guard { navController.popBackStack() },
        )
    }
    composable(Routes.EDIT_PROFILE) {
        AndroidEditProfileHost(
            dataState = dataState,
            resourceOwner = resourceOwner,
            onBack = actionAdmission.guard { navController.popBackStack() },
        )
    }
}

/** 账号安全路由：改密、设备管理与黑名单。 */
private fun NavGraphBuilder.accountSecurityDestination(
    navController: NavHostController,
    dataState: AppDataState,
    actionAdmission: UiActionAdmission,
) {
    suspend fun <T> admittedAction(onClosed: () -> T, action: suspend () -> T): T =
        dataState.runAdmittedUiAction(actionAdmission, onClosed, action)
    composable(Routes.CHANGE_PASSWORD) {
        ChangePasswordScreen(
            onChangePassword = { old, new ->
                admittedAction(onClosed = { false }) {
                    dataState.account.changePassword(old, new)
                }
            },
            onBack = actionAdmission.guard { navController.popBackStack() },
        )
    }
    composable(Routes.DEVICES) {
        LaunchedEffect(Unit) {
            admittedAction(onClosed = {}) {
                dataState.loadScreenDataByKey(ScreenDataKey.Devices)
            }
        }
        DeviceManagementScreen(
            devices = dataState.account.devices.map {
                DeviceInfo(
                    it.deviceId,
                    it.deviceName ?: "",
                    it.deviceModel ?: "",
                    it.lastLogin,
                )
            },
            currentDeviceId = dataState.account.currentDeviceId,
            onKick = actionAdmission.guard { device: String ->
                dataState.account.kickDevice(device)
            },
            onBack = actionAdmission.guard { navController.popBackStack() },
        )
    }
    composable(Routes.BLACKLIST) {
        LaunchedEffect(Unit) {
            admittedAction(onClosed = {}) {
                dataState.loadScreenDataByKey(ScreenDataKey.Blacklist)
            }
        }
        BlacklistScreen(
            blockedUsers = dataState.account.blockedContacts.map {
                BlockedUser(it.friendUid, it.user?.name ?: it.friendUid)
            },
            onUnblock = actionAdmission.guard { user: String ->
                dataState.account.unblockContact(user)
            },
            onBack = actionAdmission.guard { navController.popBackStack() },
        )
    }
}

/** 群管理路由：群详情与群机器人。 */
private fun NavGraphBuilder.groupAdminDestination(
    navController: NavHostController,
    dataState: AppDataState,
    actionAdmission: UiActionAdmission,
    launchAdmittedAction: (suspend () -> Unit) -> Boolean,
) {
    suspend fun <T> admittedAction(onClosed: () -> T, action: suspend () -> T): T =
        dataState.runAdmittedUiAction(actionAdmission, onClosed, action)
    composable(
        Routes.GROUP_DETAIL,
        arguments = listOf(navArgument("chatId") { type = NavType.StringType }),
    ) { entry ->
        val chatId = entry.arguments?.getString("chatId") ?: return@composable
        LaunchedEffect(chatId) {
            admittedAction(onClosed = {}) {
                dataState.loadScreenDataByKey(ScreenDataKey.GroupDetail(chatId))
            }
        }
        val detailReady = dataState.groups.detailTargetChatId == chatId
        val detailChat = dataState.groups.detailChat?.takeIf {
            detailReady && it.chatId == chatId
        }
        val detailMembers = dataState.groups.members.takeIf { detailReady }.orEmpty()
        val currentRole = detailMembers
            .firstOrNull { it.uid == dataState.userSession.uid }
            ?.role ?: -1
        val currentUserIsOwner = currentRole == 2
        GroupDetailScreen(
            chat = detailChat,
            members = detailMembers,
            isOwner = currentUserIsOwner,
            myUid = dataState.userSession.uid,
            onMemberClick = actionAdmission.guard { uid: String ->
                navController.navigate(Routes.userProfile(uid))
            },
            onInviteMembers = actionAdmission.guard {
                navController.navigate(Routes.inviteMembers(chatId))
            },
            onViewInviteLinks = actionAdmission.guard {
                navController.navigate(Routes.inviteLinks(chatId))
            },
            onGroupFiles = actionAdmission.guard {
                navController.navigate(Routes.groupFiles(chatId))
            },
            onGroupBots = actionAdmission.guard {
                navController.navigate(Routes.groupBots(chatId))
            },
            onLeaveGroup = actionAdmission.guard {
                dataState.groups.exit(chatId, dissolve = currentUserIsOwner) {
                    actionAdmission.runIfOpen {
                        navController.popBackStack(Routes.HOME, inclusive = false)
                    }
                }
            },
            onEditNotice = actionAdmission.guard { notice: String ->
                dataState.groups.updateNotice(chatId, notice)
            },
            onBack = actionAdmission.guard { navController.popBackStack() },
            onSetAdmin = actionAdmission.guard { uid: String ->
                dataState.groups.setMemberRole(chatId, uid, 1)
            },
            onRemoveAdmin = actionAdmission.guard { uid: String ->
                dataState.groups.setMemberRole(chatId, uid, 0)
            },
            onMuteMember = actionAdmission.guard { uid: String ->
                dataState.groups.muteMember(chatId, uid)
            },
            onUnmuteMember = actionAdmission.guard { uid: String ->
                dataState.groups.unmuteMember(chatId, uid)
            },
            onRemoveMember = actionAdmission.guard { uid: String ->
                dataState.groups.removeMember(chatId, uid)
            },
        )
    }
    composable(
        Routes.GROUP_BOTS,
        arguments = listOf(navArgument("chatId") { type = NavType.StringType }),
    ) { entry ->
        val chatId = entry.arguments?.getString("chatId") ?: return@composable
        LaunchedEffect(chatId) {
            admittedAction(onClosed = {}) {
                dataState.loadScreenDataByKey(ScreenDataKey.GroupBots(chatId))
            }
        }
        val ready = dataState.groups.groupBotsTargetChatId == chatId
        val credentialPresentation = dataState.groups.groupBotCredentialPresentation(chatId)
        GroupBotsScreen(
            chatId = chatId,
            serverUrl = dataState.deploymentIdentity.httpBaseUrl,
            bots = dataState.groups.groupBots.takeIf { ready }.orEmpty(),
            loading = !ready || dataState.groups.groupBotsLoading,
            error = dataState.groups.groupBotsError.takeIf { ready },
            canCreate = ready && dataState.groups.groupBotsError == null &&
                !dataState.groups.hasUnacknowledgedGroupBotCredential,
            creating = dataState.groups.creatingGroupBot,
            operationBotId = dataState.groups.groupBotOperationId,
            credentials = credentialPresentation.credentials?.credentials,
            credentialsChatId = credentialPresentation.credentials?.chatId,
            pendingRecovery = credentialPresentation.pendingRecovery,
            credentialCommandBlocked = dataState.groups.hasUnacknowledgedGroupBotCredential,
            onRefresh = {
                launchAdmittedAction {
                    dataState.loadScreenDataByKey(ScreenDataKey.GroupBots(chatId))
                }
            },
            onCreate = actionAdmission.guard { name: String ->
                dataState.groups.createGroupBot(chatId, name)
            },
            onRotate = actionAdmission.guard { botId: String ->
                dataState.groups.rotateGroupBotToken(chatId, botId)
            },
            onRemove = actionAdmission.guard { botId: String ->
                dataState.groups.removeGroupBot(chatId, botId)
            },
            onDismissCredentials = actionAdmission.guard {
                credentialPresentation.credentials?.chatId?.let(
                    dataState.groups::dismissGroupBotCredentials,
                )
            },
            onRetryPendingCredential = {
                launchAdmittedAction {
                    dataState.loadScreenDataByKey(ScreenDataKey.GroupBots(chatId))
                }
            },
            onAbandonPendingCredential = actionAdmission.guard {
                dataState.groups.abandonPendingGroupBotCredentialRecovery()
            },
            onBack = actionAdmission.guard { navController.popBackStack() },
        )
    }
}

/** 邀请与转发路由：拉人进群、邀请链接与消息转发。 */
private fun NavGraphBuilder.inviteDestination(
    navController: NavHostController,
    dataState: AppDataState,
    actionAdmission: UiActionAdmission,
) {
    suspend fun <T> admittedAction(onClosed: () -> T, action: suspend () -> T): T =
        dataState.runAdmittedUiAction(actionAdmission, onClosed, action)
    composable(
        Routes.INVITE_MEMBERS,
        arguments = listOf(navArgument("chatId") { type = NavType.StringType }),
    ) { entry ->
        val chatId = entry.arguments?.getString("chatId") ?: return@composable
        val contacts by dataState.contactViewModel.contacts.collectAsState()
        InviteMembersScreen(
            friendUids = contacts.map { it.friendUid },
            friendNames = contacts.associate {
                it.friendUid to (it.remark ?: it.user?.name ?: it.friendUid)
            },
            onInvite = { uids ->
                admittedAction(onClosed = { false }) {
                    dataState.groups.inviteMembers(chatId, uids)
                }
            },
            onBack = actionAdmission.guard { navController.popBackStack() },
        )
    }
    composable(
        Routes.INVITE_LINKS,
        arguments = listOf(navArgument("chatId") { type = NavType.StringType }),
    ) { entry ->
        val chatId = entry.arguments?.getString("chatId") ?: return@composable
        LaunchedEffect(chatId) {
            admittedAction(onClosed = {}) {
                dataState.loadScreenDataByKey(ScreenDataKey.InviteLinks(chatId))
            }
        }
        val links = dataState.groups.inviteLinks
            .takeIf { dataState.groups.inviteLinksTargetChatId == chatId }
            .orEmpty()
        InviteLinksScreen(
            links = links.map {
                InviteLink(it.token, it.maxUses, it.useCount, it.revokedAt > 0)
            },
            onCreateLink = {
                admittedAction(onClosed = { null }) {
                    dataState.groups.createInviteLink(chatId)
                }
            },
            onRevokeLink = actionAdmission.guard { token: String ->
                dataState.groups.revokeInviteLink(chatId, token)
            },
            onBack = actionAdmission.guard { navController.popBackStack() },
        )
    }
    composable(
        Routes.FORWARD,
        arguments = listOf(
            navArgument("chatId") { type = NavType.StringType },
            navArgument("serverSeq") { type = NavType.LongType },
        ),
    ) { entry ->
        val chatId = entry.arguments?.getString("chatId") ?: return@composable
        val serverSeq = entry.arguments?.getLong("serverSeq") ?: return@composable
        val conversations by dataState.conversationViewModel.conversations.collectAsState()
        val peerUsers by dataState.conversationViewModel.peerUsers.collectAsState()
        ForwardScreen(
            conversations = conversations,
            peerUsers = peerUsers,
            onForward = { targetChatId ->
                admittedAction(onClosed = { false }) {
                    dataState.discovery.forwardMessage(chatId, serverSeq, targetChatId)
                }
            },
            onBack = actionAdmission.guard { navController.popBackStack() },
        )
    }
}
