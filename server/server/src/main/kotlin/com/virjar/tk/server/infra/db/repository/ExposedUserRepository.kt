package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.domain.user.HumanRegistrationCommand
import com.virjar.tk.server.domain.user.PhoneAlreadyRegisteredException
import com.virjar.tk.server.domain.user.UserIdentityAllocationException
import com.virjar.tk.server.domain.user.UserIdentityCollisionException
import com.virjar.tk.server.domain.user.UserInternal
import com.virjar.tk.server.domain.user.UserProfileMutation
import com.virjar.tk.server.domain.user.UserRepository
import com.virjar.tk.server.domain.user.UsernameAlreadyRegisteredException
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.infra.db.USERS_PHONE_UNIQUE_INDEX
import com.virjar.tk.server.infra.db.USERS_UID_UNIQUE_INDEX
import com.virjar.tk.server.infra.db.USERS_USERNAME_UNIQUE_INDEX
import com.virjar.tk.server.infra.db.Users
import com.virjar.tk.server.infra.db.requireExposedTransaction
import com.virjar.tk.server.infra.db.toUserAvatar
import com.virjar.tk.protocol.model.ProfilePatch
import com.virjar.tk.protocol.model.User
import com.virjar.tk.protocol.model.UserRole
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.postgresql.util.PSQLException
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.sql.SQLException
import java.util.Base64

/** PostgreSQL LIKE 对绑定模式值默认使用反斜杠作为转义字符。 */
internal fun escapePostgresLikeLiteral(value: String): String = buildString(value.length) {
    value.forEach { character ->
        if (character == '\\' || character == '%' || character == '_') append('\\')
        append(character)
    }
}

/** 围绕精确 PostgreSQL 行锁获取的内部专用测试缝隙。生产环境传 null。 */
internal interface UserProfileLockObserver {
    fun beforeUserRowLock(uid: String)
    fun afterUserRowLock(uid: String)
}

class ExposedUserRepository internal constructor(
    private val database: Database,
    private val profileLockObserver: UserProfileLockObserver? = null,
    private val dbDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val random: SecureRandom = SecureRandom(),
) : UserRepository {
    private val logger = LoggerFactory.getLogger(ExposedUserRepository::class.java)

    override fun findByUid(uid: String): User? {
        return transaction(database) {
            Users.selectAll().where { Users.uid eq uid }.map { it.toUser() }.singleOrNull()
        }
    }

    override fun findByUids(uids: Set<String>): Map<String, User> {
        if (uids.isEmpty()) return emptyMap()
        return transaction(database) {
            buildMap {
                uids.sorted().chunked(USER_LOOKUP_BATCH_SIZE).forEach { batch ->
                    Users.selectAll().where { Users.uid inList batch }.forEach { row ->
                        put(row[Users.uid], row.toUser())
                    }
                }
            }
        }
    }

    override suspend fun findInternalByUsername(username: String): UserInternal? =
        newSuspendedTransaction(context = dbDispatcher, db = database, readOnly = true) {
            maxAttempts = 1
            Users.selectAll().where { Users.username eq username }.map { it.toUserInternal() }.singleOrNull()
        }

    override suspend fun findInternalByUid(uid: String): UserInternal? =
        newSuspendedTransaction(context = dbDispatcher, db = database, readOnly = true) {
            maxAttempts = 1
            Users.selectAll().where { Users.uid eq uid }.map { it.toUserInternal() }.singleOrNull()
        }

    override fun findByUsername(username: String): User? {
        return transaction(database) {
            Users.selectAll().where { Users.username eq username }.map { it.toUser() }.singleOrNull()
        }
    }

    override fun findByPhone(phone: String): User? {
        return transaction(database) {
            Users.selectAll().where { Users.phone eq phone }.map { it.toUser() }.singleOrNull()
        }
    }

    override fun registerHuman(
        transaction: PgWriteTransactionContext,
        command: HumanRegistrationCommand,
    ): User {
        transaction.requireExposedTransaction()
        val now = System.currentTimeMillis()
        return try {
            insertUser(
                uid = command.uid,
                username = command.username,
                name = command.name,
                passwordHash = command.passwordHash,
                phone = command.phone,
                role = UserRole.HUMAN,
                now = now,
            )
        } catch (error: Exception) {
            when (error.postgresUniqueConstraint()) {
                USERS_USERNAME_UNIQUE_INDEX -> throw UsernameAlreadyRegisteredException()
                USERS_PHONE_UNIQUE_INDEX -> throw PhoneAlreadyRegisteredException()
                USERS_UID_UNIQUE_INDEX -> throw UserIdentityCollisionException()
                else -> throw error
            }
        }
    }

    override fun createServiceAccount(
        transaction: PgWriteTransactionContext,
        uid: String,
        username: String,
        name: String,
        role: Int,
    ): User {
        transaction.requireExposedTransaction()
        return try {
            insertUser(
                uid = uid,
                username = username,
                name = name,
                passwordHash = newDisabledCredentialMarker(),
                phone = null,
                role = role,
                now = System.currentTimeMillis(),
            )
        } catch (error: Exception) {
            when (error.postgresUniqueConstraint()) {
                USERS_UID_UNIQUE_INDEX, USERS_USERNAME_UNIQUE_INDEX -> throw UserIdentityAllocationException()
                else -> throw error
            }
        }
    }

    override fun updateProfile(
        transaction: PgWriteTransactionContext,
        uid: String,
        patch: ProfilePatch,
    ): UserProfileMutation {
        transaction.requireExposedTransaction()
        profileLockObserver?.beforeUserRowLock(uid)
        val before = Users.selectAll()
            .where { Users.uid eq uid }
            .forUpdate()
            .singleOrNull()
            ?.toUser()
            ?: throw IllegalArgumentException("用户不存在")
        profileLockObserver?.afterUserRowLock(uid)

        val visibleAfter = before.copy(
            name = if (patch.name.isPresent) requireNotNull(patch.name.valueOrNull) else before.name,
            avatar = if (patch.avatar.isPresent) patch.avatar.valueOrNull else before.avatar,
            sex = if (patch.sex.isPresent) requireNotNull(patch.sex.valueOrNull) else before.sex,
            phone = if (patch.phone.isPresent) patch.phone.valueOrNull else before.phone,
        )
        if (visibleAfter == before) return UserProfileMutation(user = before, changed = false)
        check(before.revision < Long.MAX_VALUE) { "User revision exhausted" }
        val after = visibleAfter.copy(revision = before.revision + 1L)

        try {
            Users.update({ Users.uid eq uid }) {
                if (patch.name.isPresent && after.name != before.name) it[Users.name] = after.name
                if (patch.avatar.isPresent && after.avatar != before.avatar) {
                    it[Users.avatarPath] = after.avatar?.path
                    it[Users.avatarName] = after.avatar?.name
                    it[Users.avatarContentType] = after.avatar?.contentType
                    it[Users.avatarSize] = after.avatar?.size
                }
                if (patch.sex.isPresent && after.sex != before.sex) it[Users.sex] = after.sex
                if (patch.phone.isPresent && after.phone != before.phone) it[Users.phone] = after.phone
                it[Users.revision] = after.revision
                it[Users.updatedAt] = System.currentTimeMillis()
            }
        } catch (error: SQLException) {
            if (error.isPhoneUniqueViolation()) {
                // 不保留驱动异常作为 cause：PostgreSQL 的 detail 中包含
                // 冲突的值，而 RpcDispatcher 会记录业务异常消息。
                throw IllegalArgumentException("手机号已被使用")
            }
            throw error
        }
        return UserProfileMutation(user = after, changed = true)
    }

    override fun searchPublicDirectory(keyword: String, limit: Int): List<User> {
        return transaction(database) {
            val literalPattern = "%${escapePostgresLikeLiteral(keyword)}%"
            val query = Users.selectAll().where {
                (Users.status eq STATUS_ACTIVE) and
                    (Users.role eq UserRole.HUMAN) and
                    (
                        (Users.username like literalPattern) or
                            (Users.name like literalPattern) or
                            (Users.shortNo eq keyword)
                        )
            }.orderBy(
                Users.name to SortOrder.ASC,
                Users.username to SortOrder.ASC,
                Users.uid to SortOrder.ASC,
            ).limit(limit)
            val results = query.map { it.toUser() }
            logger.info("user search SQL completed: queryChars={} results={}", keyword.length, results.size)
            results
        }
    }

    private fun insertUser(
        uid: String,
        username: String,
        name: String,
        passwordHash: String,
        phone: String?,
        role: Int,
        now: Long,
    ): User {
        Users.insert {
            it[Users.uid] = uid
            it[Users.username] = username
            it[Users.name] = name
            it[Users.passwordHash] = passwordHash
            it[Users.phone] = phone
            it[Users.role] = role
            it[Users.createdAt] = now
            it[Users.updatedAt] = now
        }
        return User(uid = uid, username = username, name = name, phone = phone, role = role)
    }

    private fun ResultRow.toUser() = User(
        uid = this[Users.uid],
        username = this[Users.username],
        name = this[Users.name],
        avatar = toUserAvatar(),
        phone = this[Users.phone],
        sex = this[Users.sex],
        role = this[Users.role],
        status = this[Users.status],
        revision = this[Users.revision],
    )

    private fun ResultRow.toUserInternal() = UserInternal(
        user = User(
            uid = this[Users.uid],
            username = this[Users.username],
            name = this[Users.name],
            avatar = toUserAvatar(),
            phone = this[Users.phone],
            sex = this[Users.sex],
            role = this[Users.role],
            status = this[Users.status],
            revision = this[Users.revision],
        ),
        passwordHash = this[Users.passwordHash],
        credentialEpoch = this[Users.credentialEpoch],
    )

    private fun Throwable.postgresUniqueConstraint(): String? {
        var current: Throwable? = this
        while (current != null) {
            if (current is PSQLException &&
                current.sqlState == POSTGRES_UNIQUE_VIOLATION
            ) {
                return current.serverErrorMessage?.constraint
            }
            current = current.cause
        }
        return null
    }

    private fun SQLException.isPhoneUniqueViolation(): Boolean =
        postgresUniqueConstraint() == USERS_PHONE_UNIQUE_INDEX

    private fun newDisabledCredentialMarker(): String =
        DISABLED_CREDENTIAL_PREFIX + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(DISABLED_CREDENTIAL_BYTES).also(random::nextBytes))

    private companion object {
        const val USER_LOOKUP_BATCH_SIZE = 1_000
        const val STATUS_ACTIVE = 1
        const val DISABLED_CREDENTIAL_PREFIX = "!service-account:v1:"
        const val DISABLED_CREDENTIAL_BYTES = 32
        const val POSTGRES_UNIQUE_VIOLATION = "23505"
    }
}
