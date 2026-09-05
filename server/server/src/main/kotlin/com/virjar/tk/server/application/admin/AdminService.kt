package com.virjar.tk.server.application.admin

import com.virjar.tk.server.domain.bot.BotService
import com.virjar.tk.server.domain.chat.ChatRepository
import com.virjar.tk.server.domain.chat.ChatService
import com.virjar.tk.server.domain.contact.ContactRepository
import com.virjar.tk.server.domain.auth.DeviceRepository
import com.virjar.tk.server.domain.document.DocumentCustodyAdministrationService
import com.virjar.tk.server.domain.message.MessageService
import com.virjar.tk.server.domain.message.MessageRepository
import com.virjar.tk.server.domain.message.MessageSearch
import com.virjar.tk.server.domain.organization.OrganizationService
import com.virjar.tk.server.domain.session.OnlineSessions
import com.virjar.tk.server.domain.user.UserRepository
import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.Device
import com.virjar.tk.protocol.model.Member
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.User
import kotlinx.serialization.Serializable

/**
 * 管理后台聚合服务：运维查询与治理操作。
 * 直接编排各域 Store/Repo/Service；管理端语义（分页/全局视图）与终端用户语义分离。
 */
class AdminService internal constructor(
    private val adminUsers: AdminUserDirectory,
    private val adminChats: AdminChatDirectory,
    private val diagnostics: AdminDiagnostics,
    private val overviewAssembler: AdminOverviewAssembler,
    private val userRepository: UserRepository,
    private val deviceRepository: DeviceRepository,
    private val contactRepository: ContactRepository,
    private val chatRepository: ChatRepository,
    private val chatService: ChatService,
    private val messageService: MessageService,
    private val messages: MessageRepository,
    private val search: MessageSearch,
    private val credentialCommands: AdminCredentialCommands,
    private val onlineSessions: OnlineSessions,
    private val organizationService: OrganizationService,
    private val botService: BotService,
    private val documentCustodyAdministration: DocumentCustodyAdministrationService,
) {

    // ── Document 资产责任交接 ──

    suspend fun planDocumentCustody(
        sourceUid: String,
        targetOwnerPrincipalType: Int,
        targetOwnerPrincipalId: String,
        targetStewardUid: String,
    ) = documentCustodyAdministration.plan(
        sourceUid,
        targetOwnerPrincipalType,
        targetOwnerPrincipalId,
        targetStewardUid,
    )

    suspend fun transferDocumentCustody(
        adminPrincipal: String,
        sourceUid: String,
        operationId: String,
        expectedPlanFingerprint: String,
        targetOwnerPrincipalType: Int,
        targetOwnerPrincipalId: String,
        targetStewardUid: String,
    ) = documentCustodyAdministration.transfer(
        adminPrincipal,
        sourceUid,
        operationId,
        expectedPlanFingerprint,
        targetOwnerPrincipalType,
        targetOwnerPrincipalId,
        targetStewardUid,
    )

    // ── 组织架构 ──

    fun listOrganizationUnits() = organizationService.listUnits()

    fun listOrganizationMembers(unitId: String, recursive: Boolean) =
        organizationService.listMembers(unitId, recursive)

    suspend fun createOrganizationUnit(
        parentId: String?,
        name: String,
        leaderUid: String?,
        sortOrder: Int,
        enableGroup: Boolean,
    ) = organizationService.createUnit(parentId, name, leaderUid, sortOrder, enableGroup)

    suspend fun updateOrganizationUnit(
        unitId: String,
        parentId: String?,
        name: String,
        leaderUid: String?,
        sortOrder: Int,
    ) = organizationService.updateUnit(unitId, parentId, name, leaderUid, sortOrder)

    suspend fun archiveOrganizationUnit(unitId: String) = organizationService.archiveUnit(unitId)

    suspend fun assignOrganizationMember(unitId: String, uid: String, title: String?, primary: Boolean) =
        organizationService.assignMember(unitId, uid, title, primary)

    suspend fun removeOrganizationMember(unitId: String, uid: String) =
        organizationService.removeMember(unitId, uid)

    suspend fun enableDepartmentGroup(unitId: String) = organizationService.enableDepartmentGroup(unitId)

    suspend fun disableDepartmentGroup(unitId: String) = organizationService.disableDepartmentGroup(unitId)

    suspend fun reconcileDepartmentGroups() = organizationService.reconcileAllManagedGroups()

    // ── 通知机器人 ──

    fun listBots() = botService.list()
    suspend fun createBot(name: String) = botService.create(name)
    suspend fun rotateBotToken(botId: String) = botService.rotateToken(botId)
    suspend fun disableBot(botId: String) = botService.disable(botId)
    suspend fun grantBot(botId: String, chatId: String) = botService.grant(botId, chatId)
    suspend fun revokeBotGrant(botId: String, chatId: String) = botService.revokeGrant(botId, chatId)

    // ── 用户 ──

    fun listUsers(query: String?, pagination: AdminPageRequest): AdminPage<User> =
        adminUsers.listUsers(query, pagination)

    @Serializable
    data class UserDetail(
        val user: User,
        val devices: List<Device>,
        val friends: List<com.virjar.tk.protocol.model.Contact>,
        val groups: List<Chat>,
        val online: Boolean,
    )

    suspend fun userDetail(uid: String): UserDetail {
        val user = userRepository.findByUid(uid) ?: throw IllegalArgumentException("用户不存在: $uid")
        return UserDetail(
            user = user,
            devices = deviceRepository.getDevices(uid),
            friends = contactRepository.listFriends(uid),
            groups = chatRepository.listUserChats(uid).filter { it.chatType == 2 },
            online = onlineSessions.isOnline(uid),
        )
    }

    /** 封禁事实与凭据 epoch 在 PG 原子提交，随后发布不可逆在线会话 fence。 */
    suspend fun banUser(uid: String) = credentialCommands.ban(uid)

    suspend fun unbanUser(uid: String) = credentialCommands.unban(uid)

    suspend fun kickAll(uid: String) = onlineSessions.kickUser(uid)

    /** 重置密码并使所有旧凭据终止；密码计算由共享安全端口承担。 */
    suspend fun resetPassword(uid: String, newPassword: String) =
        credentialCommands.resetPassword(uid, newPassword)

    // ── 消息 ──

    @Serializable
    data class MessageSearchResult(
        val total: Int,
        val items: List<Message>,
        /** 以 `chatId:serverSeq` 为键；clientMsgId 只在单个聊天内唯一。 */
        val highlights: Map<String, String>,
    )

    fun searchMessages(
        keyword: String?,
        chatId: String?,
        senderUid: String?,
        start: Long?,
        end: Long?,
        pagination: AdminPageRequest,
    ): MessageSearchResult {
        val chatIds = chatId?.takeIf { it.isNotBlank() }?.let { setOf(it) } ?: emptySet()
        val resultPage = search.search(
            query = keyword ?: "",  // 空=浏览模式（SearchIndex match-all）
            chatIds = chatIds,
            senderUid = senderUid?.takeIf { it.isNotBlank() },
            startTimestamp = start?.takeIf { it > 0 },
            endTimestamp = end?.takeIf { it > 0 },
            limit = pagination.size,
            offset = pagination.searchOffset(),
        )
        val resultMessages = resultPage.hits.mapNotNull { messages.getMessage(it.chatId, it.seq) }
        val highlights = resultPage.hits.associate { hit ->
            adminMessageIdentity(hit.chatId, hit.seq) to hit.highlight
        }
        return MessageSearchResult(resultPage.total, resultMessages, highlights)
    }

    /** 消息上下文（围绕 seq 前后各 contextSize/2 条）。 */
    fun messageContext(chatId: String, seq: Long, contextSize: Int = 20): List<Message> {
        val half = contextSize / 2
        val before = messages.getHistory(chatId, seq, half, forward = false).asReversed()
        val after = messages.getHistory(chatId, seq + 1, half, forward = true)
        return before + after
    }

    /** 管理员撤回：免 sender/成员权限检查，广播链路复用。 */
    suspend fun revokeMessage(chatId: String, seq: Long) {
        messageService.adminRevoke(chatId, seq)
    }

    // ── 群 ──

    fun listGroups(query: String?, pagination: AdminPageRequest): AdminPage<Chat> =
        adminChats.listGroups(query, pagination)

    @Serializable
    data class GroupDetail(val chat: Chat, val members: List<Member>)

    fun groupDetail(chatId: String): GroupDetail {
        val chat = adminChats.findGroup(chatId) ?: throw IllegalArgumentException("群不存在: $chatId")
        return GroupDetail(chat, chatService.getMembers(chatId))
    }

    suspend fun dissolveGroup(chatId: String) = chatService.adminDissolve(chatId)

    suspend fun muteAllGroup(chatId: String) = chatService.adminMuteAll(chatId)

    suspend fun unmuteAllGroup(chatId: String) = chatService.adminUnmuteAll(chatId)

    // ── 统计 ──

    suspend fun overview(): AdminOverview = overviewAssembler.load()

    // ── 日志 ──

    fun listServerLogs(): List<AdminLogFileInfo> = diagnostics.listServerLogs()

    fun readServerLog(name: String, lines: Int): List<String> = diagnostics.readServerLog(name, lines)

}

private fun adminMessageIdentity(chatId: String, serverSeq: Long): String = "$chatId:$serverSeq"
