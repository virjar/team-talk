package com.virjar.tk.server.application.admin

import com.virjar.tk.protocol.model.AuthRules
import com.virjar.tk.server.domain.auth.CredentialAdministration
import com.virjar.tk.server.domain.auth.PasswordHasher
import com.virjar.tk.server.domain.auth.commitCredentialMutationAndFence
import com.virjar.tk.server.domain.session.OnlineSessions

/** 管理员凭据用例，与管理员读模型聚合器隔离。 */
class AdminCredentialCommands(
    private val credentials: CredentialAdministration,
    private val onlineSessions: OnlineSessions,
    private val passwordHasher: PasswordHasher,
) {
    suspend fun ban(uid: String) {
        commitCredentialMutationAndFence(
            commit = { credentials.banUser(uid) },
            publishFence = { epoch -> onlineSessions.invalidateUserCredentials(uid, epoch) },
        )
    }

    suspend fun unban(uid: String) = credentials.unbanUser(uid)

    suspend fun resetPassword(uid: String, newPassword: String) {
        AuthRules.validatePassword(newPassword)?.let { throw IllegalArgumentException(it) }
        // 哈希计算在受限的 PasswordHasher 拥有者上完成，早于任何 PostgreSQL 变更。
        val hash = passwordHasher.hash(newPassword)
        commitCredentialMutationAndFence(
            commit = { credentials.resetPasswordAndRevoke(uid, hash) },
            publishFence = { epoch -> onlineSessions.invalidateUserCredentials(uid, epoch) },
        )
    }
}
