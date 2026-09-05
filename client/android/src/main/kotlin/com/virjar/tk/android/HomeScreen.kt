package com.virjar.tk.android

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
    /** 聊天引用交给首页消费的完整目标；打开后清空，避免下次返回首页再次跳转。 */
    requestedDocument: MutableStateFlow<OfficeRefBody?>,
) {
    if (!dataState.acceptsRendering) return
    val actionAdmission = dataState.uiActionAdmission
    var homeTab by rememberSaveable { mutableIntStateOf(0) }
    val documentReference by requestedDocument.collectAsState()
    // 从聊天返回首页时，引用目标优先于上次保存的栏目。消费后仍留在文档，不触发第二次初始化。
    val selectedTab = if (documentReference != null) MainTab.DOCUMENTS.ordinal else homeTab
    val conversations by dataState.conversationViewModel.conversations.collectAsState()
    val conversationPeerUsers by dataState.conversationViewModel.peerUsers.collectAsState()
    val contacts by dataState.contactViewModel.contacts.collectAsState()
    val friendPresenceByUid by dataState.contactViewModel.friendPresenceByUid.collectAsState()
    val pendingApplyCount by dataState.contactViewModel.pendingApplyCount.collectAsState()
    val documentExitCoordinator = remember { MobileDocumentExitCoordinator() }

    // 切换标签时刷新待处理申请数
    LaunchedEffect(selectedTab) {
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
                else -> Unit
            }
        }
    }

    val tabIcons: List<Pair<ImageVector, String>> = listOf(
        Icons.AutoMirrored.Filled.Chat to "会话",
        Icons.Filled.Contacts to "通讯录",
        Icons.Filled.Description to "文档",
        Icons.Filled.Settings to "设置",
    )

    Scaffold(
        modifier = Modifier.testTag("main.home"),
        topBar = {
            // 文档拥有自己的首页/空间标题栏；叠加通用 TopAppBar 会形成两个页面标题。
            if (MainTab.entries[selectedTab] != MainTab.DOCUMENTS) {
                TopAppBar(
                    title = { Text(tabIcons[selectedTab].second) },
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
                tabIcons.forEachIndexed { index, (icon, label) ->
                    NavigationBarItem(
                        selected = selectedTab == index,
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
                            if (label == "通讯录" && pendingApplyCount > 0) {
                                BadgedBox(badge = { Badge { Text("$pendingApplyCount") } }) {
                                    Icon(icon, contentDescription = label)
                                }
                            } else {
                                Icon(icon, contentDescription = label)
                            }
                        },
                        modifier = Modifier.testTag("nav.${label}"),
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (MainTab.entries[selectedTab]) {
                MainTab.CONVERSATIONS -> ConversationListScreen(
                    conversations = conversations,
                    onConversationClick = actionAdmission.guard(onConversationClick),
                    onPinClick = actionAdmission.guard(dataState.conversationViewModel::setPinned),
                    onMuteClick = actionAdmission.guard(dataState.conversationViewModel::setMuted),
                    onMarkRead = actionAdmission.guard { chatId: String, lastSeq: Long ->
                        dataState.markConversationRead(chatId, lastSeq)
                    },
                    peerUsers = conversationPeerUsers,
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
                MainTab.SETTINGS -> MeScreen(
                    currentUser = dataState.account.currentUser,
                    onLogout = actionAdmission.guard(onLogout),
                    onEditProfile = actionAdmission.guard(onEditProfile),
                    onChangePassword = actionAdmission.guard(onChangePassword),
                    onDeviceManagement = actionAdmission.guard(onDevices),
                    onBlacklist = actionAdmission.guard(onBlacklist),
                    buildInfoText = "Git: ${com.virjar.tk.android.BuildConfig.BUILD_IDENTITY.substringAfter('+').take(8)}" +
                        "${if (com.virjar.tk.android.BuildConfig.BUILD_IDENTITY.endsWith(".dirty")) "-dirty" else ""}" +
                        "  |  Build: ${com.virjar.tk.android.BuildConfig.BUILD_TIME}",
                )
            }
        }
    }
}
