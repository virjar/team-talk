package com.virjar.tk

import android.app.Application
import android.os.Bundle
import android.os.Build
import android.util.Log
import com.virjar.tk.android.BuildConfig
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.navigation.navArgument
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import com.virjar.tk.client.*
import com.virjar.tk.model.User
import com.virjar.tk.navigation.AppDataState
import com.virjar.tk.navigation.ScreenDataKey
import com.virjar.tk.navigation.feature.DocumentDraftStore
import com.virjar.tk.ui.AppTheme
import com.virjar.tk.ui.component.LocalScreenHeaderTopSafeAreaEnabled
import com.virjar.tk.ui.screen.*
import com.virjar.tk.ui.theme.initThemeStore
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val appDataStateHolder: AndroidAppDataStateHolder by lazy {
        ViewModelProvider(this)[AndroidAppDataStateHolder::class.java]
    }
    private val beforeSessionRetirement: (ClientSession, SessionEndReason) -> Unit by lazy {
        { session, reason -> appDataStateHolder.beforeSessionRetirement(session, reason) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 全局初始化（日志注入、ServerConfig、异常拦截）已在 TeamTalkApp.onCreate 完成
        // 主题持久化：需在首次组合（TkTheme 读取）前就绪
        initThemeStore(applicationContext)
        setContent {
            AppTheme(touchDensity = true) {
                TestTagEnabler {
                val config = remember { defaultServerConfig() }
                val deploymentIdentity = remember(config) { config.deploymentIdentity() }
                val tokenStore = remember(deploymentIdentity) {
                    TokenStore(applicationContext, deploymentIdentity)
                }
                val deviceId = remember { AndroidDeviceIdentity.getOrCreate(applicationContext) }
                val deviceName = remember { "${Build.MANUFACTURER} ${Build.MODEL}".trim() }
                val uiScope = rememberCoroutineScope()
                val auth = rememberAuthController(
                    tokenStore = tokenStore,
                    deploymentIdentity = deploymentIdentity,
                    tcpHost = config.tcpHost,
                    tcpPort = config.tcpPort,
                    deviceId = deviceId,
                    deviceName = deviceName,
                    deviceModel = Build.MODEL,
                    deviceFlag = 1,
                    createCache = { identity, uid ->
                        createAndroidLocalCache(applicationContext, identity, uid)
                    },
                    onAuthenticated = { session ->
                        // 注册/登录后缓存可能还没写入，回退用 UserSession 内存字段构建
                        val us = session.userSession
                        us.username?.let { username ->
                            session.localCache.upsertUser(User(uid = us.uid, username = username, name = us.name ?: username))
                        }
                    },
                    beforeSessionRetirement = beforeSessionRetirement,
                )
                if (!auth.isLoggedIn) {
                    if (auth.autoLoggingIn) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else {
                        AuthFlow(auth)
                    }
                } else {
                    val uiSession = remember(auth.session) {
                        appDataStateHolder.forSession(auth.session!!) {
                            // Repository/ViewModel errors may arrive on a background dispatcher.
                            // Enter AuthController on the composition's UI scope. Its synchronous
                            // platform hook tears down this exact retained session before closing
                            // LocalCache and publishing the login screen.
                            uiScope.launch {
                                auth.onAuthExpired()
                            }
                        }
                    }
                    AndroidMainApp(
                        dataState = uiSession.dataState,
                        resourceOwner = uiSession.resourceOwner,
                        onLogout = auth.onLogout,
                    )
                }
                if (auth.requiresProtocolUpgrade) {
                    ProtocolUpgradeDialog(onExit = { this@MainActivity.finishAffinity() })
                }
            }
                }
        }
    }

    override fun onStop() {
        // Capture Compose-owned editor state while it is still attached. Persistence itself is a
        // process-owned serial queue: enqueue a barrier and return without a five-second main-thread
        // wait. Android may reclaim the process after onStop, so this cannot depend on disposal.
        appDataStateHolder.captureAndScheduleDocumentDraftFlush()
        super.onStop()
    }
}

/**
 * Retains session-owned composer context and unsaved document bodies across Activity recreation.
 * Ordinary configuration changes are handled in-place by the Activity; this holder also protects
 * large continuations when an explicit recreation occurs. A different signed-in uid always
 * receives fresh stores and therefore can never inherit another account's draft.
 */
internal class AndroidAppDataStateHolder(application: Application) : AndroidViewModel(application) {
    private val documentDraftPersistence =
        (application as TeamTalkApp).documentDraftPersistence
    private var composerContexts = ChatComposerContextStore()
    private var documentDrafts = newDocumentDraftStore()
    private var dataState: AppDataState? = null
    private var authenticatedResources: AndroidAuthenticatedResourceOwner? = null
    private var continuationOwnerUid: String? = null
    private val sessionOwner = AndroidSessionOwnerGate<ClientSession>()

    fun forSession(session: ClientSession, onAuthExpired: () -> Unit): AndroidAuthenticatedUiSession =
        sessionOwner.replaceOwner(session) { previousSession ->
            val currentState = dataState
            val currentResources = authenticatedResources
            if (previousSession === session && currentState != null && currentResources != null) {
                return@replaceOwner AndroidAuthenticatedUiSession(currentState, currentResources)
            }
            closeAuthenticatedResources("session replacement")
            val previous = dataState
            val previousOwnerUid = previous?.userSession?.uid ?: continuationOwnerUid
            val sameUser = previousOwnerUid == session.userSession.uid
            if (sameUser) previous?.documents?.captureDrafts()
            previous?.destroy(
                clearComposerContexts = !sameUser,
                clearDocumentDrafts = !sameUser,
            )
            if (sameUser) documentDraftPersistence.requestFlush()
            // AuthController owns the transport. A retained holder may still reference an already
            // closed session after the same ImClient has started a newer login; only release the old
            // session resources here and never request a transport disconnect from the holder.
            previousSession?.takeIf { it !== session }?.close(
                reason = SessionEndReason.PROCESS_REPLACED,
                disconnectTransport = false,
            )
            if (!sameUser) {
                composerContexts = ChatComposerContextStore()
                documentDrafts = newDocumentDraftStore()
            }
            continuationOwnerUid = null
            AppDataState(
                session = session,
                chatComposerContexts = composerContexts,
                documentDrafts = documentDrafts,
                onAuthExpired = onAuthExpired,
            ).let { state ->
                val resources = AndroidAuthenticatedResourceOwner()
                dataState = state
                authenticatedResources = resources
                AndroidAuthenticatedUiSession(state, resources)
            }
        }

    /** AuthController invokes this synchronously before the matching session can close LocalCache. */
    fun beforeSessionRetirement(session: ClientSession, reason: SessionEndReason) {
        sessionOwner.retireIfOwner(session) {
            val failures = mutableListOf<Pair<String, Throwable>>()
            fun release(owner: String, block: () -> Unit) {
                try {
                    block()
                } catch (failure: Throwable) {
                    failures += owner to failure
                }
            }

            release("authenticated resources") {
                closeAuthenticatedResources("$reason retirement")
            }
            val retiringState = dataState
            dataState = null
            when (reason.androidUiRetirementPolicy()) {
                AndroidUiRetirementPolicy.DISCARD_DRAFTS -> {
                    continuationOwnerUid = null
                    release("AppDataState") {
                        retiringState?.destroy(clearComposerContexts = true, clearDocumentDrafts = true)
                    }
                    release("document draft flush") { documentDraftPersistence.requestFlush() }
                    release("composer store reset") { composerContexts = ChatComposerContextStore() }
                    release("document store reset") { documentDrafts = newDocumentDraftStore() }
                }

                AndroidUiRetirementPolicy.PRESERVE_DURABLE_DRAFTS -> {
                    continuationOwnerUid = null
                    release("document draft capture") { retiringState?.documents?.captureDrafts() }
                    release("AppDataState") {
                        retiringState?.destroy(clearComposerContexts = true, clearDocumentDrafts = false)
                    }
                    release("document draft flush") { documentDraftPersistence.requestFlush() }
                    release("composer store reset") { composerContexts = ChatComposerContextStore() }
                    release("document store reset") { documentDrafts = newDocumentDraftStore() }
                }

                AndroidUiRetirementPolicy.PRESERVE_SAME_USER_CONTINUATION -> {
                    continuationOwnerUid = session.userSession.uid
                    release("document draft capture") { retiringState?.documents?.captureDrafts() }
                    release("AppDataState") {
                        retiringState?.destroy(clearComposerContexts = false, clearDocumentDrafts = false)
                    }
                    release("document draft flush") { documentDraftPersistence.requestFlush() }
                }
            }
            failures.forEachIndexed { index, (owner, failure) ->
                Log.e(
                    "AndroidAuth",
                    "$reason retirement cleanup failed for $owner (failure ${index + 1})",
                    failure,
                )
            }
        }
    }

    fun captureAndScheduleDocumentDraftFlush() {
        sessionOwner.withOwner {
            captureThenScheduleDocumentDraftFlush(
                captureDrafts = { dataState?.documents?.captureDrafts() ?: true },
                scheduleFlush = { documentDraftPersistence.requestFlush() },
            )
        }
    }

    override fun onCleared() {
        sessionOwner.retireCurrent { retainedSession ->
            closeAuthenticatedResources("ViewModel clearing")
            // Task removal is not an explicit account logout. Retain the uid-scoped AtomicFile so
            // a fresh process can resume the unsaved document.
            val retiringState = dataState
            dataState = null
            retiringState?.documents?.captureDrafts()
            retiringState?.destroy(clearComposerContexts = true, clearDocumentDrafts = false)
            documentDraftPersistence.requestFlush()
            retainedSession?.close(reason = SessionEndReason.SHUTDOWN, disconnectTransport = false)
            continuationOwnerUid = null
        }
    }

    private fun newDocumentDraftStore() = DocumentDraftStore(
        documentDraftPersistence,
    )

    private fun closeAuthenticatedResources(boundary: String) {
        val closing = authenticatedResources
        authenticatedResources = null
        closing?.closeAll().orEmpty().forEachIndexed { index, failure ->
            Log.e(
                "AndroidAuth",
                "Authenticated resource cleanup failed at $boundary (failure ${index + 1})",
                failure,
            )
        }
    }
}

internal data class AndroidAuthenticatedUiSession(
    val dataState: AppDataState,
    val resourceOwner: AndroidAuthenticatedResourceOwner,
)

@Composable
private fun AuthFlow(auth: AuthState) {
    var destination by remember { mutableStateOf(AuthDestination.LOGIN) }
    val backDestination = destination.backDestination()
    BackHandler(enabled = backDestination != null) {
        backDestination?.let { destination = it }
        auth.clearError()
    }
    if (destination == AuthDestination.REGISTER) {
        RegisterScreen(
            onRegister = auth.onRegister,
            onNavigateBack = { destination = AuthDestination.LOGIN; auth.clearError() }, error = auth.authError,
        )
    } else {
        LoginScreen(
            onLogin = auth.onLogin,
            onNavigateToRegister = { destination = AuthDestination.REGISTER; auth.clearError() }, error = auth.authError,
        )
    }
}

internal enum class AuthDestination {
    LOGIN,
    REGISTER,
    ;

    fun backDestination(): AuthDestination? = when (this) {
        LOGIN -> null
        REGISTER -> LOGIN
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AndroidMainApp(
    dataState: AppDataState,
    resourceOwner: AndroidAuthenticatedResourceOwner,
    onLogout: () -> Unit,
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(dataState) {
        snapshotFlow { dataState.error }
            .filterNotNull()
            .collect { message ->
                // Clear before suspending so an identical follow-up error is still observable.
                // Keeping this collection keyed to dataState avoids cancelling showSnackbar when
                // clearError itself triggers recomposition.
                dataState.clearError()
                snackbarHostState.showSnackbar(message)
            }
    }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalScreenHeaderTopSafeAreaEnabled provides true) {
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
            val chatName = decodeChatRouteName(entry.arguments?.getString("name").orEmpty())
            val chatType = entry.arguments?.getInt("type") ?: 1
            // A restored/deep-linked CHAT destination must prepare itself; the navigation click
            // that first opened it is not part of Android saved-state restoration.
            LaunchedEffect(chatId, chatName, chatType) {
                dataState.ensureChat(chatId, chatName, chatType)
            }
            // During a back-stack transition never render route A with route B's ViewModel.
            val vm = dataState.chatViewModelFor(chatId)
            val conversations by dataState.conversationViewModel.conversations.collectAsState()
            // Full drafts live in ConversationRepository/ChatComposerContextStore. Keeping this
            // mirror out of SavedState avoids a second 100k String in the Activity Bundle.
            var currentDraft by remember(chatId) { mutableStateOf<String?>(null) }
            var draftInitialized by remember(chatId) { mutableStateOf(false) }
            // 会话列表可能晚于深链聊天页加载；只在用户未编辑时接受首次服务端草稿。
            LaunchedEffect(chatId, conversations) {
                val conversation = conversations.find { it.chatId == chatId }
                if (!draftInitialized && conversation != null) {
                    currentDraft = conversation.draft
                    draftInitialized = true
                }
            }
            // @ 补全候选：群聊拉成员，私聊用好友
            // Key the state to this route. A slow result from chat A must never become the sender
            // directory while chat B is on screen.
            var mentionCandidates by remember(chatId) {
                mutableStateOf<List<com.virjar.tk.model.User>>(emptyList())
            }
            LaunchedEffect(chatId, chatType) {
                mentionCandidates = try {
                    if (chatType == com.virjar.tk.model.ChatType.GROUP.code) {
                        dataState.groups.mentionCandidates(chatId)
                    } else {
                        dataState.contactViewModel.contacts.value.mapNotNull { it.user }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
            }
            if (vm != null) { AndroidChatScreen(chatId, chatName, chatType, vm, dataState.userSession.uid,
                dataState::httpCredentialsSnapshot,
                deploymentIdentity = dataState.deploymentIdentity,
                resourceOwner = resourceOwner,
                resolveSender = { uid ->
                    mentionCandidates.firstOrNull { it.uid == uid } ?: dataState.cachedUser(uid)
                },
                mentionCandidates = mentionCandidates,
                onMentionClick = { uid ->
                    safeMentionProfileRouteOrNull(uid)?.let { route -> navController.navigate(route) }
                },
                draft = currentDraft,
                composerContextStore = dataState.chatComposerContexts,
                // ChatPanel 已防抖并在离开时同步 flush；单一出口避免父子
                // DisposableEffect 销毁顺序不确定时，旧草稿在最后一帧反向覆盖新草稿。
                onDraftChange = {
                    draftInitialized = true
                    currentDraft = it
                    dataState.saveDraft(chatId, it)
                },
                onForward = { msg -> navController.navigate(Routes.forward(msg.chatId, msg.serverSeq)) },
                onTextAttachmentPreview = { attachment ->
                    navController.navigate(Routes.textAttachmentPreview(attachment))
                },
                onGroupDetail = { navController.navigate(Routes.groupDetail(chatId)) },
                onBack = { navController.popBackStack() },
            )}
        }
        composable(
            Routes.TEXT_ATTACHMENT_PREVIEW,
            arguments = listOf(
                navArgument("path") { type = NavType.StringType },
                navArgument("name") { type = NavType.StringType },
                navArgument("contentType") { type = NavType.StringType },
                navArgument("size") { type = NavType.LongType },
            ),
        ) { entry ->
            val attachment = remember(entry) {
                runCatching {
                    com.virjar.tk.model.Attachment(
                        path = decodeAttachmentRouteValue(entry.arguments?.getString("path").orEmpty()),
                        name = decodeAttachmentRouteValue(entry.arguments?.getString("name").orEmpty()),
                        contentType = decodeAttachmentRouteValue(
                            entry.arguments?.getString("contentType").orEmpty(),
                        ),
                        size = entry.arguments?.getLong("size") ?: 0L,
                    )
                }.getOrNull()
            }
            val previewKind = attachment?.let {
                com.virjar.tk.ui.component.textAttachmentPreviewKind(it)
            }
            if (attachment == null || previewKind == null) {
                LaunchedEffect(entry) { navController.popBackStack() }
            } else {
                val sessionUser = dataState.userSession
                val ownerUid = sessionUser.uid
                val mediaResourcesLease = remember(ownerUid, dataState, resourceOwner) {
                    resourceOwner.acquire {
                        AndroidAuthenticatedMediaResources.create(
                            createMediaSession = {
                                AndroidMediaSession.create(
                                    deploymentIdentity = dataState.deploymentIdentity,
                                    ownerUid = ownerUid,
                                    credentialsProvider = dataState::httpCredentialsSnapshot,
                                )
                            },
                        )
                    }
                }
                DisposableEffect(mediaResourcesLease) {
                    onDispose {
                        runCatching(mediaResourcesLease::close).onFailure { failure ->
                            Log.e("TextAttachment", "Failed to dispose authenticated media", failure)
                        }
                    }
                }
                mediaResourcesLease.resourceOrNull()?.let { resources ->
                    AndroidTextAttachmentPreviewScreen(
                        attachment = attachment,
                        mediaSession = resources.mediaSession,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
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
            FriendAppliesScreen(
                records = dataState.account.friendApplyRecords,
                loading = dataState.account.friendApplyRecordsLoading,
                hasMore = dataState.account.friendApplyRecordsHasMore,
                onLoadMore = dataState.account::loadMoreFriendApplies,
                onAccept = { t -> dataState.account.acceptFriendApply(t) },
                onReject = { t -> dataState.account.rejectFriendApply(t) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.USER_PROFILE, arguments = listOf(navArgument("uid"){type=NavType.StringType})) { entry ->
            val uid = entry.arguments?.getString("uid") ?: return@composable
            LaunchedEffect(uid) { dataState.loadScreenDataByKey(ScreenDataKey.UserProfile(uid)) }
            UserProfileScreen(
                user = dataState.account.profileUser?.takeIf { it.uid == uid },
                myUid = dataState.userSession.uid,
                isFriend = dataState.account.isFriend,
                hasPendingApply = dataState.account.hasOutgoingFriendApply(uid),
                hasIncomingApply = dataState.account.hasIncomingFriendApply(uid),
                isApplyingFriend = dataState.account.isApplyingFriend(uid),
                onAddFriend = {
                    dataState.account.applyFriend(uid)
                },
                onViewFriendApplies = { navController.navigate(Routes.FRIEND_APPLIES) },
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
                onBlockUser = if (uid != dataState.userSession.uid) {
                    { dataState.account.blockContact(uid) { navController.popBackStack() } }
                } else null,
                onDeleteFriend = { dataState.contactViewModel.deleteFriend(uid); navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.EDIT_PROFILE) { EditProfileScreen(currentUser = dataState.account.currentUser, onSave = { n, p -> dataState.account.saveProfile(n, p) }, onBack = { navController.popBackStack() }) }
        composable(Routes.CHANGE_PASSWORD) { ChangePasswordScreen(onChangePassword = { o, n -> dataState.account.changePassword(o, n) }, onBack = { navController.popBackStack() }) }
        composable(Routes.DEVICES) {
            LaunchedEffect(Unit) { dataState.loadScreenDataByKey(ScreenDataKey.Devices) }
            DeviceManagementScreen(devices = dataState.account.devices.map { DeviceInfo(it.deviceId, it.deviceName ?: "", it.deviceModel ?: "", it.lastLogin) },
                currentDeviceId = dataState.account.currentDeviceId,
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
            val detailReady = dataState.groups.detailTargetChatId == chatId
            val detailChat = dataState.groups.detailChat?.takeIf { detailReady && it.chatId == chatId }
            val detailMembers = dataState.groups.members.takeIf { detailReady }.orEmpty()
            val currentRole = detailMembers.firstOrNull { it.uid == dataState.userSession.uid }?.role ?: -1
            val currentUserIsOwner = currentRole == 2
            GroupDetailScreen(chat = detailChat, members = detailMembers, isOwner = currentUserIsOwner,
                myUid = dataState.userSession.uid,
                onMemberClick = { uid -> navController.navigate(Routes.userProfile(uid)) }, onInviteMembers = { navController.navigate(Routes.inviteMembers(chatId)) }, onViewInviteLinks = { navController.navigate(Routes.inviteLinks(chatId)) },
                onGroupFiles = { navController.navigate(Routes.groupFiles(chatId)) },
                onGroupBots = { navController.navigate(Routes.groupBots(chatId)) },
                onLeaveGroup = {
                    dataState.groups.exit(chatId, dissolve = currentUserIsOwner) {
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
        composable(Routes.GROUP_BOTS, arguments = listOf(navArgument("chatId"){type=NavType.StringType})) { entry ->
            val chatId = entry.arguments?.getString("chatId") ?: return@composable
            LaunchedEffect(chatId) { dataState.loadScreenDataByKey(ScreenDataKey.GroupBots(chatId)) }
            val ready = dataState.groups.groupBotsTargetChatId == chatId
            GroupBotsScreen(
                chatId = chatId,
                serverUrl = defaultServerConfig().serverUrl,
                bots = dataState.groups.groupBots.takeIf { ready }.orEmpty(),
                loading = !ready || dataState.groups.groupBotsLoading,
                error = dataState.groups.groupBotsError.takeIf { ready },
                canCreate = ready && dataState.groups.groupBotsError == null,
                creating = dataState.groups.creatingGroupBot,
                operationBotId = dataState.groups.groupBotOperationId,
                credentials = dataState.groups.groupBotCredentialsFor(chatId).takeIf { ready },
                onRefresh = { scope.launch { dataState.loadScreenDataByKey(ScreenDataKey.GroupBots(chatId)) } },
                onCreate = { name -> dataState.groups.createGroupBot(chatId, name) },
                onRotate = { botId -> dataState.groups.rotateGroupBotToken(chatId, botId) },
                onRemove = { botId -> dataState.groups.removeGroupBot(chatId, botId) },
                onDismissCredentials = { dataState.groups.dismissGroupBotCredentials(chatId) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.GROUP_FILES, arguments = listOf(navArgument("chatId"){type=NavType.StringType})) { entry ->
            val chatId = entry.arguments?.getString("chatId") ?: return@composable
            val context = LocalContext.current
            val routeScope = rememberCoroutineScope()
            val sessionUser = dataState.userSession
            val sessionUid = sessionUser.uid
            val mediaResourcesLease = remember(
                dataState.deploymentIdentity,
                sessionUid,
                dataState,
                resourceOwner,
                context,
            ) {
                resourceOwner.acquire {
                    AndroidAuthenticatedMediaResources.create(
                        createMediaSession = {
                            AndroidMediaSession.create(
                                deploymentIdentity = dataState.deploymentIdentity,
                                ownerUid = sessionUid,
                                credentialsProvider = dataState::httpCredentialsSnapshot,
                            )
                        },
                        createFileDownloads = { mediaSession ->
                            AndroidFileDownloadController(context, mediaSession)
                        },
                    )
                }
            }
            DisposableEffect(mediaResourcesLease) {
                onDispose {
                    runCatching(mediaResourcesLease::close).onFailure { failure ->
                        Log.e("GroupFiles", "Failed to dispose authenticated media", failure)
                    }
                }
            }
            val mediaResources = mediaResourcesLease.resourceOrNull() ?: return@composable
            val mediaSession = mediaResources.mediaSession
            val downloads = requireNotNull(mediaResources.fileDownloads)
            var uploading by remember { mutableStateOf(false) }
            var versionTarget by remember { mutableStateOf<com.virjar.tk.model.GroupFileEntry?>(null) }
            LaunchedEffect(chatId) { dataState.loadScreenDataByKey(ScreenDataKey.GroupFiles(chatId)) }

            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) routeScope.launch {
                    uploading = true
                    var selected: PreparedMedia? = null
                    try {
                        selected = MediaHelper.prepareSelectedMedia(context, uri, mediaSession)
                        val name = selected.fileName
                        val attachment = MediaHelper.uploadFile(
                            selected.file,
                            name,
                            selected.contentType,
                            mediaSession,
                        )
                        // 文件选择器返回时路由可能已切到另一群；此时取消发布，绝不能借用 B 的当前目录。
                        if (dataState.groupFiles.chatId != chatId) return@launch
                        val target = versionTarget
                        if (target == null) dataState.groupFiles.publish(name, attachment)
                        else dataState.groupFiles.addVersion(target, attachment)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        Log.e("GroupFiles", "upload failed", e)
                        dataState.groupFiles.reportUploadError(e)
                    } finally {
                        selected?.delete()
                        versionTarget = null
                        uploading = false
                    }
                } else {
                    versionTarget = null
                }
            }

            val filesReady = dataState.groupFiles.chatId == chatId
            GroupFilesScreen(
                entries = dataState.groupFiles.entries.takeIf { filesReady }.orEmpty(),
                path = dataState.groupFiles.path.takeIf { filesReady }.orEmpty(),
                selectedFile = dataState.groupFiles.selectedFile.takeIf { filesReady },
                versions = dataState.groupFiles.versions.takeIf { filesReady }.orEmpty(),
                loading = !filesReady || dataState.groupFiles.loading,
                uploading = uploading,
                onRefresh = { routeScope.launch { dataState.groupFiles.refresh() } },
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
            val links = dataState.groups.inviteLinks
                .takeIf { dataState.groups.inviteLinksTargetChatId == chatId }
                .orEmpty()
            InviteLinksScreen(links = links.map { InviteLink(it.token, it.maxUses, it.useCount, it.revokedAt > 0) },
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
