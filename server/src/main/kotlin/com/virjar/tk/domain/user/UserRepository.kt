package com.virjar.tk.domain.user

import com.virjar.tk.model.User

/** Persistence port owned by the user domain. */
interface UserRepository {
    fun findByUid(uid: String): User?
    fun findInternalByUsername(username: String): UserInternal?
    fun findInternalByUid(uid: String): UserInternal?
    fun findByUsername(username: String): User?
    fun findByPhone(phone: String): User?
    fun create(uid: String, username: String, name: String, passwordHash: String, phone: String? = null): User
    fun updateProfile(uid: String, name: String? = null, avatar: String? = null, sex: Int? = null, phone: String? = null)
    fun updatePassword(uid: String, passwordHash: String)
    fun searchUsers(keyword: String, limit: Int = 20): List<User>
}

data class UserInternal(
    val user: User,
    val passwordHash: String,
)
