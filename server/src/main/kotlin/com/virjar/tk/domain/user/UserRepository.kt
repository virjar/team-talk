package com.virjar.tk.domain.user

import com.virjar.tk.model.User

/** Persistence port owned by the user domain. */
interface UserRepository {
    fun findByUid(uid: String): User?
    fun findInternalByUsername(username: String): UserInternal?
    fun findInternalByUid(uid: String): UserInternal?
    fun findByUsername(username: String): User?
    fun findByPhone(phone: String): User?
    fun create(
        uid: String,
        username: String,
        name: String,
        passwordHash: String,
        phone: String? = null,
        role: Int = 0,
    ): User
    fun updateProfile(uid: String, name: String? = null, avatar: String? = null, sex: Int? = null, phone: String? = null)
    fun searchUsers(keyword: String, limit: Int = 20): List<User>
}

class UserInternal(
    val user: User,
    val passwordHash: String,
    val credentialEpoch: Long,
) {
    override fun toString(): String =
        "UserInternal(uid=${user.uid}, credentialEpoch=$credentialEpoch, passwordHash=<redacted>)"
}
