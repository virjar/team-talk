package com.virjar.tk

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import com.virjar.tk.navigation.AppDataState
import com.virjar.tk.navigation.MainTab
import com.virjar.tk.ui.screen.DirectoryScreen
import com.virjar.tk.ui.screen.ConversationListScreen
import com.virjar.tk.ui.screen.MeScreen
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    dataState: AppDataState,
    onLogout: () -> Unit,
    onConversationClick: (String, String, Int) -> Unit,
    onGlobalSearch: () -> Unit,
    onFriendApplies: () -> Unit,
    onUserProfile: (String) -> Unit,
    onEditProfile: () -> Unit,
    onChangePassword: () -> Unit,
    onDevices: () -> Unit,
    onBlacklist: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val conversations by dataState.conversationViewModel.conversations.collectAsState()
    val contacts by dataState.contactViewModel.contacts.collectAsState()
    val pendingApplyCount by dataState.contactViewModel.pendingApplyCount.collectAsState()
    val directoryScope = rememberCoroutineScope()

    // 切换标签时刷新待处理申请数
    LaunchedEffect(selectedTab) {
        if (selectedTab == 1) { // 通讯录
            dataState.contactViewModel.refreshPendingApplyCount()
            dataState.organization.refresh()
        }
    }

    val tabIcons: List<Pair<ImageVector, String>> = listOf(
        Icons.Filled.Chat to "会话",
        Icons.Filled.Contacts to "通讯录",
        Icons.Filled.Settings to "设置",
    )

    Scaffold(
        modifier = Modifier.testTag("main.home"),
        topBar = {
            TopAppBar(
                title = { Text(tabIcons[selectedTab].second) },
                actions = {
                    IconButton(onClick = onGlobalSearch, modifier = Modifier.testTag("action.search")) {
                        Icon(Icons.Filled.Search, contentDescription = "全局搜索")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                tabIcons.forEachIndexed { index, (icon, label) ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
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
                    onConversationClick = { chatId ->
                        val conv = conversations.find { it.chatId == chatId }
                        onConversationClick(chatId, conv?.chatName ?: chatId.take(16), conv?.chatType ?: 1)
                    },
                    onPinClick = { chatId, pinned -> dataState.session.localCache.toggleConversationPin(chatId, pinned) },
                    onMarkRead = { chatId, lastSeq ->
                        dataState.session.localCache.markConversationRead(chatId, lastSeq)
                    },
                )
                MainTab.CONTACTS -> Column(modifier = Modifier.fillMaxSize()) {
                    DirectoryScreen(
                        contacts = contacts,
                        units = dataState.organization.units,
                        members = dataState.organization.members,
                        selectedUnitId = dataState.organization.selectedUnitId,
                        organizationLoading = dataState.organization.loading,
                        onUnitClick = { unitId -> directoryScope.launch { dataState.organization.selectUnit(unitId) } },
                        onUserClick = onUserProfile,
                        modifier = Modifier.weight(1f),
                        pendingApplyCount = pendingApplyCount,
                        onFriendApplies = onFriendApplies,
                    )
                }
                MainTab.SETTINGS -> MeScreen(
                    currentUser = dataState.account.currentUser,
                    onLogout = onLogout,
                    onEditProfile = onEditProfile,
                    onChangePassword = onChangePassword,
                    onDeviceManagement = onDevices,
                    onBlacklist = onBlacklist,
                    buildInfoText = "Git: ${com.virjar.tk.android.BuildConfig.GIT_COMMIT_ID.take(8)}  |  Build: ${com.virjar.tk.android.BuildConfig.BUILD_TIME}",
                )
            }
        }
    }
}
