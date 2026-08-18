package com.virjar.tk.application.admin

import com.virjar.tk.domain.auth.TokenRepository
import com.virjar.tk.domain.bot.BotService
import com.virjar.tk.domain.chat.AdminPage
import com.virjar.tk.domain.chat.ChatRepository
import com.virjar.tk.domain.chat.ChatService
import com.virjar.tk.domain.contact.ContactRepository
import com.virjar.tk.domain.device.DeviceRepository
import com.virjar.tk.domain.message.MessageService
import com.virjar.tk.domain.message.MessageRepository
import com.virjar.tk.domain.message.MessageSearch
import com.virjar.tk.domain.organization.OrganizationService
import com.virjar.tk.domain.session.OnlineSessions
import com.virjar.tk.domain.user.UserRepository
import com.virjar.tk.domain.user.UserService
import com.virjar.tk.infra.db.Users
import com.virjar.tk.model.Chat
import com.virjar.tk.model.Device
import com.virjar.tk.model.Member
import com.virjar.tk.model.Message
import com.virjar.tk.domain.device.toModel
import com.virjar.tk.model.User
import com.virjar.tk.protocol.NotifyType
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import kotlinx.serialization.Serializable
import java.io.File

/**
 * 管理后台聚合服务：运维查询与治理操作。
 * 直接编排各域 Store/Repo/Service；管理端语义（分页/全局视图）与终端用户语义分离。
 */
class AdminService(
    private val userRepository: UserRepository,
    private val userService: UserService,
    private val deviceRepository: DeviceRepository,
    private val contactRepository: ContactRepository,
    private val chatRepository: ChatRepository,
    private val chatService: ChatService,
    private val messageService: MessageService,
    private val messages: MessageRepository,
    private val search: MessageSearch,
    private val tokens: TokenRepository,
    private val onlineSessions: OnlineSessions,
    private val organizationService: OrganizationService,
    private val botService: BotService,
    private val logsDir: File,
    private val clientLogsDir: File,
) {

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
    fun createBot(name: String) = botService.create(name)
    fun rotateBotToken(botId: String) = botService.rotateToken(botId)
    suspend fun disableBot(botId: String) = botService.disable(botId)
    suspend fun grantBot(botId: String, chatId: String) = botService.grant(botId, chatId)
    suspend fun revokeBotGrant(botId: String, chatId: String) = botService.revokeGrant(botId, chatId)

    // ── 用户 ──

        fun listUsers(query: String?, page: Int, size: Int): AdminPage<User> = transaction {
        val base = Users.selectAll()
        val filtered = if (query.isNullOrBlank()) base else base.where {
            (Users.username like "%$query%") or (Users.name like "%$query%") or (Users.uid like "%$query%")
        }
        val total = filtered.count()
        val items = filtered.orderBy(Users.createdAt, SortOrder.DESC)
            .limit(size).offset(((page - 1) * size).toLong())
            .map { it.toUserModel() }
        AdminPage(total, items)
    }

    @Serializable
    data class UserDetail(
        val user: User,
        val devices: List<Device>,
        val friends: List<com.virjar.tk.model.Contact>,
        val groups: List<Chat>,
        val online: Boolean,
    )

    suspend fun userDetail(uid: String): UserDetail {
        val user = userRepository.findByUid(uid) ?: throw IllegalArgumentException("用户不存在: $uid")
        return UserDetail(
            user = user,
            devices = deviceRepository.getDevices(uid).map { it.toModel() },
            friends = contactRepository.listFriends(uid),
            groups = chatRepository.listUserChats(uid).filter { it.chatType == 2 },
            online = onlineSessions.isOnline(uid),
        )
    }

    /** 封禁：status + 全 token 吊销 + 全设备踢线（三动作）。 */
    suspend fun banUser(uid: String) {
        transaction {
            Users.selectAll().where { Users.uid eq uid }.singleOrNull()
                ?: throw IllegalArgumentException("用户不存在: $uid")
            Users.update({ Users.uid eq uid }) { it[status] = 2 }
        }
        tokens.revokeAllUserTokens(uid)
        onlineSessions.kickUser(uid)
    }

    suspend fun unbanUser(uid: String) {
        transaction {
            Users.update({ Users.uid eq uid }) { it[status] = 1 }
        }
    }

    suspend fun kickAll(uid: String) = onlineSessions.kickUser(uid)

    /** 重置密码：新 BCrypt + 全设备踢线（强制重新登录）。 */
    suspend fun resetPassword(uid: String, newPassword: String) {
        require(newPassword.length >= 6) { "密码至少 6 位" }
        val hash = BCrypt.hashpw(newPassword, BCrypt.gensalt())
        transaction {
            Users.update({ Users.uid eq uid }) { it[passwordHash] = hash }
        }
        tokens.revokeAllUserTokens(uid)
        onlineSessions.kickUser(uid)
    }

    // ── 消息 ──

    @Serializable
    data class MessageSearchResult(
        val total: Int,
        val items: List<Message>,
        val highlights: Map<String, String>, // clientMsgId -> 高亮片段
    )

    fun searchMessages(
        keyword: String?,
        chatId: String?,
        senderUid: String?,
        start: Long?,
        end: Long?,
        page: Int,
        size: Int,
    ): MessageSearchResult {
        val chatIds = chatId?.takeIf { it.isNotBlank() }?.let { setOf(it) } ?: emptySet()
        val resultPage = search.search(
            query = keyword ?: "",  // 空=浏览模式（SearchIndex match-all）
            chatIds = chatIds,
            senderUid = senderUid?.takeIf { it.isNotBlank() },
            startTimestamp = start?.takeIf { it > 0 },
            endTimestamp = end?.takeIf { it > 0 },
            limit = size,
            offset = (page - 1) * size,
        )
        val resultMessages = resultPage.hits.mapNotNull { messages.getMessage(it.chatId, it.seq) }
        val highlights = resultPage.hits.associate { it.clientMsgId to it.highlight }
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

    fun listGroups(query: String?, page: Int, size: Int): AdminPage<Chat> = chatRepository.listGroups(query, page, size)

    @Serializable
    data class GroupDetail(val chat: Chat, val members: List<Member>)

    fun groupDetail(chatId: String): GroupDetail {
        val chat = chatRepository.getChatById(chatId) ?: throw IllegalArgumentException("群不存在: $chatId")
        return GroupDetail(chat, chatService.getMembers(chatId))
    }

    suspend fun dissolveGroup(chatId: String) = chatService.adminDissolve(chatId)

    suspend fun muteAllGroup(chatId: String) = chatService.adminMuteAll(chatId)

    suspend fun unmuteAllGroup(chatId: String) = chatService.adminUnmuteAll(chatId)

    // ── 统计 ──

    @Serializable
    data class Overview(
        val onlineCount: Int,
        val userCount: Long,
        val groupCount: Long,
        val todayEvents: Long,
        val storageRocksdbBytes: Long,
        val storageFileStoreBytes: Long,
    )

    suspend fun overview(): Overview {
        val dayStart = System.currentTimeMillis() - System.currentTimeMillis() % (24 * 3600 * 1000L)
        return Overview(
            onlineCount = onlineSessions.onlineUids().size,
            userCount = transaction { Users.selectAll().count() },
            groupCount = chatRepository.countGroups(),
            todayEvents = chatRepository.countEventsSince(dayStart),
            storageRocksdbBytes = dirSize(logsDir.parentFile?.resolve("rocksdb")),
            storageFileStoreBytes = dirSize(logsDir.parentFile?.resolve("file-store")),
        )
    }

    private fun dirSize(dir: File?): Long =
        dir?.takeIf { it.exists() }?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    // ── 日志 ──

    @Serializable
    data class LogFileInfo(val name: String, val sizeBytes: Long, val lastModified: Long)

    fun listServerLogs(): List<LogFileInfo> {
        val dirs = listOf(logsDir, File(logsDir, "traces"))
        return dirs.flatMap { dir ->
            dir.takeIf { it.exists() }?.listFiles()?.filter { it.isFile }?.map {
                LogFileInfo("${if (dir == logsDir) "" else "traces/"}${it.name}", it.length(), it.lastModified())
            } ?: emptyList()
        }.sortedByDescending { it.lastModified }
    }

    /** tail 读取（路径穿越防护：canonical 必须落在 logsDir 内）。 */
    fun readServerLog(name: String, lines: Int): List<String> {
        val file = File(logsDir, name)
        val canonical = file.canonicalFile
        if (!canonical.path.startsWith(logsDir.canonicalPath + File.separator)) {
            throw IllegalArgumentException("非法日志路径: $name")
        }
        if (!canonical.exists()) throw IllegalArgumentException("日志不存在: $name")
        return canonical.readLines().takeLast(lines.coerceIn(1, 2000))
    }

    fun listClientLogDirs(): Map<String, Map<String, List<String>>> {
        val root = clientLogsDir.takeIf { it.exists() } ?: return emptyMap()
        return root.listFiles()?.filter { it.isDirectory }?.associate { uidDir ->
            val devices = uidDir.listFiles()?.filter { it.isDirectory }?.associate { devDir ->
                devDir.name to (devDir.listFiles()?.map { it.name } ?: emptyList())
            } ?: emptyMap()
            uidDir.name to devices
        } ?: emptyMap()
    }

    fun readClientLog(uid: String, deviceId: String, date: String): List<String> {
        val dir = File(clientLogsDir, "$uid/$deviceId")
        val file = File(dir, date)
        val canonical = file.canonicalFile
        if (!canonical.path.startsWith(clientLogsDir.canonicalPath + File.separator)) {
            throw IllegalArgumentException("非法日志路径")
        }
        if (!canonical.exists()) throw IllegalArgumentException("日志不存在")
        return canonical.readLines().takeLast(2000)
    }
}

private fun org.jetbrains.exposed.sql.ResultRow.toUserModel() = User(
    uid = this[Users.uid],
    username = this[Users.username],
    name = this[Users.name],
    avatar = this[Users.avatar],
    phone = this[Users.phone],
    sex = this[Users.sex],
    role = this[Users.role],
    status = this[Users.status],
)
