package com.virjar.tk.domain.organization

import com.virjar.tk.domain.chat.ManagedChatPolicy
import com.virjar.tk.domain.transaction.PgTransactionContext
import com.virjar.tk.model.Chat
import com.virjar.tk.model.OrganizationMember
import com.virjar.tk.model.OrganizationUnit

data class OrganizationCommandResult<T>(
    val value: T,
    val projections: List<ManagedChatProjectionTask>,
)

data class ManagedChatProjectionTask(
    val unitId: String,
    val chatId: String,
    val revision: Long,
    val desiredActive: Boolean,
)

data class ManagedChatProjectionCursor(
    val revision: Long,
    val unitId: String,
)

data class ManagedChatProjectionMutation(
    val task: ManagedChatProjectionTask,
    val applied: Boolean,
    val chat: Chat? = null,
    val addedUids: List<String> = emptyList(),
    val removedUids: List<String> = emptyList(),
    val remainingUids: List<String> = emptyList(),
    val finalUids: List<String> = emptyList(),
)

data class ManagedChatProjectionFailure(
    val unitId: String,
    val chatId: String,
    val revision: Long,
    val attemptCount: Int,
    val detail: String,
)

/** PostgreSQL-backed port for the single-organization directory. */
interface OrganizationRepository : ManagedChatPolicy {
    fun listUnits(): List<OrganizationUnit>
    fun findUnit(unitId: String): OrganizationUnit?

    fun createUnit(
        transaction: PgTransactionContext,
        unit: OrganizationUnit,
        enableGroup: Boolean,
    ): OrganizationCommandResult<OrganizationUnit>

    fun updateUnit(
        transaction: PgTransactionContext,
        unitId: String,
        parentId: String?,
        name: String,
        leaderUid: String?,
        sortOrder: Int,
    ): OrganizationCommandResult<OrganizationUnit>

    fun archiveUnit(
        transaction: PgTransactionContext,
        unitId: String,
    ): OrganizationCommandResult<Unit>

    fun assignMember(
        transaction: PgTransactionContext,
        member: OrganizationMember,
    ): OrganizationCommandResult<OrganizationMember>

    fun removeMember(
        transaction: PgTransactionContext,
        unitId: String,
        uid: String,
    ): OrganizationCommandResult<Unit>

    fun enableGroup(
        transaction: PgTransactionContext,
        unitId: String,
    ): OrganizationCommandResult<OrganizationUnit>

    fun disableGroup(
        transaction: PgTransactionContext,
        unitId: String,
    ): OrganizationCommandResult<OrganizationUnit>

    fun listMembers(unitIds: Set<String>): List<OrganizationMember>
    /** 一次查询返回各节点的直属成员数；不存在成员的节点由调用方补零。 */
    fun countDirectMembers(unitIds: Set<String>): Map<String, Int>
    fun listMemberships(uid: String): List<OrganizationMember>
}

/** Durable revision-CAS projection port. Every mutation must join the supplied PG unit of work. */
interface OrganizationManagedChatProjectionStore {
    fun listPending(
        after: ManagedChatProjectionCursor?,
        limit: Int,
        includeDeferred: Boolean,
        nowMillis: Long,
    ): List<ManagedChatProjectionTask>

    fun apply(
        transaction: PgTransactionContext,
        task: ManagedChatProjectionTask,
    ): ManagedChatProjectionMutation

    fun recordFailure(
        transaction: PgTransactionContext,
        task: ManagedChatProjectionTask,
        detail: String,
        nowMillis: Long,
    )

    fun countPending(): Long
    fun currentFailure(): ManagedChatProjectionFailure?
}
