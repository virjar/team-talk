package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.domain.auth.AccessTokenValidator
import com.virjar.tk.server.domain.auth.AuthenticatedDeviceLimitReachedException
import com.virjar.tk.server.domain.auth.AuthenticatedDevicePolicy
import com.virjar.tk.server.domain.auth.CredentialAdministration
import com.virjar.tk.server.domain.auth.CredentialDevice
import com.virjar.tk.server.domain.auth.CredentialIssueRequest
import com.virjar.tk.server.domain.auth.CredentialSessionAuthority
import com.virjar.tk.server.domain.auth.CredentialSubject
import com.virjar.tk.server.domain.auth.IssuedCredentials
import com.virjar.tk.server.domain.auth.InitialCredentialIssuer
import com.virjar.tk.server.domain.auth.TokenInfo
import com.virjar.tk.server.domain.auth.TokenRepository
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.infra.db.Credentials
import com.virjar.tk.server.infra.db.ClientTelemetryDevices
import com.virjar.tk.server.infra.db.ClientTelemetryPolicies
import com.virjar.tk.server.infra.db.Devices
import com.virjar.tk.server.infra.db.Users
import com.virjar.tk.server.infra.db.requireExposedTransaction
import com.virjar.tk.protocol.model.User
import com.virjar.tk.protocol.model.UserRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
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

/** 确定性测试缝隙。参数只包含身份信息，绝不包含原始或哈希后的 token。 */
fun interface CredentialRepositoryHooks {
    suspend fun beforeUserLock(operation: CredentialMutation, uid: String, deviceId: String?) = Unit
    suspend fun afterUserLock(operation: CredentialMutation, uid: String, deviceId: String?)

    object None : CredentialRepositoryHooks {
        override suspend fun afterUserLock(operation: CredentialMutation, uid: String, deviceId: String?) = Unit
    }
}

/**
 * PostgreSQL 凭据聚合。
 *
 * 每次变更都按一个顺序获取锁：user、device、credential。原始秘密只返回
 * 一次，只有 SHA-256 十六进制摘要被持久化。校验是单个连接快照，
 * 并在数据库错误时 fail closed。
 */
class ExposedCredentialRepository(
    private val database: Database,
    private val clock: () -> Long = System::currentTimeMillis,
    private val dbDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val hooks: CredentialRepositoryHooks = CredentialRepositoryHooks.None,
    private val random: SecureRandom = SecureRandom(),
) : TokenRepository, AccessTokenValidator, CredentialAdministration, InitialCredentialIssuer,
    CredentialSessionAuthority {
    private val logger = LoggerFactory.getLogger(ExposedCredentialRepository::class.java)

    override fun issueInitialCredentials(
        transaction: PgWriteTransactionContext,
        user: User,
        device: CredentialDevice,
    ): IssuedCredentials {
        transaction.requireExposedTransaction()
        val storedUser = lockUser(user.uid)
            ?: error("Registration user disappeared inside its aggregate transaction")
        check(storedUser[Users.role] == UserRole.HUMAN && storedUser[Users.status] == STATUS_ACTIVE) {
            "Initial credentials require a newly registered active human"
        }
        check(storedUser[Users.credentialEpoch] == INITIAL_USER_CREDENTIAL_EPOCH) {
            "Initial credential owner has an unexpected user epoch"
        }
        check(storedUser[Users.deviceCredentialSequence] == 0L) {
            "Initial credential owner already allocated a device epoch"
        }
        check(Devices.selectAll().where { Devices.uid eq user.uid }.none()) {
            "Initial credential owner already has a device"
        }
        check(Credentials.selectAll().where { Credentials.uid eq user.uid }.none()) {
            "Initial credential owner already has credentials"
        }

        val now = clock()
        val deviceEpoch = allocateDeviceCredentialEpoch(storedUser)
        Devices.insert {
            it[Devices.uid] = user.uid
            it[deviceId] = device.deviceId
            it[deviceName] = device.deviceName
            it[deviceModel] = device.deviceModel
            it[deviceFlag] = device.deviceFlag
            it[status] = STATUS_ACTIVE
            it[credentialEpoch] = deviceEpoch
            it[lastLogin] = now
            it[createdAt] = now
        }
        return insertCredentialPair(
            uid = user.uid,
            username = storedUser[Users.username],
            name = storedUser[Users.name],
            device = device,
            userEpoch = INITIAL_USER_CREDENTIAL_EPOCH,
            deviceEpoch = deviceEpoch,
            now = now,
        )
    }

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
            val deviceEpoch = rotateAndLockDevice(user, request.device, now)
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
            // 拥有者发现刻意不加锁。权威行只在 user/device 锁之后
            // 重新读取，因此伪造的设备无法消费真正的 token。
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

            // refresh bearer 是此设备的稳定根，并保留其原始绝对
            // 过期时间。只有访问凭据轮换。保留相同的 refresh 哈希，
            // 使已提交的响应在 socket 或客户端进程丢失 AUTH_RESP 后仍可安全重试。
            val deletedAccessCredentials = Credentials.deleteWhere {
                (Credentials.uid eq uid) and
                    (Credentials.deviceId eq device.deviceId) and
                    (Credentials.tokenType eq TYPE_ACCESS)
            }
            check(deletedAccessCredentials == 1) {
                "Active credential aggregate must contain exactly one access token"
            }
            val deviceEpoch = allocateDeviceCredentialEpoch(user)
            updateDeviceMetadata(uid, device, now, deviceEpoch)
            val updatedRefreshCredentials = Credentials.update({
                (Credentials.tokenHash eq refreshHash) and
                    (Credentials.tokenType eq TYPE_REFRESH)
            }) {
                // 不更新 createdAt/expiresAt：refresh 生命周期是从密码签发起
                // 绝对的 90 天，而不是被后台重连不断延长的滑动窗口。
                it[deviceCredentialEpoch] = deviceEpoch
            }
            check(updatedRefreshCredentials == 1) {
                "Active credential aggregate must contain exactly one refresh token"
            }
            insertAccessCredential(
                uid = uid,
                username = user[Users.username],
                name = user[Users.name],
                refreshToken = refreshToken,
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
            val user = lockUser(uid) ?: return@credentialTransaction null
            hooks.afterUserLock(CredentialMutation.REVOKE_DEVICE, uid, deviceId)
            val device = lockDevice(uid, deviceId) ?: return@credentialTransaction null
            val currentDeviceEpoch = device[Devices.credentialEpoch]

            val committedEpoch = if (device[Devices.status] == STATUS_ACTIVE) {
                allocateDeviceCredentialEpoch(user).also { next ->
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
            deleteTelemetryInstallationControl(uid, deviceId)
            committedEpoch
        }

    override suspend fun revokeDeviceIfCurrent(
        uid: String,
        deviceId: String,
        expectedDeviceCredentialEpoch: Long,
    ): Long? = credentialTransaction {
        require(expectedDeviceCredentialEpoch > 0L) { "Session device credential epoch must be positive" }
        hooks.beforeUserLock(CredentialMutation.REVOKE_DEVICE, uid, deviceId)
        val user = lockUser(uid) ?: return@credentialTransaction null
        hooks.afterUserLock(CredentialMutation.REVOKE_DEVICE, uid, deviceId)
        val device = lockDevice(uid, deviceId) ?: return@credentialTransaction null
        val currentDeviceEpoch = device[Devices.credentialEpoch]
        check(currentDeviceEpoch >= expectedDeviceCredentialEpoch) {
            "Session device credential epoch is ahead of authority"
        }

        // 来自更旧 TCP 会话的延迟登出，绝不能吊销同一安装上
        // 之后由密码登录或刷新签发的凭据。
        if (currentDeviceEpoch != expectedDeviceCredentialEpoch) {
            return@credentialTransaction currentDeviceEpoch
        }

        val committedEpoch = if (device[Devices.status] == STATUS_ACTIVE) {
            allocateDeviceCredentialEpoch(user).also { next ->
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
        deleteTelemetryInstallationControl(uid, deviceId)
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
            val nextRevision = checkedNextUserRevision(user[Users.revision])
            checkedNextEpoch(currentEpoch).also { next ->
                Users.update({ Users.uid eq uid }) {
                    it[status] = STATUS_REVOKED
                    it[revision] = nextRevision
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
            val user = lockUser(uid) ?: throw IllegalArgumentException("用户不存在: $uid")
            hooks.afterUserLock(CredentialMutation.UNBAN_USER, uid, null)
            if (user[Users.status] != STATUS_ACTIVE) {
                val nextRevision = checkedNextUserRevision(user[Users.revision])
                Users.update({ Users.uid eq uid }) {
                    it[status] = STATUS_ACTIVE
                    it[revision] = nextRevision
                    it[updatedAt] = clock()
                }
            }
        }
    }

    override suspend fun resetPasswordAndRevoke(uid: String, passwordHash: String): Long =
        credentialTransaction {
            hooks.beforeUserLock(CredentialMutation.RESET_PASSWORD, uid, null)
            val user = lockUser(uid) ?: throw IllegalArgumentException("用户不存在: $uid")
            hooks.afterUserLock(CredentialMutation.RESET_PASSWORD, uid, null)
            require(user[Users.role] == UserRole.HUMAN) { "服务账户不支持密码重置" }
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
            newSuspendedTransaction(
                context = dbDispatcher,
                db = database,
                readOnly = true,
            ) {
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

    override suspend fun isCurrent(
        uid: String,
        deviceId: String,
        userCredentialEpoch: Long,
        deviceCredentialEpoch: Long,
    ): Boolean {
        if (
            uid.isBlank() || deviceId.isBlank() ||
            userCredentialEpoch <= 0L || deviceCredentialEpoch <= 0L
        ) return false
        return try {
            newSuspendedTransaction(
                context = dbDispatcher,
                db = database,
                readOnly = true,
            ) {
                maxAttempts = 1
                Users
                    .join(
                        Devices,
                        JoinType.INNER,
                        onColumn = Users.uid,
                        otherColumn = Devices.uid,
                    )
                    .selectAll()
                    .where {
                        (Users.uid eq uid) and
                            (Users.status eq STATUS_ACTIVE) and
                            (Users.role eq UserRole.HUMAN) and
                            (Users.credentialEpoch eq userCredentialEpoch) and
                            (Devices.deviceId eq deviceId) and
                            (Devices.status eq STATUS_ACTIVE) and
                            (Devices.credentialEpoch eq deviceCredentialEpoch)
                    }
                    .limit(1)
                    .singleOrNull() != null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger.warn("Credential-session authority check failed closed", error)
            false
        }
    }

    private suspend fun <T> credentialTransaction(block: suspend org.jetbrains.exposed.sql.Transaction.() -> T): T =
        newSuspendedTransaction(
            context = dbDispatcher,
            db = database,
        ) {
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

    private fun rotateAndLockDevice(user: ResultRow, device: CredentialDevice, now: Long): Long {
        val uid = user[Users.uid]
        val existing = lockDevice(uid, device.deviceId)
        if (existing == null) {
            reclaimRevokedDeviceSlotOrThrow(uid)
            val epoch = allocateDeviceCredentialEpoch(user)
            Devices.insert {
                it[Devices.uid] = uid
                it[deviceId] = device.deviceId
                it[deviceName] = device.deviceName
                it[deviceModel] = device.deviceModel
                it[deviceFlag] = device.deviceFlag
                it[status] = STATUS_ACTIVE
                it[credentialEpoch] = epoch
                it[lastLogin] = now
                it[createdAt] = now
            }
            return epoch
        }
        if (existing[Devices.status] == STATUS_REVOKED) {
            // 重新激活的设备 id 是一个新的已鉴权安装世代。它绝不能
            // 继承已退休世代的运行时档案或精确诊断采集规则。
            deleteTelemetryInstallationControl(uid, device.deviceId)
        }
        val epoch = allocateDeviceCredentialEpoch(user)
        updateDeviceMetadata(uid, device, now, epoch)
        return epoch
    }

    /**
     * 每次凭据变更都已锁定用户行，它序列化此 uid 的空设备
     * 谓词与插入。新安装只能回收一个已吊销的
     * least-recently-used 行；全部活跃的容量 fail closed，而不暴露账户细节。
     */
    private fun reclaimRevokedDeviceSlotOrThrow(uid: String) {
        val deviceCount = Devices.selectAll().where { Devices.uid eq uid }.count()
        if (deviceCount < AuthenticatedDevicePolicy.MAX_DEVICES_PER_USER) return
        check(deviceCount == AuthenticatedDevicePolicy.MAX_DEVICES_PER_USER.toLong()) {
            "Persistent device capacity invariant violated"
        }

        val reclaimed = Devices.selectAll()
            .where { (Devices.uid eq uid) and (Devices.status eq STATUS_REVOKED) }
            .orderBy(
                Devices.lastLogin to SortOrder.ASC,
                Devices.createdAt to SortOrder.ASC,
                Devices.id to SortOrder.ASC,
            )
            .forUpdate()
            .firstOrNull()
            ?: throw AuthenticatedDeviceLimitReachedException()
        val reclaimedDeviceId = reclaimed[Devices.deviceId]
        Credentials.deleteWhere {
            (Credentials.uid eq uid) and (Credentials.deviceId eq reclaimedDeviceId)
        }
        deleteTelemetryInstallationControl(uid, reclaimedDeviceId)
        check(Devices.deleteWhere { Devices.id eq reclaimed[Devices.id] } == 1) {
            "Revoked device slot disappeared while the user aggregate was locked"
        }
    }

    /** 遥测事实从属于一个活跃的已鉴权安装世代。 */
    private fun deleteTelemetryInstallationControl(uid: String, deviceId: String) {
        ClientTelemetryPolicies.deleteWhere {
            (ClientTelemetryPolicies.targetUid eq uid) and
                (ClientTelemetryPolicies.targetDeviceKey eq deviceId)
        }
        ClientTelemetryDevices.deleteWhere {
            (ClientTelemetryDevices.uid eq uid) and
                (ClientTelemetryDevices.deviceId eq deviceId)
        }
    }

    /** 从不可驱逐的用户行分配，使被回收的设备 id 绝不会让 fence 倒退。 */
    private fun allocateDeviceCredentialEpoch(user: ResultRow): Long {
        val current = user[Users.deviceCredentialSequence]
        val next = checkedNextEpoch(current)
        val uid = user[Users.uid]
        check(Users.update({ Users.uid eq uid }) { it[deviceCredentialSequence] = next } == 1) {
            "Credential owner disappeared while locked"
        }
        return next
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
        val refreshToken = generateRawToken()
        val refreshExpiry = Math.addExact(now, REFRESH_TOKEN_TTL)
        val issued = insertAccessCredential(
            uid = uid,
            username = username,
            name = name,
            refreshToken = refreshToken,
            device = device,
            userEpoch = userEpoch,
            deviceEpoch = deviceEpoch,
            now = now,
        )
        insertCredential(refreshToken, TYPE_REFRESH, uid, device, userEpoch, deviceEpoch, now, refreshExpiry)
        return issued
    }

    private fun insertAccessCredential(
        uid: String,
        username: String,
        name: String,
        refreshToken: String,
        device: CredentialDevice,
        userEpoch: Long,
        deviceEpoch: Long,
        now: Long,
    ): IssuedCredentials {
        val accessToken = generateRawToken()
        val accessExpiry = Math.addExact(now, ACCESS_TOKEN_TTL)
        insertCredential(accessToken, TYPE_ACCESS, uid, device, userEpoch, deviceEpoch, now, accessExpiry)
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
        check(current in 0L until Long.MAX_VALUE) { "Credential epoch exhausted" }
        return current + 1L
    }

    private fun checkedNextUserRevision(current: Long): Long {
        check(current in 1L until Long.MAX_VALUE) { "User revision exhausted" }
        return current + 1L
    }

    private companion object {
        const val INITIAL_USER_CREDENTIAL_EPOCH = 1L
        const val STATUS_ACTIVE = 1
        const val STATUS_REVOKED = 2
        const val TYPE_ACCESS = 1
        const val TYPE_REFRESH = 2
        const val TOKEN_BYTES = 32
        const val HEX = "0123456789abcdef"
        const val ACCESS_TOKEN_TTL = 30L * 24 * 60 * 60 * 1000
        const val REFRESH_TOKEN_TTL = 90L * 24 * 60 * 60 * 1000
    }
}
