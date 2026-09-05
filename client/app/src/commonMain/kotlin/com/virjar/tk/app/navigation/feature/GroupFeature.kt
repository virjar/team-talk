package com.virjar.tk.app.navigation.feature

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.client.ClientSession
import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.shared.client.PendingGroupCreationCommand
import com.virjar.tk.protocol.http.GroupBotSummary
import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.InviteLink
import com.virjar.tk.protocol.model.Member
import com.virjar.tk.protocol.model.User
import com.virjar.tk.app.navigation.UiLocalDataBoundary
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.shared.repository.PendingGroupBotCredentialRecovery
import com.virjar.tk.shared.repository.RecoveredGroupBotCredentials
import com.virjar.tk.app.telemetry.ClientUiAction
import com.virjar.tk.app.telemetry.ClientUiPage
import com.virjar.tk.app.telemetry.ClientUiTelemetrySink
import com.virjar.tk.app.telemetry.NoopClientUiTelemetrySink
import com.virjar.tk.app.telemetry.startActionAttempt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

internal fun isMemberProjectionRefresh(type: NotifyType): Boolean = when (type) {
    NotifyType.MEMBER_ADDED,
    NotifyType.MEMBER_REMOVED,
    NotifyType.MEMBER_MUTED,
    NotifyType.MEMBER_UNMUTED,
    NotifyType.MEMBER_ROLE_CHANGED -> true
    else -> false
}

internal fun shouldClearMentionProjection(currentTarget: String?, disposedChatId: String?): Boolean =
    disposedChatId == null || currentTarget == disposedChatId

internal fun shouldRefreshRecoveredInviteLinks(currentTarget: String?, recoveredChatId: String): Boolean =
    currentTarget == recoveredChatId

data class GroupBotCredentialPresentation(
    val credentials: RecoveredGroupBotCredentials?,
    val pendingRecovery: PendingGroupBotCredentialRecovery?,
    val targetsCurrentChat: Boolean,
)

internal fun resolveGroupBotCredentialPresentation(
    currentChatId: String,
    credentials: RecoveredGroupBotCredentials?,
    pending: PendingGroupBotCredentialRecovery?,
): GroupBotCredentialPresentation = GroupBotCredentialPresentation(
    credentials = credentials,
    pendingRecovery = if (credentials == null) pending else null,
    targetsCurrentChat = (credentials?.chatId ?: pending?.chatId) == currentChatId,
)

/** HTTP 401 已经带着它确切的被拒绝 bearer 路由；通用回调会与之竞争。 */
internal fun shouldReportGroupBotFailure(failure: Throwable): Boolean = failure !is AppError.AuthExpired

/** 群组创建、成员、设置和邀请链接用例。 */
class GroupFeature internal constructor(
    private val session: ClientSession,
    private val scope: CoroutineScope,
    private val reportError: (Throwable, String) -> Unit,
    private val localData: UiLocalDataBoundary,
    private val telemetry: ClientUiTelemetrySink = NoopClientUiTelemetrySink,
) {
    var detailChat by mutableStateOf<Chat?>(null)
        private set
    var members by mutableStateOf(emptyList<Member>())
        private set
    var inviteLinks by mutableStateOf(emptyList<InviteLink>())
        private set
    var detailTargetChatId by mutableStateOf<String?>(null)
        private set
    var mentionUsers by mutableStateOf(emptyList<User>())
        private set
    var mentionTargetChatId by mutableStateOf<String?>(null)
        private set
    var inviteLinksTargetChatId by mutableStateOf<String?>(null)
        private set
    var groupBots by mutableStateOf(emptyList<GroupBotSummary>())
        private set
    var groupBotsTargetChatId by mutableStateOf<String?>(null)
        private set
    var groupBotsLoading by mutableStateOf(false)
        private set
    var groupBotsError by mutableStateOf<String?>(null)
        private set
    private var unacknowledgedGroupBotCredentials by mutableStateOf<RecoveredGroupBotCredentials?>(null)
    var pendingGroupBotCredentialRecovery by mutableStateOf<PendingGroupBotCredentialRecovery?>(null)
        private set
    private var groupBotCredentialRecoveryFailure: Throwable? = null
    private var abandoningGroupBotCredentialOperationId: String? = null
    val hasUnacknowledgedGroupBotCredential: Boolean
        get() = unacknowledgedGroupBotCredentials != null || pendingGroupBotCredentialRecovery != null
    var creatingGroupBot by mutableStateOf(false)
        private set
    var groupBotOperationId by mutableStateOf<String?>(null)
        private set
    var pendingGroupCreation by mutableStateOf<PendingGroupCreationCommand?>(null)
        private set
    var groupCreationDraftLoaded by mutableStateOf(false)
        private set
    var groupCreationDraftError by mutableStateOf<String?>(null)
        private set

    private val detailGate = LatestRequestGate<String>()
    private val inviteLinksGate = LatestRequestGate<String>()
    private val groupBotsGate = LatestRequestGate<String>()
    private var detailProjectionJob: Job? = null
    private var mentionProjectionJob: Job? = null
    private var reconnectRefreshJob: Job? = null
    private val groupBotCredentialLifecycle = GroupBotCredentialLifecycle(
        repository = session.groupBotManagementRepo,
        localData = localData,
        isOwnerActive = session::isBusinessActive,
        publishSnapshot = { snapshot ->
            unacknowledgedGroupBotCredentials = snapshot.recovered
            pendingGroupBotCredentialRecovery = snapshot.pending
            groupBotCredentialRecoveryFailure = snapshot.failure
        },
    )

    init {
        // 凭据命令是本地可靠事实，与机器人页面当前是否选中无关。确切的 replay
        // 可以在 ACK 丢失或进程重启之后安全恢复客户端持有的那一个 token；
        // 任何不确定的传输/认证失败都不得退役它。
        scope.launch { recoverPendingGroupBotCredential(reportFailures = false) }
        scope.launch {
            try {
                val restored = localData.run { session.chatRepo.getPendingGroupCreation() }
                if (session.isBusinessActive) {
                    pendingGroupCreation = restored
                    groupCreationDraftError = null
                    groupCreationDraftLoaded = true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (session.isBusinessActive) {
                    // Fail closed：不要让一次新操作覆盖一条无法恢复、
                    // 并且可能已经在服务器上权威的持久命令。
                    reportError(failure, "恢复待创建群组失败")
                    groupCreationDraftError =
                        "本地待创建群组记录无法恢复，当前不能创建群组；请返回并清理该账号本地数据后重试"
                    groupCreationDraftLoaded = true
                }
            }
        }
        scope.launch {
            session.eventProcessor.chatEvents.collect { (type, chat) ->
                // MEMBER_* 只携带 Chat 摘要。它是一个已认证的刷新提示，
                // 绝不是可以直接写进成员投影的成员事实。
                if (
                    isMemberProjectionRefresh(type) &&
                    session.connectionState.value == ConnectionState.AUTHENTICATED &&
                    isActiveMemberTarget(chat.chatId)
                ) {
                    refreshActiveMemberProjection(chat.chatId, reportFailures = false)
                }
            }
        }
        scope.launch {
            var authenticated = session.connectionState.value == ConnectionState.AUTHENTICATED
            session.connectionState.collect { state ->
                val nowAuthenticated = state == ConnectionState.AUTHENTICATED
                if (nowAuthenticated && !authenticated) {
                    reconnectRefreshJob?.cancel()
                    reconnectRefreshJob = scope.launch { refreshActiveProjectionsAfterReconnect() }
                }
                authenticated = nowAuthenticated
            }
        }
        scope.launch {
            session.inviteLinkRecoveryCompletions.collect { recoveredChatId ->
                if (shouldRefreshRecoveredInviteLinks(inviteLinksTargetChatId, recoveredChatId)) {
                    loadInviteLinks(recoveredChatId, clearBeforeLoad = false)
                }
            }
        }
    }

    /** 把 composer 绑定到持久化的成员/用户投影，然后在在线时刷新它。 */
    suspend fun loadMentionCandidates(chatId: String) {
        bindMentionProjection(chatId)
        if (session.connectionState.value == ConnectionState.AUTHENTICATED) {
            refreshMemberProjection(chatId, reportFailures = false)
        }
    }

    /** 释放 composer 的投影，而不让过期的 route 清除更新的目标。 */
    fun clearMentionCandidates(chatId: String? = null) {
        if (!shouldClearMentionProjection(mentionTargetChatId, chatId)) return
        mentionTargetChatId = null
        mentionUsers = emptyList()
        mentionProjectionJob?.cancel()
        mentionProjectionJob = null
    }

    internal suspend fun loadDetail(chatId: String) {
        bindDetailProjection(chatId)
        refreshDetailProjection(chatId, reportFailures = true)
    }

    private fun bindDetailProjection(chatId: String) {
        val cache = session.localCache
        val targetChanged = detailTargetChatId != chatId
        detailTargetChatId = chatId
        if (targetChanged) {
            detailChat = null
            members = emptyList()
        }
        detailProjectionJob?.cancel()
        detailProjectionJob = scope.launch {
            localData.projection {
                combine(cache.observeChat(chatId), cache.observeMembers(chatId)) { chat, projectedMembers ->
                    chat to projectedMembers
                }
            }.collect { (chat, projectedMembers) ->
                    if (detailTargetChatId != chatId) return@collect
                    detailChat = chat?.takeIf { it.chatId == chatId }
                    members = projectedMembers.filter { it.chatId == chatId }
                }
        }
    }

    private fun bindMentionProjection(chatId: String) {
        val cache = session.localCache
        val targetChanged = mentionTargetChatId != chatId
        mentionTargetChatId = chatId
        if (targetChanged) mentionUsers = emptyList()
        mentionProjectionJob?.cancel()
        mentionProjectionJob = scope.launch {
            localData.projection { cache.observeMembers(chatId) }.collect { projectedMembers ->
                if (mentionTargetChatId == chatId) mentionUsers = memberUsers(projectedMembers)
            }
        }
    }

    private suspend fun refreshDetailProjection(chatId: String, reportFailures: Boolean) {
        val token = detailGate.begin(chatId)
        if (session.connectionState.value != ConnectionState.AUTHENTICATED) return

        val chatResult = localData.run { session.chatRepo.getChat(chatId) }
        if (!detailGate.isCurrent(token)) return
        when (chatResult) {
            is Outcome.Success -> if (chatResult.value == null && detailChat == null) {
                if (reportFailures) reportError(IllegalStateException("群详情不存在"), "加载群详情失败")
            }
            is Outcome.Failure -> if (
                reportFailures &&
                shouldReportCacheRefreshFailure(
                    chatResult.error,
                    detailTargetChatId == chatId && detailChat != null,
                )
            ) {
                reportError(chatResult.error, "加载群详情失败")
            }
        }

        val memberResult = localData.run { session.chatRepo.getMembers(chatId) }
        if (!detailGate.isCurrent(token)) return
        if (memberResult is Outcome.Failure && reportFailures) {
            val hasLocalProjection = detailTargetChatId == chatId &&
                (detailChat != null || members.isNotEmpty())
            if (shouldReportCacheRefreshFailure(memberResult.error, hasLocalProjection)) {
                reportError(memberResult.error, "加载群成员失败")
            }
        }
    }

    internal suspend fun loadInviteLinks(chatId: String) {
        loadInviteLinks(chatId, clearBeforeLoad = true)
    }

    internal suspend fun loadGroupBots(chatId: String) {
        loadGroupBots(chatId, clearBeforeLoad = true)
    }

    private suspend fun loadGroupBots(chatId: String, clearBeforeLoad: Boolean) {
        val token = groupBotsGate.begin(chatId)
        groupBotsTargetChatId = chatId
        if (clearBeforeLoad) {
            groupBots = emptyList()
            groupBotsError = null
        }
        groupBotsLoading = true
        try {
            val recoveryFailure = recoverPendingGroupBotCredential(reportFailures = false)
            val loaded = session.groupBotManagementRepo.list(chatId).getOrThrow()
            if (!session.isBusinessActive) return
            if (!groupBotsGate.isCurrent(token)) return
            groupBots = loaded
            groupBotsError = recoveryFailure?.message
            recoveryFailure?.let { failure ->
                if (shouldReportGroupBotFailure(failure)) reportError(failure, "恢复群机器人凭据失败")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            if (session.isBusinessActive && groupBotsGate.isCurrent(token)) {
                groupBotsError = e.message ?: "加载群机器人失败"
                if (shouldReportGroupBotFailure(e)) reportError(e, "加载群机器人失败")
            }
        } finally {
            if (session.isBusinessActive && groupBotsGate.isCurrent(token)) groupBotsLoading = false
        }
    }

    fun createGroupBot(chatId: String, name: String) {
        if (
            !groupBotsGate.targets(chatId) ||
            creatingGroupBot ||
            groupBotOperationId != null ||
            hasUnacknowledgedGroupBotCredential
        ) return
        creatingGroupBot = true
        groupBotsError = null
        scope.launch {
            try {
                val result = localData.run { session.groupBotManagementRepo.create(chatId, name) }
                if (!session.isBusinessActive) return@launch
                val snapshot = groupBotCredentialLifecycle.publishCommandResult(chatId, result)
                if (!session.isBusinessActive) return@launch
                snapshot.failure?.let { throw it }
                refreshGroupBotsIfCurrent(chatId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (session.isBusinessActive && groupBotsGate.targets(chatId)) {
                    groupBotsError = e.message ?: "创建机器人失败"
                    if (shouldReportGroupBotFailure(e)) reportError(e, "创建机器人失败")
                }
            } finally {
                if (session.isBusinessActive) creatingGroupBot = false
            }
        }
    }

    fun rotateGroupBotToken(chatId: String, botId: String) {
        if (
            !groupBotsGate.targets(chatId) ||
            creatingGroupBot ||
            groupBotOperationId != null ||
            hasUnacknowledgedGroupBotCredential
        ) return
        groupBotOperationId = botId
        groupBotsError = null
        scope.launch {
            try {
                val result = localData.run { session.groupBotManagementRepo.rotate(chatId, botId) }
                if (!session.isBusinessActive) return@launch
                val snapshot = groupBotCredentialLifecycle.publishCommandResult(chatId, result)
                if (!session.isBusinessActive) return@launch
                snapshot.failure?.let { throw it }
                refreshGroupBotsIfCurrent(chatId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (session.isBusinessActive && groupBotsGate.targets(chatId)) {
                    groupBotsError = e.message ?: "轮换机器人 Token 失败"
                    if (shouldReportGroupBotFailure(e)) reportError(e, "轮换机器人 Token 失败")
                }
            } finally {
                if (session.isBusinessActive) groupBotOperationId = null
            }
        }
    }

    fun removeGroupBot(chatId: String, botId: String) {
        if (!groupBotsGate.targets(chatId) || creatingGroupBot || groupBotOperationId != null) return
        groupBotOperationId = botId
        groupBotsError = null
        scope.launch {
            try {
                session.groupBotManagementRepo.remove(chatId, botId).getOrThrow()
                if (!session.isBusinessActive) return@launch
                if (!groupBotsGate.targets(chatId)) return@launch
                refreshGroupBotsIfCurrent(chatId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (session.isBusinessActive && groupBotsGate.targets(chatId)) {
                    groupBotsError = e.message ?: "移除机器人失败"
                    if (shouldReportGroupBotFailure(e)) reportError(e, "移除机器人失败")
                }
            } finally {
                if (session.isBusinessActive) groupBotOperationId = null
            }
        }
    }

    fun groupBotCredentialPresentation(chatId: String): GroupBotCredentialPresentation =
        resolveGroupBotCredentialPresentation(
            currentChatId = chatId,
            credentials = unacknowledgedGroupBotCredentials,
            pending = pendingGroupBotCredentialRecovery,
        )

    fun dismissGroupBotCredentials(chatId: String) {
        val recovered = unacknowledgedGroupBotCredentials?.takeIf { it.chatId == chatId } ?: return
        val credentials = recovered.credentials
        scope.launch {
            try {
                val cleared = groupBotCredentialLifecycle.acknowledge(credentials)
                if (!session.isBusinessActive) return@launch
                val current = unacknowledgedGroupBotCredentials
                if (current?.credentials?.operationId != credentials.operationId) return@launch
                if (!cleared) {
                    reportError(
                        IllegalStateException("群机器人凭据确认记录不匹配"),
                        "关闭群机器人凭据失败",
                    )
                    return@launch
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (session.isBusinessActive) reportError(failure, "关闭群机器人凭据失败")
            }
        }
    }

    fun abandonPendingGroupBotCredentialRecovery() {
        val pending = pendingGroupBotCredentialRecovery ?: return
        if (abandoningGroupBotCredentialOperationId != null) return
        abandoningGroupBotCredentialOperationId = pending.operationId
        scope.launch {
            try {
                val cleared = groupBotCredentialLifecycle.abandon(pending)
                if (!session.isBusinessActive) return@launch
                if (pendingGroupBotCredentialRecovery?.operationId != pending.operationId) return@launch
                if (!cleared) {
                    reportError(
                        IllegalStateException("群机器人待恢复记录不匹配"),
                        "放弃恢复群机器人凭据失败",
                    )
                    return@launch
                }
                groupBotsError = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (session.isBusinessActive) reportError(failure, "放弃恢复群机器人凭据失败")
            } finally {
                if (abandoningGroupBotCredentialOperationId == pending.operationId) {
                    abandoningGroupBotCredentialOperationId = null
                }
            }
        }
    }

    private suspend fun loadInviteLinks(chatId: String, clearBeforeLoad: Boolean) {
        val token = inviteLinksGate.begin(chatId)
        inviteLinksTargetChatId = chatId
        if (clearBeforeLoad) inviteLinks = emptyList()
        try {
            val loaded = session.chatRepo.listInviteLinks(chatId).getOrThrow()
            if (!inviteLinksGate.isCurrent(token)) return
            if (loaded.any { it.chatId != chatId }) {
                reportError(IllegalStateException("邀请链接响应身份不匹配"), "加载邀请链接失败")
                return
            }
            inviteLinks = loaded
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            if (inviteLinksGate.isCurrent(token)) reportError(e, "加载邀请链接失败")
        }
    }

    suspend fun create(name: String, memberUids: List<String>): String? {
        val attempt = telemetry.startActionAttempt(
            ClientUiPage.CREATE_GROUP,
            ClientUiAction.CREATE_GROUP,
        )
        return try {
            check(groupCreationDraftLoaded) { "建群草稿尚未完成本地恢复" }
            val (result, persisted) = localData.run {
                val outcome = session.chatRepo.createRecoverableGroup(
                    name = name,
                    memberUids = memberUids,
                )
                outcome to session.chatRepo.getPendingGroupCreation()
            }
            pendingGroupCreation = persisted
            when (result) {
                is Outcome.Success -> {
                    attempt.succeed()
                    val refresh = localData.run { session.conversationRepo.listConversations() }
                    if (refresh is Outcome.Failure) {
                        // 服务器已经确认创建，命令回执已清除。
                        // 投影刷新失败绝不能把那次成功变成一次重复 retry。
                        reportError(refresh.error, "刷新会话列表失败")
                    }
                    result.value.chatId
                }
                is Outcome.Failure -> {
                    attempt.fail()
                    reportError(result.error, "创建群组失败")
                    null
                }
            }
        } catch (cancelled: CancellationException) {
            attempt.cancel()
            throw cancelled
        } catch (failure: Exception) {
            attempt.fail()
            reportError(failure, "创建群组失败")
            null
        }
    }

    /** 显式放弃恢复的命令；下一次提交收到一个新的 operation ID。 */
    suspend fun discardPendingCreation(): Boolean {
        val current = pendingGroupCreation ?: return true
        return try {
            val remaining = localData.run {
                session.chatRepo.discardPendingGroupCreation(current.operationId)
                session.chatRepo.getPendingGroupCreation()
            }
            pendingGroupCreation = remaining
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            reportError(failure, "放弃待创建群组失败")
            false
        }
    }

    fun setMemberRole(chatId: String, uid: String, role: Int) = scope.launch {
        runAndRefresh(chatId, "修改角色失败") { session.chatRepo.setMemberRole(chatId, uid, role).getOrThrow() }
    }

    fun muteMember(chatId: String, uid: String, duration: Int = 3600) = scope.launch {
        runAndRefresh(chatId, "禁言失败") { session.chatRepo.muteMember(chatId, uid, duration).getOrThrow() }
    }

    fun unmuteMember(chatId: String, uid: String) = scope.launch {
        runAndRefresh(chatId, "解除禁言失败") { session.chatRepo.unmuteMember(chatId, uid).getOrThrow() }
    }

    fun removeMember(chatId: String, uid: String) = scope.launch {
        runAndRefresh(chatId, "移除成员失败") { session.chatRepo.removeMember(chatId, uid).getOrThrow() }
    }

    fun updateNotice(chatId: String, notice: String) = scope.launch {
        runAndRefresh(chatId, "更新群公告失败") { session.chatRepo.updateGroup(chatId, notice = notice).getOrThrow() }
    }

    fun exit(chatId: String, dissolve: Boolean, onExited: () -> Unit) = scope.launch {
        try {
            localData.run {
                if (dissolve) session.chatRepo.dissolveGroup(chatId).getOrThrow()
                else session.chatRepo.leaveGroup(chatId).getOrThrow()
            }
            onExited()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            reportError(e, if (dissolve) "解散群组失败" else "退出群组失败")
        }
    }

    suspend fun inviteMembers(chatId: String, uids: List<String>): Boolean = try {
        localData.run { session.chatRepo.addMembers(chatId, uids).getOrThrow() }
        refreshActiveMemberProjection(chatId, reportFailures = false)
        true
    } catch (e: AppError) {
        reportError(e, "邀请成员失败")
        false
    }

    suspend fun createInviteLink(chatId: String): String? {
        val attempt = telemetry.startActionAttempt(
            ClientUiPage.INVITE_LINKS,
            ClientUiAction.CREATE_INVITE_LINK,
        )
        return try {
            val token = localData.run { session.chatRepo.createInviteLink(chatId).getOrThrow() }
            attempt.succeed()
            refreshInviteLinksIfCurrent(chatId)
            token
        } catch (cancelled: CancellationException) {
            attempt.cancel()
            throw cancelled
        } catch (e: Exception) {
            attempt.fail()
            if (inviteLinksGate.targets(chatId)) reportError(e, "创建链接失败")
            null
        }
    }

    fun revokeInviteLink(chatId: String, token: String) = scope.launch {
        try {
            session.chatRepo.revokeInviteLink(token).getOrThrow()
            refreshInviteLinksIfCurrent(chatId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            if (inviteLinksGate.targets(chatId)) reportError(e, "撤销链接失败")
        }
    }

    private suspend fun runAndRefresh(chatId: String, fallback: String, action: suspend () -> Unit) {
        try {
            localData.run { action() }
            refreshDetailIfCurrent(chatId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            if (detailGate.targets(chatId)) reportError(e, fallback)
        }
    }

    private suspend fun refreshDetailIfCurrent(chatId: String) {
        if (detailTargetChatId == chatId) refreshDetailProjection(chatId, reportFailures = false)
    }

    private fun isActiveMemberTarget(chatId: String): Boolean =
        detailTargetChatId == chatId || mentionTargetChatId == chatId

    private suspend fun refreshActiveMemberProjection(chatId: String, reportFailures: Boolean) {
        if (isActiveMemberTarget(chatId)) refreshMemberProjection(chatId, reportFailures)
    }

    private suspend fun refreshMemberProjection(chatId: String, reportFailures: Boolean) {
        if (session.connectionState.value != ConnectionState.AUTHENTICATED) return
        val result = localData.run { session.chatRepo.getMembers(chatId) }
        if (result is Outcome.Failure && reportFailures && isActiveMemberTarget(chatId)) {
            val hasLocalProjection = when {
                detailTargetChatId == chatId -> detailChat != null || members.isNotEmpty()
                mentionTargetChatId == chatId -> mentionUsers.isNotEmpty()
                else -> false
            }
            if (shouldReportCacheRefreshFailure(result.error, hasLocalProjection)) {
                reportError(result.error, "刷新群成员失败")
            }
        }
    }

    private suspend fun refreshActiveProjectionsAfterReconnect() {
        val groupBotTarget = groupBotsTargetChatId
        if (groupBotTarget == null || !groupBotsGate.targets(groupBotTarget)) {
            recoverPendingGroupBotCredential(reportFailures = false)
        }
        val detailTarget = detailTargetChatId
        if (detailTarget != null) refreshDetailProjection(detailTarget, reportFailures = false)
        val mentionTarget = mentionTargetChatId
        if (mentionTarget != null && mentionTarget != detailTarget) {
            refreshMemberProjection(mentionTarget, reportFailures = false)
        }
        groupBotTarget?.let { target ->
            if (groupBotsGate.targets(target)) loadGroupBots(target, clearBeforeLoad = false)
        }
    }

    /** 发布脱敏的封禁状态；只有经过证明的服务器终止响应才自动退役。 */
    private suspend fun recoverPendingGroupBotCredential(reportFailures: Boolean): Throwable? {
        val snapshot = groupBotCredentialLifecycle.recover()
        val failure = snapshot.failure
        if (
            failure != null && reportFailures && session.isBusinessActive &&
            shouldReportGroupBotFailure(failure)
        ) {
            reportError(failure, "恢复群机器人凭据失败")
        }
        return failure
    }

    private fun memberUsers(projectedMembers: List<Member>): List<User> = projectedMembers
        .asSequence()
        .mapNotNull(Member::user)
        .distinctBy(User::uid)
        .toList()

    private suspend fun refreshInviteLinksIfCurrent(chatId: String) {
        if (inviteLinksGate.targets(chatId)) loadInviteLinks(chatId, clearBeforeLoad = false)
    }

    private suspend fun refreshGroupBotsIfCurrent(chatId: String) {
        if (groupBotsGate.targets(chatId)) loadGroupBots(chatId, clearBeforeLoad = false)
    }
}
