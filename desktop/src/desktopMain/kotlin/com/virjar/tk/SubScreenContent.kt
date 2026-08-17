package com.virjar.tk

import androidx.compose.runtime.*
import com.virjar.tk.navigation.AppDataState
import com.virjar.tk.ui.screen.*
import kotlinx.coroutines.launch

/**
 * 单个子屏幕的渲染器（参数驱动，不读写全局导航状态）。
 * 右栏面板与独立子窗口两个容器共用：容器负责提供 navigate/back/openChatAndClose，
 * 同一屏幕在两种容器中行为一致（此前 SubWindowHost 为窗口复制了三个分支）。
 *
 * @param navigate 容器内跳转（面板：push 面板栈；窗口：push 局部栈）
 * @param back 返回上一级（栈>1 弹栈；初始屏=关闭容器，由容器定义）
 * @param openChatAndClose 打开会话并关闭当前容器
 * @param onLeaveGroup 离开群组后的清理（关闭面板 + 会话失效处理，容器定义）
 * @param showBack 是否渲染返回键（窗口恒真 §2.6；面板仅栈>1 时，初始屏靠 ESC 关）
 */
@Composable
fun SubScreenContent(
    screen: SubScreen,
    data: AppDataState,
    navigate: (SubScreen) -> Unit,
    back: () -> Unit,
    openChatAndClose: (chatId: String, chatName: String, chatType: Int) -> Unit,
    onLeaveGroup: (chatId: String) -> Unit,
    showBack: Boolean,
) {
    val scope = rememberCoroutineScope()
    val contacts by data.contactViewModel.contacts.collectAsState()
    val conversations by data.conversationViewModel.conversations.collectAsState()

    LaunchedEffect(screen) {
        screen.dataKey()?.let { data.loadScreenDataByKey(it) }
    }

    val onBack: (() -> Unit)? = if (showBack) back else null

    when (screen) {
        is SubScreen.Devices -> DeviceManagementScreen(
            devices = data.devices.map { DeviceInfo(it.deviceId, it.deviceName ?: "", it.deviceModel ?: "", it.lastLogin) },
            onKick = { deviceId -> data.kickDevice(deviceId) },
            onBack = onBack,
        )

        is SubScreen.Blacklist -> BlacklistScreen(
            blockedUsers = data.blockedContacts.map { BlockedUser(it.friendUid, it.user?.name ?: it.friendUid) },
            onUnblock = { uid -> data.unblockContact(uid) },
            onBack = onBack,
        )

        is SubScreen.EditProfile -> EditProfileScreen(
            currentUser = data.currentUser,
            onSave = { name, _ -> data.saveProfile(name, null) },
            onBack = onBack,
        )

        is SubScreen.ChangePassword -> ChangePasswordScreen(
            onChangePassword = { old, new -> data.changePassword(old, new) },
            onBack = onBack,
        )

        is SubScreen.FriendApplies -> FriendAppliesScreen(
            applies = data.applies,
            onAccept = { token -> data.acceptFriendApply(token) },
            onReject = { token -> data.rejectFriendApply(token) },
            onBack = onBack,
        )

        is SubScreen.SearchUsers -> SearchUsersScreen(
            searchUsers = { query -> data.searchUsers(query) },
            onUserClick = { uid -> navigate(SubScreen.UserProfile(uid)) },
            onBack = onBack,
        )

        is SubScreen.CreateGroup -> CreateGroupScreen(
            contacts = contacts,
            onCreateGroup = { name, uids ->
                val chatId = data.createGroup(name, uids)
                    ?: return@CreateGroupScreen Result.failure(Exception("创建失败"))
                openChatAndClose(chatId, name, 2)
                Result.success(chatId)
            },
            onBack = onBack,
        )

        is SubScreen.GroupDetail -> GroupDetailScreen(
            chat = data.groupDetailChat,
            members = data.groupMembers,
            isOwner = data.groupMembers.any { it.uid == data.userSession.uid && it.role == 2 },
            myUid = data.userSession.uid,
            onMemberClick = { uid -> navigate(SubScreen.UserProfile(uid)) },
            onInviteMembers = { navigate(SubScreen.InviteMembers(screen.chatId)) },
            onViewInviteLinks = { navigate(SubScreen.InviteLinks(screen.chatId)) },
            onLeaveGroup = { onLeaveGroup(screen.chatId) },
            onEditNotice = { notice -> data.updateGroupNotice(screen.chatId, notice) },
            onBack = onBack,
            onSetAdmin = { uid -> data.setMemberRole(screen.chatId, uid, 1) },
            onRemoveAdmin = { uid -> data.setMemberRole(screen.chatId, uid, 0) },
            onMuteMember = { uid -> data.muteMember(screen.chatId, uid) },
            onUnmuteMember = { uid -> data.unmuteMember(screen.chatId, uid) },
            onRemoveMember = { uid -> data.removeMember(screen.chatId, uid) },
        )

        is SubScreen.InviteMembers -> InviteMembersScreen(
            friendUids = contacts.map { it.friendUid },
            friendNames = contacts.associate { it.friendUid to (it.remark ?: it.user?.name ?: it.friendUid) },
            onInvite = { uids -> data.inviteMembers(screen.chatId, uids) },
            onBack = onBack,
        )

        is SubScreen.InviteLinks -> InviteLinksScreen(
            links = data.inviteLinks.map { InviteLink(it.token, it.maxUses, it.useCount, it.revokedAt > 0) },
            onCreateLink = { data.createInviteLink(screen.chatId) },
            onRevokeLink = { token -> data.revokeInviteLink(screen.chatId, token) },
            onBack = onBack,
        )

        is SubScreen.UserProfile -> {
            // 局部状态：apply 后即时反馈「已申请」，避免硬编码 false
            var hasPendingApply by remember { mutableStateOf(false) }
            LaunchedEffect(screen.uid) { hasPendingApply = false }
            UserProfileScreen(
                user = data.profileUser,
                isFriend = data.isFriend,
                hasPendingApply = hasPendingApply,
                onAddFriend = {
                    data.contactViewModel.apply(screen.uid)
                    hasPendingApply = true
                },
                onSendMessage = { scope.launch {
                    val chatId = data.startPersonalChat(screen.uid) ?: return@launch
                    openChatAndClose(chatId, data.profileUser?.name ?: screen.uid.take(12), 1)
                }},
                onDeleteFriend = {
                    data.contactViewModel.deleteFriend(screen.uid)
                    back()
                },
                onBack = onBack,
            )
        }

        is SubScreen.Forward -> ForwardScreen(
            conversations = conversations,
            onForward = { targetChatId -> data.forwardMessage(screen.message.chatId, screen.message.serverSeq, targetChatId) },
            onBack = onBack,
        )

        is SubScreen.SearchMessages -> SearchMessagesScreen(
            searchMessages = { query -> data.searchMessages(query) },
            onMessageClick = { chatId, _ ->
                val conv = conversations.find { it.chatId == chatId }
                openChatAndClose(chatId, conv?.chatName ?: chatId.take(16), conv?.chatType ?: 1)
            },
            onBack = onBack,
        )
    }
}
