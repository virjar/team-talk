package com.virjar.tk.desktop

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip

import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowScope
import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.shared.client.FriendPresence
import com.virjar.tk.desktop.media.DesktopSessionResources
import com.virjar.tk.protocol.model.ChatType
import com.virjar.tk.protocol.model.Contact
import com.virjar.tk.protocol.model.Conversation
import com.virjar.tk.protocol.model.User
import com.virjar.tk.app.navigation.MainTab
import com.virjar.tk.app.telemetry.ClientUiPage
import com.virjar.tk.app.telemetry.ClientUiAction
import com.virjar.tk.app.telemetry.ClientActionOutcome
import com.virjar.tk.app.telemetry.ClientSystemEvent
import com.virjar.tk.app.telemetry.ClientSystemState
import com.virjar.tk.app.telemetry.PageDwellTracker
import com.virjar.tk.app.ui.component.AvatarPlaceholder
import com.virjar.tk.app.ui.screen.DirectoryScreen
import com.virjar.tk.app.ui.screen.ConversationListScreen
import com.virjar.tk.app.ui.screen.conversationIdentityPresentation
import com.virjar.tk.app.ui.theme.Tk
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
    nav: DesktopNav,
    presentationGate: DesktopSessionPresentationGate,
    resources: DesktopSessionResources,
    mainWindow: java.awt.Window,
    mainWindowReadActive: Boolean,
    connectionState: ConnectionState,
    protocolCompatibility: com.virjar.tk.shared.client.ProtocolCompatibility?,
    onToggleWindowZoom: () -> Unit,
    onLogout: () -> Unit,
) {
    // AuthController 同步地退役 Desktop owner，但连接流程的失效通知仍可能为这个已分离的子树
    // 安排最后一次重组。绝不能让那次过期渲染在 AppDataState.destroy 越过准入边界之后
    // 再借用任何业务资源。
    if (!presentationGate.isOpen || !nav.acceptsRendering) return

    val documentAssetUiScope = rememberCoroutineScope()
    val documentEmbeddedAssetImports = remember(resources, presentationGate, documentAssetUiScope) {
        DesktopEmbeddedAssetImportGateway(
            resources = resources,
            transfer = resources.fileTransfer,
            publishOnUi = { action ->
                documentAssetUiScope.launch { presentationGate.runIfOpen(action) }
            },
        )
    }
    val chatEmbeddedAssetImports = remember(resources, presentationGate, documentAssetUiScope) {
        DesktopEmbeddedAssetImportGateway(
            resources = resources,
            transfer = resources.fileTransfer,
            publishOnUi = { action ->
                documentAssetUiScope.launch { presentationGate.runIfOpen(action) }
            },
        )
    }
    val documentFileDownloads = remember(resources, presentationGate, documentAssetUiScope) {
        DesktopFileDownloadController(
            resources = resources,
            uiScope = documentAssetUiScope,
            actionAdmission = presentationGate,
            onDownloaded = DesktopExternalFileOpener::open,
            telemetry = nav.telemetry,
            telemetryPage = ClientUiPage.DOCUMENTS,
        )
    }
    val documentEmbeddedAssetMedia = remember(
        resources,
        presentationGate,
        documentFileDownloads,
    ) {
        com.virjar.tk.app.ui.bridge.EmbeddedAssetMediaConfig(
            fileDownloads = documentFileDownloads,
            imageContent = { attachment, modifier ->
                com.virjar.tk.desktop.media.CachedImageContent(
                    attachment = attachment,
                    resources = resources,
                    actionAdmission = presentationGate,
                    modifier = modifier,
                )
            },
        )
    }
    DisposableEffect(chatEmbeddedAssetImports, documentEmbeddedAssetImports, documentFileDownloads) {
        onDispose {
            chatEmbeddedAssetImports.close()
            documentEmbeddedAssetImports.close()
            documentFileDownloads.close()
        }
    }

    val telemetryPage = desktopTelemetryPage(nav)
    val pageDwell = remember(nav.telemetry) {
        PageDwellTracker(System::currentTimeMillis, nav.telemetry::recordPageDwell)
    }
    var lastOpenedTelemetryPage by remember(nav.telemetry) { mutableStateOf<ClientUiPage?>(null) }
    LaunchedEffect(telemetryPage, mainWindowReadActive) {
        if (mainWindowReadActive) {
            pageDwell.enter(telemetryPage)
            if (lastOpenedTelemetryPage != telemetryPage) {
                lastOpenedTelemetryPage = telemetryPage
                nav.telemetry.recordAction(
                    telemetryPage,
                    ClientUiAction.OPEN_PAGE,
                    ClientActionOutcome.SUCCEEDED,
                )
            }
        } else {
            pageDwell.pause()
        }
    }
    LaunchedEffect(mainWindowReadActive) {
        nav.telemetry.recordSystem(
            if (mainWindowReadActive) {
                ClientSystemEvent.WINDOW_FOCUSED
            } else {
                ClientSystemEvent.WINDOW_UNFOCUSED
            },
            if (mainWindowReadActive) ClientSystemState.FOCUSED else ClientSystemState.UNFOCUSED,
        )
    }
    LaunchedEffect(connectionState) {
        nav.telemetry.recordSystem(
            ClientSystemEvent.CONNECTION_STATE,
            desktopConnectionTelemetryState(connectionState),
        )
    }
    DisposableEffect(pageDwell) {
        nav.telemetry.recordSystem(ClientSystemEvent.WINDOW_OPENED, ClientSystemState.OPEN)
        onDispose {
            pageDwell.finish(
                desktopWindowDisposalExitReason(presentationGate.isOpen),
            )
            nav.telemetry.recordSystem(ClientSystemEvent.WINDOW_CLOSED, ClientSystemState.CLOSED)
        }
    }

    val conversations by nav.conversationViewModel.conversations.collectAsState()
    val conversationPeerUsers by nav.conversationViewModel.peerUsers.collectAsState()
    val activeConversation = conversations.find { it.chatId == nav.chatId }
    val activeChatName = activeConversation?.let { conversation ->
        conversationIdentityPresentation(
            conversation,
            conversation.peerUid?.let(conversationPeerUsers::get),
        ).name
    } ?: nav.chatId?.take(16).orEmpty()
    val activeChatType = activeConversation?.chatType ?: ChatType.PERSONAL.code
    val contacts by nav.contactViewModel.contacts.collectAsState()
    val friendPresenceByUid by nav.contactViewModel.friendPresenceByUid.collectAsState()
    val pendingApplyCount by nav.contactViewModel.pendingApplyCount.collectAsState()
    var documentsInitialized by remember { mutableStateOf(false) }

    LaunchedEffect(nav.selectedTab, nav.documentWindowVisible) {
        nav.runAdmittedUiAction(presentationGate, onClosed = {}) {
            when (MainTab.entries[nav.selectedTab]) {
                MainTab.CONTACTS -> {
                    nav.contactViewModel.refreshPendingApplyCount()
                    nav.organization.refresh()
                }
                MainTab.DOCUMENTS -> if (!nav.documentWindowVisible) {
                    if (documentsInitialized) {
                        // refresh 保留当前首页/空间位置；open 会回到首页，只用于会话内首次进入。
                        nav.documents.refresh()
                    } else {
                        documentsInitialized = true
                        nav.documents.open()
                    }
                }
                else -> Unit
            }
        }
    }

    // ESC 优先关闭资料弹窗，再关闭检查器：AWT KeyEventDispatcher 层拦截（Compose onPreviewKeyEvent 依赖
    // 焦点节点存在，无焦点时（点完非 focusable 的列表行）按键不派发——旧版
    // 「ESC 不可靠」的根因）。按窗口归属分流，弹层/对话框是独立 Window 不受影响。
    DisposableEffect(mainWindow) {
        val unregisterEscape = registerEscapeInterceptor(mainWindow) {
            var consumed = false
            presentationGate.runIfOpen {
                consumed = when {
                    nav.profileUid != null -> { nav.closeProfile(); true }
                    nav.inspectorStack.isNotEmpty() -> { nav.popInspector(); true }
                    nav.mainPaneScreen != null -> { nav.closeMainPane(); true }
                    else -> false
                }
            }
            consumed
        }
        val unregisterSearch = registerGlobalSearchShortcut(mainWindow) {
            presentationGate.runIfOpen {
                // 模态资料弹窗持有键盘焦点。即使合成事件直接发往 owner 窗口，也要让快捷键保持失效。
                if (nav.profileUid == null) nav.openGlobalSearch(requestFocus = true)
            }
        }
        onDispose {
            unregisterEscape()
            unregisterSearch()
        }
    }

    // uid → User 解析链：当前消息窗口的有界常驻投影 → currentUser → userSession 兜底。
    // 渲染路径不读 SQLite；自动登录初始投影尚未发布时，自己的消息仍有稳定兜底。
    val userSession = nav.userSession
    val resolveUser: (String) -> User? = { uid ->
        nav.residentChatUser(uid)
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
            SubWindow(
                screen = windowScreen,
                nav = nav,
                presentationGate = presentationGate,
                resources = resources,
                onClose = presentationGate.guard { nav.windowScreen = null },
            )
        }
    }
    if (nav.documentWindowVisible) {
        DocumentWorkspaceWindow(
            nav = nav,
            presentationGate = presentationGate,
            embeddedAssetImports = documentEmbeddedAssetImports,
            embeddedAssetMedia = documentEmbeddedAssetMedia,
            onClose = presentationGate.guard { nav.documentWindowVisible = false },
        )
    }

    // @ 候选只读 LocalCache 的成员/用户组合投影；RPC 仅负责使该投影收敛。
    LaunchedEffect(nav.chatId, activeChatType) {
        nav.runAdmittedUiAction(presentationGate, onClosed = {}) {
            val chatId = nav.chatId
            if (chatId != null && activeChatType == ChatType.GROUP.code) {
                nav.groups.loadMentionCandidates(chatId)
            } else {
                nav.groups.clearMentionCandidates()
            }
        }
    }
    val mentionCandidates = when {
        nav.chatId == null -> emptyList()
        activeChatType == ChatType.GROUP.code && nav.groups.mentionTargetChatId == nav.chatId -> {
            nav.groups.mentionUsers
        }
        activeChatType == ChatType.GROUP.code -> emptyList()
        else -> contacts.mapNotNull { it.user }
    }

    // ── 应用壳层 + 三栏常驻布局 ──
    Box(modifier = Modifier.fillMaxSize().testTag("main.home")) {
        Column(modifier = Modifier.fillMaxSize()) {
            DesktopTitleBar(
                query = nav.globalSearchQuery,
                onQueryChange = { query ->
                    presentationGate.runIfOpen {
                        nav.globalSearchQuery = query
                        nav.openGlobalSearch()
                    }
                },
                onSearchFocus = presentationGate.guard { nav.openGlobalSearch() },
                focusNonce = nav.searchFocusNonce,
                connectionState = connectionState,
                onToggleWindowZoom = presentationGate.guard(onToggleWindowZoom),
            )
            com.virjar.tk.app.ui.component.ProtocolUpgradeBanner(protocolCompatibility)
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // ── 左栏：细导航栏（56dp 图标式，规格 §1.5）──
            SlimNavRail(
                selectedTab = nav.selectedTab,
                onSelectTab = { index ->
                    presentationGate.runIfOpen {
                        nav.selectedTab = index
                        nav.closeInspector()
                        nav.closeMainPane()
                        if (MainTab.entries[index] != MainTab.CONVERSATIONS) nav.chatId = null
                    }
                },
                onOpenSettings = presentationGate.guard(nav::openSettings),
                pendingApplyCount = pendingApplyCount,
                currentUserName = resolveUser(userSession.uid)?.name,
                currentUserAvatar = resolveUser(userSession.uid)?.avatar,
            )

            MainListPane(nav, presentationGate, onLogout, conversations, conversationPeerUsers, contacts, friendPresenceByUid, pendingApplyCount)
            MainContentPane(
                nav, presentationGate, resources, chatEmbeddedAssetImports, documentEmbeddedAssetImports,
                documentEmbeddedAssetMedia, resolveUser, mentionCandidates, activeConversation,
                activeChatName, activeChatType, mainWindowReadActive,
            )
        }
        }

        MainOverlayLayers(nav, mainWindow, presentationGate, resources, onLogout)
    }
}

/** 中栏列表区：按当前 tab 分流会话/通讯录/设置（300dp，规格 §1.5），展开态整体让位。 */
@Composable
private fun MainListPane(
    nav: DesktopNav,
    presentationGate: DesktopSessionPresentationGate,
    onLogout: () -> Unit,
    conversations: List<Conversation>,
    conversationPeerUsers: Map<String, User>,
    contacts: List<Contact>,
    friendPresenceByUid: Map<String, FriendPresence>,
    pendingApplyCount: Int,
) {
    val directoryScope = rememberCoroutineScope()
    val expandedWorkspace = MainTab.entries[nav.selectedTab] == MainTab.DOCUMENTS || nav.mainPaneScreen != null
    // 三级层次：rail(surfaceVariant 深灰) → 列表(background 浅灰) → 内容(白)
    if (!expandedWorkspace) {
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
                                presentationGate.runIfOpen {
                                    nav.openChat(chatId)
                                }
                            },
                            onPinClick = presentationGate.guard(nav.conversationViewModel::setPinned),
                            onMuteClick = presentationGate.guard(nav.conversationViewModel::setMuted),
                            onMarkRead = { chatId, lastSeq ->
                                presentationGate.runIfOpen {
                                    nav.markConversationRead(chatId, lastSeq)
                                }
                            },
                            peerUsers = conversationPeerUsers,
                        )
                    }
                }

                MainTab.CONTACTS -> {
                    Column {
                        ListHeader(title = "通讯录")
                        // 桌面使用搜索 + 鼠标滚动；移动端字母索引条不占用中栏右侧空间。
                        DirectoryScreen(
                            contacts = contacts,
                            friendPresenceByUid = friendPresenceByUid,
                            units = nav.organization.units,
                            members = nav.organization.members,
                            selectedUnitId = nav.organization.selectedUnitId,
                            organizationInitialized = nav.organization.initialized,
                            organizationUnitSnapshotKnown = nav.organization.unitSnapshotKnown,
                            organizationLoading = nav.organization.loading,
                            organizationMemberSnapshotKnown = nav.organization.memberSnapshotKnown,
                            organizationMembersLoading = nav.organization.membersLoading,
                            onUnitClick = { unitId ->
                                directoryScope.launch {
                                    nav.runAdmittedUiAction(presentationGate, onClosed = {}) {
                                        nav.organization.selectUnit(unitId)
                                    }
                                }
                            },
                            onGroupClick = { chatId, _ ->
                                presentationGate.runIfOpen { nav.openChat(chatId) }
                            },
                            onUserClick = presentationGate.guard(nav::openProfile),
                            modifier = Modifier.weight(1f),
                            pendingApplyCount = pendingApplyCount,
                            onFriendApplies = presentationGate.guard {
                                nav.openScreen(SubScreen.FriendApplies)
                            },
                            showAlphabetIndex = false,
                        )
                    }
                }

                MainTab.DOCUMENTS -> Unit

                // 个人设置已改为居中模态（DesktopSettingsDialog），不再占用中栏；
                // 该分支仅为枚举穷尽保留，正常路径 selectedTab 不会停在 SETTINGS。
                MainTab.SETTINGS -> Unit
            }
        }
    }
}

/**
 * 右栏主内容区：全局搜索/文档工作台/聊天三路由分流。全局搜索替换主内容；
 * 群设置覆盖在聊天右侧，不再把聊天页替换掉。聊天上下文（草稿分发、语音播放）随本栏常驻。
 */
@Composable
private fun RowScope.MainContentPane(
    nav: DesktopNav,
    presentationGate: DesktopSessionPresentationGate,
    resources: DesktopSessionResources,
    chatEmbeddedAssetImports: DesktopEmbeddedAssetImportGateway,
    documentEmbeddedAssetImports: DesktopEmbeddedAssetImportGateway,
    documentEmbeddedAssetMedia: com.virjar.tk.app.ui.bridge.EmbeddedAssetMediaConfig,
    resolveUser: (String) -> User?,
    mentionCandidates: List<User>,
    activeConversation: Conversation?,
    activeChatName: String,
    activeChatType: Int,
    mainWindowReadActive: Boolean,
) {
    // 语音应用内播放（native 引擎，聊天面板级共享：切会话即静音）
    val voicePlayback = rememberDesktopVoicePlayback(resources, presentationGate)

    Surface(
        modifier = Modifier.weight(1f).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        val mainPaneScreen = nav.mainPaneScreen
        if (mainPaneScreen != null) {
            SubScreenContent(
                screen = mainPaneScreen,
                data = nav,
                presentationGate = presentationGate,
                resources = resources,
                navigate = presentationGate.guard(nav::openScreen),
                back = presentationGate.guard(nav::closeMainPane),
                openChatAndClose = presentationGate.guard(nav::openChat),
                openMessageAndClose = presentationGate.guard(nav::openMessage),
                openUserProfile = presentationGate.guard(nav::openProfile),
                onLeaveGroup = {},
                showBack = mainPaneScreen !is SubScreen.GlobalSearch,
                globalSearchQuery = nav.globalSearchQuery,
                onGlobalSearchQueryChange = presentationGate.guard { query: String ->
                    nav.globalSearchQuery = query
                },
            )
        } else if (MainTab.entries[nav.selectedTab] == MainTab.DOCUMENTS) {
            if (nav.documentWindowVisible) {
                DocumentDetachedPlaceholder(
                    onBringBack = presentationGate.guard { nav.documentWindowVisible = false },
                )
            } else {
                DesktopDocumentWorkspaceHost(
                    workspace = nav.documents,
                    presentationGate = presentationGate,
                    embeddedAssetImports = documentEmbeddedAssetImports,
                    embeddedAssetMedia = documentEmbeddedAssetMedia,
                    onDetach = presentationGate.guard { nav.documentWindowVisible = true },
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                val activeChatId = nav.chatId
                val activeChatViewModel = activeChatId?.let(nav::chatViewModelFor)
                when {
                    activeChatId != null && activeChatViewModel != null -> {
                        ChatPanelWrapper(
                            chatId = activeChatId,
                            chatName = activeChatName,
                            chatType = activeChatType,
                            viewModel = activeChatViewModel,
                            myUid = nav.userSession.uid,
                            presentationGate = presentationGate,
                            resources = resources,
                            embeddedAssetImports = chatEmbeddedAssetImports,
                            telemetry = nav.telemetry,
                            saveDraft = nav::saveDraft,
                            draftLifecycleBridge = nav.chatDraftLifecycle,
                            // null 表示会话尚未加载；已知没有草稿以空字符串交给编辑器。
                            cachedDraft = activeConversation?.let { it.draft.orEmpty() },
                            composerContextStore = nav.chatComposerContexts,
                            resolveSender = { uid ->
                                mentionCandidates.firstOrNull { it.uid == uid } ?: resolveUser(uid)
                            },
                            voicePlayback = voicePlayback,
                            onMentionClick = presentationGate.guard(nav::openProfile),
                            mentionCandidates = mentionCandidates,
                            chatForegroundActive = mainWindowReadActive,
                            messageFocusTarget = nav.messageFocusTarget
                                ?.takeIf { target -> target.chatId == activeChatId },
                            messageFocusRequestId = nav.messageFocusRequestId,
                            onForward = presentationGate.guard { msg ->
                                nav.openScreen(SubScreen.Forward(msg))
                            },
                            onSaveMessage = presentationGate.guard { msg ->
                                nav.messageActions.save(msg.chatId, msg.serverSeq)
                            },
                            officeRefHost = nav,
                            onOpenOfficeRef = presentationGate.guard { msg, body, onDenied ->
                                nav.messageActions.openReference(
                                    reference = body,
                                    onOpen = {
                                        if (body.isDocument) {
                                            nav.selectedTab = MainTab.DOCUMENTS.ordinal
                                            nav.documents.openDocumentRef(body.spaceId, body.targetId)
                                        } else {
                                            nav.openScreen(SubScreen.GroupFiles(body.spaceId))
                                        }
                                    },
                                    onDenied = onDenied,
                                )
                            },
                            onGroupSettings = presentationGate.guard {
                                nav.chatId?.let { nav.openScreen(SubScreen.GroupDetail(it)) }
                            },
                        )
                    }
                    // 空态（规格 §2.1：Logo + 主提示 + 次提示）
                    else -> MainPaneEmptyState(MainTab.entries[nav.selectedTab])
                }

                ChatInspectorHost(nav, presentationGate, resources)
            }
        }
    }
}

/** 主内容之上的覆盖层：个人设置模态、用户资料模态弹窗与会话反馈提示。 */
@Composable
private fun BoxScope.MainOverlayLayers(
    nav: DesktopNav,
    mainWindow: java.awt.Window,
    presentationGate: DesktopSessionPresentationGate,
    resources: DesktopSessionResources,
    onLogout: () -> Unit,
) {
    if (nav.settingsOpen) {
        key(Unit) {
            DesktopSettingsDialog(
                nav = nav,
                ownerWindow = mainWindow,
                presentationGate = presentationGate,
                resources = resources,
                buildInfoText = "Git: ${BuildConfig.BUILD_IDENTITY.substringAfter('+').take(8)}" +
                    "${if (BuildConfig.BUILD_IDENTITY.endsWith(".dirty")) "-dirty" else ""}" +
                    "  |  Build: ${BuildConfig.BUILD_TIME}",
                onLogout = presentationGate.guard {
                    nav.telemetry.recordAction(
                        ClientUiPage.SETTINGS,
                        ClientUiAction.LOGOUT,
                        ClientActionOutcome.STARTED,
                    )
                    onLogout()
                },
                onDismiss = presentationGate.guard(nav::closeSettings),
            )
        }
    }

    nav.profileUid?.let { uid ->
        key(uid) {
            DesktopUserProfileDialog(
                uid = uid,
                nav = nav,
                ownerWindow = mainWindow,
                presentationGate = presentationGate,
                onDismiss = presentationGate.guard(nav::closeProfile),
            )
        }
    }

    // action 失败提示（此前面板/中栏 action 的错误完全静默）
    SessionFeedbackSnackbar(nav, presentationGate)
}

/**
 * 与聊天上下文绑定的非模态检查器。抽屉覆盖聊天右侧，不参与 Row 测量，因此打开时
 * 消息区和输入框不会重新布局；关闭后仍停留在原会话和滚动位置。抽屉外部由透明点击层
 * 阻断：第一次点击只关闭抽屉，不会穿透触发聊天操作。
 */
@Composable
private fun BoxScope.ChatInspectorHost(
    nav: DesktopNav,
    presentationGate: DesktopSessionPresentationGate,
    resources: DesktopSessionResources,
) {
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
                        onClick = presentationGate.guard(nav::closeInspector),
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
                    presentationGate = presentationGate,
                    resources = resources,
                    navigate = presentationGate.guard { target ->
                        if (target.presentation == SubScreenPresentation.CHAT_INSPECTOR) {
                            nav.pushInspector(target)
                        } else {
                            nav.openScreen(target)
                        }
                    },
                    back = presentationGate.guard(nav::popInspector),
                    openChatAndClose = presentationGate.guard(nav::openChat),
                    openMessageAndClose = presentationGate.guard(nav::openMessage),
                    openUserProfile = presentationGate.guard(nav::openProfile),
                    onLeaveGroup = { chatId ->
                        presentationGate.runIfOpen {
                            val isOwner = nav.groups.members.any {
                                it.uid == nav.userSession.uid && it.role == 2
                            }
                            nav.groups.exit(chatId, dissolve = isOwner) {
                                presentationGate.runIfOpen {
                                    nav.closeInspector()
                                    if (nav.chatId == chatId) nav.chatId = null
                                }
                            }
                        }
                    },
                    showBack = nav.inspectorStack.size > 1,
                    onClose = presentationGate.guard(nav::closeInspector),
                )
            }
        }
    }
}

/** 不同主 tab 使用各自语义空态，避免内容区仍提示“选择会话”。 */
@Composable
private fun MainPaneEmptyState(tab: MainTab) {
    val (icon, title, detail) = when (tab) {
        MainTab.CONVERSATIONS -> Triple(Icons.AutoMirrored.Filled.Chat, "选择一个会话", "从会话列表继续沟通，或使用顶部搜索查找内容")
        MainTab.CONTACTS -> Triple(Icons.Filled.Contacts, "选择一个联系人", "查看资料、发送消息或从资料页发起群聊")
        MainTab.DOCUMENTS -> Triple(Icons.Filled.Description, "打开企业文档", "从独立文档入口访问空间、目录和协作文档")
        // 正常路径不会停在设置（设置是模态）；若出现则指向正确的入口。
        MainTab.SETTINGS -> Triple(Icons.Filled.Settings, "账号与设置", "点击左下角的设置图标管理个人资料、安全与外观")
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

@Composable
private fun DocumentDetachedPlaceholder(onBringBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().testTag("documents.detached.placeholder"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("文档工作台已在独立窗口打开", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text("多个空间与已打开标签会保留在该窗口中", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
        FilledTonalButton(onClick = onBringBack, modifier = Modifier.testTag("documents.detached.bringBack")) {
            Text("收回主窗口")
        }
    }
}

internal fun desktopTelemetryPage(nav: DesktopNav): ClientUiPage = desktopMainWindowTelemetryPage(
    selectedTab = MainTab.entries.getOrNull(nav.selectedTab),
    chatOpen = nav.chatId != null,
    mainPaneScreen = nav.mainPaneScreen,
    inspectorScreen = nav.inspectorStack.lastOrNull(),
    independentlyTrackedWindowScreen = nav.windowScreen,
    independentlyTrackedProfileOpen = nav.profileUid != null,
)

/** 独立任务窗口与资料弹窗永远不会替换主窗口当前可见的页面。 */
internal fun desktopMainWindowTelemetryPage(
    selectedTab: MainTab?,
    chatOpen: Boolean,
    mainPaneScreen: SubScreen?,
    inspectorScreen: SubScreen?,
    @Suppress("UNUSED_PARAMETER") independentlyTrackedWindowScreen: SubScreen?,
    @Suppress("UNUSED_PARAMETER") independentlyTrackedProfileOpen: Boolean,
): ClientUiPage = when {
    inspectorScreen != null -> desktopTelemetryPage(inspectorScreen)
    mainPaneScreen != null -> desktopTelemetryPage(mainPaneScreen)
    chatOpen && selectedTab == MainTab.CONVERSATIONS -> ClientUiPage.CHAT
    else -> when (selectedTab) {
        MainTab.CONVERSATIONS -> ClientUiPage.CONVERSATIONS
        MainTab.CONTACTS -> ClientUiPage.CONTACTS
        MainTab.DOCUMENTS -> ClientUiPage.DOCUMENTS
        MainTab.SETTINGS -> ClientUiPage.SETTINGS
        null -> ClientUiPage.CONVERSATIONS
    }
}

internal fun desktopTelemetryPage(screen: SubScreen): ClientUiPage = when (screen) {
    SubScreen.FriendApplies -> ClientUiPage.FRIEND_APPLIES
    SubScreen.SearchUsers -> ClientUiPage.SEARCH_USERS
    is SubScreen.CreateGroup -> ClientUiPage.CREATE_GROUP
    SubScreen.SearchMessages,
    SubScreen.GlobalSearch,
    -> ClientUiPage.SEARCH_MESSAGES
    is SubScreen.Forward -> ClientUiPage.FORWARD
    is SubScreen.GroupDetail -> ClientUiPage.GROUP_DETAIL
    is SubScreen.InviteMembers -> ClientUiPage.INVITE_MEMBERS
    is SubScreen.InviteLinks -> ClientUiPage.INVITE_LINKS
    is SubScreen.GroupFiles -> ClientUiPage.GROUP_FILES
    is SubScreen.GroupBots -> ClientUiPage.GROUP_BOTS
}

internal fun desktopConnectionTelemetryState(state: ConnectionState): ClientSystemState = when (state) {
    ConnectionState.DISCONNECTED -> ClientSystemState.DISCONNECTED
    ConnectionState.CONNECTING -> ClientSystemState.CONNECTING
    ConnectionState.CONNECTED -> ClientSystemState.CONNECTED
    ConnectionState.SYNCHRONIZING -> ClientSystemState.SYNCHRONIZING
    ConnectionState.AUTHENTICATED -> ClientSystemState.AUTHENTICATED
    ConnectionState.AUTH_FAILED -> ClientSystemState.AUTHENTICATION_FAILED
}
