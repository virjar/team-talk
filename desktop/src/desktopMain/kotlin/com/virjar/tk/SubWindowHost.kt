package com.virjar.tk

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.virjar.tk.client.ClientSession
import com.virjar.tk.ui.AppTheme
import com.virjar.tk.ui.screen.SearchUsersScreen
import com.virjar.tk.ui.screen.SearchMessagesScreen
import com.virjar.tk.ui.screen.UserProfileScreen
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 子窗口宿主。新窗口类子页面（独立临时任务）的窗口创建与销毁。
 * 从 MainAppContent 拆出以控制文件规模。
 */
@Composable
internal fun SubWindow(
    screen: SubScreen,
    appState: AppState,
    onClose: () -> Unit,
) {
    val (title, width, height) = when (screen) {
        SubScreen.EditProfile -> Triple("编辑资料", 400.dp, 360.dp)
        SubScreen.ChangePassword -> Triple("修改密码", 400.dp, 380.dp)
        SubScreen.Devices -> Triple("设备管理", 500.dp, 500.dp)
        SubScreen.Blacklist -> Triple("黑名单", 450.dp, 500.dp)
        SubScreen.FriendApplies -> Triple("好友申请", 450.dp, 500.dp)
        SubScreen.SearchUsers -> Triple("搜索用户", 500.dp, 560.dp)
        SubScreen.CreateGroup -> Triple("创建群组", 450.dp, 560.dp)
        SubScreen.SearchMessages -> Triple("搜索消息", 500.dp, 560.dp)
        SubScreen.Forward -> Triple("转发到", 400.dp, 500.dp)
        else -> Triple("TeamTalk", 450.dp, 500.dp)
    }
    val shortId = screen::class.simpleName ?: "subwindow"
    Window(
        onCloseRequest = {
            TestServiceBridge.unregisterWindow(shortId)
            onClose()
        },
        title = title,
        state = rememberWindowState(width = width, height = height),
    ) {
        TestServiceBridge.registerWindowWithId(shortId, window)
        // 子窗口也带 TeamTalk 图标（与主窗口一致）
        setTeamTalkIcon()
        AppTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                SubWindowContent(screen, appState, onClose)
            }
        }
    }
}

/**
 * 新窗口内容：独立局部导航，不碰 appState.currentScreen。
 */
@Composable
private fun SubWindowContent(
    initialScreen: SubScreen,
    appState: AppState,
    onClose: () -> Unit,
) {
    var localScreen by remember { mutableStateOf<SubScreen>(initialScreen) }
    val scope = rememberCoroutineScope()
    val contacts by appState.contactViewModel.contacts.collectAsState()
    val conversations by appState.conversationViewModel.conversations.collectAsState()

    LaunchedEffect(localScreen) {
        appState.loadScreenData(localScreen)
    }

    when (localScreen) {
        is SubScreen.SearchUsers -> SearchUsersScreen(
            searchUsers = { query -> try { appState.userRepo.search(query).getOrThrow() } catch (e: Exception) { appState.handleError(e, "搜索失败"); emptyList() } },
            onUserClick = { uid -> appState.selectedProfileUid = uid; localScreen = SubScreen.UserProfile },
            onBack = null,
        )

        is SubScreen.UserProfile -> {
            var hasPendingApply by remember { mutableStateOf(false) }
            LaunchedEffect(appState.selectedProfileUid) { hasPendingApply = false }
            UserProfileScreen(
                user = appState.profileUser,
                isFriend = appState.isFriend,
                hasPendingApply = hasPendingApply,
                onAddFriend = {
                    appState.contactViewModel.apply(appState.selectedProfileUid ?: return@UserProfileScreen)
                    hasPendingApply = true
                },
                onSendMessage = {
                    scope.launch {
                        try {
                            val uid = appState.selectedProfileUid ?: return@launch
                            val chat = appState.chatRepo.createPersonalChat(uid).getOrThrow()
                            appState.openChat(chat.chatId, appState.profileUser?.name ?: uid.take(12))
                            onClose()
                        } catch (e: Exception) { appState.handleError(e, "创建聊天失败") }
                    }
                },
                onDeleteFriend = {
                    val uid = appState.selectedProfileUid ?: return@UserProfileScreen
                    appState.contactViewModel.deleteFriend(uid)
                    onClose()
                },
                onBack = null,
            )
        }

        is SubScreen.SearchMessages -> SearchMessagesScreen(
            searchMessages = { query -> try { appState.messageRepo.searchMessages("", query).getOrThrow() } catch (e: Exception) { appState.handleError(e, "搜索失败"); emptyList() } },
            onMessageClick = { chatId, _ ->
                val conv = conversations.find { it.chatId == chatId }
                appState.openChat(chatId, conv?.chatName ?: chatId.take(16))
                onClose()
            },
            onBack = null,
        )

        else -> SubScreenRouter(
            appState = appState,
            backTarget = { _ -> null },
            onOpenChatAndDismiss = onClose,
            onLeaveGroup = onClose,
            desktopPanelMode = true,
        )
    }
}
