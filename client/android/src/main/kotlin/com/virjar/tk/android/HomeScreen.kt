package com.virjar.tk.android

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.virjar.tk.app.navigation.AppDataState
import com.virjar.tk.app.navigation.MainTab
import com.virjar.tk.protocol.body.OfficeRefBody
import kotlinx.coroutines.flow.MutableStateFlow
import com.virjar.tk.app.ui.screen.DirectoryScreen
import com.virjar.tk.app.ui.screen.ConversationListScreen
import com.virjar.tk.app.ui.screen.DocumentWorkspaceHost
import com.virjar.tk.app.ui.screen.MobileDocumentExitCoordinator
import com.virjar.tk.app.ui.screen.MeScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    dataState: AppDataState,
    resourceOwner: AndroidAuthenticatedResourceOwner,
    launchAdmittedAction: (suspend () -> Unit) -> Boolean,
    onSelectedTabChanged: (MainTab) -> Unit = {},
    onLogout: () -> Unit,
    onConversationClick: (String) -> Unit,
    onGlobalSearch: () -> Unit,
    onFriendApplies: () -> Unit,
    onUserProfile: (String) -> Unit,
    onEditProfile: () -> Unit,
    onChangePassword: () -> Unit,
    onDevices: () -> Unit,
    onBlacklist: () -> Unit,
    onLocalStorage: () -> Unit,
    /** 聊天引用交给首页消费的完整目标；打开后清空，避免下次返回首页再次跳转。 */
    requestedDocument: MutableStateFlow<OfficeRefBody?>,
    requestedTask: MutableStateFlow<String?>,
) {
    if (!dataState.acceptsRendering) return
    val processOwner = androidx.compose.ui.platform.LocalContext.current.applicationContext as TeamTalkApp
    val pushSettings = processOwner.oemPush
    var showNotifications by remember { mutableStateOf(pushSettings.needsConsent) }
    var showAppUpgrade by remember { mutableStateOf(false) }
    if (showNotifications) AndroidOemPushDialog(pushSettings) { showNotifications = false }
    if (showAppUpgrade) {
        AndroidUpgradeDialog(serverBaseUrl = BuildConfig.SERVER_BASE_URL) { showAppUpgrade = false }
    }
    val actionAdmission = dataState.uiActionAdmission
    var homeTab by rememberSaveable { mutableIntStateOf(0) }
    val documentReference by requestedDocument.collectAsState()
    val taskReference by requestedTask.collectAsState()
    // 从聊天返回首页时，引用目标优先于上次保存的栏目。消费后仍留在文档，不触发第二次初始化。
    val selectedTab = when {
        taskReference != null -> MainTab.TASKS.ordinal
        documentReference != null -> MainTab.DOCUMENTS.ordinal
        else -> homeTab
    }
    val conversations by dataState.conversationViewModel.conversations.collectAsState()
    val mentionedChatIds by dataState.mentionedChatIds.collectAsState()
    val conversationPeerUsers by dataState.conversationViewModel.peerUsers.collectAsState()
    val groupAvatarMembers by dataState.conversationViewModel.groupAvatarMembers.collectAsState()
    val contacts by dataState.contactViewModel.contacts.collectAsState()
    val friendPresenceByUid by dataState.contactViewModel.friendPresenceByUid.collectAsState()
    val pendingApplyCount by dataState.contactViewModel.pendingApplyCount.collectAsState()
    val documentExitCoordinator = remember { MobileDocumentExitCoordinator() }

    // 切换标签时刷新待处理申请数
    LaunchedEffect(selectedTab, taskReference) {
        onSelectedTabChanged(MainTab.entries[selectedTab])
        dataState.runAdmittedUiAction(actionAdmission, onClosed = {}) {
            when (MainTab.entries[selectedTab]) {
                MainTab.CONTACTS -> {
                    dataState.contactViewModel.refreshPendingApplyCount()
                    dataState.organization.refresh()
                }
                MainTab.DOCUMENTS -> {
                    val reference = requestedDocument.value
                    // open() 会回到文档首页；初始化与打开目标必须按顺序执行。
                    dataState.documents.open()
                    if (reference != null) {
                        dataState.documents.openDocumentRef(reference.spaceId, reference.targetId)
                        homeTab = MainTab.DOCUMENTS.ordinal
                        requestedDocument.compareAndSet(reference, null)
                    }
                }
                MainTab.TASKS -> {
                    dataState.tasks.open()
                    requestedTask.value?.let { taskId ->
                        dataState.tasks.openTask(taskId)
                        homeTab = MainTab.TASKS.ordinal
                        requestedTask.compareAndSet(taskId, null)
                    }
                }
                else -> Unit
            }
        }
    }

    // 选中用面性（filled）、未选中用线性（outlined），同一功能两套配对图标（T009）。
    data class TabIcon(val filled: ImageVector, val outlined: ImageVector, val label: String)
    val tabIcons = listOf(
        TabIcon(Icons.AutoMirrored.Filled.Chat, Icons.AutoMirrored.Outlined.Chat, "会话"),
        TabIcon(Icons.Filled.Contacts, Icons.Outlined.Contacts, "通讯录"),
        TabIcon(Icons.Filled.Description, Icons.Outlined.Description, "文档"),
        TabIcon(Icons.Filled.Assignment, Icons.Outlined.Assignment, "任务"),
        TabIcon(Icons.Filled.Settings, Icons.Outlined.Settings, "设置"),
    )

    Scaffold(
        modifier = Modifier.testTag("main.home"),
        topBar = {
            // 文档拥有自己的首页/空间标题栏；叠加通用 TopAppBar 会形成两个页面标题。
            if (MainTab.entries[selectedTab] !in setOf(MainTab.DOCUMENTS, MainTab.TASKS)) {
                TopAppBar(
                    title = { Text(tabIcons[selectedTab].label) },
                    actions = {
                        IconButton(
                            onClick = actionAdmission.guard(onGlobalSearch),
                            modifier = Modifier.testTag("action.search"),
                        ) {
                            Icon(Icons.Filled.Search, contentDescription = "全局搜索")
                        }
                    },
                )
            }
        },
        bottomBar = {
            NavigationBar {
                tabIcons.forEachIndexed { index, tab ->
                    val selected = selectedTab == index
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            actionAdmission.runIfOpen {
                                if (MainTab.entries[selectedTab] == MainTab.DOCUMENTS && index != selectedTab) {
                                    documentExitCoordinator.requestExit { homeTab = index }
                                } else {
                                    homeTab = index
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
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (MainTab.entries[selectedTab]) {
                MainTab.CONVERSATIONS -> ConversationListScreen(
                    conversations = conversations,
                    mentionedChatIds = mentionedChatIds,
                    onConversationClick = actionAdmission.guard(onConversationClick),
                    onPinClick = actionAdmission.guard(dataState.conversationViewModel::setPinned),
                    onMuteClick = actionAdmission.guard(dataState.conversationViewModel::setMuted),
                    onMarkRead = actionAdmission.guard { chatId: String, lastSeq: Long ->
                        dataState.markConversationRead(chatId, lastSeq)
                    },
                    peerUsers = conversationPeerUsers,
                    peerRemarks = remember(contacts) { com.virjar.tk.app.ui.screen.contactRemarks(contacts) },
                    groupMembers = groupAvatarMembers,
                    loadMessagePreview = dataState.conversationViewModel::messagePreview,
                )
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
                        onUserClick = actionAdmission.guard(onUserProfile),
                        modifier = Modifier.weight(1f),
                        pendingApplyCount = pendingApplyCount,
                        onFriendApplies = actionAdmission.guard(onFriendApplies),
                    )
                }
                MainTab.DOCUMENTS -> AndroidDocumentWorkspaceHost(
                    dataState = dataState,
                    resourceOwner = resourceOwner,
                    launchAdmittedAction = launchAdmittedAction,
                    mobileExitCoordinator = documentExitCoordinator,
                    // 文档首页再返回时回到应用一级会话页，不直接退出 Activity。
                    onExitDocuments = actionAdmission.guard {
                        homeTab = MainTab.CONVERSATIONS.ordinal
                    },
                )
                MainTab.TASKS -> {
                    androidx.activity.compose.BackHandler {
                        actionAdmission.runIfOpen {
                            if (!dataState.tasks.handleBack()) homeTab = MainTab.CONVERSATIONS.ordinal
                        }
                    }
                    com.virjar.tk.app.ui.screen.TaskWorkspaceScreen(
                        feature = dataState.tasks,
                        actionAdmission = actionAdmission,
                        compactMode = true,
                    )
                }
                MainTab.SETTINGS -> MeScreen(
                    currentUser = dataState.account.currentUser,
                    onLogout = actionAdmission.guard(onLogout),
                    onEditProfile = actionAdmission.guard(onEditProfile),
                    onChangePassword = actionAdmission.guard(onChangePassword),
                    onDeviceManagement = actionAdmission.guard(onDevices),
                    onBlacklist = actionAdmission.guard(onBlacklist),
                    onLocalStorage = actionAdmission.guard(onLocalStorage),
                    onCheckAppUpgrade = { showAppUpgrade = true },
                    onNotificationSettings = if (pushSettings.available) {
                        actionAdmission.guard { showNotifications = true }
                    } else null,
                    buildInfoText = "Git: ${com.virjar.tk.android.BuildConfig.BUILD_IDENTITY.substringAfter('+').take(8)}" +
                        "${if (com.virjar.tk.android.BuildConfig.BUILD_IDENTITY.endsWith(".dirty")) "-dirty" else ""}" +
                        "  |  Build: ${com.virjar.tk.android.BuildConfig.BUILD_TIME}",
                )
            }
        }
    }
}
