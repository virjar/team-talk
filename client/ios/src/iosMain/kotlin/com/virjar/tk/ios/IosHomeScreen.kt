package com.virjar.tk.ios

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import com.virjar.tk.app.navigation.AppDataState
import com.virjar.tk.app.navigation.MainTab
import com.virjar.tk.app.ui.component.TaskAttentionBanner
import com.virjar.tk.app.ui.component.TaskAttentionRefresh
import com.virjar.tk.app.ui.component.TkNavIcons
import com.virjar.tk.app.ui.screen.ConversationListScreen
import com.virjar.tk.app.ui.screen.DirectoryScreen
import com.virjar.tk.app.ui.screen.MeScreen
import com.virjar.tk.app.ui.screen.MobileDocumentExitCoordinator
import com.virjar.tk.protocol.body.OfficeRefBody

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IosHomeScreen(ui: IosSessionUi, onLogout: () -> Unit) {
    val dataState = ui.data
    val navigation = ui.navigation
    fun onConversationClick(id: String) { navigation.chat(id) }
    fun onUserProfile(id: String) { navigation.profile(id) }
    fun onFriendApplies() { navigation.open(IosRoute(IosPage.FRIEND_APPLIES)) }
    fun onEditProfile() { navigation.open(IosRoute(IosPage.EDIT_PROFILE)) }
    fun onChangePassword() { navigation.open(IosRoute(IosPage.CHANGE_PASSWORD)) }
    fun onDevices() { navigation.open(IosRoute(IosPage.DEVICES)) }
    fun onBlacklist() { navigation.open(IosRoute(IosPage.BLACKLIST)) }
    fun onGlobalSearch() { navigation.open(IosRoute(IosPage.SEARCH)) }
    fun onLocalStorage() { navigation.open(IosRoute(IosPage.LOCAL_STORAGE)) }
    val foreground by IosApplicationRuntime.foreground.collectAsState()
    val actionAdmission = dataState.uiActionAdmission
    TaskAttentionRefresh(
        dataState.tasks.attention,
        foreground
    )
    val documentReference = navigation.requestedDocument
    val taskReference = navigation.requestedTask
    // 从聊天返回首页时，引用目标优先于上次保存的栏目。消费后仍留在文档，不触发第二次初始化。
    val selectedTab = when {
        taskReference != null || dataState.tasks.workspaceRequested -> MainTab.TASKS.ordinal
        documentReference != null -> MainTab.DOCUMENTS.ordinal
        else -> navigation.homeTab.ordinal
    }
    val conversations by dataState.conversationViewModel.conversations.collectAsState()
    val mentionedChatIds by dataState.chat.mentionedChatIds.collectAsState()
    val conversationPeerUsers by dataState.conversationViewModel.peerUsers.collectAsState()
    val groupAvatarMembers by dataState.conversationViewModel.groupAvatarMembers.collectAsState()
    val contacts by dataState.contactViewModel.contacts.collectAsState()
    val friendPresenceByUid by dataState.contactViewModel.friendPresenceByUid.collectAsState()
    val pendingApplyCount by dataState.contactViewModel.pendingApplyCount.collectAsState()
    val documentExitCoordinator = remember { MobileDocumentExitCoordinator() }

    // 切换标签时刷新待处理申请数
    LaunchedEffect(selectedTab, taskReference, dataState.tasks.workspaceRequested) {
        dataState.runAdmittedUiAction(actionAdmission, onClosed = {}) {
            when (MainTab.entries[selectedTab]) {
                MainTab.CONTACTS -> {
                    dataState.contactViewModel.refreshPendingApplyCount()
                    dataState.organization.refresh()
                }
                MainTab.DOCUMENTS -> {
                    val reference = navigation.requestedDocument
                    // open() 会回到文档首页；初始化与打开目标必须按顺序执行。
                    dataState.documents.open()
                    if (reference != null) {
                        dataState.documents.openDocumentRef(reference.spaceId, reference.targetId)
                        navigation.homeTab = MainTab.DOCUMENTS
                        if (navigation.requestedDocument == reference) navigation.requestedDocument = null
                    }
                }
                MainTab.TASKS -> {
                    navigation.homeTab = MainTab.TASKS
                    dataState.tasks.open()
                    navigation.requestedTask?.let { taskId ->
                        dataState.tasks.openTask(taskId)
                        navigation.homeTab = MainTab.TASKS
                        if (navigation.requestedTask == taskId) navigation.requestedTask = null
                    }
                }
                else -> Unit
            }
        }
    }

    // 选中用面性（filled）、未选中用线性（outlined），同一功能两套配对图标（T009）。
    // 一级导航用专属矢量 TkNavIcons——通用图标会话/文档/任务剪影雷同（内测反馈）；设置仍是通用齿轮。
    data class TabIcon(val filled: ImageVector, val outlined: ImageVector, val label: String)
    val tabIcons = listOf(
        TabIcon(TkNavIcons.ChatsFilled, TkNavIcons.ChatsOutlined, "会话"),
        TabIcon(TkNavIcons.ContactsFilled, TkNavIcons.ContactsOutlined, "通讯录"),
        TabIcon(TkNavIcons.DocumentsFilled, TkNavIcons.DocumentsOutlined, "文档"),
        TabIcon(TkNavIcons.TasksFilled, TkNavIcons.TasksOutlined, "任务"),
        TabIcon(Icons.Filled.Settings, Icons.Outlined.Settings, "设置"),
    )

    // 文档编辑模式（内测 T029）：编辑态隐藏底部导航，把整屏让给编辑器/输入法。
    val documentEditingActive = remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.testTag("main.home"),
        // 顶部 inset 取状态栏与挖孔/灵动岛的最大值：全屏编辑页等内容首行必须避开
        // 屏上相机；底部与水平方向维持默认（底部导航与输入法各自处理）。
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets
            .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
            .union(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
        topBar = {
            // 文档拥有自己的首页/空间标题栏；叠加通用 TopAppBar 会形成两个页面标题。
            if (MainTab.entries[selectedTab] !in setOf(MainTab.DOCUMENTS, MainTab.TASKS)) {
                TopAppBar(
                    title = { Text(tabIcons[selectedTab].label) },
                    actions = {
                        IconButton(
                            onClick = actionAdmission.guard(::onGlobalSearch),
                            modifier = Modifier.testTag("action.search"),
                        ) {
                            Icon(Icons.Filled.Search, contentDescription = "全局搜索")
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (documentEditingActive.value) return@Scaffold
            NavigationBar {
                tabIcons.forEachIndexed { index, tab ->
                    val selected = selectedTab == index
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            actionAdmission.runIfOpen {
                                if (MainTab.entries[selectedTab] == MainTab.DOCUMENTS && index != selectedTab) {
                                    documentExitCoordinator.requestExit { navigation.homeTab = MainTab.entries[index] }
                                } else {
                                    navigation.homeTab = MainTab.entries[index]
                                }
                            }
                        },
                        icon = {
                            val icon = if (selected) tab.filled else tab.outlined
                            if (tab.label == "通讯录" && pendingApplyCount > 0) {
                                BadgedBox(badge = { Badge { Text("$pendingApplyCount") } }) {
                                    Icon(icon, contentDescription = tab.label)
                                }
                            } else {
                                Icon(icon, contentDescription = tab.label)
                            }
                        },
                        modifier = Modifier.testTag("nav.${tab.label}"),
                        label = {
                            Text(
                                tab.label,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                    )
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!documentEditingActive.value) dataState.tasks.attention.assigned?.let { summary ->
                TaskAttentionBanner(
                    total = summary.openCount,
                    overdue = summary.overdueCount,
                    stale = dataState.tasks.attention.assignedStale,
                    onOpen = actionAdmission.guard {
                        dataState.tasks.openAssignedTodos()
                        navigation.homeTab = MainTab.TASKS
                    },
                )
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (MainTab.entries[selectedTab]) {
                    MainTab.CONVERSATIONS -> {
                        // 固定系统账号会话拉起（内测反馈 T058）：幂等，失败静默重试下次登录。
                        LaunchedEffect(Unit) { dataState.chat.ensureSystemChats() }
                        // 群头像懒加载（内测反馈 T053）
                        LaunchedEffect(conversations) {
                            dataState.chat.ensureGroupAvatars(
                                conversations.filter { it.chatType == com.virjar.tk.protocol.model.ChatType.GROUP.code }
                                    .map { it.chatId },
                            )
                        }
                        val chatAvatars by dataState.chat.chatAvatars.collectAsState(emptyMap())
                        ConversationListScreen(
                            conversations = conversations,
                            mentionedChatIds = mentionedChatIds,
                            groupAvatars = chatAvatars,
                            onConversationClick = actionAdmission.guard(::onConversationClick),
                            onPinClick = actionAdmission.guard(dataState.conversationViewModel::setPinned),
                            onMuteClick = actionAdmission.guard(dataState.conversationViewModel::setMuted),
                            onMarkRead = actionAdmission.guard { chatId: String, lastSeq: Long ->
                                dataState.chat.markConversationRead(chatId, lastSeq)
                            },
                            onMarkUnread = actionAdmission.guard { chatId: String ->
                                dataState.conversationViewModel.setMarkedUnread(chatId, true)
                            },
                            peerUsers = conversationPeerUsers,
                            peerRemarks = remember(contacts) { com.virjar.tk.app.ui.screen.contactRemarks(contacts) },
                            groupMembers = groupAvatarMembers,
                            loadMessagePreview = dataState.conversationViewModel::messagePreview,
                        )
                    }
                    MainTab.CONTACTS -> Column(modifier = Modifier.fillMaxSize()) {
                        DirectoryScreen(
                            contacts = contacts,
                            friendPresenceByUid = friendPresenceByUid,
                            units = dataState.organization.units,
                            members = dataState.organization.members,
                            selectedUnitId = dataState.organization.selectedUnitId,
                            organizationInitialized = dataState.organization.initialized,
                            organizationUnitSnapshotKnown = dataState.organization.unitSnapshotKnown,
                            organizationLoading = dataState.organization.loading,
                            organizationMemberSnapshotKnown = dataState.organization.memberSnapshotKnown,
                            organizationMembersLoading = dataState.organization.membersLoading,
                            organizationAccessRevoked = dataState.organization.accessRevoked,
                            onUnitClick = { unitId ->
                                dataState.launchAdmittedUiAction {
                                    dataState.organization.selectUnit(unitId)
                                }
                            },
                            onGroupClick = actionAdmission.guard { chatId, _ ->
                                onConversationClick(chatId)
                            },
                            onUserClick = actionAdmission.guard(::onUserProfile),
                            modifier = Modifier.weight(1f),
                            pendingApplyCount = pendingApplyCount,
                            onFriendApplies = actionAdmission.guard(::onFriendApplies),
                        )
                    }
                    MainTab.DOCUMENTS -> IosDocumentWorkspaceHost(
                        onEditingActive = { documentEditingActive.value = it },
                        ui = ui,
                        mobileExitCoordinator = documentExitCoordinator,
                        // 文档首页再返回时回到应用一级会话页，不直接关闭应用。
                        onExitDocuments = actionAdmission.guard {
                            navigation.homeTab = MainTab.CONVERSATIONS
                        },
                    )
                    MainTab.TASKS -> {
                        IosTaskWorkspaceHost(ui,
                            onOpenDocument = { reference ->
                                navigation.requestedDocument = reference
                                navigation.homeTab = MainTab.DOCUMENTS
                            })
                    }
                    MainTab.SETTINGS -> MeScreen(
                        currentUser = dataState.account.currentUser,
                        onLogout = actionAdmission.guard(onLogout),
                        onEditProfile = actionAdmission.guard(::onEditProfile),
                        onChangePassword = actionAdmission.guard(::onChangePassword),
                        onDeviceManagement = actionAdmission.guard(::onDevices),
                        onBlacklist = actionAdmission.guard(::onBlacklist),
                        onLocalStorage = actionAdmission.guard(::onLocalStorage),
                        onCheckAppUpgrade = null,
                        onNotificationSettings = { openIosUrl(platform.UIKit.UIApplicationOpenSettingsURLString) },
                        buildInfoText = "${ClientBuildConfig.APP_VERSION} · ${ClientBuildConfig.BUILD_IDENTITY}",
                    )
                }
            }
        }
    }
}
