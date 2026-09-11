package com.virjar.tk.server.infra.push

import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.model.AuthRules
import com.virjar.tk.protocol.model.ConversationCapacityPolicy
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.server.domain.message.MessageRepository
import com.virjar.tk.server.infra.db.*
import com.virjar.tk.server.infra.db.repository.ExposedConversationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.MessageDigest
import java.util.UUID

/** OEM hints share the durable sync dispatch transaction; only the HTTP attempt happens outside it. */
internal class OemPushNotifications(
    private val database: Database,
    private val configuration: OemPushConfiguration,
    private val datasetId: String,
    private val messages: MessageRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val send: suspend (OemPushVendorConfiguration, OemPushNotification) -> OemPushDeliveryResult = ::sendOemPush,
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
        require(registrationId.length <= 4096 && registrationId.none { it.isWhitespace() || it.isISOControl() }) {
            "Invalid OEM push registration"
        }
        require(packageName.length <= 255 && deploymentFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "Invalid OEM push registration identity"
        }
        val vendorConfiguration = configuration[vendor]
        return transaction(database) {
            // Same lock order as credential refresh/revocation. The epoch comes from authenticated RPC context.
            val user = Users.selectAll().where { Users.uid eq uid }.forUpdate().singleOrNull()
                ?: return@transaction false
            val device = Devices.selectAll().where {
                (Devices.uid eq uid) and (Devices.deviceId eq deviceId)
            }.forUpdate().singleOrNull() ?: return@transaction false
            if (user[Users.status] != 1 || device[Devices.status] != 1 ||
                device[Devices.credentialEpoch] != deviceCredentialEpoch ||
                device[Devices.deviceFlag] != AuthRules.DEVICE_FLAG_ANDROID
            ) return@transaction false
            val credential = Credentials.selectAll().where {
                (Credentials.uid eq uid) and (Credentials.deviceId eq deviceId) and
                    (Credentials.tokenType eq REFRESH_TOKEN_TYPE) and
                    (Credentials.userCredentialEpoch eq user[Users.credentialEpoch]) and
                    (Credentials.deviceCredentialEpoch eq deviceCredentialEpoch) and
                    (Credentials.expiresAt greater clock())
            }.forUpdate().singleOrNull() ?: return@transaction false
            val refreshHash = credential[Credentials.tokenHash]
            if (registrationId.isEmpty() || vendorConfiguration == null ||
                packageName != vendorConfiguration.packageName
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
            ) return@transaction true

            val registrationHash = digest(registrationId)
            // An installation changing accounts must stop targeting its previous login.
            OemPushRegistrations.deleteWhere {
                (refreshTokenHash eq refreshHash) or (OemPushRegistrations.registrationHash eq registrationHash)
            }
            OemPushRegistrations.insert {
                it[refreshTokenHash] = refreshHash
                it[OemPushRegistrations.vendor] = vendor
                it[OemPushRegistrations.registrationId] = registrationId
                it[OemPushRegistrations.registrationHash] = registrationHash
                it[generation] = UUID.randomUUID().toString()
                it[OemPushRegistrations.packageName] = packageName
                it[OemPushRegistrations.deploymentFingerprint] = deploymentFingerprint
            }
            true
        }
    }

    /** Called inside SyncEventDispatcher's mark-dispatched transaction, never from the TCP event loop. */
    fun recordDispatchedEvent(uid: String, eventId: Long, notifyType: Int, payload: ByteArray) {
        if (configuration.vendors.isEmpty() || notifyType != NotifyType.MESSAGE_RECV.code) return
        val message = ProtoCodec.decode(Message, payload)
        if (message.senderUid == uid || message.serverSeq <= 0 ||
            message.flags and (Message.FLAG_REVOKED or Message.FLAG_EDITED) != 0
        ) return
        if (!unread(uid, message.chatId, message.serverSeq)) return
        val credentials = Credentials.select(Credentials.tokenHash).where {
            (Credentials.uid eq uid) and (Credentials.tokenType eq REFRESH_TOKEN_TYPE) and
                (Credentials.expiresAt greater clock())
        }
        val enabledVendors = configuration.vendors.keys.toList()
        val registrations = OemPushRegistrations.selectAll().where {
            (OemPushRegistrations.refreshTokenHash inSubQuery credentials) and
                (OemPushRegistrations.vendor inList enabledVendors) and
                (OemPushRegistrations.pendingEventId less eventId)
        }.orderBy(OemPushRegistrations.refreshTokenHash, SortOrder.ASC).forUpdate().toList()
        var readableChatIds: Set<String>? = null
        for (row in registrations) {
            if (row[OemPushRegistrations.packageName] !=
                configuration[row[OemPushRegistrations.vendor]]?.packageName
            ) continue
            val chats = pendingChats(row).toMutableMap()
            chats[message.chatId] = maxOf(chats[message.chatId] ?: 0, message.serverSeq)
            if (chats.size > ConversationCapacityPolicy.MAX_CONVERSATIONS_PER_USER) {
                // Old scopes can outlive membership while the provider is unavailable. They must
                // not consume today's conversation quota or block the durable sync dispatcher.
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
                // Preserve a failed provider's backoff when new messages arrive.
            }
        }
    }

    suspend fun drainDue(): Int = deliveryGate.withLock {
        if (configuration.vendors.isEmpty()) return@withLock 0
        val due = transaction(database) {
            OemPushRegistrations.selectAll().where {
                (OemPushRegistrations.pendingEventId greater OemPushRegistrations.deliveredEventId) and
                    (OemPushRegistrations.nextAttemptAt lessEq clock()) and
                    (OemPushRegistrations.vendor inList configuration.vendors.keys.toList())
            }.orderBy(OemPushRegistrations.nextAttemptAt, SortOrder.ASC)
                .limit(MAX_PER_PASS).toList()
        }
        for (row in due) {
            val notification = transaction(database) { currentNotification(row) }
            val vendorConfiguration = notification?.let { configuration[it.vendor] }
            val result = if (notification == null || vendorConfiguration == null) {
                OemPushDeliveryResult(accepted = true)
            } else {
                try {
                    send(vendorConfiguration, notification)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    OemPushDeliveryResult(accepted = false, reason = "TRANSPORT_FAILURE")
                }
            }
            finish(row, result)
        }
        due.size
    }

    private fun currentNotification(row: ResultRow): OemPushNotification? {
        val refreshHash = row[OemPushRegistrations.refreshTokenHash]
        val credential = Credentials.join(Users, JoinType.INNER, Credentials.uid, Users.uid)
            .join(Devices, JoinType.INNER, Credentials.uid, Devices.uid).selectAll().where {
            (Credentials.tokenHash eq refreshHash) and (Credentials.tokenType eq REFRESH_TOKEN_TYPE) and
                (Credentials.expiresAt greater clock()) and (Users.status eq 1) and
                (Users.credentialEpoch eq Credentials.userCredentialEpoch) and
                (Devices.deviceId eq Credentials.deviceId) and (Devices.status eq 1) and
                (Devices.credentialEpoch eq Credentials.deviceCredentialEpoch) and
                (Devices.deviceFlag eq AuthRules.DEVICE_FLAG_ANDROID)
        }.singleOrNull() ?: return null
        val uid = credential[Credentials.uid]
        val vendor = row[OemPushRegistrations.vendor]
        val vendorConfiguration = configuration[vendor] ?: return null
        if (row[OemPushRegistrations.packageName] != vendorConfiguration.packageName) return null
        // Re-registration/revocation and newer queue entries may have happened after the bounded scan.
        if (!OemPushRegistrations.selectAll().where { sameAttempt(row) }.any()) return null
        val pending = pendingChats(row)
        // One coalesced notification can point at any still-readable pending conversation. Reading
        // the newest conversation must not erase an older unread conversation in the same batch.
        val chatId = conversations.readableConversations(uid).andWhere {
            (Conversations.chatId inList pending.keys.toList()) and (Conversations.isMuted eq false)
        }.orderBy(Conversations.lastMsgTimestamp, SortOrder.DESC).toList().firstOrNull { conversation ->
            val id = conversation[Conversations.chatId]
            val seq = pending.getValue(id)
            if (conversation[Conversations.readSeq] >= seq || conversation[Conversations.lastMsgSeq] < seq) false
            else messages.getMessage(id, seq)?.let { message ->
                message.senderUid != uid && message.flags and Message.FLAG_REVOKED == 0
            } == true
        }?.get(Conversations.chatId) ?: return null
        return OemPushNotification(
            vendor, row[OemPushRegistrations.registrationId], row[OemPushRegistrations.deploymentFingerprint],
            datasetId, uid, chatId,
            // Retry of one pending event retains the OEM dedup key, including across server restarts.
            digest("${row[OemPushRegistrations.generation]}:${row[OemPushRegistrations.pendingEventId]}").take(20),
        )
    }

    private fun unread(uid: String, chatId: String, seq: Long): Boolean =
        conversations.readableConversations(uid).andWhere {
            (Conversations.chatId eq chatId) and (Conversations.isMuted eq false) and
                (Conversations.readSeq less seq) and (Conversations.lastMsgSeq greaterEq seq)
        }.any()

    private fun finish(row: ResultRow, result: OemPushDeliveryResult) = transaction(database) {
        if (result.invalidRegistration) {
            OemPushRegistrations.deleteWhere { sameRegistration(row) }
        } else {
            // Failure/backoff belongs to the registration even when more messages arrived during
            // HTTP. Only a successful acknowledgement consumes the captured pending event set.
            OemPushRegistrations.update({ if (result.accepted) sameAttempt(row) else sameRegistration(row) }) {
                if (result.accepted) {
                    it[deliveredEventId] = row[OemPushRegistrations.pendingEventId]
                    it[OemPushRegistrations.pendingChats] = "{}"
                    it[attempts] = 0
                    it[nextAttemptAt] = clock()
                    it[lastFailure] = null
                } else {
                    val attempt = (row[OemPushRegistrations.attempts] + 1).coerceAtMost(30)
                    it[attempts] = attempt
                    it[nextAttemptAt] = clock() + (5_000L * (1L shl minOf(attempt - 1, 10))).coerceAtMost(3_600_000L)
                    it[lastFailure] = result.reason?.takeIf { reason -> reason.matches(Regex("[A-Z0-9_]{1,40}")) }
                        ?: "PROVIDER_FAILURE"
                }
            }
        }
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
