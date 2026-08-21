package com.virjar.tk.domain.user

import com.virjar.tk.domain.transaction.PgTransactionContext
import com.virjar.tk.model.User

/**
 * Stateless user-domain facade. User status and credential epochs are security facts, so process
 * caches must never outlive the PostgreSQL row that changed them.
 */
class UserStore(private val repo: UserRepository) {
    fun findByUid(uid: String): User? = repo.findByUid(uid)

    fun findByUsername(username: String): User? = repo.findByUsername(username)

    fun findByPhone(phone: String): User? = repo.findByPhone(phone)

    fun searchUsers(keyword: String, limit: Int = 20): List<User> =
        repo.searchUsers(keyword, limit)

    fun findInternalByUsername(username: String) = repo.findInternalByUsername(username)

    fun findInternalByUid(uid: String) = repo.findInternalByUid(uid)

    fun create(
        uid: String,
        username: String,
        name: String,
        passwordHash: String,
        phone: String? = null,
        role: Int = 0,
    ): User {
        return repo.create(uid, username, name, passwordHash, phone, role)
    }

    fun createServiceAccount(
        transaction: PgTransactionContext,
        uid: String,
        username: String,
        name: String,
        unusablePasswordHash: String,
        role: Int,
    ): User = repo.createServiceAccount(
        transaction = transaction,
        uid = uid,
        username = username,
        name = name,
        unusablePasswordHash = unusablePasswordHash,
        role = role,
    )

    fun updateProfile(uid: String, name: String? = null, avatar: String? = null, sex: Int? = null, phone: String? = null) {
        repo.updateProfile(uid, name, avatar, sex, phone)
    }

}
