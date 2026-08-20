package com.virjar.tk.navigation.feature

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.virjar.tk.AppError
import com.virjar.tk.client.ClientSession
import com.virjar.tk.model.Chat
import com.virjar.tk.model.InviteLink
import com.virjar.tk.model.Member
import com.virjar.tk.model.User
import com.virjar.tk.client.defaultServerConfig
import com.virjar.tk.http.GroupBotCredentials
import com.virjar.tk.http.GroupBotSummary
import com.virjar.tk.repository.GroupBotManagementRepository
import com.virjar.tk.repository.HttpGroupBotManagementRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Group creation, membership, settings and invite-link use cases. */
class GroupFeature internal constructor(
    private val session: ClientSession,
    private val scope: CoroutineScope,
    private val reportError: (Throwable, String) -> Unit,
    private val botRepositoryFactory: () -> GroupBotManagementRepository = {
        HttpGroupBotManagementRepository(defaultServerConfig().serverUrl, session.userSession.accessToken)
    },
) {
    var detailChat by mutableStateOf<Chat?>(null)
        private set
    var members by mutableStateOf(emptyList<Member>())
        private set
    var inviteLinks by mutableStateOf(emptyList<InviteLink>())
        private set
    var detailTargetChatId by mutableStateOf<String?>(null)
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
    private var groupBotCredentialsByChat by mutableStateOf<Map<String, GroupBotCredentials>>(emptyMap())
    var creatingGroupBot by mutableStateOf(false)
        private set
    var groupBotOperationId by mutableStateOf<String?>(null)
        private set

    private val detailGate = LatestRequestGate<String>()
    private val inviteLinksGate = LatestRequestGate<String>()
    private val groupBotsGate = LatestRequestGate<String>()

    /** Optional composer query; platform shells never receive the chat repository itself. */
    suspend fun mentionCandidates(chatId: String): List<User> = try {
        session.chatRepo.getMembers(chatId).getOrThrow().mapNotNull(Member::user)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyList()
    }

    internal suspend fun loadDetail(chatId: String) {
        loadDetail(chatId, clearBeforeLoad = true)
    }

    private suspend fun loadDetail(chatId: String, clearBeforeLoad: Boolean) {
        val token = detailGate.begin(chatId)
        detailTargetChatId = chatId
        if (clearBeforeLoad) {
            detailChat = null
            members = emptyList()
        }
        try {
            // 先加载到局部变量，再原子提交，避免 chat 已是 B 而 members 仍是 A。
            val loadedChat = session.chatRepo.getChat(chatId).getOrThrow()
                ?: throw IllegalStateException("群详情不存在")
            val loadedMembers = session.chatRepo.getMembers(chatId).getOrThrow()
            if (!detailGate.isCurrent(token)) return
            if (loadedChat.chatId != chatId || loadedMembers.any { it.chatId != chatId }) {
                reportError(IllegalStateException("群详情响应身份不匹配"), "加载群详情失败")
                return
            }
            detailChat = loadedChat
            members = loadedMembers
        } catch (e: Exception) {
            if (detailGate.isCurrent(token)) reportError(e, "加载群详情失败")
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
            val loaded = botRepositoryFactory().list(chatId).getOrThrow()
            if (!groupBotsGate.isCurrent(token)) return
            groupBots = loaded
            groupBotsError = null
        } catch (e: Exception) {
            if (groupBotsGate.isCurrent(token)) {
                groupBotsError = e.message ?: "加载群机器人失败"
                reportError(e, "加载群机器人失败")
            }
        } finally {
            if (groupBotsGate.isCurrent(token)) groupBotsLoading = false
        }
    }

    fun createGroupBot(chatId: String, name: String) {
        if (
            !groupBotsGate.targets(chatId) ||
            creatingGroupBot ||
            groupBotOperationId != null ||
            groupBotCredentialsByChat.containsKey(chatId)
        ) return
        creatingGroupBot = true
        groupBotsError = null
        scope.launch {
            try {
                val created = botRepositoryFactory().create(chatId, name).getOrThrow()
                groupBotCredentialsByChat = groupBotCredentialsByChat + (chatId to created)
                refreshGroupBotsIfCurrent(chatId)
            } catch (e: Exception) {
                if (groupBotsGate.targets(chatId)) {
                    groupBotsError = e.message ?: "创建机器人失败"
                    reportError(e, "创建机器人失败")
                }
            } finally {
                creatingGroupBot = false
            }
        }
    }

    fun rotateGroupBotToken(chatId: String, botId: String) {
        if (
            !groupBotsGate.targets(chatId) ||
            creatingGroupBot ||
            groupBotOperationId != null ||
            groupBotCredentialsByChat.containsKey(chatId)
        ) return
        groupBotOperationId = botId
        groupBotsError = null
        scope.launch {
            try {
                val rotated = botRepositoryFactory().rotate(chatId, botId).getOrThrow()
                groupBotCredentialsByChat = groupBotCredentialsByChat + (chatId to rotated)
                refreshGroupBotsIfCurrent(chatId)
            } catch (e: Exception) {
                if (groupBotsGate.targets(chatId)) {
                    groupBotsError = e.message ?: "轮换机器人 Token 失败"
                    reportError(e, "轮换机器人 Token 失败")
                }
            } finally {
                groupBotOperationId = null
            }
        }
    }

    fun removeGroupBot(chatId: String, botId: String) {
        if (!groupBotsGate.targets(chatId) || creatingGroupBot || groupBotOperationId != null) return
        groupBotOperationId = botId
        groupBotsError = null
        scope.launch {
            try {
                botRepositoryFactory().remove(chatId, botId).getOrThrow()
                if (!groupBotsGate.targets(chatId)) return@launch
                refreshGroupBotsIfCurrent(chatId)
            } catch (e: Exception) {
                if (groupBotsGate.targets(chatId)) {
                    groupBotsError = e.message ?: "移除机器人失败"
                    reportError(e, "移除机器人失败")
                }
            } finally {
                groupBotOperationId = null
            }
        }
    }

    fun groupBotCredentialsFor(chatId: String): GroupBotCredentials? = groupBotCredentialsByChat[chatId]

    fun dismissGroupBotCredentials(chatId: String) {
        groupBotCredentialsByChat = groupBotCredentialsByChat - chatId
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
        } catch (e: Exception) {
            if (inviteLinksGate.isCurrent(token)) reportError(e, "加载邀请链接失败")
        }
    }

    suspend fun create(name: String, memberUids: List<String>): String? = try {
        val chat = session.chatRepo.createGroup(name, memberUids = memberUids).getOrThrow()
        session.conversationRepo.listConversations()
        chat.chatId
    } catch (e: AppError) {
        reportError(e, "创建群组失败")
        null
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
            if (dissolve) session.chatRepo.dissolveGroup(chatId).getOrThrow()
            else session.chatRepo.leaveGroup(chatId).getOrThrow()
            onExited()
        } catch (e: Exception) {
            reportError(e, if (dissolve) "解散群组失败" else "退出群组失败")
        }
    }

    suspend fun inviteMembers(chatId: String, uids: List<String>): Boolean = try {
        session.chatRepo.addMembers(chatId, uids).getOrThrow()
        true
    } catch (e: AppError) {
        reportError(e, "邀请成员失败")
        false
    }

    suspend fun createInviteLink(chatId: String): String? = try {
        val token = session.chatRepo.createInviteLink(chatId).getOrThrow()
        refreshInviteLinksIfCurrent(chatId)
        token
    } catch (e: Exception) {
        if (inviteLinksGate.targets(chatId)) reportError(e, "创建链接失败")
        null
    }

    fun revokeInviteLink(chatId: String, token: String) = scope.launch {
        try {
            session.chatRepo.revokeInviteLink(token).getOrThrow()
            refreshInviteLinksIfCurrent(chatId)
        } catch (e: Exception) {
            if (inviteLinksGate.targets(chatId)) reportError(e, "撤销链接失败")
        }
    }

    private suspend fun runAndRefresh(chatId: String, fallback: String, action: suspend () -> Unit) {
        try {
            action()
            refreshDetailIfCurrent(chatId)
        } catch (e: Exception) {
            if (detailGate.targets(chatId)) reportError(e, fallback)
        }
    }

    private suspend fun refreshDetailIfCurrent(chatId: String) {
        if (detailGate.targets(chatId)) loadDetail(chatId, clearBeforeLoad = false)
    }

    private suspend fun refreshInviteLinksIfCurrent(chatId: String) {
        if (inviteLinksGate.targets(chatId)) loadInviteLinks(chatId, clearBeforeLoad = false)
    }

    private suspend fun refreshGroupBotsIfCurrent(chatId: String) {
        if (groupBotsGate.targets(chatId)) loadGroupBots(chatId, clearBeforeLoad = false)
    }
}
