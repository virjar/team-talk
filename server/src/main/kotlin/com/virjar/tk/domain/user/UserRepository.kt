package com.virjar.tk.domain.user

import com.virjar.tk.infra.db.Users
import com.virjar.tk.model.User
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

class UserRepository {
    private val logger = LoggerFactory.getLogger(UserRepository::class.java)

    data class UserInternal(
        val user: User,
        val passwordHash: String,
    )

    fun findByUid(uid: String): User? {
        return transaction {
            Users.selectAll().where { Users.uid eq uid }.map { it.toUser() }.singleOrNull()
        }
    }

    fun findInternalByUsername(username: String): UserInternal? {
        return transaction {
            Users.selectAll().where { Users.username eq username }.map { it.toUserInternal() }.singleOrNull()
        }
    }

    fun findInternalByUid(uid: String): UserInternal? {
        return transaction {
            Users.selectAll().where { Users.uid eq uid }.map { it.toUserInternal() }.singleOrNull()
        }
    }

    fun findByUsername(username: String): User? {
        return transaction {
            Users.selectAll().where { Users.username eq username }.map { it.toUser() }.singleOrNull()
        }
    }

    fun findByPhone(phone: String): User? {
        return transaction {
            Users.selectAll().where { Users.phone eq phone }.map { it.toUser() }.singleOrNull()
        }
    }

    fun create(uid: String, username: String, name: String, passwordHash: String, phone: String? = null): User {
        val now = System.currentTimeMillis()
        return transaction {
            Users.insert {
                it[Users.uid] = uid
                it[Users.username] = username
                it[Users.name] = name
                it[Users.passwordHash] = passwordHash
                it[Users.phone] = phone
                it[Users.createdAt] = now
                it[Users.updatedAt] = now
            }
            User(uid = uid, username = username, name = name, phone = phone)
        }
    }

    fun updateProfile(uid: String, name: String? = null, avatar: String? = null, sex: Int? = null, phone: String? = null) {
        transaction {
            Users.update({ Users.uid eq uid }) {
                name?.let { v -> it[Users.name] = v }
                avatar?.let { v -> it[Users.avatar] = v }
                sex?.let { v -> it[Users.sex] = v }
                phone?.let { v -> it[Users.phone] = v }
                it[Users.updatedAt] = System.currentTimeMillis()
            }
        }
    }

    fun updatePassword(uid: String, passwordHash: String) {
        transaction {
            Users.update({ Users.uid eq uid }) {
                it[Users.passwordHash] = passwordHash
                it[Users.updatedAt] = System.currentTimeMillis()
            }
        }
    }

    fun searchUsers(keyword: String, limit: Int = 20): List<User> {
        return transaction {
            val query = Users.selectAll().where {
                (Users.username like "%$keyword%") or (Users.name like "%$keyword%") or (Users.shortNo eq keyword)
            }.limit(limit)
            val count = query.count()
            logger.info("searchUsers keyword='$keyword' SQL count=$count")
            query.map { it.toUser() }
        }
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
    )
}
