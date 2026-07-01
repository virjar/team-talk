package com.virjar.tk

import androidx.compose.runtime.*
import com.virjar.tk.ui.screen.*
import kotlinx.coroutines.launch

/**
 * 共享子屏幕路由。处理通用子屏幕的类型安全路由。
 *
 * @param appState 共享状态
 * @param backTarget 当子屏幕返回时，返回目标屏幕（null = 回到主布局）
 * @param onOpenChatAndDismiss 从子屏幕打开聊天并关闭当前子屏幕
 * @param onLeaveGroup 离开群组的完整回调（包含导航和清理）
 * @param desktopPanelMode Desktop 面板模式：为 true 时子页面不渲染返回按钮（onBack=null），
 *        桌面面板靠 ESC 或外部交互关闭，不存在返回导航
 */
@Composable
fun SubScreenRouter(
    appState: AppState,
    backTarget: (fromScreen: SubScreen) -> SubScreen?,
    onOpenChatAndDismiss: () -> Unit,
    onLeaveGroup: () -> Unit,
    desktopPanelMode: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val contacts by appState.contactViewModel.contacts.collectAsState()
    val conversations by appState.conversationViewModel.conversations.collectAsState()

    LaunchedEffect(appState.currentScreen) {
        appState.loadScreenData(appState.currentScreen)
    }

    fun navBack(from: SubScreen) {
        appState.currentScreen = backTarget(from)
    }

    // desktopPanelMode 时所有子页面 onBack = null（无返回按钮），靠 ESC/外部关闭
    fun backOrNull(from: SubScreen): (() -> Unit)? =
        if (desktopPanelMode) null else { { navBack(from) } }

    // 显示错误 SnackBar
    appState.error?.let { errorMsg ->
        LaunchedEffect(errorMsg) {
            // 平台层需要消费 error 显示 SnackBar，此处只做自动清除
            kotlinx.coroutines.delay(3000)
            appState.clearError()
        }
    }

    when (val screen = appState.currentScreen) {
        is SubScreen.Devices -> DeviceManagementScreen(
            devices = appState.devices.map { DeviceInfo(it.deviceId, it.deviceName ?: "", it.deviceModel ?: "", it.lastLogin) },
            onKick = { deviceId -> appState.kickDevice(deviceId) },
            onBack = backOrNull(screen),
        )

        is SubScreen.Blacklist -> BlacklistScreen(
            blockedUsers = appState.blockedContacts.map { BlockedUser(it.friendUid, it.user?.name ?: it.friendUid) },
            onUnblock = { uid -> appState.unblockContact(uid) },
            onBack = backOrNull(screen),
        )

        is SubScreen.EditProfile -> EditProfileScreen(
            currentUser = appState.currentUser,
            onSave = { name, _ -> appState.saveProfile(name, null) },
            onBack = backOrNull(screen),
        )

        is SubScreen.ChangePassword -> ChangePasswordScreen(
            onChangePassword = { old, new -> appState.changePassword(old, new) },
            onBack = backOrNull(screen),
        )

        is SubScreen.FriendApplies -> FriendAppliesScreen(
            applies = appState.applies,
            onAccept = { token -> appState.acceptFriendApply(token) },
            onReject = { token -> appState.rejectFriendApply(token) },
            onBack = backOrNull(screen),
        )

        is SubScreen.SearchUsers -> SearchUsersScreen(
            searchUsers = { query -> appState.searchUsers(query) },
            onUserClick = { uid -> appState.selectedProfileUid = uid; appState.currentScreen = SubScreen.UserProfile },
            onBack = backOrNull(screen),
        )

        is SubScreen.CreateGroup -> CreateGroupScreen(
            contacts = contacts,
            onCreateGroup = { name, uids ->
                val chatId = appState.createGroup(name, uids)
                if (chatId != null) {
                    appState.openChat(chatId, name, 2)
                    onOpenChatAndDismiss()
                    Result.success(chatId)
                } else Result.failure(Exception("创建失败"))
            },
            onBack = backOrNull(screen),
        )

        is SubScreen.GroupDetail -> {
            val groupChatId = appState.selectedGroupChatId ?: ""
            GroupDetailScreen(
                chat = appState.groupDetailChat,
                members = appState.groupMembers,
                isOwner = appState.groupMembers.any { it.uid == appState.userSession.uid && it.role == 2 },
                myUid = appState.userSession.uid,
                onMemberClick = { uid -> appState.selectedProfileUid = uid; appState.currentScreen = SubScreen.UserProfile },
                onInviteMembers = { appState.currentScreen = SubScreen.InviteMembers },
                onViewInviteLinks = { appState.currentScreen = SubScreen.InviteLinks },
                onLeaveGroup = onLeaveGroup,
                onEditNotice = { notice -> appState.updateGroupNotice(groupChatId, notice) },
                onBack = backOrNull(screen),
                onSetAdmin = { uid -> appState.setMemberRole(groupChatId, uid, 1) },
                onRemoveAdmin = { uid -> appState.setMemberRole(groupChatId, uid, 0) },
                onMuteMember = { uid -> appState.muteMember(groupChatId, uid) },
                onUnmuteMember = { uid -> appState.unmuteMember(groupChatId, uid) },
                onRemoveMember = { uid -> appState.removeMember(groupChatId, uid) },
            )
        }

        is SubScreen.InviteMembers -> InviteMembersScreen(
            friendUids = contacts.map { it.friendUid },
            friendNames = contacts.associate { it.friendUid to (it.remark ?: it.user?.name ?: it.friendUid) },
            onInvite = { uids ->
                val chatId = appState.selectedGroupChatId ?: return@InviteMembersScreen false
                appState.inviteMembers(chatId, uids)
            },
            onBack = backOrNull(screen) ?: { appState.currentScreen = SubScreen.GroupDetail },
        )

        is SubScreen.InviteLinks -> InviteLinksScreen(
            links = appState.inviteLinks.map { InviteLink(it.token, it.maxUses, it.useCount, it.revokedAt > 0) },
            onCreateLink = {
                val chatId = appState.selectedGroupChatId ?: return@InviteLinksScreen null
                appState.createInviteLink(chatId)
            },
            onRevokeLink = { token ->
                val chatId = appState.selectedGroupChatId ?: return@InviteLinksScreen
                appState.revokeInviteLink(chatId, token)
            },
            onBack = backOrNull(screen) ?: { appState.currentScreen = SubScreen.GroupDetail },
        )

        is SubScreen.UserProfile -> {
            // 局部状态：apply 后即时反馈「已申请」，避免硬编码 false
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
                onSendMessage = { scope.launch {
                    val uid = appState.selectedProfileUid ?: return@launch
                    val chatId = appState.startPersonalChat(uid)
                    if (chatId != null) {
                        appState.openChat(chatId, appState.profileUser?.name ?: uid.take(12))
                        onOpenChatAndDismiss()
                    }
                }},
                onDeleteFriend = {
                    val uid = appState.selectedProfileUid ?: return@UserProfileScreen
                    appState.contactViewModel.deleteFriend(uid)
                    backOrNull(screen)?.invoke()
                },
                onBack = backOrNull(screen),
            )
        }

        is SubScreen.Forward -> ForwardScreen(
            conversations = conversations,
            onForward = { targetChatId ->
                val msg = appState.forwardMessage ?: return@ForwardScreen false
                appState.forwardMessage(msg.chatId, msg.serverSeq, targetChatId)
            },
            onBack = backOrNull(screen) ?: { appState.forwardMessage = null; navBack(screen) },
        )

        is SubScreen.SearchMessages -> SearchMessagesScreen(
            searchMessages = { query -> appState.searchMessages(query) },
            onMessageClick = { chatId, _ ->
                val conv = conversations.find { it.chatId == chatId }
                appState.openChat(chatId, conv?.chatName ?: chatId.take(16))
                onOpenChatAndDismiss()
            },
            onBack = backOrNull(screen),
        )

        null -> { /* 主布局，不由 SubScreenRouter 处理 */ }
    }
}
