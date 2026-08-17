package com.virjar.tk

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
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

import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.virjar.tk.client.ClientSession
import com.virjar.tk.model.ChatType
import com.virjar.tk.model.Message
import com.virjar.tk.model.User
import com.virjar.tk.navigation.MainTab
import com.virjar.tk.ui.component.AvatarPlaceholder
import com.virjar.tk.ui.component.GalleryItem
import com.virjar.tk.ui.component.PlatformMediaActions
import com.virjar.tk.ui.component.TeamTalkLogo
import com.virjar.tk.ui.component.UnreadBadge
import com.virjar.tk.ui.component.rememberMediaClickHandler
import com.virjar.tk.ui.screen.ChatPanel
import com.virjar.tk.ui.screen.ConversationListScreen
import com.virjar.tk.ui.screen.MeHeaderStyle
import com.virjar.tk.ui.screen.MeScreen
import com.virjar.tk.ui.theme.Tk
import com.virjar.tk.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 主内容区（三栏布局：导航栏 + 列表栏 + 内容栏）。
 *
 * 三栏常驻，子页面按 §2.1 分流：群详情/成员/资料/邀请渲染为右栏面板（ESC 逐级返回），
 * 其余弹独立子窗口（§2.6）。这是桌面 IM 的标准范式（飞书/Slack），
 * 区别于 Android 的全屏页面导航。视觉规格：doc/04-ui-design/components.md §1.5/§2.1。
 */
@Composable
internal fun MainAppContent(
    session: ClientSession,
    mainWindow: java.awt.Window,
    onLogout: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val nav = rememberDesktopNav(session)
    val conversations by nav.conversationViewModel.conversations.collectAsState()
    val contacts by nav.contactViewModel.contacts.collectAsState()
    val pendingApplyCount by nav.contactViewModel.pendingApplyCount.collectAsState()

    DisposableEffect(Unit) {
        onDispose { nav.destroy() }
    }

    // ESC 关闭面板：AWT KeyEventDispatcher 层拦截（Compose onPreviewKeyEvent 依赖
    // 焦点节点存在，无焦点时（点完非 focusable 的列表行）按键不派发——旧版
    // 「ESC 不可靠」的根因）。按窗口归属分流，弹层/对话框是独立 Window 不受影响。
    DisposableEffect(mainWindow) {
        val unregister = registerEscapeInterceptor(mainWindow) {
            if (nav.panelStack.isNotEmpty()) { nav.popPanel(); true } else false
        }
        onDispose { unregister() }
    }

    // uid → User 解析链：本地缓存 → currentUser → userSession 终极兜底。
    // 自动登录路径 localCache/currentUser 可能为空，没有兜底时自己消息头像退化为 uid 首字母。
    val userSession = nav.userSession
    val resolveUser: (String) -> User? = { uid ->
        nav.localCache.getUser(uid)
            ?: nav.currentUser?.takeIf { it.uid == uid }
            ?: if (uid == userSession.uid) {
                User(
                    uid = uid,
                    username = userSession.username ?: uid,
                    name = userSession.name?.ifBlank { null } ?: userSession.username ?: uid,
                )
            } else null
    }

    // ── 独立子窗口（§2.6，与右栏面板互斥）──
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

    // ── 三栏常驻布局 ──
    Box(modifier = Modifier.fillMaxSize().testTag("main.home")) {
        Row(modifier = Modifier.fillMaxSize()) {
            // ── 左栏：细导航栏（56dp 图标式，规格 §1.5）──
            SlimNavRail(
                selectedTab = nav.selectedTab,
                onSelectTab = { index ->
                    nav.selectedTab = index
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
                            ListHeader(
                                title = "会话",
                                actions = {
                                    // 对齐 Android HomeScreen TopAppBar：搜索/发起群聊/添加好友 三图标
                                    IconButton(onClick = { nav.openScreen(SubScreen.SearchMessages) },
                                        modifier = Modifier.testTag("action.search")) {
                                        Icon(Icons.Filled.Search, contentDescription = "搜索消息", tint = Tk.colors.secondaryText)
                                    }
                                    IconButton(onClick = { nav.openScreen(SubScreen.CreateGroup) },
                                        modifier = Modifier.testTag("action.createGroup")) {
                                        Icon(Icons.Filled.GroupAdd, contentDescription = "发起群聊", tint = Tk.colors.secondaryText)
                                    }
                                    IconButton(onClick = { nav.openScreen(SubScreen.SearchUsers) },
                                        modifier = Modifier.testTag("action.addFriend")) {
                                        Icon(Icons.Filled.PersonAdd, contentDescription = "添加好友", tint = Tk.colors.secondaryText)
                                    }
                                },
                            )
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
                            ListHeader(
                                title = "通讯录",
                                actions = {
                                    IconButton(onClick = { nav.openScreen(SubScreen.SearchUsers) },
                                        modifier = Modifier.testTag("action.addFriend")) {
                                        Icon(Icons.Filled.Search, contentDescription = "搜索用户", tint = Tk.colors.secondaryText)
                                    }
                                    IconButton(onClick = { nav.openScreen(SubScreen.CreateGroup) },
                                        modifier = Modifier.testTag("action.createGroup")) {
                                        Icon(Icons.Filled.GroupAdd, contentDescription = "创建群组", tint = Tk.colors.secondaryText)
                                    }
                                    IconButton(onClick = { nav.openScreen(SubScreen.FriendApplies) },
                                        modifier = Modifier.testTag("action.friendApplies")) {
                                        if (pendingApplyCount > 0) {
                                            BadgedBox(badge = { UnreadBadge(pendingApplyCount) }) {
                                                Icon(Icons.Filled.PersonAdd, contentDescription = "好友申请", tint = Tk.colors.secondaryText)
                                            }
                                        } else {
                                            Icon(Icons.Filled.PersonAdd, contentDescription = "好友申请", tint = Tk.colors.secondaryText)
                                        }
                                    }
                                },
                            )
                            LazyColumn(modifier = Modifier.weight(1f)) {
                                items(contacts, key = { it.friendUid }) { contact ->
                                    val displayName = contact.remark ?: contact.user?.name ?: contact.friendUid
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = Tk.dimens.listItemHeight)
                                            .clickable { nav.openScreen(SubScreen.UserProfile(contact.friendUid)) }
                                            .padding(horizontal = Tk.spacing.md),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        AvatarPlaceholder(
                                            name = contact.user?.name ?: displayName,
                                            size = Tk.dimens.listAvatar.value.toInt(),
                                        )
                                        Spacer(Modifier.width(Tk.spacing.md))
                                        Column {
                                            Text(displayName, style = MaterialTheme.typography.titleSmall)
                                            val remark = contact.remark
                                            val userName = contact.user?.name
                                            if (remark != null && userName != null && remark != userName) {
                                                Text(userName, style = MaterialTheme.typography.bodySmall, color = Tk.colors.secondaryText)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    MainTab.SETTINGS -> {
                        MeScreen(
                            currentUser = nav.currentUser,
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

            // ── 右栏：内容区（聊天面板 / 子页面面板 / 空态）──
            // 面板渲染在聊天区之上，ESC 逐级弹栈（处理在根节点）。
            Surface(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                val panelScreen = nav.panelStack.lastOrNull()
                when {
                    // 右栏面板：与聊天上下文相关的子页面（群详情/成员/资料/邀请）
                    panelScreen != null -> SubScreenContent(
                        screen = panelScreen,
                        data = nav,
                        navigate = { nav.panelStack = nav.panelStack + it },
                        back = { nav.popPanel() },
                        openChatAndClose = { chatId, name, chatType -> nav.openChat(chatId, name, chatType) },
                        onLeaveGroup = { chatId ->
                            nav.leaveGroup(chatId) {
                                nav.panelStack = emptyList()
                                if (nav.chatId == chatId) nav.chatId = null
                            }
                        },
                        // 面板初始屏无返回键（ESC 关）；容器内跳转后（群详情→邀请成员）可返回
                        showBack = nav.panelStack.size > 1,
                    )
                    // 聊天面板
                    nav.chatId != null && nav.chatViewModel != null -> {
                        // 从会话列表读取当前会话的草稿作为初始值
                        val conv = conversations.find { it.chatId == nav.chatId }
                        ChatPanelWrapper(
                            chatId = nav.chatId!!,
                            chatName = nav.chatName,
                            chatType = nav.chatType,
                            viewModel = nav.chatViewModel!!,
                            myUid = nav.userSession.uid,
                            conversationRepo = nav.conversationRepo,
                            initialDraft = conv?.draft,
                            resolveSender = resolveUser,
                            voicePlayback = voicePlayback,
                            onMentionClick = { uid -> nav.openScreen(SubScreen.UserProfile(uid)) },
                            mentionCandidates = mentionCandidates,
                            onForward = { msg -> nav.openScreen(SubScreen.Forward(msg)) },
                            onGroupDetail = { nav.openScreen(SubScreen.GroupDetail(nav.chatId!!)) },
                        )
                    }
                    // 空态（规格 §2.1：Logo + 主提示 + 次提示）
                    else -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            TeamTalkLogo(size = 72.dp, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(Tk.spacing.lg))
                            Text("选择一个会话开始聊天", style = MaterialTheme.typography.titleSmall, color = Tk.colors.secondaryText)
                            Spacer(Modifier.height(Tk.spacing.xs))
                            Text("或从左侧通讯录发起对话", style = MaterialTheme.typography.bodySmall, color = Tk.colors.metaText)
                        }
                    }
                }
            }
        }

        // action 失败提示（此前面板/中栏 action 的错误完全静默）
        ErrorSnackbar(nav)
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
    conversationRepo: com.virjar.tk.repository.ConversationRepository,
    initialDraft: String?,
    onForward: (Message) -> Unit,
    onGroupDetail: () -> Unit,
    resolveSender: ((uid: String) -> User?)? = null,
    voicePlayback: com.virjar.tk.ui.component.VoicePlaybackController? = null,
    onMentionClick: ((uid: String) -> Unit)? = null,
    mentionCandidates: List<User> = emptyList(),
) {
    val messagesState = viewModel.messages.collectAsState()

    // 媒体画廊窗口状态
    var showGallery by remember { mutableStateOf(false) }
    var galleryItems by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var galleryIndex by remember { mutableIntStateOf(0) }

    val onMediaClick = rememberMediaClickHandler(
        messages = messagesState,
        actions = object : PlatformMediaActions {
            // 语音已走 voicePlayback 应用内播放（ChatPanel.voicePlayback），此链路不再触达
            override fun playVoice(url: String) {}
            override fun openFile(url: String) {
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { DesktopMediaHelper.openFile(url) }
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
        // 聊天面板头：复用 ListHeader（群聊标题可点击进群详情）
        ListHeader(
            title = chatName.ifEmpty { chatId.take(16) },
            onTitleClick = if (ChatType.fromCode(chatType) == ChatType.GROUP) onGroupDetail else null,
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
