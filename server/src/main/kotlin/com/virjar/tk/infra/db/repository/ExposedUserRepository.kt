package com.virjar.tk.infra.db.repository

import com.virjar.tk.domain.user.UserInternal
import com.virjar.tk.domain.user.UserProfileMutation
import com.virjar.tk.domain.user.UserRepository
import com.virjar.tk.domain.transaction.PgTransactionContext
import com.virjar.tk.infra.db.USERS_PHONE_UNIQUE_INDEX
import com.virjar.tk.infra.db.Users
import com.virjar.tk.infra.db.requireExposedTransaction
import com.virjar.tk.model.ProfilePatch
import com.virjar.tk.model.User
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.postgresql.util.PSQLException
import org.slf4j.LoggerFactory
import java.sql.SQLException

/** Internal-only test seam around the exact PostgreSQL row-lock acquisition. Production passes null. */
internal interface UserProfileLockObserver {
    fun beforeUserRowLock(uid: String)
    fun afterUserRowLock(uid: String)
}

class ExposedUserRepository internal constructor(
    private val profileLockObserver: UserProfileLockObserver? = null,
) : UserRepository {
    private val logger = LoggerFactory.getLogger(ExposedUserRepository::class.java)

    override fun findByUid(uid: String): User? {
        return transaction {
            Users.selectAll().where { Users.uid eq uid }.map { it.toUser() }.singleOrNull()
        }
    }

    override fun findInternalByUsername(username: String): UserInternal? {
        return transaction {
            Users.selectAll().where { Users.username eq username }.map { it.toUserInternal() }.singleOrNull()
        }
    }

    override fun findInternalByUid(uid: String): UserInternal? {
        return transaction {
            Users.selectAll().where { Users.uid eq uid }.map { it.toUserInternal() }.singleOrNull()
        }
    }

    override fun findByUsername(username: String): User? {
        return transaction {
            Users.selectAll().where { Users.username eq username }.map { it.toUser() }.singleOrNull()
        }
    }

    override fun findByPhone(phone: String): User? {
        return transaction {
            Users.selectAll().where { Users.phone eq phone }.map { it.toUser() }.singleOrNull()
        }
    }

    override fun create(uid: String, username: String, name: String, passwordHash: String, phone: String?, role: Int): User {
        val now = System.currentTimeMillis()
        return transaction {
            insertUser(uid, username, name, passwordHash, phone, role, now)
        }
    }

    override fun createServiceAccount(
        transaction: PgTransactionContext,
        uid: String,
        username: String,
        name: String,
        unusablePasswordHash: String,
        role: Int,
    ): User {
        transaction.requireExposedTransaction()
        return insertUser(
            uid = uid,
            username = username,
            name = name,
            passwordHash = unusablePasswordHash,
            phone = null,
            role = role,
            now = System.currentTimeMillis(),
        )
    }

    override fun updateProfile(
        transaction: PgTransactionContext,
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

        val after = before.copy(
            name = if (patch.name.isPresent) requireNotNull(patch.name.valueOrNull) else before.name,
            avatar = if (patch.avatar.isPresent) patch.avatar.valueOrNull else before.avatar,
            sex = if (patch.sex.isPresent) requireNotNull(patch.sex.valueOrNull) else before.sex,
            phone = if (patch.phone.isPresent) patch.phone.valueOrNull else before.phone,
        )
        if (after == before) return UserProfileMutation(user = before, changed = false)

        try {
            Users.update({ Users.uid eq uid }) {
                if (patch.name.isPresent && after.name != before.name) it[Users.name] = after.name
                if (patch.avatar.isPresent && after.avatar != before.avatar) it[Users.avatar] = after.avatar
                if (patch.sex.isPresent && after.sex != before.sex) it[Users.sex] = after.sex
                if (patch.phone.isPresent && after.phone != before.phone) it[Users.phone] = after.phone
                it[Users.updatedAt] = System.currentTimeMillis()
            }
        } catch (error: SQLException) {
            if (error.isPhoneUniqueViolation()) {
                // Do not retain the driver exception as a cause: PostgreSQL's detail contains the
                // conflicting value and RpcDispatcher logs business exception messages.
                throw IllegalArgumentException("手机号已被使用")
            }
            throw error
        }
        return UserProfileMutation(user = after, changed = true)
    }

    override fun searchUsers(keyword: String, limit: Int): List<User> {
        return transaction {
            val query = Users.selectAll().where {
                (Users.username like "%$keyword%") or (Users.name like "%$keyword%") or (Users.shortNo eq keyword)
            }.limit(limit)
            val count = query.count()
            logger.info("searchUsers keyword='$keyword' SQL count=$count")
            query.map { it.toUser() }
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
        avatar = this[Users.avatar],
        phone = this[Users.phone],
        sex = this[Users.sex],
        role = this[Users.role],
        status = this[Users.status],
    )

    private fun ResultRow.toUserInternal() = UserInternal(
        user = User(
            uid = this[Users.uid],
            username = this[Users.username],
            name = this[Users.name],
            avatar = this[Users.avatar],
            phone = this[Users.phone],
            sex = this[Users.sex],
            role = this[Users.role],
            status = this[Users.status],
        ),
        passwordHash = this[Users.passwordHash],
        credentialEpoch = this[Users.credentialEpoch],
    )

    private fun SQLException.isPhoneUniqueViolation(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is PSQLException &&
                current.sqlState == POSTGRES_UNIQUE_VIOLATION &&
                current.serverErrorMessage?.constraint == USERS_PHONE_UNIQUE_INDEX
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private companion object {
        const val POSTGRES_UNIQUE_VIOLATION = "23505"
    }
}
