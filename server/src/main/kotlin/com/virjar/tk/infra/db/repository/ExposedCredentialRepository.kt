package com.virjar.tk.infra.db.repository

import com.virjar.tk.domain.auth.AccessTokenValidator
import com.virjar.tk.domain.auth.CredentialAdministration
import com.virjar.tk.domain.auth.CredentialDevice
import com.virjar.tk.domain.auth.CredentialIssueRequest
import com.virjar.tk.domain.auth.CredentialSubject
import com.virjar.tk.domain.auth.IssuedCredentials
import com.virjar.tk.domain.auth.TokenInfo
import com.virjar.tk.domain.auth.TokenRepository
import com.virjar.tk.infra.db.Credentials
import com.virjar.tk.infra.db.Devices
import com.virjar.tk.infra.db.Users
import com.virjar.tk.model.UserRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

enum class CredentialMutation {
    ISSUE,
    REFRESH,
    REVOKE_DEVICE,
    CHANGE_PASSWORD,
    BAN_USER,
    UNBAN_USER,
    RESET_PASSWORD,
}

/** Deterministic test seam. Arguments contain identity only and never a raw or hashed token. */
fun interface CredentialRepositoryHooks {
    suspend fun beforeUserLock(operation: CredentialMutation, uid: String, deviceId: String?) = Unit
    suspend fun afterUserLock(operation: CredentialMutation, uid: String, deviceId: String?)

    object None : CredentialRepositoryHooks {
        override suspend fun afterUserLock(operation: CredentialMutation, uid: String, deviceId: String?) = Unit
    }
}

/**
 * PostgreSQL credential aggregate.
 *
 * Every mutation acquires locks in one order: user, device, credential. Raw secrets are returned
 * once and only SHA-256 hex digests are persisted. Validation is a single joined snapshot and
 * fails closed on database errors.
 */
class ExposedCredentialRepository(
    private val clock: () -> Long = System::currentTimeMillis,
    private val dbDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val hooks: CredentialRepositoryHooks = CredentialRepositoryHooks.None,
    private val random: SecureRandom = SecureRandom(),
) : TokenRepository, AccessTokenValidator, CredentialAdministration {
    private val logger = LoggerFactory.getLogger(ExposedCredentialRepository::class.java)

    override suspend fun issueCredentials(request: CredentialIssueRequest): IssuedCredentials? =
        credentialTransaction {
            hooks.beforeUserLock(CredentialMutation.ISSUE, request.uid, request.device.deviceId)
            val user = lockUser(request.uid) ?: return@credentialTransaction null
            if (user[Users.status] != STATUS_ACTIVE || user[Users.role] != UserRole.HUMAN) {
                return@credentialTransaction null
            }
            if (user[Users.credentialEpoch] != request.expectedUserCredentialEpoch ||
                user[Users.passwordHash] != request.expectedPasswordHash
            ) {
                return@credentialTransaction null
            }
            hooks.afterUserLock(CredentialMutation.ISSUE, request.uid, request.device.deviceId)

            val now = clock()
            val deviceEpoch = rotateAndLockDevice(request.uid, request.device, now)
            Credentials.deleteWhere {
                (Credentials.uid eq request.uid) and
                    (Credentials.deviceId eq request.device.deviceId)
            }
            insertCredentialPair(
                uid = request.uid,
                username = user[Users.username],
                name = user[Users.name],
                device = request.device,
                userEpoch = request.expectedUserCredentialEpoch,
                deviceEpoch = deviceEpoch,
                now = now,
            )
        }

    override suspend fun refreshCredentials(
        refreshToken: String,
        device: CredentialDevice,
    ): IssuedCredentials? {
        if (refreshToken.isBlank()) return null
        val refreshHash = tokenHash(refreshToken)
        return credentialTransaction {
            // Owner discovery is intentionally unlocked. The authoritative row is re-read only
            // after the user/device locks, so a forged device cannot consume the real token.
            val owner = Credentials.selectAll().where {
                (Credentials.tokenHash eq refreshHash) and
                    (Credentials.tokenType eq TYPE_REFRESH)
            }.singleOrNull() ?: return@credentialTransaction null
            if (owner[Credentials.deviceId] != device.deviceId || owner[Credentials.deviceFlag] != device.deviceFlag) {
                return@credentialTransaction null
            }
            val uid = owner[Credentials.uid]
            hooks.beforeUserLock(CredentialMutation.REFRESH, uid, device.deviceId)
            val user = lockUser(uid) ?: return@credentialTransaction null
            hooks.afterUserLock(CredentialMutation.REFRESH, uid, device.deviceId)
            val deviceRow = lockDevice(uid, device.deviceId) ?: return@credentialTransaction null
            val credential = Credentials.selectAll().where {
                (Credentials.tokenHash eq refreshHash) and
                    (Credentials.tokenType eq TYPE_REFRESH)
            }.forUpdate().singleOrNull() ?: return@credentialTransaction null

            val now = clock()
            val userEpoch = user[Users.credentialEpoch]
            val previousDeviceEpoch = deviceRow[Devices.credentialEpoch]
            if (user[Users.status] != STATUS_ACTIVE || user[Users.role] != UserRole.HUMAN ||
                deviceRow[Devices.status] != STATUS_ACTIVE ||
                credential[Credentials.uid] != uid ||
                credential[Credentials.deviceId] != device.deviceId ||
                credential[Credentials.deviceFlag] != device.deviceFlag ||
                credential[Credentials.userCredentialEpoch] != userEpoch ||
                credential[Credentials.deviceCredentialEpoch] != previousDeviceEpoch ||
                credential[Credentials.expiresAt] < now
            ) {
                return@credentialTransaction null
            }

            // A device owns exactly one credential pair. Rotation invalidates the previous access
            // token as well as the one-time refresh token and keeps the table bounded.
            Credentials.deleteWhere {
                (Credentials.uid eq uid) and (Credentials.deviceId eq device.deviceId)
            }
            val deviceEpoch = checkedNextEpoch(previousDeviceEpoch)
            updateDeviceMetadata(uid, device, now, deviceEpoch)
            insertCredentialPair(
                uid = uid,
                username = user[Users.username],
                name = user[Users.name],
                device = device,
                userEpoch = userEpoch,
                deviceEpoch = deviceEpoch,
                now = now,
            )
        }
    }

    override suspend fun revokeDevice(uid: String, deviceId: String): Long? =
        credentialTransaction {
            hooks.beforeUserLock(CredentialMutation.REVOKE_DEVICE, uid, deviceId)
            lockUser(uid) ?: return@credentialTransaction null
            hooks.afterUserLock(CredentialMutation.REVOKE_DEVICE, uid, deviceId)
            val device = lockDevice(uid, deviceId) ?: return@credentialTransaction null
            val currentDeviceEpoch = device[Devices.credentialEpoch]

            val committedEpoch = if (device[Devices.status] == STATUS_ACTIVE) {
                checkedNextEpoch(currentDeviceEpoch).also { next ->
                    Devices.update({ (Devices.uid eq uid) and (Devices.deviceId eq deviceId) }) {
                        it[status] = STATUS_REVOKED
                        it[credentialEpoch] = next
                    }
                }
            } else {
                currentDeviceEpoch
            }
            Credentials.deleteWhere {
                (Credentials.uid eq uid) and (Credentials.deviceId eq deviceId)
            }
            committedEpoch
        }

    override suspend fun changePasswordAndRevoke(
        uid: String,
        expectedPasswordHash: String,
        newPasswordHash: String,
    ): Long? = credentialTransaction {
        hooks.beforeUserLock(CredentialMutation.CHANGE_PASSWORD, uid, null)
        val user = lockUser(uid) ?: return@credentialTransaction null
        hooks.afterUserLock(CredentialMutation.CHANGE_PASSWORD, uid, null)
        if (user[Users.status] != STATUS_ACTIVE || user[Users.role] != UserRole.HUMAN ||
            user[Users.passwordHash] != expectedPasswordHash
        ) {
            return@credentialTransaction null
        }
        val nextEpoch = checkedNextEpoch(user[Users.credentialEpoch])
        Users.update({ Users.uid eq uid }) {
            it[passwordHash] = newPasswordHash
            it[credentialEpoch] = nextEpoch
            it[updatedAt] = clock()
        }
        Credentials.deleteWhere { Credentials.uid eq uid }
        nextEpoch
    }

    override suspend fun banUser(uid: String): Long = credentialTransaction {
        hooks.beforeUserLock(CredentialMutation.BAN_USER, uid, null)
        val user = lockUser(uid) ?: throw IllegalArgumentException("用户不存在: $uid")
        hooks.afterUserLock(CredentialMutation.BAN_USER, uid, null)
        val currentEpoch = user[Users.credentialEpoch]
        val committedEpoch = if (user[Users.status] == STATUS_ACTIVE) {
            checkedNextEpoch(currentEpoch).also { next ->
                Users.update({ Users.uid eq uid }) {
                    it[status] = STATUS_REVOKED
                    it[credentialEpoch] = next
                    it[updatedAt] = clock()
                }
            }
        } else {
            currentEpoch
        }
        Credentials.deleteWhere { Credentials.uid eq uid }
        committedEpoch
    }

    override suspend fun unbanUser(uid: String) {
        credentialTransaction {
            hooks.beforeUserLock(CredentialMutation.UNBAN_USER, uid, null)
            lockUser(uid) ?: throw IllegalArgumentException("用户不存在: $uid")
            hooks.afterUserLock(CredentialMutation.UNBAN_USER, uid, null)
            Users.update({ Users.uid eq uid }) {
                it[status] = STATUS_ACTIVE
                it[updatedAt] = clock()
            }
        }
    }

    override suspend fun resetPasswordAndRevoke(uid: String, passwordHash: String): Long =
        credentialTransaction {
            hooks.beforeUserLock(CredentialMutation.RESET_PASSWORD, uid, null)
            val user = lockUser(uid) ?: throw IllegalArgumentException("用户不存在: $uid")
            hooks.afterUserLock(CredentialMutation.RESET_PASSWORD, uid, null)
            val nextEpoch = checkedNextEpoch(user[Users.credentialEpoch])
            Users.update({ Users.uid eq uid }) {
                it[Users.passwordHash] = passwordHash
                it[credentialEpoch] = nextEpoch
                it[updatedAt] = clock()
            }
            Credentials.deleteWhere { Credentials.uid eq uid }
            nextEpoch
        }

    override suspend fun validateAccessToken(token: String): TokenInfo? {
        if (token.isBlank()) return null
        return try {
            newSuspendedTransaction(dbDispatcher, readOnly = true) {
                maxAttempts = 1
                val now = clock()
                Credentials
                    .join(
                        Users,
                        JoinType.INNER,
                        onColumn = Credentials.uid,
                        otherColumn = Users.uid,
                    )
                    .join(
                        Devices,
                        JoinType.INNER,
                        additionalConstraint = {
                            (Credentials.uid eq Devices.uid) and
                                (Credentials.deviceId eq Devices.deviceId)
                        },
                    )
                    .selectAll()
                    .where {
                        (Credentials.tokenHash eq tokenHash(token)) and
                            (Credentials.tokenType eq TYPE_ACCESS) and
                            (Credentials.expiresAt greaterEq now) and
                            (Users.status eq STATUS_ACTIVE) and
                            (Users.role eq UserRole.HUMAN) and
                            (Users.credentialEpoch eq Credentials.userCredentialEpoch) and
                            (Devices.status eq STATUS_ACTIVE) and
                            (Devices.credentialEpoch eq Credentials.deviceCredentialEpoch)
                    }
                    .singleOrNull()
                    ?.toTokenInfo()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger.warn("Access-token validation failed closed", error)
            null
        }
    }

    private suspend fun <T> credentialTransaction(block: suspend org.jetbrains.exposed.sql.Transaction.() -> T): T =
        newSuspendedTransaction(dbDispatcher) {
            maxAttempts = 1
            block()
        }

    private fun lockUser(uid: String): ResultRow? = Users.selectAll()
        .where { Users.uid eq uid }
        .forUpdate()
        .singleOrNull()

    private fun lockDevice(uid: String, deviceId: String): ResultRow? = Devices.selectAll()
        .where { (Devices.uid eq uid) and (Devices.deviceId eq deviceId) }
        .forUpdate()
        .singleOrNull()

    private fun rotateAndLockDevice(uid: String, device: CredentialDevice, now: Long): Long {
        val existing = lockDevice(uid, device.deviceId)
        if (existing == null) {
            Devices.insert {
                it[Devices.uid] = uid
                it[deviceId] = device.deviceId
                it[deviceName] = device.deviceName
                it[deviceModel] = device.deviceModel
                it[deviceFlag] = device.deviceFlag
                it[status] = STATUS_ACTIVE
                it[credentialEpoch] = INITIAL_EPOCH
                it[lastLogin] = now
                it[createdAt] = now
            }
            return INITIAL_EPOCH
        }
        val epoch = checkedNextEpoch(existing[Devices.credentialEpoch])
        updateDeviceMetadata(uid, device, now, epoch)
        return epoch
    }

    private fun updateDeviceMetadata(uid: String, device: CredentialDevice, now: Long, epoch: Long) {
        Devices.update({ (Devices.uid eq uid) and (Devices.deviceId eq device.deviceId) }) {
            it[deviceName] = device.deviceName
            it[deviceModel] = device.deviceModel
            it[deviceFlag] = device.deviceFlag
            it[status] = STATUS_ACTIVE
            it[credentialEpoch] = epoch
            it[lastLogin] = now
        }
    }

    private fun insertCredentialPair(
        uid: String,
        username: String,
        name: String,
        device: CredentialDevice,
        userEpoch: Long,
        deviceEpoch: Long,
        now: Long,
    ): IssuedCredentials {
        val accessToken = generateRawToken()
        val refreshToken = generateRawToken()
        val accessExpiry = Math.addExact(now, ACCESS_TOKEN_TTL)
        val refreshExpiry = Math.addExact(now, REFRESH_TOKEN_TTL)
        insertCredential(accessToken, TYPE_ACCESS, uid, device, userEpoch, deviceEpoch, now, accessExpiry)
        insertCredential(refreshToken, TYPE_REFRESH, uid, device, userEpoch, deviceEpoch, now, refreshExpiry)
        return IssuedCredentials(
            accessToken = accessToken,
            refreshToken = refreshToken,
            principal = TokenInfo(
                uid = uid,
                deviceId = device.deviceId,
                deviceFlag = device.deviceFlag,
                createdAt = now,
                expiresAt = accessExpiry,
                userCredentialEpoch = userEpoch,
                deviceCredentialEpoch = deviceEpoch,
            ),
            subject = CredentialSubject(username = username, name = name),
        )
    }

    private fun insertCredential(
        rawToken: String,
        type: Int,
        uid: String,
        device: CredentialDevice,
        userEpoch: Long,
        deviceEpoch: Long,
        now: Long,
        expiresAt: Long,
    ) {
        Credentials.insert {
            it[tokenHash] = tokenHash(rawToken)
            it[tokenType] = type
            it[Credentials.uid] = uid
            it[deviceId] = device.deviceId
            it[deviceFlag] = device.deviceFlag
            it[userCredentialEpoch] = userEpoch
            it[deviceCredentialEpoch] = deviceEpoch
            it[createdAt] = now
            it[Credentials.expiresAt] = expiresAt
        }
    }

    private fun ResultRow.toTokenInfo() = TokenInfo(
        uid = this[Credentials.uid],
        deviceId = this[Credentials.deviceId],
        deviceFlag = this[Credentials.deviceFlag],
        createdAt = this[Credentials.createdAt],
        expiresAt = this[Credentials.expiresAt],
        userCredentialEpoch = this[Credentials.userCredentialEpoch],
        deviceCredentialEpoch = this[Credentials.deviceCredentialEpoch],
    )

    private fun generateRawToken(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(TOKEN_BYTES).also(random::nextBytes))

    private fun tokenHash(rawToken: String): String = buildString(64) {
        MessageDigest.getInstance("SHA-256")
            .digest(rawToken.toByteArray(Charsets.UTF_8))
            .forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX[value ushr 4])
                append(HEX[value and 0x0f])
            }
    }

    private fun checkedNextEpoch(current: Long): Long {
        check(current in INITIAL_EPOCH until Long.MAX_VALUE) { "Credential epoch exhausted" }
        return current + 1L
    }

    private companion object {
        const val STATUS_ACTIVE = 1
        const val STATUS_REVOKED = 2
        const val INITIAL_EPOCH = 1L
        const val TYPE_ACCESS = 1
        const val TYPE_REFRESH = 2
        const val TOKEN_BYTES = 32
        const val HEX = "0123456789abcdef"
        const val ACCESS_TOKEN_TTL = 30L * 24 * 60 * 60 * 1000
        const val REFRESH_TOKEN_TTL = 90L * 24 * 60 * 60 * 1000
    }
}
