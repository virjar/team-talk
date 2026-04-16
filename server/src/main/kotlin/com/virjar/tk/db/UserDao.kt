package com.virjar.tk.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt

data class UserRow(
    val id: Long,
    val uid: String,
    val username: String?,
    val name: String,
    val phone: String?,
    val zone: String,
    val avatar: String,
    val sex: Int,
    val shortNo: String?,
    val status: Int,
    val role: Int,
)

object UserDao {

    fun create(
        uid: String,
        username: String?,
        name: String,
        phone: String?,
        zone: String,
        plainPassword: String,
    ): UserRow = transaction {
        val hash = BCrypt.hashpw(plainPassword, BCrypt.gensalt())
        val now = System.currentTimeMillis()
        val id = Users.insert {
            it[Users.uid] = uid
            it[Users.username] = username
            it[Users.name] = name
            it[Users.phone] = phone
            it[Users.zone] = zone
            it[Users.passwordHash] = hash
            it[Users.createdAt] = now
            it[Users.updatedAt] = now
        } get Users.id

        UserRow(
            id = id.value,
            uid = uid,
            username = username,
            name = name,
            phone = phone,
            zone = zone,
            avatar = "",
            sex = 0,
            shortNo = null,
            status = 1,
            role = 0,
        )
    }

    fun findByUid(uid: String): UserRow? = transaction {
        Users.selectAll().where { Users.uid eq uid }
            .map { it.toUserRow() }
            .singleOrNull()
    }

    fun findByUsername(username: String): UserRow? = transaction {
        Users.selectAll().where { Users.username eq username }
            .map { it.toUserRow() }
            .singleOrNull()
    }

    fun findByPhone(zone: String, phone: String): UserRow? = transaction {
        Users.selectAll().where { (Users.zone eq zone) and (Users.phone eq phone) }
            .map { it.toUserRow() }
            .singleOrNull()
    }

    fun verifyPassword(uid: String, plainPassword: String): Boolean = transaction {
        Users.selectAll().where { Users.uid eq uid }
            .singleOrNull()
            ?.let { BCrypt.checkpw(plainPassword, it[Users.passwordHash]) }
            ?: false
    }

    fun updateProfile(uid: String, name: String?, avatar: String?, sex: Int?) = transaction {
        Users.update({ Users.uid eq uid }) {
            name?.let { v -> it[Users.name] = v }
            avatar?.let { v -> it[Users.avatar] = v }
            sex?.let { v -> it[Users.sex] = v }
            it[Users.updatedAt] = System.currentTimeMillis()
        }
    }

    /** 启动时批量加载所有用户（同步事务） */
    fun loadAll(): List<UserRow> = transaction {
        Users.selectAll().map { it.toUserRow() }
    }

    fun search(query: String, limit: Int = 20): List<UserRow> = transaction {
        val likePattern = "%$query%"
        Users.selectAll().where {
            (Users.name like likePattern) or
            (Users.username like likePattern) or
            (Users.phone like likePattern) or
            (Users.shortNo like likePattern)
        }.limit(limit).map { it.toUserRow() }
    }

    private fun ResultRow.toUserRow() = UserRow(
        id = this[Users.id].value,
        uid = this[Users.uid],
        username = this[Users.username],
        name = this[Users.name],
        phone = this[Users.phone],
        zone = this[Users.zone],
        avatar = this[Users.avatar],
        sex = this[Users.sex],
        shortNo = this[Users.shortNo],
        status = this[Users.status],
        role = this[Users.role],
    )
}
