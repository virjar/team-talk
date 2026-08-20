package com.virjar.tk

import androidx.compose.runtime.*
import com.virjar.tk.media.DesktopSessionResources
import com.virjar.tk.navigation.AppDataState
import com.virjar.tk.ui.screen.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 单个子屏幕的渲染器（参数驱动，不读写全局导航状态）。
 * 右栏面板与独立子窗口两个容器共用：容器负责提供 navigate/back/openChatAndClose，
 * 同一屏幕在两种容器中行为一致（此前 SubWindowHost 为窗口复制了三个分支）。
 *
 * @param navigate 容器内跳转（面板：push 面板栈；窗口：push 局部栈）
 * @param back 返回上一级（栈>1 弹栈；初始屏=关闭容器，由容器定义）
 * @param openChatAndClose 打开会话并关闭当前容器
 * @param openUserProfile 用 Desktop 模态弹窗打开用户资料，不进入页面栈
 * @param onLeaveGroup 离开群组后的清理（关闭面板 + 会话失效处理，容器定义）
 * @param showBack 是否提供返回能力（任务窗口根页仍需用于完成后关闭；箭头显示由宿主控制）
 * @param onClose 检查器根页面的关闭动作；任务窗口和主内容页不提供
 */
@Composable
internal fun SubScreenContent(
    screen: SubScreen,
    data: AppDataState,
    resources: DesktopSessionResources,
    navigate: (SubScreen) -> Unit,
    back: () -> Unit,
    openChatAndClose: (chatId: String, chatName: String, chatType: Int) -> Unit,
    openUserProfile: (uid: String) -> Unit,
    onLeaveGroup: (chatId: String) -> Unit,
    showBack: Boolean,
    onClose: (() -> Unit)? = null,
    globalSearchQuery: String = "",
    onGlobalSearchQueryChange: (String) -> Unit = {},
) {
    val contacts by data.contactViewModel.contacts.collectAsState()
    val conversations by data.conversationViewModel.conversations.collectAsState()
    val actionScope = rememberCoroutineScope()
    var textPreviewEvent by remember { mutableStateOf<DesktopTextAttachmentPreviewEvent?>(null) }
    val fileDownloads = remember(resources) {
        DesktopFileDownloadController(
            resources = resources,
            onDownloaded = DesktopExternalFileOpener::open,
            onTextAttachmentPreview = { event ->
                actionScope.launch { textPreviewEvent = event }
            },
        )
    }
    DisposableEffect(fileDownloads) {
        onDispose { fileDownloads.close() }
    }

    LaunchedEffect(screen) {
        screen.dataKey()?.let { data.loadScreenDataByKey(it) }
    }

    val onBack: (() -> Unit)? = if (showBack) back else null

    when (screen) {
        is SubScreen.Devices -> DeviceManagementScreen(
            devices = data.account.devices.map { DeviceInfo(it.deviceId, it.deviceName ?: "", it.deviceModel ?: "", it.lastLogin) },
            currentDeviceId = data.account.currentDeviceId,
            onKick = { deviceId -> data.account.kickDevice(deviceId) },
            onBack = onBack,
        )

        is SubScreen.Blacklist -> BlacklistScreen(
            blockedUsers = data.account.blockedContacts.map { BlockedUser(it.friendUid, it.user?.name ?: it.friendUid) },
            onUnblock = { uid -> data.account.unblockContact(uid) },
            onBack = onBack,
        )

        is SubScreen.EditProfile -> EditProfileScreen(
            currentUser = data.account.currentUser,
            onSave = { name, _ -> data.account.saveProfile(name, null) },
            onBack = onBack,
        )

        is SubScreen.ChangePassword -> ChangePasswordScreen(
            onChangePassword = { old, new -> data.account.changePassword(old, new) },
            onBack = onBack,
        )

        is SubScreen.FriendApplies -> FriendAppliesScreen(
            records = data.account.friendApplyRecords,
            loading = data.account.friendApplyRecordsLoading,
            hasMore = data.account.friendApplyRecordsHasMore,
            onLoadMore = data.account::loadMoreFriendApplies,
            onAccept = { token -> data.account.acceptFriendApply(token) },
            onReject = { token -> data.account.rejectFriendApply(token) },
            onBack = onBack,
        )

        is SubScreen.SearchUsers -> SearchUsersScreen(
            searchUsers = { query -> data.discovery.searchUsers(query) },
            onUserClick = openUserProfile,
            onBack = onBack,
        )

        is SubScreen.CreateGroup -> CreateGroupScreen(
            contacts = contacts,
            onCreateGroup = { name, uids ->
                val chatId = data.groups.create(name, uids)
                    ?: return@CreateGroupScreen Result.failure(Exception("创建失败"))
                openChatAndClose(chatId, name, 2)
                Result.success(chatId)
            },
            onBack = onBack,
            initialSelectedUids = screen.preselectedUids,
        )

        is SubScreen.GroupDetail -> GroupDetailScreen(
            chat = data.groups.detailChat,
            members = data.groups.members,
            isOwner = data.groups.members.any { it.uid == data.userSession.uid && it.role == 2 },
            myUid = data.userSession.uid,
            onMemberClick = openUserProfile,
            onInviteMembers = { navigate(SubScreen.InviteMembers(screen.chatId)) },
            onViewInviteLinks = { navigate(SubScreen.InviteLinks(screen.chatId)) },
            onGroupFiles = { navigate(SubScreen.GroupFiles(screen.chatId)) },
            onGroupBots = { navigate(SubScreen.GroupBots(screen.chatId)) },
            onLeaveGroup = { onLeaveGroup(screen.chatId) },
            onEditNotice = { notice -> data.groups.updateNotice(screen.chatId, notice) },
            onBack = onBack,
            onSetAdmin = { uid -> data.groups.setMemberRole(screen.chatId, uid, 1) },
            onRemoveAdmin = { uid -> data.groups.setMemberRole(screen.chatId, uid, 0) },
            onMuteMember = { uid -> data.groups.muteMember(screen.chatId, uid) },
            onUnmuteMember = { uid -> data.groups.unmuteMember(screen.chatId, uid) },
            onRemoveMember = { uid -> data.groups.removeMember(screen.chatId, uid) },
            onClose = onClose,
        )

        is SubScreen.InviteMembers -> InviteMembersScreen(
            friendUids = contacts.map { it.friendUid },
            friendNames = contacts.associate { it.friendUid to (it.remark ?: it.user?.name ?: it.friendUid) },
            onInvite = { uids -> data.groups.inviteMembers(screen.chatId, uids) },
            onBack = onBack,
        )

        is SubScreen.InviteLinks -> InviteLinksScreen(
            links = data.groups.inviteLinks.map { InviteLink(it.token, it.maxUses, it.useCount, it.revokedAt > 0) },
            onCreateLink = { data.groups.createInviteLink(screen.chatId) },
            onRevokeLink = { token -> data.groups.revokeInviteLink(screen.chatId, token) },
            onBack = onBack,
        )

        is SubScreen.GroupBots -> {
            val ready = data.groups.groupBotsTargetChatId == screen.chatId
            GroupBotsScreen(
                chatId = screen.chatId,
                serverUrl = resources.serverBaseUrl,
                bots = data.groups.groupBots.takeIf { ready }.orEmpty(),
                loading = !ready || data.groups.groupBotsLoading,
                error = data.groups.groupBotsError.takeIf { ready },
                canCreate = ready && data.groups.groupBotsError == null,
                creating = data.groups.creatingGroupBot,
                operationBotId = data.groups.groupBotOperationId,
                credentials = data.groups.groupBotCredentialsFor(screen.chatId).takeIf { ready },
                onRefresh = {
                    actionScope.launch { data.loadScreenDataByKey(com.virjar.tk.navigation.ScreenDataKey.GroupBots(screen.chatId)) }
                },
                onCreate = { name -> data.groups.createGroupBot(screen.chatId, name) },
                onRotate = { botId -> data.groups.rotateGroupBotToken(screen.chatId, botId) },
                onRemove = { botId -> data.groups.removeGroupBot(screen.chatId, botId) },
                onDismissCredentials = { data.groups.dismissGroupBotCredentials(screen.chatId) },
                onBack = onBack,
                onClose = onClose,
            )
        }

        is SubScreen.GroupFiles -> {
            var uploading by remember(screen.chatId) { mutableStateOf(false) }

            fun chooseAndUpload(versionTarget: com.virjar.tk.model.GroupFileEntry?) {
                val file = DesktopFilePicker.chooseFile("选择群文件") ?: return
                actionScope.launch {
                    uploading = true
                    try {
                        val attachment = resources.fileTransfer.upload(file)
                        if (versionTarget == null) data.groupFiles.publish(file.name, attachment)
                        else data.groupFiles.addVersion(versionTarget, attachment)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        if (runCatching { resources.ensureOpen() }.isSuccess) {
                            com.virjar.tk.util.AppLog.fault("GroupFiles", "upload failed: ${e.message}")
                            data.groupFiles.reportUploadError(e)
                        }
                    } finally {
                        if (runCatching { resources.ensureOpen() }.isSuccess) uploading = false
                    }
                }
            }

            GroupFilesScreen(
                entries = data.groupFiles.entries,
                path = data.groupFiles.path,
                selectedFile = data.groupFiles.selectedFile,
                versions = data.groupFiles.versions,
                loading = data.groupFiles.loading,
                uploading = uploading,
                onRefresh = { actionScope.launch { data.groupFiles.refresh() } },
                onEnter = data.groupFiles::enter,
                onUp = data.groupFiles::up,
                onCreateFolder = data.groupFiles::createFolder,
                onUpload = { chooseAndUpload(null) },
                onOpenFile = fileDownloads::openOrDownload,
                onShowVersions = data.groupFiles::showVersions,
                onUploadVersion = { chooseAndUpload(it) },
                onRename = data.groupFiles::rename,
                onDelete = data.groupFiles::delete,
                onBack = onBack,
                onClose = onClose,
            )
        }

        is SubScreen.Forward -> ForwardScreen(
            conversations = conversations,
            onForward = { targetChatId -> data.discovery.forwardMessage(screen.message.chatId, screen.message.serverSeq, targetChatId) },
            onBack = onBack,
        )

        is SubScreen.SearchMessages -> SearchMessagesScreen(
            searchMessages = { query -> data.discovery.searchMessages(query) },
            onMessageClick = { chatId, _ ->
                val conv = conversations.find { it.chatId == chatId }
                openChatAndClose(chatId, conv?.chatName ?: chatId.take(16), conv?.chatType ?: 1)
            },
            onBack = onBack,
        )

        is SubScreen.GlobalSearch -> GlobalSearchScreen(
            query = globalSearchQuery,
            onQueryChange = onGlobalSearchQueryChange,
            conversations = conversations,
            contacts = contacts,
            searchMessages = { query -> data.discovery.searchMessages(query) },
            searchUsers = { query -> data.discovery.searchUsers(query) },
            onConversationClick = { conversation ->
                openChatAndClose(
                    conversation.chatId,
                    conversation.chatName ?: conversation.chatId.take(16),
                    conversation.chatType,
                )
            },
            onMessageClick = { message ->
                val conversation = conversations.find { it.chatId == message.chatId }
                openChatAndClose(
                    message.chatId,
                    conversation?.chatName ?: message.chatId.take(16),
                    conversation?.chatType ?: 1,
                )
            },
            onUserClick = { user -> openUserProfile(user.uid) },
            excludedUserUid = data.userSession.uid,
            onBack = onBack,
            showSearchField = false,
        )
    }

    DesktopTextAttachmentPreviewDialog(
        event = textPreviewEvent,
        onDismiss = { textPreviewEvent = null },
        onRetry = fileDownloads::openOrDownload,
        onOpenExternally = fileDownloads::openExternally,
    )
}
