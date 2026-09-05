package com.virjar.tk.android


import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.app.navigation.AppDataState
import com.virjar.tk.app.navigation.ScreenDataKey
import com.virjar.tk.app.navigation.MainTab
import com.virjar.tk.app.ui.UiActionAdmission
import com.virjar.tk.app.ui.screen.BlacklistScreen
import com.virjar.tk.app.ui.screen.BlockedUser
import com.virjar.tk.app.ui.screen.ChangePasswordScreen
import com.virjar.tk.app.ui.screen.conversationIdentityPresentation
import com.virjar.tk.app.ui.screen.CreateGroupScreen
import com.virjar.tk.app.ui.screen.DeviceInfo
import com.virjar.tk.app.ui.screen.DeviceManagementScreen
import com.virjar.tk.app.ui.screen.ForwardScreen
import com.virjar.tk.app.ui.screen.FriendAppliesScreen
import com.virjar.tk.app.ui.screen.GlobalSearchScreen
import com.virjar.tk.app.ui.screen.GroupBotsScreen
import com.virjar.tk.app.ui.screen.GroupDetailScreen
import com.virjar.tk.app.ui.screen.InviteLink
import com.virjar.tk.app.ui.screen.InviteLinksScreen
import com.virjar.tk.app.ui.screen.InviteMembersScreen
import com.virjar.tk.app.ui.screen.SearchUsersScreen
import com.virjar.tk.app.ui.screen.UserProfileScreen
import com.virjar.tk.app.telemetry.ClientUiAction
import com.virjar.tk.app.telemetry.ClientUiPage
import com.virjar.tk.app.telemetry.ClientActionOutcome
import com.virjar.tk.app.viewmodel.MessageFocusTarget
import kotlinx.coroutines.flow.MutableStateFlow
import com.virjar.tk.protocol.body.OfficeRefBody
import kotlinx.coroutines.launch

/** 聊天路由：按路由身份恢复会话、草稿与 @ 候选投影。 */
internal fun NavGraphBuilder.chatDestination(
    navController: NavHostController,
    dataState: AppDataState,
    resourceOwner: AndroidAuthenticatedResourceOwner,
    actionAdmission: UiActionAdmission,
    requestedDocument: MutableStateFlow<OfficeRefBody?>,
    chatEmbeddedAssetImports: AndroidEmbeddedAssetImportGateway,
    chatEmbeddedAssetSelector: AndroidEmbeddedAssetSelector,
) {
    suspend fun <T> admittedAction(onClosed: () -> T, action: suspend () -> T): T =
        dataState.runAdmittedUiAction(actionAdmission, onClosed, action)
    composable(
        Routes.CHAT,
        arguments = listOf(
            navArgument("chatId") { type = NavType.StringType },
            navArgument("targetSeq") {
                type = NavType.LongType
                defaultValue = 0L
            },
        ),
    ) { entry ->
        val chatId = entry.arguments?.getString("chatId") ?: return@composable
        val messageFocusTarget = entry.arguments?.getLong("targetSeq")
            ?.takeIf { seq -> seq > 0L }
            ?.let { seq -> MessageFocusTarget(chatId, seq) }
        // 恢复/深链进入的 CHAT 目标必须自己完成准备；最初打开它的导航点击
        // 并不属于 Android 保存状态恢复的一部分。
        LaunchedEffect(chatId) {
            actionAdmission.runIfOpen {
                dataState.ensureChat(chatId)
            }
        }
        // 在返回栈转场期间，绝不能用路由 B 的 ViewModel 渲染路由 A。
        val viewModel = dataState.chatViewModelFor(chatId)
        val conversations by dataState.conversationViewModel.conversations.collectAsState()
        val peerUsers by dataState.conversationViewModel.peerUsers.collectAsState()
        // 路由只携带身份。可变的标题/类型总是来自持久的 LocalCache 投影，
        // 因此恢复出来的返回栈不会冻结元数据。
        val currentConversation = conversations.find { it.chatId == chatId }
        val chatName = currentConversation?.let { conversation ->
            conversationIdentityPresentation(conversation, conversation.peerUid?.let(peerUsers::get)).name
        } ?: chatId.take(16)
        val chatType = currentConversation?.chatType
            ?: com.virjar.tk.protocol.model.ChatType.PERSONAL.code
        val chatContacts by dataState.contactViewModel.contacts.collectAsState()
        // @ 候选只读 LocalCache 的成员/用户组合投影；路由身份阻止 A→B 串页。
        LaunchedEffect(chatId, chatType) {
            admittedAction(onClosed = {}) {
                if (chatType == com.virjar.tk.protocol.model.ChatType.GROUP.code) {
                    dataState.groups.loadMentionCandidates(chatId)
                } else {
                    dataState.groups.clearMentionCandidates()
                }
            }
        }
        DisposableEffect(chatId, chatType) {
            onDispose {
                if (chatType == com.virjar.tk.protocol.model.ChatType.GROUP.code) {
                    actionAdmission.runIfOpen {
                        dataState.groups.clearMentionCandidates(chatId)
                    }
                }
            }
        }
        val mentionCandidates = if (chatType == com.virjar.tk.protocol.model.ChatType.GROUP.code) {
            dataState.groups.mentionUsers.takeIf {
                dataState.groups.mentionTargetChatId == chatId
            }.orEmpty()
        } else {
            chatContacts.mapNotNull { it.user }
        }
        if (viewModel != null) {
            AndroidChatScreen(
                chatId,
                chatName,
                chatType,
                viewModel,
                dataState.userSession.uid,
                dataState::httpCredentialsSnapshot,
                deploymentIdentity = dataState.deploymentIdentity,
                datasetId = dataState.datasetId,
                resourceOwner = resourceOwner,
                embeddedAssetImports = chatEmbeddedAssetImports,
                embeddedAssetSelector = chatEmbeddedAssetSelector,
                telemetry = dataState.telemetry,
                onAuthExpired = dataState::reportHttpAuthExpired,
                resolveSender = { uid ->
                    mentionCandidates.firstOrNull { it.uid == uid } ?: dataState.residentChatUser(uid)
                },
                mentionCandidates = mentionCandidates,
                onMentionClick = actionAdmission.guard { uid: String ->
                    safeMentionProfileRouteOrNull(uid)?.let { route ->
                        navController.navigate(route)
                    }
                },
                // null 表示会话尚未加载；已知没有草稿以空字符串交给编辑器。
                cachedDraft = currentConversation?.let { it.draft.orEmpty() },
                composerContextStore = dataState.chatComposerContexts,
                draftLifecycleBridge = dataState.chatDraftLifecycle,
                actionAdmission = dataState.uiActionAdmission,
                launchAdmittedAction = { action ->
                    dataState.launchAdmittedUiAction(action = action)
                },
                // ChatPanel 已防抖并在离开时同步 flush；单一出口避免父子
                // DisposableEffect 销毁顺序不确定时，旧草稿在最后一帧反向覆盖新草稿。
                onDraftChange = { dataState.saveDraft(chatId, it) },
                onForward = actionAdmission.guard { message: com.virjar.tk.protocol.model.Message ->
                    navController.navigate(Routes.forward(message.chatId, message.serverSeq))
                },
                onSaveMessage = actionAdmission.guard { message: com.virjar.tk.protocol.model.Message ->
                    dataState.messageActions.save(message.chatId, message.serverSeq)
                },
                officeRefHost = dataState,
                onOpenOfficeRef = actionAdmission.guard {
                        body: OfficeRefBody, onDenied ->
                    dataState.messageActions.openReference(
                        reference = body,
                        onOpen = {
                            if (body.isDocument) {
                                // 由首页先初始化工作区，再消费完整目标；不能在被聊天覆盖的首页提前打开。
                                requestedDocument.value = body
                                navController.navigate(Routes.HOME) {
                                    popUpTo(Routes.HOME) { inclusive = false }
                                    launchSingleTop = true
                                }
                            } else {
                                navController.navigate(Routes.groupFiles(body.spaceId))
                            }
                        },
                        onDenied = onDenied,
                    )
                },
                onTextAttachmentPreview = actionAdmission.guard { attachment: com.virjar.tk.protocol.model.Attachment ->
                    navController.navigate(Routes.textAttachmentPreview(attachment))
                },
                onGroupDetail = actionAdmission.guard {
                    navController.navigate(Routes.groupDetail(chatId))
                },
                onBack = actionAdmission.guard { navController.popBackStack() },
                messageFocusTarget = messageFocusTarget,
            )
        }
    }
}
