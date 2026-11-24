package com.virjar.tk.server.infra.push

import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.model.AuthRules
import com.virjar.tk.protocol.model.ChatType
import com.virjar.tk.protocol.model.Contact
import com.virjar.tk.protocol.model.ConversationCapacityPolicy
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.UserStatus
import com.virjar.tk.server.domain.message.MessageRepository
import com.virjar.tk.server.infra.db.*
import com.virjar.tk.server.infra.db.repository.ExposedConversationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.MessageDigest
import java.util.*

/** 移动端提示共享持久的同步分发事务；只有 HTTP 投递尝试发生在事务之外。 */
internal class OemPushNotifications(
    private val database: Database,
    private val configuration: OemPushConfiguration,
    private val datasetId: String,
    private val messages: MessageRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sendApns: suspend (OemPushNotification) -> OemPushDeliveryResult = { pushRejected("APNS_UNAVAILABLE") },
    private val send: suspend (OemPushVendorConfiguration, OemPushNotification) -> OemPushDeliveryResult,
) {
    private val conversations = ExposedConversationRepository(database)
    private val deliveryGate = Mutex()

    fun register(
        uid: String,
        deviceId: String,
        deviceCredentialEpoch: Long,
        vendor: String,
        registrationId: String,
        packageName: String,
        deploymentFingerprint: String,
    ): Boolean {
        if (vendor !in OemPushVendors.ALL) return false
        return registerChannel(uid, deviceId, deviceCredentialEpoch, vendor, registrationId,
            packageName, deploymentFingerprint, AuthRules.DEVICE_FLAG_ANDROID)
    }

    fun registerApns(
        uid: String,
        deviceId: String,
        deviceCredentialEpoch: Long,
        deviceToken: String,
        bundleId: String,
        environment: String,
        deploymentFingerprint: String,
    ): Boolean {
        val target = requireNotNull(ApnsEnvironment.fromWire(environment)) { "Invalid APNs environment" }
        require(deviceToken.isEmpty() || (deviceToken.length % 2 == 0 &&
            deviceToken.matches(Regex("[0-9a-fA-F]{2,512}")))) { "Invalid APNs device token" }
        return registerChannel(uid, deviceId, deviceCredentialEpoch, target.channel, deviceToken.lowercase(),
            bundleId, deploymentFingerprint, AuthRules.DEVICE_FLAG_IOS)
    }

    private fun registerChannel(
        uid: String,
        deviceId: String,
        deviceCredentialEpoch: Long,
        vendor: String,
        registrationId: String,
        packageName: String,
        deploymentFingerprint: String,
        deviceFlag: Int,
    ): Boolean {
        require(registrationId.length <= 4096 && registrationId.none { it.isWhitespace() || it.isISOControl() }) {
            "Invalid OEM push registration"
        }
        require(packageName.length <= 255 && deploymentFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "Invalid OEM push registration identity"
        }
        val configuredPackage = configuration.packageName(vendor)
        return transaction(database) {
            // 锁顺序与凭据刷新/吊销一致。epoch 来自已认证的 RPC 上下文。
            val user = Users.selectAll().where { Users.uid eq uid }.forUpdate().singleOrNull()
                ?: return@transaction false
            val device = Devices.selectAll().where {
                (Devices.uid eq uid) and (Devices.deviceId eq deviceId)
            }.forUpdate().singleOrNull() ?: return@transaction false
            if (user[Users.status] != UserStatus.ACTIVE.code || device[Devices.status] != Devices.STATUS_ACTIVE ||
                device[Devices.credentialEpoch] != deviceCredentialEpoch ||
                device[Devices.deviceFlag] != deviceFlag
            ) return@transaction false
            val credential = Credentials.selectAll().where {
                (Credentials.uid eq uid) and (Credentials.deviceId eq deviceId) and
                    (Credentials.tokenType eq REFRESH_TOKEN_TYPE) and
                    (Credentials.userCredentialEpoch eq user[Users.credentialEpoch]) and
                    (Credentials.deviceCredentialEpoch eq deviceCredentialEpoch) and
                    (Credentials.expiresAt greater clock())
            }.forUpdate().singleOrNull() ?: return@transaction false
            val refreshHash = credential[Credentials.tokenHash]
            if (registrationId.isEmpty() || configuredPackage == null ||
                packageName != configuredPackage
            ) {
                OemPushRegistrations.deleteWhere { refreshTokenHash eq refreshHash }
                return@transaction false
            }
            val existing = OemPushRegistrations.selectAll().where {
                OemPushRegistrations.refreshTokenHash eq refreshHash
            }.singleOrNull()
            if (existing != null && existing[OemPushRegistrations.vendor] == vendor &&
                existing[OemPushRegistrations.registrationId] == registrationId &&
                existing[OemPushRegistrations.packageName] == packageName &&
                existing[OemPushRegistrations.deploymentFingerprint] == deploymentFingerprint
            ) {
                if (deviceFlag == AuthRules.DEVICE_FLAG_IOS) {
                    // APNs 可能在恢复有效性后重发相同字节。保留待处理工作，
                    // 但较早的在途 410 不得删除这份新注册。
                    OemPushRegistrations.update({ OemPushRegistrations.refreshTokenHash eq refreshHash }) {
                        it[generation] = UUID.randomUUID().toString()
                        it[registeredAt] = clock()
                    }
                }
                return@transaction true
            }

            val registrationHash = digest(if (deviceFlag == AuthRules.DEVICE_FLAG_IOS) {
                "$vendor:$packageName:$registrationId"
            } else registrationId)
            // 切换了账号的安装必须停止指向其上一次登录。
            OemPushRegistrations.deleteWhere {
                (refreshTokenHash eq refreshHash) or (OemPushRegistrations.registrationHash eq registrationHash)
            }
            OemPushRegistrations.insert {
                it[refreshTokenHash] = refreshHash
                it[OemPushRegistrations.vendor] = vendor
                it[OemPushRegistrations.registrationId] = registrationId
                it[OemPushRegistrations.registrationHash] = registrationHash
                it[generation] = UUID.randomUUID().toString()
                it[registeredAt] = clock()
                it[OemPushRegistrations.packageName] = packageName
                it[OemPushRegistrations.deploymentFingerprint] = deploymentFingerprint
            }
            true
        }
    }

    /** 在 SyncEventDispatcher 的标记分发事务内调用，绝不在 TCP 事件循环中调用。 */
    fun recordDispatchedEvent(uid: String, eventId: Long, notifyType: Int, payload: ByteArray) {
        if (configuration.channels.isEmpty() || notifyType != NotifyType.MESSAGE_RECV.code) return
        val message = ProtoCodec.decode(Message, payload)
        if (message.senderUid == uid || message.serverSeq <= 0 ||
            message.flags and (Message.FLAG_REVOKED or Message.FLAG_EDITED) != 0
        ) return
        if (!unread(uid, message.chatId, message.serverSeq)) return
        val credentials = Credentials.select(Credentials.tokenHash).where {
            (Credentials.uid eq uid) and (Credentials.tokenType eq REFRESH_TOKEN_TYPE) and
                (Credentials.expiresAt greater clock())
        }
        val enabledVendors = configuration.channels.toList()
        val registrations = OemPushRegistrations.selectAll().where {
            (OemPushRegistrations.refreshTokenHash inSubQuery credentials) and
                (OemPushRegistrations.vendor inList enabledVendors) and
                (OemPushRegistrations.pendingEventId less eventId)
        }.orderBy(OemPushRegistrations.refreshTokenHash, SortOrder.ASC).forUpdate().toList()
        var readableChatIds: Set<String>? = null
        for (row in registrations) {
            if (row[OemPushRegistrations.packageName] !=
                configuration.packageName(row[OemPushRegistrations.vendor])
            ) continue
            val chats = pendingChats(row).toMutableMap()
            chats[message.chatId] = maxOf(chats[message.chatId] ?: 0, message.serverSeq)
            if (chats.size > ConversationCapacityPolicy.MAX_CONVERSATIONS_PER_USER) {
                // 提供商不可用期间，旧 scope 可能比成员关系存活得更久。它们不得
                // 消耗当前的会话配额，也不得阻塞持久的同步分发器。
                val current = readableChatIds ?: conversations.readableConversations(uid)
                    .mapTo(mutableSetOf()) { it[Conversations.chatId] }
                    .also { readableChatIds = it }
                chats.keys.retainAll(current)
            }
            check(chats.size <= ConversationCapacityPolicy.MAX_CONVERSATIONS_PER_USER)
            OemPushRegistrations.update({
                OemPushRegistrations.refreshTokenHash eq row[OemPushRegistrations.refreshTokenHash]
            }) {
                it[pendingEventId] = eventId
                it[OemPushRegistrations.pendingChats] = Json.encodeToString(chats)
                // 新消息到达时保留已失败提供商的退避。
            }
        }
    }

    suspend fun drainDue(): Int = deliveryGate.withLock {
        if (configuration.channels.isEmpty()) return@withLock 0
        val due = transaction(database) {
            OemPushRegistrations.selectAll().where {
                (OemPushRegistrations.pendingEventId greater OemPushRegistrations.deliveredEventId) and
                    (OemPushRegistrations.nextAttemptAt lessEq clock()) and
                    (OemPushRegistrations.vendor inList configuration.channels.toList())
            }.orderBy(OemPushRegistrations.nextAttemptAt, SortOrder.ASC)
                .limit(MAX_PER_PASS).toList()
        }
        for (row in due) {
            val notification = transaction(database) { currentNotification(row) }
            val vendorConfiguration = notification?.let { configuration[it.vendor] }
            val result = if (notification == null) {
                OemPushDeliveryResult(accepted = true)
            } else {
                try {
                    if (ApnsEnvironment.fromChannel(notification.vendor) != null) sendApns(notification)
                    else send(checkNotNull(vendorConfiguration), notification)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    OemPushDeliveryResult(accepted = false, reason = "TRANSPORT_FAILURE")
                }
            }
            finish(row, notification, result)
        }
        due.size
    }

    private fun currentNotification(row: ResultRow): OemPushNotification? {
        val refreshHash = row[OemPushRegistrations.refreshTokenHash]
        val vendor = row[OemPushRegistrations.vendor]
        val isApns = ApnsEnvironment.fromChannel(vendor) != null
        val deviceFlag = if (isApns) AuthRules.DEVICE_FLAG_IOS else AuthRules.DEVICE_FLAG_ANDROID
        val credential = Credentials.join(Users, JoinType.INNER, Credentials.uid, Users.uid)
            .join(Devices, JoinType.INNER, Credentials.uid, Devices.uid).selectAll().where {
            (Credentials.tokenHash eq refreshHash) and (Credentials.tokenType eq REFRESH_TOKEN_TYPE) and
                (Credentials.expiresAt greater clock()) and (Users.status eq UserStatus.ACTIVE.code) and
                (Users.credentialEpoch eq Credentials.userCredentialEpoch) and
                (Devices.deviceId eq Credentials.deviceId) and (Devices.status eq Devices.STATUS_ACTIVE) and
                (Devices.credentialEpoch eq Credentials.deviceCredentialEpoch) and
                (Devices.deviceFlag eq deviceFlag)
        }.singleOrNull() ?: return null
        val uid = credential[Credentials.uid]
        if (row[OemPushRegistrations.packageName] != configuration.packageName(vendor)) return null
        // 有界扫描之后可能已发生重新注册/吊销或出现更新的队列条目。
        if (!OemPushRegistrations.selectAll().where { sameAttempt(row) }.any()) return null
        val pending = pendingChats(row)
        // 一条合并通知可以指向任意仍可读的待处理会话。读取最新会话
        // 不得抹掉同一批次中更早的未读会话。
        val selected = conversations.readableConversations(uid).andWhere {
            (Conversations.chatId inList pending.keys.toList()) and (Conversations.isMuted eq false)
        }.orderBy(Conversations.lastMsgTimestamp, SortOrder.DESC).toList().firstNotNullOfOrNull { conversation ->
            val id = conversation[Conversations.chatId]
            val seq = pending.getValue(id)
            if (conversation[Conversations.readSeq] >= seq || conversation[Conversations.lastMsgSeq] < seq) null
            else messages.getMessage(id, seq)?.takeIf { message ->
                message.senderUid != uid && message.flags and Message.FLAG_REVOKED == 0
            }?.let { message ->
                Triple(conversation, seq, message)
            }
        } ?: return null
        val (selectedConversation, selectedSeq, selectedMessage) = selected
        val chatId = selectedConversation[Conversations.chatId]
        var alertTitle: String? = null
        var alertBody: String? = null
        var badgeCount: Int? = null
        if (isApns) {
            val apns = configuration.apns
            if (apns != null) {
                val chatType = selectedConversation[Conversations.chatType]
                val groupName = if (chatType == ChatType.GROUP.code) {
                    GroupChats.select(GroupChats.name).where { GroupChats.chatId eq chatId }
                        .singleOrNull()?.get(GroupChats.name)
                } else null
                val senderDisplayName = resolveApnsSenderDisplayName(
                    recipientUid = uid,
                    senderUid = selectedMessage.senderUid,
                    chatId = chatId,
                    chatType = chatType,
                )
                val formatted = ApnsNotificationFormatter.format(
                    contentMode = apns.contentMode,
                    fallbackTitle = apns.title,
                    chatType = chatType,
                    groupName = groupName,
                    senderDisplayName = senderDisplayName,
                    recipientUid = uid,
                    message = selectedMessage,
                )
                alertTitle = formatted.title
                alertBody = formatted.body
                badgeCount = conversations.readableConversations(uid).andWhere {
                    (Conversations.isMuted eq false) and (Conversations.lastMsgSeq greater Conversations.readSeq)
                }.sumOf { rowConv ->
                    (rowConv[Conversations.lastMsgSeq] - rowConv[Conversations.readSeq]).coerceAtLeast(0L)
                }.coerceIn(1L, 99_999L).toInt()
            }
        }
        return OemPushNotification(
            vendor = vendor,
            registrationId = row[OemPushRegistrations.registrationId],
            deploymentFingerprint = row[OemPushRegistrations.deploymentFingerprint],
            datasetId = datasetId,
            uid = uid,
            chatId = chatId,
            // 同一待处理事件的重试保留去重键；多会话并发时各自持有独立 collapse id 避免后推覆盖前推。
            jobKey = digest(
                "${row[OemPushRegistrations.generation]}:${row[OemPushRegistrations.pendingEventId]}:$chatId:$selectedSeq",
            ).take(20),
            registeredAt = row[OemPushRegistrations.registeredAt],
            deliveredSeq = selectedSeq,
            alertTitle = alertTitle,
            alertBody = alertBody,
            badgeCount = badgeCount,
        )
    }

    private fun resolveApnsSenderDisplayName(
        recipientUid: String,
        senderUid: String,
        chatId: String,
        chatType: Int,
    ): String? {
        if (senderUid.isBlank()) return null
        val remark = Friends.select(Friends.remark).where {
            (Friends.uid eq recipientUid) and (Friends.friendUid eq senderUid) and (Friends.status eq Contact.STATUS_NORMAL)
        }.limit(1).singleOrNull()?.get(Friends.remark)?.trim()?.takeIf { it.isNotEmpty() }
        if (remark != null) return remark
        if (chatType == ChatType.GROUP.code) {
            val groupNickname = GroupMembers.select(GroupMembers.nickname).where {
                (GroupMembers.chatId eq chatId) and (GroupMembers.uid eq senderUid) and
                    (GroupMembers.status eq GroupMembers.STATUS_ACTIVE)
            }.limit(1).singleOrNull()?.get(GroupMembers.nickname)?.trim()?.takeIf { it.isNotEmpty() }
            if (groupNickname != null) return groupNickname
        }
        val user = Users.select(Users.name, Users.username).where { Users.uid eq senderUid }.limit(1).singleOrNull()
        return user?.get(Users.name)?.trim()?.takeIf { it.isNotEmpty() }
            ?: user?.get(Users.username)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun unread(uid: String, chatId: String, seq: Long): Boolean =
        conversations.readableConversations(uid).andWhere {
            (Conversations.chatId eq chatId) and (Conversations.isMuted eq false) and
                (Conversations.readSeq less seq) and (Conversations.lastMsgSeq greaterEq seq)
        }.any()

    private fun finish(
        row: ResultRow,
        notification: OemPushNotification?,
        result: OemPushDeliveryResult,
    ) = transaction(database) {
        if (result.invalidRegistration) {
            OemPushRegistrations.deleteWhere { sameRegistration(row) }
            return@transaction
        }
        if (!result.accepted) {
            // 即使 HTTP 期间又有更多消息到达，失败/退避仍归属于该注册。
            OemPushRegistrations.update({ sameRegistration(row) }) {
                val attempt = (row[OemPushRegistrations.attempts] + 1).coerceAtMost(30)
                it[attempts] = attempt
                it[nextAttemptAt] = clock() + (5_000L * (1L shl minOf(attempt - 1, 10))).coerceAtMost(3_600_000L)
                it[lastFailure] = result.reason?.takeIf { reason -> reason.matches(Regex("[A-Z0-9_]{1,40}")) }
                    ?: "PROVIDER_FAILURE"
            }
            return@transaction
        }
        val isApns = ApnsEnvironment.fromChannel(row[OemPushRegistrations.vendor]) != null
        val remainingChats = if (isApns && notification != null) {
            remainingUnreadPendingChats(row, notification)
        } else {
            emptyMap()
        }
        // 只有同代次且未在飞行期间被更新的事件集才能推进消费状态。
        OemPushRegistrations.update({ sameAttempt(row) }) {
            if (remainingChats.isNotEmpty()) {
                it[OemPushRegistrations.pendingChats] = Json.encodeToString(remainingChats)
                it[attempts] = 0
                it[nextAttemptAt] = clock()
                it[lastFailure] = null
            } else {
                it[deliveredEventId] = row[OemPushRegistrations.pendingEventId]
                it[OemPushRegistrations.pendingChats] = "{}"
                it[attempts] = 0
                it[nextAttemptAt] = clock()
                it[lastFailure] = null
            }
        }
    }

    private fun remainingUnreadPendingChats(
        row: ResultRow,
        delivered: OemPushNotification,
    ): Map<String, Long> {
        val currentPending = pendingChats(row).toMutableMap()
        val deliveredPendingSeq = currentPending[delivered.chatId]
        if (deliveredPendingSeq != null && deliveredPendingSeq <= delivered.deliveredSeq) {
            currentPending.remove(delivered.chatId)
        }
        if (currentPending.isEmpty()) return emptyMap()
        val uid = Credentials.select(Credentials.uid).where {
            Credentials.tokenHash eq row[OemPushRegistrations.refreshTokenHash]
        }.limit(1).singleOrNull()?.get(Credentials.uid) ?: return emptyMap()
        val readable = conversations.readableConversations(uid).andWhere {
            (Conversations.chatId inList currentPending.keys.toList()) and (Conversations.isMuted eq false)
        }.toList()
        val remaining = linkedMapOf<String, Long>()
        for (conversation in readable) {
            val id = conversation[Conversations.chatId]
            val seq = currentPending[id] ?: continue
            if (conversation[Conversations.readSeq] < seq && conversation[Conversations.lastMsgSeq] >= seq) {
                val msg = messages.getMessage(id, seq)
                if (msg != null && msg.senderUid != uid && msg.flags and Message.FLAG_REVOKED == 0) {
                    remaining[id] = seq
                }
            }
        }
        return remaining
    }

    private fun sameRegistration(row: ResultRow): Op<Boolean> = with(SqlExpressionBuilder) {
        (OemPushRegistrations.refreshTokenHash eq row[OemPushRegistrations.refreshTokenHash]) and
            (OemPushRegistrations.generation eq row[OemPushRegistrations.generation])
    }

    private fun sameAttempt(row: ResultRow): Op<Boolean> = with(SqlExpressionBuilder) {
        sameRegistration(row) and
            (OemPushRegistrations.pendingEventId eq row[OemPushRegistrations.pendingEventId])
    }

    private fun pendingChats(row: ResultRow): Map<String, Long> {
        val encoded = row[OemPushRegistrations.pendingChats]
        check(encoded.length <= ConversationCapacityPolicy.MAX_CONVERSATIONS_PER_USER * 80)
        return Json.decodeFromString<Map<String, Long>>(encoded).also { chats ->
            check(chats.size <= ConversationCapacityPolicy.MAX_CONVERSATIONS_PER_USER &&
                chats.all { (chat, seq) -> chat.isNotEmpty() && chat.length <= 36 && seq > 0 })
        }
    }

    companion object {
        const val MAX_PER_PASS = 32
        private const val REFRESH_TOKEN_TYPE = 2
        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
