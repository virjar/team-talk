package com.virjar.tk.app.navigation.feature.document

import com.virjar.tk.protocol.model.DocumentSpaceGrant
import com.virjar.tk.protocol.ReliableCommandContract
import java.util.UUID

/** 一个显式文档权限意图的进程生命周期可靠身份。 */
internal data class DocumentPolicyMutationIntent(
    val operationId: String,
    val spaceId: String,
    val expectedPolicyRevision: Long,
    val issuedAt: Long,
    val kind: Kind,
    val principalType: Int,
    val principalId: String,
    val role: Int?,
    val includeDescendants: Boolean,
) {
    enum class Kind { UPSERT, REMOVE }

    internal fun key() = Key(
        spaceId,
        kind,
        principalType,
        principalId,
        role,
        includeDescendants,
    )

    internal data class Key(
        val spaceId: String,
        val kind: Kind,
        val principalType: Int,
        val principalId: String,
        val role: Int?,
        val includeDescendants: Boolean,
    )
}

/**
 * 在工作区的整个生命周期内，把结果未知的 ACL retry 保持在一个操作身份上。
 * 确定的 4xx 拒绝和成功的确认释放该意图；传输/5xx 和取消保留它。
 * 有界映射绝不可能把一个未知身份驱逐成第二条命令。
 */
internal class DocumentPolicyMutationOutbox(
    private val nextOperationId: () -> String = { UUID.randomUUID().toString() },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val capacity: Int = MAX_PENDING,
) {
    private val pendingByKey = linkedMapOf<DocumentPolicyMutationIntent.Key, DocumentPolicyMutationIntent>()

    init {
        require(capacity > 0) { "document policy outbox capacity must be positive" }
    }

    fun acquireUpsert(
        spaceId: String,
        expectedPolicyRevision: Long,
        principalType: Int,
        principalId: String,
        role: Int,
        includeDescendants: Boolean,
    ): DocumentPolicyMutationIntent = acquire(
        spaceId = spaceId,
        expectedPolicyRevision = expectedPolicyRevision,
        kind = DocumentPolicyMutationIntent.Kind.UPSERT,
        principalType = principalType,
        principalId = principalId,
        role = role,
        includeDescendants =
            principalType == DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT && includeDescendants,
    )

    fun acquireRemove(
        spaceId: String,
        expectedPolicyRevision: Long,
        principalType: Int,
        principalId: String,
    ): DocumentPolicyMutationIntent = acquire(
        spaceId = spaceId,
        expectedPolicyRevision = expectedPolicyRevision,
        kind = DocumentPolicyMutationIntent.Kind.REMOVE,
        principalType = principalType,
        principalId = principalId,
        role = null,
        includeDescendants = false,
    )

    fun complete(intent: DocumentPolicyMutationIntent): Boolean =
        pendingByKey.remove(intent.key(), intent)

    fun contains(intent: DocumentPolicyMutationIntent): Boolean = pendingByKey[intent.key()] == intent

    fun pending(): List<DocumentPolicyMutationIntent> = pendingByKey.values.toList()

    private fun acquire(
        spaceId: String,
        expectedPolicyRevision: Long,
        kind: DocumentPolicyMutationIntent.Kind,
        principalType: Int,
        principalId: String,
        role: Int?,
        includeDescendants: Boolean,
    ): DocumentPolicyMutationIntent {
        require(spaceId.isNotBlank()) { "document policy space id must not be blank" }
        require(expectedPolicyRevision > 0L) { "document policy revision must be positive" }
        require(principalId.isNotBlank()) { "document policy principal id must not be blank" }
        val candidate = DocumentPolicyMutationIntent(
            operationId = "",
            spaceId = spaceId,
            expectedPolicyRevision = expectedPolicyRevision,
            issuedAt = 0L,
            kind = kind,
            principalType = principalType,
            principalId = principalId,
            role = role,
            includeDescendants = includeDescendants,
        )
        pendingByKey[candidate.key()]?.let { return it }
        require(pendingByKey.values.none { it.spaceId == spaceId }) {
            "该文档空间仍有结果未知的权限操作，请先重试原操作"
        }
        require(pendingByKey.size < capacity) { "待确认的文档权限操作过多，请先重试已有操作" }
        val operationId = nextOperationId()
        require(runCatching { UUID.fromString(operationId).toString() }.getOrNull() == operationId) {
            "document policy operation id must be a canonical UUID"
        }
        val issuedAt = nowMillis()
        ReliableCommandContract.lastActiveAt(issuedAt)
        return candidate.copy(operationId = operationId, issuedAt = issuedAt)
            .also { pendingByKey[it.key()] = it }
    }

    private companion object {
        const val MAX_PENDING = 64
    }
}
