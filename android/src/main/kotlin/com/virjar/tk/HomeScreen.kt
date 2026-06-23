package com.virjar.tk

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.virjar.tk.navigation.AppDataState
import com.virjar.tk.navigation.MainTab
import com.virjar.tk.ui.component.AvatarPlaceholder
import com.virjar.tk.ui.screen.ConversationListScreen
import com.virjar.tk.ui.screen.MeScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    dataState: AppDataState,
    onLogout: () -> Unit,
    onConversationClick: (String, String, Int) -> Unit,
    onSearchMessages: () -> Unit,
    onSearchUsers: () -> Unit,
    onCreateGroup: () -> Unit,
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

    // 切换标签时刷新待处理申请数
    LaunchedEffect(selectedTab) {
        if (selectedTab == 1) { // 通讯录
            dataState.contactViewModel.refreshPendingApplyCount()
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
                    when (MainTab.entries[selectedTab]) {
                        MainTab.CONVERSATIONS -> {
                            IconButton(onClick = onSearchMessages, modifier = Modifier.testTag("action.search")) { Icon(Icons.Filled.Search, contentDescription = "搜索") }
                            IconButton(onClick = onCreateGroup, modifier = Modifier.testTag("action.createGroup")) { Icon(Icons.Filled.GroupAdd, contentDescription = "发起群聊") }
                            IconButton(onClick = onSearchUsers, modifier = Modifier.testTag("action.addFriend")) { Icon(Icons.Filled.PersonAdd, contentDescription = "添加好友") }
                        }
                        MainTab.CONTACTS -> {
                            if (pendingApplyCount > 0) {
                                BadgedBox(
                                    badge = { Badge { Text("$pendingApplyCount") } },
                                    modifier = Modifier.testTag("action.friendApplies"),
                                ) {
                                    IconButton(onClick = onFriendApplies) {
                                        Icon(Icons.Filled.PersonAdd, contentDescription = "好友申请")
                                    }
                                }
                            } else {
                                IconButton(onClick = onFriendApplies, modifier = Modifier.testTag("action.friendApplies")) {
                                    Icon(Icons.Filled.PersonAdd, contentDescription = "好友申请")
                                }
                            }
                        }
                        MainTab.SETTINGS -> {}
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
                )
                MainTab.CONTACTS -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    // 待处理好友申请入口（有申请时展示在列表顶部）
                    if (pendingApplyCount > 0) {
                        item(key = "pending_applies_header") {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.fillMaxWidth().clickable { onFriendApplies() },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Badge(containerColor = MaterialTheme.colorScheme.error) {
                                        Text("$pendingApplyCount")
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        "$pendingApplyCount 条好友申请",
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                            }
                        }
                    }
                    items(contacts, key = { it.friendUid }) { contact ->
                        val user = contact.user
                        val remark = contact.remark
                        val displayName = remark ?: user?.name ?: user?.username ?: contact.friendUid
                        val subName = if (remark != null && user?.name != null) user.name else user?.username
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onUserProfile(contact.friendUid) }
                                .testTag("contact.${contact.friendUid.take(8)}")
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AvatarPlaceholder(name = displayName, size = 44)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (subName != null) {
                                    Text(
                                        subName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 72.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
                MainTab.SETTINGS -> MeScreen(
                    currentUser = dataState.currentUser,
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
