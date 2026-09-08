package com.virjar.tk.server.api

import com.virjar.tk.server.application.admin.AdminAuditRecord
import com.virjar.tk.server.application.admin.AdminAuditFailureReason
import com.virjar.tk.server.application.admin.AdminBootstrap
import com.virjar.tk.server.application.admin.AdminCredential
import com.virjar.tk.server.application.admin.AdminSecurityService
import com.virjar.tk.server.application.admin.AdminSecurityStore
import com.virjar.tk.server.domain.auth.AuthenticationAttemptGuard
import com.virjar.tk.server.domain.auth.PasswordHasher

/** Fast session/route fixture; persistence and BCrypt are exercised by AdminSecurityIntegrationTest. */
internal fun testAdminSecurity(
    username: String? = "admin",
    password: String? = "test-only-password",
    clock: () -> Long = { 1_000L },
    maxActiveTokens: Int = AdminSecurityService.DEFAULT_MAX_ACTIVE_TOKENS,
    authenticationAttempts: AuthenticationAttemptGuard = AuthenticationAttemptGuard(),
) = AdminSecurityService(
    object : AdminSecurityStore {
        private var saved: AdminCredential? = null
        override fun credential() = saved
        override fun saveCredential(credential: AdminCredential, action: String, now: Long) { saved = credential }
        override fun beginAudit(actor: String, action: String, target: String, now: Long) = 1L
        override fun completeAudit(id: Long, status: Int, now: Long, failureReason: AdminAuditFailureReason?) = Unit
        override fun audits(beforeId: Long?, limit: Int) = emptyList<AdminAuditRecord>()
    },
    object : PasswordHasher {
        override suspend fun hash(rawPassword: String) = "fixture:$rawPassword"
        override suspend fun verify(rawPassword: String, encodedHash: String?) = encodedHash == "fixture:$rawPassword"
    },
    authenticationAttempts, AdminBootstrap(username, password, null), clock, maxActiveTokens,
)
