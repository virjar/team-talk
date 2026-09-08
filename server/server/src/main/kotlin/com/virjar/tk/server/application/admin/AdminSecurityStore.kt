package com.virjar.tk.server.application.admin

import kotlinx.serialization.Serializable

/** Blocking persistence port, called on the HTTP/startup blocking boundary. Never stores bearer tokens. */
interface AdminSecurityStore {
    fun credential(): AdminCredential?
    fun saveCredential(credential: AdminCredential, action: String, now: Long)
    fun beginAudit(actor: String, action: String, target: String, now: Long): Long
    fun completeAudit(id: Long, status: Int, now: Long, failureReason: AdminAuditFailureReason? = AdminAuditFailureReason.forStatus(status))
    fun audits(beforeId: Long?, limit: Int): List<AdminAuditRecord>
}

class AdminCredential(
    val username: String,
    val passwordHash: String,
    val updatedAt: Long,
    val recoveryId: String?,
)

@Serializable
data class AdminAuditRecord(
    val id: Long,
    val actor: String,
    val action: String,
    val target: String,
    val createdAt: Long,
    val completedAt: Long?,
    val result: String,
    val httpStatus: Int?,
    val failureReason: AdminAuditFailureReason?,
)

/** Stable categories only: exception messages and submitted credentials never enter the audit record. */
@Serializable
enum class AdminAuditFailureReason {
    INVALID_CREDENTIALS, RATE_LIMITED, UNAUTHENTICATED, FORBIDDEN, INVALID_REQUEST,
    NOT_FOUND, CONFLICT, SERVICE_UNAVAILABLE, INTERNAL_ERROR,
    ;

    companion object {
        fun forStatus(status: Int): AdminAuditFailureReason? = when (status) {
            in 200..399 -> null
            400, 413, 415, 422 -> INVALID_REQUEST
            401 -> UNAUTHENTICATED
            403 -> FORBIDDEN
            404 -> NOT_FOUND
            409 -> CONFLICT
            429 -> RATE_LIMITED
            502, 503, 504 -> SERVICE_UNAVAILABLE
            in 400..499 -> INVALID_REQUEST
            else -> INTERNAL_ERROR
        }
    }
}
