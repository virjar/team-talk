package com.virjar.tk

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.virjar.tk.client.ClientSession
import com.virjar.tk.model.ChatType
import com.virjar.tk.model.Message
import com.virjar.tk.model.User
import com.virjar.tk.navigation.MainTab
import com.virjar.tk.ui.component.GalleryItem
import com.virjar.tk.ui.component.PlatformMediaActions
import com.virjar.tk.ui.component.rememberMediaClickHandler
import com.virjar.tk.ui.screen.ChatPanel
import com.virjar.tk.ui.screen.ConversationListScreen
import com.virjar.tk.ui.screen.MeHeaderStyle
import com.virjar.tk.ui.screen.MeScreen
import com.virjar.tk.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主内容区（三栏布局：导航栏 + 列表栏 + 内容栏）。
 *
 * 三栏常驻，子页面（搜索/群详情/编辑资料等）渲染为右栏面板，而非全屏覆盖。
 * 这是桌面 IM 的标准范式（飞书/Slack），区别于 Android 的全屏页面导航。
 */
@Composable
internal fun MainAppContent(
    session: ClientSession,
    onLogout: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val appState = rememberAppState(session)
    val conversations by appState.conversationViewModel.conversations.collectAsState()
    val contacts by appState.contactViewModel.contacts.collectAsState()
    val pendingApplyCount by appState.contactViewModel.pendingApplyCount.collectAsState()

    DisposableEffect(Unit) {
        onDispose { appState.destroy() }
    }

    // 右栏面板类型：与聊天上下文相关，渲染在右栏（飞书群详情范式）
    val panelScreens = setOf(
        SubScreen.GroupDetail, SubScreen.UserProfile,
        SubScreen.InviteMembers, SubScreen.InviteLinks,
    )

    // 当前子页面是否走右栏面板（其余走新窗口）
    val isPanelScreen = appState.currentScreen != null && appState.currentScreen in panelScreens

    // 关闭右栏面板（ESC / 返回箭头 / 关闭按钮通用）
    val closePanel: () -> Unit = { appState.currentScreen = null }

    // ── 新窗口类子页面（独立任务，临时窗口，关了销毁）──
    // 与右栏面板互斥：currentScreen 非 null 且不在 panelScreens 时，弹新窗口
    val windowScreen = if (appState.currentScreen != null && !isPanelScreen) appState.currentScreen else null
    // key(windowScreen)：screen 切换时强制重建 SubWindow，避免 remember 残留旧 localScreen
    // （否则 SearchMessages→CreateGroup 切换时窗口内容不更新）
    if (windowScreen != null) {
        key(windowScreen) {
            SubWindow(screen = windowScreen, appState = appState, onClose = closePanel)
        }
    }

    // ── 三栏常驻布局 ──
    Row(modifier = Modifier.fillMaxSize().testTag("main.home")) {
        // ── 左栏：导航栏 ──
        NavigationRail(modifier = Modifier.fillMaxHeight()) {
            MainTab.entries.forEachIndexed { index, tab ->
                NavigationRailItem(
                    selected = appState.selectedTab == index,
                    onClick = {
                        appState.selectedTab = index
                        if (tab != MainTab.CONVERSATIONS) appState.selectedChatId = null
                    },
                    icon = {
                        val imageVector = when (tab) {
                            MainTab.CONVERSATIONS -> Icons.AutoMirrored.Filled.Chat
                            MainTab.CONTACTS -> Icons.Filled.Contacts
                            MainTab.SETTINGS -> Icons.Filled.Settings
                        }
                        // 通讯录有待处理好友申请时显示红点（对齐 Android HomeScreen.kt:95-101）
                        if (tab == MainTab.CONTACTS && pendingApplyCount > 0) {
                            BadgedBox(badge = { Badge { Text("$pendingApplyCount") } }) {
                                Icon(imageVector, contentDescription = tab.label)
                            }
                        } else {
                            Icon(imageVector, contentDescription = tab.label)
                        }
                    },
                    label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        // ── 中栏：列表区（会话/通讯录/设置，固定 300dp）──
        Surface(
            modifier = Modifier.width(300.dp).fillMaxHeight(),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            when (MainTab.entries[appState.selectedTab]) {
                MainTab.CONVERSATIONS -> {
                    Column {
                        ListHeader(
                            title = "会话",
                            actions = {
                                // 对齐 Android HomeScreen TopAppBar：搜索/发起群聊/添加好友 三图标
                                IconButton(onClick = { appState.currentScreen = SubScreen.SearchMessages },
                                    modifier = Modifier.testTag("action.search")) {
                                    Icon(Icons.Filled.Search, contentDescription = "搜索消息")
                                }
                                IconButton(onClick = { appState.currentScreen = SubScreen.CreateGroup },
                                    modifier = Modifier.testTag("action.createGroup")) {
                                    Icon(Icons.Filled.GroupAdd, contentDescription = "发起群聊")
                                }
                                IconButton(onClick = { appState.currentScreen = SubScreen.SearchUsers },
                                    modifier = Modifier.testTag("action.addFriend")) {
                                    Icon(Icons.Filled.PersonAdd, contentDescription = "添加好友")
                                }
                            },
                        )
                        ConversationListScreen(
                            conversations = conversations,
                            onConversationClick = { chatId ->
                                val conv = conversations.find { it.chatId == chatId }
                                appState.openChat(chatId, conv?.chatName ?: chatId.take(16), conv?.chatType ?: 1)
                            },
                            onPinClick = { chatId, pinned -> appState.session.localCache.toggleConversationPin(chatId, pinned) },
                        )
                    }
                }

                MainTab.CONTACTS -> {
                    Column {
                        ListHeader(
                            title = "通讯录",
                            actions = {
                                IconButton(onClick = { appState.currentScreen = SubScreen.SearchUsers },
                                    modifier = Modifier.testTag("action.addFriend")) {
                                    Icon(Icons.Filled.Search, contentDescription = "搜索用户")
                                }
                                IconButton(onClick = { appState.currentScreen = SubScreen.CreateGroup },
                                    modifier = Modifier.testTag("action.createGroup")) {
                                    Icon(Icons.Filled.GroupAdd, contentDescription = "创建群组")
                                }
                                IconButton(onClick = { appState.currentScreen = SubScreen.FriendApplies },
                                    modifier = Modifier.testTag("action.friendApplies")) {
                                    if (pendingApplyCount > 0) {
                                        BadgedBox(badge = { Badge { Text("$pendingApplyCount") } }) {
                                            Icon(Icons.Filled.PersonAdd, contentDescription = "好友申请")
                                        }
                                    } else {
                                        Icon(Icons.Filled.PersonAdd, contentDescription = "好友申请")
                                    }
                                }
                            },
                        )
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(contacts, key = { it.friendUid }) { contact ->
                                val displayName = contact.remark ?: contact.user?.name ?: contact.friendUid
                                ListItem(
                                    headlineContent = { Text(displayName) },
                                    supportingContent = {
                                        val remark = contact.remark
                                        val userName = contact.user?.name
                                        if (remark != null && userName != null) {
                                            Text(userName, style = MaterialTheme.typography.bodySmall)
                                        }
                                    },
                                    modifier = Modifier.clickable {
                                        appState.selectedProfileUid = contact.friendUid
                                        appState.currentScreen = SubScreen.UserProfile
                                    },
                                )
                            }
                        }
                    }
                }

                MainTab.SETTINGS -> {
                    MeScreen(
                        currentUser = appState.currentUser,
                        onLogout = onLogout,
                        onEditProfile = { appState.currentScreen = SubScreen.EditProfile },
                        onChangePassword = { appState.currentScreen = SubScreen.ChangePassword },
                        onDeviceManagement = { appState.currentScreen = SubScreen.Devices },
                        onBlacklist = { appState.currentScreen = SubScreen.Blacklist },
                        buildInfoText = "Git: ${BuildConfig.GIT_COMMIT_ID.take(8)}  |  Build: ${BuildConfig.BUILD_TIME}",
                        headerStyle = MeHeaderStyle.Compact,
                    )
                }
            }
        }

        // ── 右栏：内容区（聊天面板 / 子页面面板 / 空态）──
        // 子页面渲染为右栏面板（叠加在聊天区），ESC 或 ArrowBack 关闭回聊天。
        // onPreviewKeyEvent 监听 ESC 关闭当前面板。
        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .onPreviewKeyEvent { event ->
                    // ESC 关闭当前子页面面板
                    if (event.type == KeyEventType.KeyUp && event.key == Key.Escape && appState.currentScreen != null) {
                        closePanel()
                        true
                    } else {
                        false
                    }
                },
            color = MaterialTheme.colorScheme.surface,
        ) {
            when {
                // 右栏面板：与聊天上下文相关的子页面（群详情/成员/资料/邀请）
                isPanelScreen -> {
                    SubScreenRouter(
                        appState = appState,
                        // 子页面的 onBack = 关闭面板回聊天（桌面范式）
                        backTarget = { _ -> null },
                        onOpenChatAndDismiss = closePanel,
                        onLeaveGroup = {
                            scope.launch {
                                try { appState.chatRepo.deleteChat(appState.selectedGroupChatId!!) } catch (_: Exception) {}
                                appState.currentScreen = null
                                appState.selectedChatId = null
                            }
                        },
                        // 桌面面板模式：子页面不渲染返回按钮，靠 ESC 关闭
                        desktopPanelMode = true,
                    )
                }
                // 聊天面板
                appState.selectedChatId != null && appState.chatViewModel != null -> {
                    // 从会话列表读取当前会话的草稿作为初始值
                    val conv = conversations.find { it.chatId == appState.selectedChatId }
                    ChatPanelWrapper(
                        chatId = appState.selectedChatId!!,
                        chatName = appState.selectedChatName,
                        chatType = appState.selectedChatType,
                        viewModel = appState.chatViewModel!!,
                        myUid = appState.userSession.uid,
                        conversationRepo = appState.conversationRepo,
                        initialDraft = conv?.draft,
                        resolveSender = { uid -> appState.localCache.getUser(uid) },
                        onForward = { msg -> appState.forwardMessage = msg; appState.currentScreen = SubScreen.Forward },
                        onGroupDetail = { appState.selectedGroupChatId = appState.selectedChatId; appState.currentScreen = SubScreen.GroupDetail },
                    )
                }
                // 空态
                else -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("选择一个会话开始聊天", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * 列表/面板头 —— 飞书/Slack 桌面范式：抬升 Surface + 标题 + 右侧操作槽 + 底部分隔线。
 * 中栏列表头和聊天面板头共用此组件（消除两段近乎重复的 Surface+Row+Divider 模板）。
 *
 * @param title 标题文字
 * @param onTitleClick 标题点击回调（群聊标题可点击进群详情），null 不可点击
 * @param actions 右侧操作槽（图标按钮等）
 */
@Composable
private fun ListHeader(
    title: String,
    onTitleClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(tonalElevation = 2.dp) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .weight(1f)
                        .then(if (onTitleClick != null) Modifier.clickable(onClick = onTitleClick) else Modifier),
                )
                actions()
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
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
) {
    val messagesState = viewModel.messages.collectAsState()

    // 媒体画廊窗口状态
    var showGallery by remember { mutableStateOf(false) }
    var galleryItems by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var galleryIndex by remember { mutableIntStateOf(0) }

    // 附件菜单状态
    var showAttachSheet by remember { mutableStateOf(false) }

    val onMediaClick = rememberMediaClickHandler(
        messages = messagesState,
        actions = object : PlatformMediaActions {
            override fun playVoice(url: String) {
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { DesktopMediaHelper.playAudio(url) }
            }
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
            onDraftChange = { draft ->
                // 空草稿传 null，避免 [草稿] 标签残留
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    conversationRepo.setDraft(chatId, draft.ifBlank { null })
                }
            },
            media = com.virjar.tk.ui.bridge.ChatMediaConfig(
                onAttachClick = { showAttachSheet = true },
                onPickImage = { DesktopMediaHelper.pickAndSendImage(chatId, myUid, viewModel) },
                onPickFile = { DesktopMediaHelper.pickAndSendFile(chatId, myUid, viewModel) },
                onVoiceRecord = { start ->
                    if (start) DesktopMediaHelper.startRecording()
                    else DesktopMediaHelper.stopAndSendVoice(chatId, myUid, viewModel)
                },
                imageContent = { url, modifier -> DesktopImageContent(url, modifier) },
                onMediaClick = onMediaClick,
            ),
        )
    }

    // 附件菜单
    if (showAttachSheet) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showAttachSheet = false },
            confirmButton = {},
            title = { Text("发送附件") },
            text = {
                Column {
                    androidx.compose.material3.TextButton(onClick = {
                        showAttachSheet = false
                        DesktopMediaHelper.pickAndSendImage(chatId, myUid, viewModel)
                    }) { Text("图片") }
                    androidx.compose.material3.TextButton(onClick = {
                        showAttachSheet = false
                        DesktopMediaHelper.pickAndSendVideo(chatId, myUid, viewModel)
                    }) { Text("视频") }
                    androidx.compose.material3.TextButton(onClick = {
                        showAttachSheet = false
                        DesktopMediaHelper.pickAndSendFile(chatId, myUid, viewModel)
                    }) { Text("文件") }
                }
            },
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
