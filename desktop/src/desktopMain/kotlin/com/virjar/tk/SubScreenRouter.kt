package com.virjar.tk

import androidx.compose.runtime.*
import com.virjar.tk.AppError
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
            onKick = { deviceId ->
                scope.launch {
                    try { appState.deviceRepo.kickDevice(deviceId).getOrThrow(); appState.devices = appState.deviceRepo.listDevices().getOrThrow() } catch (e: AppError) { appState.handleError(e, "踢出设备失败") }
                }
            },
            onBack = backOrNull(screen),
        )

        is SubScreen.Blacklist -> BlacklistScreen(
            blockedUsers = appState.blockedContacts.map { BlockedUser(it.friendUid, it.user?.name ?: it.friendUid) },
            onUnblock = { uid ->
                scope.launch {
                    try { appState.contactRepo.removeFromBlacklist(uid).getOrThrow(); appState.blockedContacts = appState.contactRepo.listBlacklist().getOrThrow() } catch (e: AppError) { appState.handleError(e, "移出黑名单失败") }
                }
            },
            onBack = backOrNull(screen),
        )

        is SubScreen.EditProfile -> EditProfileScreen(
            currentUser = appState.currentUser,
            onSave = { name, _ ->
                try { appState.userRepo.updateProfile(name = name).getOrThrow(); true } catch (e: AppError) { appState.handleError(e, "保存失败"); false }
            },
            onBack = backOrNull(screen),
        )

        is SubScreen.ChangePassword -> ChangePasswordScreen(
            onChangePassword = { old, new ->
                try { appState.userRepo.changePassword(old, new).getOrThrow() } catch (e: AppError) { appState.handleError(e, "修改密码失败"); false }
            },
            onBack = backOrNull(screen),
        )

        is SubScreen.FriendApplies -> FriendAppliesScreen(
            applies = appState.applies,
            onAccept = { token ->
                try { appState.contactRepo.accept(token).getOrThrow(); appState.applies = appState.contactRepo.listApplies().getOrThrow() } catch (e: AppError) { appState.handleError(e, "接受申请失败") }
            },
            onReject = { token ->
                try { appState.contactRepo.reject(token).getOrThrow(); appState.applies = appState.contactRepo.listApplies().getOrThrow() } catch (e: AppError) { appState.handleError(e, "拒绝申请失败") }
            },
            onBack = backOrNull(screen),
        )

        is SubScreen.SearchUsers -> SearchUsersScreen(
            searchUsers = { query -> try { appState.userRepo.search(query).getOrThrow() } catch (e: AppError) { appState.handleError(e, "搜索失败"); emptyList() } },
            onUserClick = { uid -> appState.selectedProfileUid = uid; appState.currentScreen = SubScreen.UserProfile },
            onBack = backOrNull(screen),
        )

        is SubScreen.CreateGroup -> CreateGroupScreen(
            contacts = contacts,
            onCreateGroup = { name, uids ->
                try {
                    val chat = appState.chatRepo.createGroup(name, memberUids = uids).getOrThrow()
                    appState.openChat(chat.chatId, name, 2)
                    onOpenChatAndDismiss()
                    Result.success(chat.chatId)
                } catch (e: AppError) { appState.handleError(e, "创建群组失败"); Result.failure(e) }
            },
            onBack = backOrNull(screen),
        )

        is SubScreen.GroupDetail -> GroupDetailScreen(
            chat = appState.groupDetailChat,
            members = appState.groupMembers,
            isOwner = appState.groupMembers.any { it.uid == appState.userSession.uid && it.role == 2 },
            myUid = appState.userSession.uid,
            onMemberClick = { uid -> appState.selectedProfileUid = uid; appState.currentScreen = SubScreen.UserProfile },
            onInviteMembers = { appState.currentScreen = SubScreen.InviteMembers },
            onViewInviteLinks = { appState.currentScreen = SubScreen.InviteLinks },
            onLeaveGroup = onLeaveGroup,
            onEditNotice = { notice -> scope.launch { appState.chatRepo.updateGroup(appState.selectedGroupChatId ?: "", notice = notice); appState.loadScreenData(SubScreen.GroupDetail) } },
            onBack = backOrNull(screen),
            onSetAdmin = { uid -> scope.launch { appState.chatRepo.setMemberRole(appState.selectedGroupChatId ?: "", uid, 1); appState.loadScreenData(SubScreen.GroupDetail) } },
            onRemoveAdmin = { uid -> scope.launch { appState.chatRepo.setMemberRole(appState.selectedGroupChatId ?: "", uid, 0); appState.loadScreenData(SubScreen.GroupDetail) } },
            onMuteMember = { uid -> scope.launch { appState.chatRepo.muteMember(appState.selectedGroupChatId ?: "", uid, 3600); appState.loadScreenData(SubScreen.GroupDetail) } },
            onUnmuteMember = { uid -> scope.launch { appState.chatRepo.unmuteMember(appState.selectedGroupChatId ?: "", uid); appState.loadScreenData(SubScreen.GroupDetail) } },
            onRemoveMember = { uid -> scope.launch { appState.chatRepo.removeMember(appState.selectedGroupChatId ?: "", uid); appState.loadScreenData(SubScreen.GroupDetail) } },
        )

        is SubScreen.InviteMembers -> InviteMembersScreen(
            friendUids = contacts.map { it.friendUid },
            friendNames = contacts.associate { it.friendUid to (it.remark ?: it.user?.name ?: it.friendUid) },
            onInvite = { uids ->
                val chatId = appState.selectedGroupChatId ?: return@InviteMembersScreen false
                try { appState.chatRepo.addMembers(chatId, uids).getOrThrow(); true } catch (e: AppError) { appState.handleError(e, "邀请成员失败"); false }
            },
            onBack = backOrNull(screen) ?: { appState.currentScreen = SubScreen.GroupDetail },
        )

        is SubScreen.InviteLinks -> InviteLinksScreen(
            links = appState.inviteLinks.map { InviteLink(it.token, it.maxUses, it.useCount, it.revokedAt > 0) },
            onCreateLink = {
                val chatId = appState.selectedGroupChatId ?: return@InviteLinksScreen null
                try {
                    val token = appState.chatRepo.createInviteLink(chatId).getOrThrow()
                    appState.inviteLinks = appState.chatRepo.listInviteLinks(chatId).getOrThrow()
                    token
                } catch (e: AppError) { appState.handleError(e, "创建链接失败"); null }
            },
            onRevokeLink = { token ->
                scope.launch {
                    val chatId = appState.selectedGroupChatId ?: return@launch
                    try { appState.chatRepo.revokeInviteLink(token).getOrThrow(); appState.inviteLinks = appState.chatRepo.listInviteLinks(chatId).getOrThrow() } catch (e: AppError) { appState.handleError(e, "撤销链接失败") }
                }
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
                onSendMessage = {
                    scope.launch {
                        try {
                            val uid = appState.selectedProfileUid ?: return@launch
                            val chat = appState.chatRepo.createPersonalChat(uid).getOrThrow()
                            appState.openChat(chat.chatId, appState.profileUser?.name ?: uid.take(12))
                            onOpenChatAndDismiss()
                        } catch (e: AppError) { appState.handleError(e, "创建聊天失败") }
                    }
                },
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
                try {
                    val msg = appState.forwardMessage ?: return@ForwardScreen false
                    appState.messageRepo.forwardMessage(msg.chatId, msg.serverSeq, targetChatId).getOrThrow()
                    true
                } catch (e: AppError) { appState.handleError(e, "转发失败"); false }
            },
            onBack = backOrNull(screen) ?: { appState.forwardMessage = null; navBack(screen) },
        )

        is SubScreen.SearchMessages -> SearchMessagesScreen(
            searchMessages = { query -> try { appState.messageRepo.searchMessages("", query).getOrThrow() } catch (e: AppError) { appState.handleError(e, "搜索失败"); emptyList() } },
            onMessageClick = { chatId, _ ->
                val conv = conversations.find { it.chatId == chatId }
                appState.openChat(chatId, conv?.chatName ?: chatId.take(16))
                onOpenChatAndDismiss()
            },
            onBack = backOrNull(screen),
        )

        null, is SubScreen.Main, is SubScreen.Chat, is SubScreen.Contacts, is SubScreen.Settings -> { /* 平台级屏幕，不由 SubScreenRouter 处理 */ }
    }
}
