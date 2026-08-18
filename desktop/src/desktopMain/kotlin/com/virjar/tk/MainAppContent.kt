package com.virjar.tk

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester

import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowScope
import com.virjar.tk.client.ClientSession
import com.virjar.tk.client.ConnectionState
import com.virjar.tk.model.ChatType
import com.virjar.tk.model.Message
import com.virjar.tk.model.User
import com.virjar.tk.navigation.MainTab
import com.virjar.tk.ui.component.AvatarPlaceholder
import com.virjar.tk.ui.component.GalleryItem
import com.virjar.tk.ui.component.PlatformMediaActions
import com.virjar.tk.ui.component.rememberMediaClickHandler
import com.virjar.tk.ui.screen.ChatPanel
import com.virjar.tk.ui.screen.DirectoryScreen
import com.virjar.tk.ui.screen.ConversationListScreen
import com.virjar.tk.ui.screen.GlobalSearchField
import com.virjar.tk.ui.screen.MeHeaderStyle
import com.virjar.tk.ui.screen.MeScreen
import com.virjar.tk.ui.theme.Tk
import com.virjar.tk.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private val ChatInspectorWidth = 400.dp

/**
 * 主内容区（三栏布局：导航栏 + 列表栏 + 内容栏）。
 *
 * 三栏常驻，子页面按 §2.1 分流：群详情/成员/邀请渲染为聊天右侧覆盖式检查器，
 * 用户资料显示为紧凑模态弹窗，其余流程弹独立任务窗口（§2.6）。
 * 区别于 Android 的全屏页面导航。交互规格：doc/05-clients/desktop.md。
 */
@Composable
internal fun WindowScope.MainAppContent(
    session: ClientSession,
    mainWindow: java.awt.Window,
    connectionState: ConnectionState,
    onLogout: () -> Unit,
) {
    val nav = rememberDesktopNav(session)
    val conversations by nav.conversationViewModel.conversations.collectAsState()
    val contacts by nav.contactViewModel.contacts.collectAsState()
    val pendingApplyCount by nav.contactViewModel.pendingApplyCount.collectAsState()
    val directoryScope = rememberCoroutineScope()

    LaunchedEffect(nav.selectedTab) {
        if (MainTab.entries[nav.selectedTab] == MainTab.CONTACTS) {
            nav.contactViewModel.refreshPendingApplyCount()
            nav.organization.refresh()
        }
    }

    DisposableEffect(Unit) {
        onDispose { nav.destroy() }
    }

    // ESC 优先关闭资料弹窗，再关闭检查器：AWT KeyEventDispatcher 层拦截（Compose onPreviewKeyEvent 依赖
    // 焦点节点存在，无焦点时（点完非 focusable 的列表行）按键不派发——旧版
    // 「ESC 不可靠」的根因）。按窗口归属分流，弹层/对话框是独立 Window 不受影响。
    DisposableEffect(mainWindow) {
        val unregisterEscape = registerEscapeInterceptor(mainWindow) {
            when {
                nav.profileUid != null -> { nav.closeProfile(); true }
                nav.inspectorStack.isNotEmpty() -> { nav.popInspector(); true }
                nav.mainPaneScreen != null -> { nav.closeMainPane(); true }
                else -> false
            }
        }
        val unregisterSearch = registerGlobalSearchShortcut(mainWindow) {
            nav.openGlobalSearch(requestFocus = true)
        }
        onDispose {
            unregisterEscape()
            unregisterSearch()
        }
    }

    // uid → User 解析链：本地缓存 → currentUser → userSession 终极兜底。
    // 自动登录路径 localCache/currentUser 可能为空，没有兜底时自己消息头像退化为 uid 首字母。
    val userSession = nav.userSession
    val resolveUser: (String) -> User? = { uid ->
        nav.localCache.getUser(uid)
            ?: nav.account.currentUser?.takeIf { it.uid == uid }
            ?: if (uid == userSession.uid) {
                User(
                    uid = uid,
                    username = userSession.username ?: uid,
                    name = userSession.name?.ifBlank { null } ?: userSession.username ?: uid,
                )
            } else null
    }

    // ── 独立子窗口（§2.6，与主内容页/聊天检查器互斥）──
    // key(windowScreen)：入口切换时强制重建 SubWindow，清空窗口内局部导航栈
    nav.windowScreen?.let { windowScreen ->
        key(windowScreen) {
            SubWindow(screen = windowScreen, nav = nav, onClose = { nav.windowScreen = null })
        }
    }

    // 语音应用内播放（native 引擎，聊天面板级共享：切会话即静音）
    val voicePlayback = rememberDesktopVoicePlayback()

    // @ 补全候选：群聊拉成员列表；私聊用好友列表（chatId 不含对方 uid，好友即候选）
    var mentionCandidates by remember { mutableStateOf<List<User>>(emptyList()) }
    LaunchedEffect(nav.chatId, nav.chatType, contacts.size) {
        val chatId = nav.chatId
        mentionCandidates = if (chatId == null) {
            emptyList()
        } else if (nav.chatType == ChatType.GROUP.code) {
            try {
                nav.chatRepo.getMembers(chatId).getOrNull()?.mapNotNull { it.user } ?: emptyList()
            } catch (_: Exception) { emptyList() }
        } else {
            contacts.mapNotNull { it.user }
        }
    }

    // ── 应用壳层 + 三栏常驻布局 ──
    Box(modifier = Modifier.fillMaxSize().testTag("main.home")) {
        Column(modifier = Modifier.fillMaxSize()) {
            DesktopTitleBar(
                query = nav.globalSearchQuery,
                onQueryChange = { query ->
                    nav.globalSearchQuery = query
                    nav.openGlobalSearch()
                },
                onSearchFocus = { nav.openGlobalSearch() },
                focusNonce = nav.searchFocusNonce,
                connectionState = connectionState,
            )
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // ── 左栏：细导航栏（56dp 图标式，规格 §1.5）──
            SlimNavRail(
                selectedTab = nav.selectedTab,
                onSelectTab = { index ->
                    nav.selectedTab = index
                    nav.closeInspector()
                    nav.closeMainPane()
                    if (MainTab.entries[index] != MainTab.CONVERSATIONS) nav.chatId = null
                },
                pendingApplyCount = pendingApplyCount,
                currentUserName = resolveUser(userSession.uid)?.name,
            )

            // ── 中栏：列表区（会话/通讯录/设置，300dp）──
            // 三级层次：rail(surfaceVariant 深灰) → 列表(background 浅灰) → 内容(白)
            Surface(
                modifier = Modifier.width(Tk.dimens.listPaneWidth).fillMaxHeight(),
                color = MaterialTheme.colorScheme.background,
            ) {
                when (MainTab.entries[nav.selectedTab]) {
                    MainTab.CONVERSATIONS -> {
                        Column {
                            ListHeader(title = "会话")
                            ConversationListScreen(
                                conversations = conversations,
                                selectedChatId = nav.chatId,
                                onConversationClick = { chatId ->
                                    val conv = conversations.find { it.chatId == chatId }
                                    nav.openChat(chatId, conv?.chatName ?: chatId.take(16), conv?.chatType ?: 1)
                                },
                                onPinClick = { chatId, pinned -> nav.session.localCache.toggleConversationPin(chatId, pinned) },
                                onMarkRead = { chatId, lastSeq ->
                                    nav.session.localCache.markConversationRead(chatId, lastSeq)
                                },
                            )
                        }
                    }

                    MainTab.CONTACTS -> {
                        Column {
                            ListHeader(title = "通讯录")
                            // 桌面使用搜索 + 鼠标滚动；移动端字母索引条不占用中栏右侧空间。
                            DirectoryScreen(
                                contacts = contacts,
                                units = nav.organization.units,
                                members = nav.organization.members,
                                selectedUnitId = nav.organization.selectedUnitId,
                                organizationLoading = nav.organization.loading,
                                onUnitClick = { unitId -> directoryScope.launch { nav.organization.selectUnit(unitId) } },
                                onUserClick = nav::openProfile,
                                modifier = Modifier.weight(1f),
                                pendingApplyCount = pendingApplyCount,
                                onFriendApplies = { nav.openScreen(SubScreen.FriendApplies) },
                                showAlphabetIndex = false,
                            )
                        }
                    }

                    MainTab.SETTINGS -> {
                        MeScreen(
                            currentUser = nav.account.currentUser,
                            onLogout = onLogout,
                            onEditProfile = { nav.openScreen(SubScreen.EditProfile) },
                            onChangePassword = { nav.openScreen(SubScreen.ChangePassword) },
                            onDeviceManagement = { nav.openScreen(SubScreen.Devices) },
                            onBlacklist = { nav.openScreen(SubScreen.Blacklist) },
                            buildInfoText = "Git: ${BuildConfig.GIT_COMMIT_ID.take(8)}  |  Build: ${BuildConfig.BUILD_TIME}",
                            headerStyle = MeHeaderStyle.Compact,
                        )
                    }
                }
            }

            // ── 右栏：主内容 + 聊天检查器 ──
            // 全局搜索替换主内容；群设置覆盖在聊天右侧，不再把聊天页替换掉。
            Surface(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                val mainPaneScreen = nav.mainPaneScreen
                if (mainPaneScreen != null) {
                    SubScreenContent(
                        screen = mainPaneScreen,
                        data = nav,
                        navigate = nav::openScreen,
                        back = nav::closeMainPane,
                        openChatAndClose = { chatId, name, chatType -> nav.openChat(chatId, name, chatType) },
                        openUserProfile = nav::openProfile,
                        onLeaveGroup = {},
                        showBack = false,
                        globalSearchQuery = nav.globalSearchQuery,
                        onGlobalSearchQueryChange = { nav.globalSearchQuery = it },
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        when {
                            nav.chatId != null && nav.chatViewModel != null -> {
                                // 从会话列表读取当前会话的草稿作为初始值
                                val conv = conversations.find { it.chatId == nav.chatId }
                                ChatPanelWrapper(
                                    chatId = nav.chatId!!,
                                    chatName = nav.chatName,
                                    chatType = nav.chatType,
                                    viewModel = nav.chatViewModel!!,
                                    myUid = nav.userSession.uid,
                                    accessToken = nav.userSession.accessToken,
                                    conversationRepo = nav.conversationRepo,
                                    initialDraft = conv?.draft,
                                    resolveSender = resolveUser,
                                    voicePlayback = voicePlayback,
                                    onMentionClick = nav::openProfile,
                                    mentionCandidates = mentionCandidates,
                                    onForward = { msg -> nav.openScreen(SubScreen.Forward(msg)) },
                                    onGroupSettings = { nav.openScreen(SubScreen.GroupDetail(nav.chatId!!)) },
                                )
                            }
                            // 空态（规格 §2.1：Logo + 主提示 + 次提示）
                            else -> MainPaneEmptyState(MainTab.entries[nav.selectedTab])
                        }

                        ChatInspectorHost(nav)
                    }
                }
            }
        }
        }

        nav.profileUid?.let { uid ->
            key(uid) {
                DesktopUserProfileDialog(uid = uid, nav = nav, onDismiss = nav::closeProfile)
            }
        }

        // action 失败提示（此前面板/中栏 action 的错误完全静默）
        ErrorSnackbar(nav)
    }
}

/**
 * 与聊天上下文绑定的非模态检查器。抽屉覆盖聊天右侧，不参与 Row 测量，因此打开时
 * 消息区和输入框不会重新布局；关闭后仍停留在原会话和滚动位置。抽屉外部由透明点击层
 * 阻断：第一次点击只关闭抽屉，不会穿透触发聊天操作。
 */
@Composable
private fun BoxScope.ChatInspectorHost(nav: DesktopNav) {
    val requestedScreen = nav.inspectorStack.lastOrNull()
    var renderedScreen by remember { mutableStateOf<SubScreen?>(null) }
    val visibility = remember { MutableTransitionState(false) }

    LaunchedEffect(requestedScreen) {
        if (requestedScreen != null) renderedScreen = requestedScreen
        visibility.targetState = requestedScreen != null
    }

    if (requestedScreen != null) {
        val outsideInteraction = remember { MutableInteractionSource() }
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .testTag("chat.inspector.dismissArea")
                    .clickable(
                        interactionSource = outsideInteraction,
                        indication = null,
                        onClick = nav::closeInspector,
                    ),
            )
            Spacer(modifier = Modifier.width(ChatInspectorWidth))
        }
    }

    AnimatedVisibility(
        visibleState = visibility,
        modifier = Modifier.align(Alignment.CenterEnd),
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
    ) {
        val screen = renderedScreen ?: return@AnimatedVisibility
        Surface(
            modifier = Modifier
                .width(ChatInspectorWidth)
                .fillMaxHeight()
                .testTag("chat.inspector"),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 12.dp,
        ) {
            Row {
                VerticalDivider(color = Tk.colors.divider)
                SubScreenContent(
                    screen = screen,
                    data = nav,
                    navigate = nav::pushInspector,
                    back = nav::popInspector,
                    openChatAndClose = { chatId, name, chatType -> nav.openChat(chatId, name, chatType) },
                    openUserProfile = nav::openProfile,
                    onLeaveGroup = { chatId ->
                        val isOwner = nav.groups.members.any {
                            it.uid == nav.userSession.uid && it.role == 2
                        }
                        nav.groups.exit(chatId, dissolve = isOwner) {
                            nav.closeInspector()
                            if (nav.chatId == chatId) nav.chatId = null
                        }
                    },
                    showBack = nav.inspectorStack.size > 1,
                    onClose = nav::closeInspector,
                )
            }
        }
    }
}

/**
 * Desktop 应用级顶栏。macOS 原生窗口按钮浮在左侧，应用内容延伸到标题区；
 * 搜索位于全局壳层，而不是任何业务栏目的局部标题中。
 */
@Composable
private fun WindowScope.DesktopTitleBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchFocus: () -> Unit,
    focusNonce: Int,
    connectionState: ConnectionState,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusNonce) {
        if (focusNonce > 0) focusRequester.requestFocus()
    }

    Surface(
        modifier = Modifier.fillMaxWidth().height(Tk.dimens.appBarHeight).testTag("app.titleBar"),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 0.dp,
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    WindowDraggableArea(modifier = Modifier.fillMaxSize())
                    Row(
                        modifier = Modifier.fillMaxHeight().padding(start = 76.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("TeamTalk", style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    }
                }

                GlobalSearchField(
                    query = query,
                    onQueryChange = onQueryChange,
                    onFocused = onSearchFocus,
                    focusRequester = focusRequester,
                    shortcutLabel = "⌘ K",
                    height = Tk.dimens.globalSearchHeight,
                    modifier = Modifier
                        .widthIn(min = 320.dp, max = 460.dp)
                        .weight(1.35f)
                        .testTag("action.search"),
                )

                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    WindowDraggableArea(modifier = Modifier.fillMaxSize())
                    val statusLabel = when (connectionState) {
                        ConnectionState.AUTHENTICATED -> "在线"
                        ConnectionState.CONNECTING -> "连接中"
                        ConnectionState.CONNECTED -> "验证中"
                        ConnectionState.AUTH_FAILED -> "认证失效"
                        ConnectionState.DISCONNECTED -> "离线"
                    }
                    val statusColor = when (connectionState) {
                        ConnectionState.AUTHENTICATED -> Tk.colors.online
                        ConnectionState.CONNECTING, ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
                        else -> Tk.colors.metaText
                    }
                    Row(
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = Tk.spacing.lg),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(RoundedCornerShape(50))
                                .background(statusColor),
                        )
                        Spacer(Modifier.width(Tk.spacing.xs))
                        Text(statusLabel, style = MaterialTheme.typography.labelSmall, color = Tk.colors.secondaryText)
                    }
                }
            }
            HorizontalDivider(color = Tk.colors.divider)
        }
    }
}

/** 不同主 tab 使用各自语义空态，避免设置页仍提示“选择会话”。 */
@Composable
private fun MainPaneEmptyState(tab: MainTab) {
    val (icon, title, detail) = when (tab) {
        MainTab.CONVERSATIONS -> Triple(Icons.AutoMirrored.Filled.Chat, "选择一个会话", "从会话列表继续沟通，或使用顶部搜索查找内容")
        MainTab.CONTACTS -> Triple(Icons.Filled.Contacts, "选择一个联系人", "查看资料、发送消息或从资料页发起群聊")
        MainTab.SETTINGS -> Triple(Icons.Filled.Settings, "账号与设置", "在左侧管理个人资料、安全、设备和外观")
    }
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            modifier = Modifier.size(64.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = Tk.colors.secondaryText, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.height(Tk.spacing.lg))
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(Tk.spacing.xs))
        Text(detail, style = MaterialTheme.typography.bodySmall, color = Tk.colors.metaText)
    }
}

/** 全局错误 Snackbar：消费 [AppDataState.error] 并显示（3s 自动消失）。 */
@Composable
private fun BoxScope.ErrorSnackbar(data: com.virjar.tk.navigation.AppDataState) {
    val snackbarHostState = remember { SnackbarHostState() }
    val error = data.error
    LaunchedEffect(error) {
        val msg = error ?: return@LaunchedEffect
        data.clearError()
        snackbarHostState.showSnackbar(msg)
    }
    SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
}

@Composable
private fun ChatPanelWrapper(
    chatId: String,
    chatName: String,
    chatType: Int,
    viewModel: ChatViewModel,
    myUid: String,
    accessToken: String?,
    conversationRepo: com.virjar.tk.repository.ConversationRepository,
    initialDraft: String?,
    onForward: (Message) -> Unit,
    onGroupSettings: () -> Unit,
    resolveSender: ((uid: String) -> User?)? = null,
    voicePlayback: com.virjar.tk.ui.component.VoicePlaybackController? = null,
    onMentionClick: ((uid: String) -> Unit)? = null,
    mentionCandidates: List<User> = emptyList(),
) {
    val messagesState = viewModel.messages.collectAsState()

    // 文件附件下载控制器（media/ 目录缓存；下载完成调系统打开）
    val fileDownloads = remember {
        DesktopFileDownloadController(
            serverUrl = com.virjar.tk.client.defaultServerConfig().serverUrl,
            accessToken = accessToken,
            cacheDir = java.io.File(System.getProperty("teamtalk.data.dir"), "media"),
            onDownloaded = { f -> runCatching { java.awt.Desktop.getDesktop().open(f) } },
        )
    }
    DisposableEffect(fileDownloads) {
        onDispose { fileDownloads.close() }
    }

    // 媒体画廊窗口状态
    var showGallery by remember { mutableStateOf(false) }
    var galleryItems by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var galleryIndex by remember { mutableIntStateOf(0) }

    val onMediaClick = rememberMediaClickHandler(
        messages = messagesState,
        actions = object : PlatformMediaActions {
            // 语音已走 voicePlayback 应用内播放（ChatPanel.voicePlayback），此链路不再触达
            override fun playVoice(attachment: com.virjar.tk.model.Attachment) {}
            override fun openFile(attachment: com.virjar.tk.model.Attachment) {
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { DesktopMediaHelper.openFile(attachment.path) }
            }
            override fun showGallery(items: List<GalleryItem>, index: Int) {
                galleryIndex = index; galleryItems = items; showGallery = true
            }
        },
    )

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .dragAndDropTarget(
                shouldStartDragAndDrop = { true },
                target = remember(chatId, myUid) {
                    object : DragAndDropTarget {
                        override fun onDrop(event: DragAndDropEvent): Boolean {
                            val data = event.dragData()
                            if (data is DragData.FilesList) {
                                data.readFiles().forEach { uri ->
                                    // uri 格式: file:///path/to/file
                                    val path = java.net.URI(uri).path
                                    val file = java.io.File(path)
                                    if (file.exists()) {
                                        DesktopMediaHelper.sendDroppedFile(chatId, myUid, file, viewModel)
                                    }
                                }
                                return true
                            }
                            return false
                        }
                    }
                },
            ),
    ) {
        // 群名称保持纯标题；设置使用明确的齿轮入口，打开聊天右侧检查器。
        val isGroup = ChatType.fromCode(chatType) == ChatType.GROUP
        ListHeader(
            title = chatName.ifEmpty { chatId.take(16) },
            actions = {
                if (isGroup) {
                    IconButton(
                        onClick = onGroupSettings,
                        modifier = Modifier.size(40.dp).testTag("chat.settings"),
                    ) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = "群设置",
                            tint = Tk.colors.secondaryText,
                            modifier = Modifier.size(Tk.dimens.iconSize),
                        )
                    }
                }
            },
        )
        ChatPanel(
            chatId, chatName, viewModel, myUid,
            chatType = chatType,
            resolveSender = resolveSender,
            onForward = onForward,
            initialDraft = initialDraft,
            voicePlayback = voicePlayback,
            mentionCandidates = mentionCandidates,
            selectableText = true,
            onDraftChange = { draft ->
                // 空草稿传 null，避免 [草稿] 标签残留
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    conversationRepo.setDraft(chatId, draft.ifBlank { null })
                }
            },
            media = com.virjar.tk.ui.bridge.ChatMediaConfig(
                fileDownloads = fileDownloads,
                onPickImage = { DesktopMediaHelper.pickAndSendImage(chatId, myUid, viewModel) },
                onPickFile = { DesktopMediaHelper.pickAndSendFile(chatId, myUid, viewModel) },
                onPickVideo = { DesktopMediaHelper.pickAndSendVideo(chatId, myUid, viewModel) },
                onMentionClick = onMentionClick,
                onUrlClick = { url ->
                    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                        try { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) } catch (_: Exception) {}
                    }
                },
                onVoiceRecord = { start ->
                    if (start) DesktopMediaHelper.startRecording()
                    else DesktopMediaHelper.stopAndSendVoice(chatId, myUid, viewModel)
                },
                imageContent = { url, modifier -> com.virjar.tk.media.CachedImageContent(url, modifier) },
                onMediaClick = onMediaClick,
            ),
        )
    }

    // 全屏媒体画廊（独立窗口）
    MediaGalleryWindow(
        visible = showGallery,
        items = galleryItems,
        initialIndex = galleryIndex,
        onDismiss = { showGallery = false },
    )
}
