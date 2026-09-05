package com.virjar.tk.desktop

import androidx.compose.runtime.*
import com.virjar.tk.desktop.media.DesktopSessionDiagnosticEvent
import com.virjar.tk.desktop.media.DesktopSessionResources
import com.virjar.tk.app.navigation.AppDataState
import com.virjar.tk.app.telemetry.ClientUiPage
import com.virjar.tk.app.ui.screen.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
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
    presentationGate: DesktopSessionPresentationGate,
    resources: DesktopSessionResources,
    navigate: (SubScreen) -> Unit,
    back: () -> Unit,
    openChatAndClose: (chatId: String) -> Unit,
    openMessageAndClose: (chatId: String, serverSeq: Long) -> Unit,
    openUserProfile: (uid: String) -> Unit,
    onLeaveGroup: (chatId: String) -> Unit,
    showBack: Boolean,
    onClose: (() -> Unit)? = null,
    globalSearchQuery: String = "",
    onGlobalSearchQueryChange: (String) -> Unit = {},
) {
    if (!presentationGate.isOpen || !data.acceptsRendering) return

    val contacts by data.contactViewModel.contacts.collectAsState()
    val conversations by data.conversationViewModel.conversations.collectAsState()
    val conversationPeerUsers by data.conversationViewModel.peerUsers.collectAsState()
    val globalSearchUsers by data.globalSearchUserViewModel.users.collectAsState()
    val actionScope = rememberCoroutineScope()
    val textPreviewEventState = remember { mutableStateOf<DesktopTextAttachmentPreviewEvent?>(null) }
    var textPreviewEvent by textPreviewEventState
    val textPreviewOwner = remember(actionScope, textPreviewEventState) {
        DesktopTextAttachmentPreviewOwner(actionScope, textPreviewEventState)
    }
    val fileDownloads = remember(resources, actionScope, presentationGate, textPreviewOwner, data.telemetry) {
        DesktopFileDownloadController(
            resources = resources,
            uiScope = actionScope,
            actionAdmission = presentationGate,
            onDownloaded = { file ->
                presentationGate.runIfOpen { DesktopExternalFileOpener.open(file) }
            },
            onTextAttachmentPreview = { event ->
                var delivery: kotlinx.coroutines.Deferred<Boolean>? = null
                presentationGate.runIfOpen {
                    delivery = textPreviewOwner.offer(event)
                }
                delivery
            },
            telemetry = data.telemetry,
            telemetryPage = when (screen) {
                is SubScreen.GroupFiles -> ClientUiPage.GROUP_FILES
                else -> ClientUiPage.CHAT
            },
        )
    }
    DisposableEffect(fileDownloads, textPreviewOwner) {
        onDispose {
            textPreviewOwner.close()
            fileDownloads.close()
        }
    }

    LaunchedEffect(screen) {
        data.runAdmittedUiAction(presentationGate, onClosed = {}) {
            screen.dataKey()?.let { data.loadScreenDataByKey(it) }
        }
    }

    val navigateIfOpen = presentationGate.guard(navigate)
    val backIfOpen = presentationGate.guard(back)
    val openChatIfOpen = presentationGate.guard(openChatAndClose)
    val openMessageIfOpen = presentationGate.guard(openMessageAndClose)
    val openProfileIfOpen = presentationGate.guard(openUserProfile)
    val leaveGroupIfOpen = presentationGate.guard(onLeaveGroup)
    val closeIfOpen = onClose?.let { presentationGate.guard(it) }
    val onBack: (() -> Unit)? = if (showBack) backIfOpen else null
    suspend fun <T> admittedSuspend(
        onClosed: () -> T,
        action: suspend () -> T,
    ): T = data.runAdmittedUiAction(presentationGate, onClosed, action)

    when (screen) {
        is SubScreen.FriendApplies -> FriendAppliesScreen(
            records = data.account.friendApplyRecords,
            loading = data.account.friendApplyRecordsLoading,
            hasMore = data.account.friendApplyRecordsHasMore,
            onLoadMore = presentationGate.guard(data.account::loadMoreFriendApplies),
            onAccept = { token ->
                admittedSuspend(onClosed = {}) { data.account.acceptFriendApply(token) }
            },
            onReject = { token ->
                admittedSuspend(onClosed = {}) { data.account.rejectFriendApply(token) }
            },
            onBack = onBack,
        )

        is SubScreen.SearchUsers -> SearchUsersScreen(
            searchUsers = { query ->
                admittedSuspend(onClosed = { emptyList() }) {
                    data.discovery.searchUsers(query)
                }
            },
            onUserClick = openProfileIfOpen,
            onBack = onBack,
        )

        is SubScreen.CreateGroup -> CreateGroupScreen(
            contacts = contacts,
            pendingGroupCreation = data.groups.pendingGroupCreation,
            groupCreationDraftLoaded = data.groups.groupCreationDraftLoaded,
            groupCreationDraftError = data.groups.groupCreationDraftError,
            onCreateGroup = { name, uids ->
                admittedSuspend(
                    onClosed = { Result.failure(Exception("会话已关闭")) },
                ) {
                    val chatId = data.groups.create(name, uids)
                    if (chatId == null) {
                        Result.failure(Exception("创建失败"))
                    } else {
                        openChatIfOpen(chatId)
                        Result.success(chatId)
                    }
                }
            },
            onDiscardPendingGroupCreation = {
                admittedSuspend(onClosed = { false }) {
                    data.groups.discardPendingCreation()
                }
            },
            onBack = onBack,
            initialSelectedUids = screen.preselectedUids,
        )

        is SubScreen.GroupDetail -> {
            val detailReady = data.groups.detailTargetChatId == screen.chatId
            val detailChat = data.groups.detailChat?.takeIf { detailReady && it.chatId == screen.chatId }
            val detailMembers = data.groups.members.takeIf { detailReady }.orEmpty()
            GroupDetailScreen(
                chat = detailChat,
                members = detailMembers,
                isOwner = detailMembers.any { it.uid == data.userSession.uid && it.role == 2 },
                myUid = data.userSession.uid,
                onMemberClick = openProfileIfOpen,
                onInviteMembers = presentationGate.guard {
                    navigateIfOpen(SubScreen.InviteMembers(screen.chatId))
                },
                onViewInviteLinks = presentationGate.guard {
                    navigateIfOpen(SubScreen.InviteLinks(screen.chatId))
                },
                onGroupFiles = presentationGate.guard {
                    navigateIfOpen(SubScreen.GroupFiles(screen.chatId))
                },
                onGroupBots = presentationGate.guard {
                    navigateIfOpen(SubScreen.GroupBots(screen.chatId))
                },
                onLeaveGroup = presentationGate.guard { leaveGroupIfOpen(screen.chatId) },
                onEditNotice = presentationGate.guard { notice ->
                    data.groups.updateNotice(screen.chatId, notice)
                },
                onBack = onBack,
                onSetAdmin = presentationGate.guard { uid ->
                    data.groups.setMemberRole(screen.chatId, uid, 1)
                },
                onRemoveAdmin = presentationGate.guard { uid ->
                    data.groups.setMemberRole(screen.chatId, uid, 0)
                },
                onMuteMember = presentationGate.guard { uid ->
                    data.groups.muteMember(screen.chatId, uid)
                },
                onUnmuteMember = presentationGate.guard { uid ->
                    data.groups.unmuteMember(screen.chatId, uid)
                },
                onRemoveMember = presentationGate.guard { uid ->
                    data.groups.removeMember(screen.chatId, uid)
                },
                onClose = closeIfOpen,
            )
        }

        is SubScreen.InviteMembers -> InviteMembersScreen(
            friendUids = contacts.map { it.friendUid },
            friendNames = contacts.associate { it.friendUid to (it.remark ?: it.user?.name ?: it.friendUid) },
            onInvite = { uids ->
                admittedSuspend(onClosed = { false }) {
                    data.groups.inviteMembers(screen.chatId, uids)
                }
            },
            onBack = onBack,
        )

        is SubScreen.InviteLinks -> InviteLinksScreen(
            links = data.groups.inviteLinks.map { InviteLink(it.token, it.maxUses, it.useCount, it.revokedAt > 0) },
            onCreateLink = {
                admittedSuspend(onClosed = { null }) {
                    data.groups.createInviteLink(screen.chatId)
                }
            },
            onRevokeLink = presentationGate.guard { token ->
                data.groups.revokeInviteLink(screen.chatId, token)
            },
            onBack = onBack,
        )

        is SubScreen.GroupBots -> {
            val ready = data.groups.groupBotsTargetChatId == screen.chatId
            val credentialPresentation = data.groups.groupBotCredentialPresentation(screen.chatId)
            GroupBotsScreen(
                chatId = screen.chatId,
                serverUrl = resources.serverBaseUrl,
                bots = data.groups.groupBots.takeIf { ready }.orEmpty(),
                loading = !ready || data.groups.groupBotsLoading,
                error = data.groups.groupBotsError.takeIf { ready },
                canCreate = ready && data.groups.groupBotsError == null &&
                    !data.groups.hasUnacknowledgedGroupBotCredential,
                creating = data.groups.creatingGroupBot,
                operationBotId = data.groups.groupBotOperationId,
                credentials = credentialPresentation.credentials?.credentials,
                credentialsChatId = credentialPresentation.credentials?.chatId,
                pendingRecovery = credentialPresentation.pendingRecovery,
                credentialCommandBlocked = data.groups.hasUnacknowledgedGroupBotCredential,
                onRefresh = {
                    actionScope.launch {
                        admittedSuspend(onClosed = {}) {
                            data.loadScreenDataByKey(
                                com.virjar.tk.app.navigation.ScreenDataKey.GroupBots(screen.chatId),
                            )
                        }
                    }
                },
                onCreate = presentationGate.guard { name ->
                    data.groups.createGroupBot(screen.chatId, name)
                },
                onRotate = presentationGate.guard { botId ->
                    data.groups.rotateGroupBotToken(screen.chatId, botId)
                },
                onRemove = presentationGate.guard { botId ->
                    data.groups.removeGroupBot(screen.chatId, botId)
                },
                onDismissCredentials = presentationGate.guard {
                    credentialPresentation.credentials?.chatId?.let(
                        data.groups::dismissGroupBotCredentials,
                    )
                },
                onRetryPendingCredential = {
                    actionScope.launch {
                        admittedSuspend(onClosed = {}) {
                            data.loadScreenDataByKey(
                                com.virjar.tk.app.navigation.ScreenDataKey.GroupBots(screen.chatId),
                            )
                        }
                    }
                },
                onAbandonPendingCredential = presentationGate.guard {
                    data.groups.abandonPendingGroupBotCredentialRecovery()
                },
                onBack = onBack,
                onClose = closeIfOpen,
            )
        }

        is SubScreen.GroupFiles -> {
            var uploading by remember(screen.chatId) { mutableStateOf(false) }

            fun chooseAndUpload(versionTarget: com.virjar.tk.protocol.model.GroupFileEntry?) {
                if (!presentationGate.runIfOpen {}) return
                val file = DesktopFilePicker.chooseFile("选择群文件") ?: return
                actionScope.launch {
                    if (!presentationGate.runIfOpen { uploading = true }) return@launch
                    try {
                        val attachment = admittedSuspend(onClosed = { null }) {
                            resources.fileTransfer.upload(file)
                        } ?: return@launch
                        admittedSuspend(onClosed = {}) {
                            if (versionTarget == null) data.groupFiles.publish(file.name, attachment)
                            else data.groupFiles.addVersion(versionTarget, attachment)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        if (resources.canDeliverUiResult()) {
                            presentationGate.runIfOpen {
                                resources.diagnostics.record(
                                    DesktopSessionDiagnosticEvent.GROUP_FILE_UPLOAD_FAILED,
                                )
                                data.groupFiles.reportUploadError(e)
                            }
                        }
                    } finally {
                        if (resources.canDeliverUiResult()) {
                            presentationGate.runIfOpen { uploading = false }
                        }
                    }
                }
            }

            GroupFilesScreen(
                entries = data.groupFiles.entries,
                stale = data.groupFiles.stale,
                path = data.groupFiles.path,
                selectedFile = data.groupFiles.selectedFile,
                versions = data.groupFiles.versions,
                loading = data.groupFiles.loading,
                uploading = uploading,
                onRefresh = {
                    actionScope.launch {
                        admittedSuspend(onClosed = {}) { data.groupFiles.refresh() }
                    }
                },
                onEnter = presentationGate.guard(data.groupFiles::enter),
                onUp = presentationGate.guard(data.groupFiles::up),
                onCreateFolder = presentationGate.guard(data.groupFiles::createFolder),
                onUpload = { chooseAndUpload(null) },
                onOpenFile = presentationGate.guard(fileDownloads::openOrDownload),
                onShowVersions = presentationGate.guard(data.groupFiles::showVersions),
                onUploadVersion = ::chooseAndUpload,
                onRename = presentationGate.guard(data.groupFiles::rename),
                onDelete = presentationGate.guard(data.groupFiles::delete),
                onBack = onBack,
                onClose = closeIfOpen,
            )
        }

        is SubScreen.Forward -> ForwardScreen(
            conversations = conversations,
            peerUsers = conversationPeerUsers,
            onForward = { targetChatId ->
                admittedSuspend(onClosed = { false }) {
                    data.discovery.forwardMessage(
                        screen.message.chatId,
                        screen.message.serverSeq,
                        targetChatId,
                    )
                }
            },
            onBack = onBack,
        )

        is SubScreen.SearchMessages -> SearchMessagesScreen(
            searchMessages = { query ->
                admittedSuspend(onClosed = { emptyList() }) {
                    data.discovery.searchMessages(query)
                }
            },
            onMessageClick = { chatId, serverSeq ->
                openMessageIfOpen(chatId, serverSeq)
            },
            onBack = onBack,
        )

        is SubScreen.GlobalSearch -> GlobalSearchScreen(
            query = globalSearchQuery,
            onQueryChange = presentationGate.guard(onGlobalSearchQueryChange),
            conversations = conversations,
            contacts = contacts,
            conversationPeerUsers = conversationPeerUsers,
            canonicalSearchUsers = globalSearchUsers,
            onDisplayedSearchUserUidsChange = data.globalSearchUserViewModel::bindDisplayedUserUids,
            searchMessages = { query ->
                admittedSuspend(onClosed = { emptyList() }) {
                    data.discovery.searchMessages(query)
                }
            },
            searchUsers = { query ->
                admittedSuspend(onClosed = { emptyList() }) {
                    data.discovery.searchUsers(query)
                }
            },
            onConversationClick = { conversation ->
                openChatIfOpen(conversation.chatId)
            },
            onMessageClick = { message ->
                openMessageIfOpen(message.chatId, message.serverSeq)
            },
            onUserClick = presentationGate.guard { user -> openProfileIfOpen(user.uid) },
            excludedUserUid = data.userSession.uid,
            onBack = onBack,
            showSearchField = false,
        )
    }

    DesktopTextAttachmentPreviewDialog(
        event = textPreviewEvent,
        presentationGate = presentationGate,
        onDismiss = presentationGate.guard(textPreviewOwner::clear),
        onRetry = presentationGate.guard(fileDownloads::openOrDownload),
        onOpenExternally = presentationGate.guard(fileDownloads::openExternally),
    )
}
