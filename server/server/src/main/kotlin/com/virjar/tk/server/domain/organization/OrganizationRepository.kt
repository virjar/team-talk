package com.virjar.tk.server.domain.organization

import com.virjar.tk.server.domain.chat.ManagedChatPolicy
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.OrganizationMember
import com.virjar.tk.protocol.model.OrganizationUnit

/** 不透明组织节点游标背后的稳定数据库键。 */
data class OrganizationUnitPageAnchor(val unitId: String)

/** 不透明成员游标背后的稳定数据库关系键。 */
data class OrganizationMemberPageAnchor(val unitId: String, val uid: String)

data class OrganizationUnitPageSlice(
    val revision: Long,
    val items: List<OrganizationUnit>,
    val nextAnchor: OrganizationUnitPageAnchor?,
    /** 当 [revision] 不再匹配请求游标内嵌的修订时为真。 */
    val snapshotChanged: Boolean = false,
)

data class OrganizationMemberPageSlice(
    val revision: Long,
    val items: List<OrganizationMember>,
    val nextAnchor: OrganizationMemberPageAnchor?,
    val snapshotChanged: Boolean = false,
)

data class OrganizationCommandResult<T>(
    val value: T,
    /** 与 [value] 原子提交的全局组织修订。 */
    val revision: Long,
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

data class ManagedChatMemberRoleChange(
    val uid: String,
    val previousRole: Int,
    val currentRole: Int,
)

data class ManagedChatProjectionMutation(
    val task: ManagedChatProjectionTask,
    val applied: Boolean,
    val chat: Chat? = null,
    val addedUids: List<String> = emptyList(),
    val removedUids: List<String> = emptyList(),
    val remainingUids: List<String> = emptyList(),
    val roleChanges: List<ManagedChatMemberRoleChange> = emptyList(),
    val chatMetadataChanged: Boolean = false,
)

data class ManagedChatProjectionFailure(
    val unitId: String,
    val chatId: String,
    val revision: Long,
    val attemptCount: Int,
    val detail: String,
)

/** 单一组织目录的 PostgreSQL 支撑端口。 */
interface OrganizationRepository : ManagedChatPolicy {
    /** 一个可重复读键集页，在任何目录行返回之前先做修订围栏。 */
    fun listUnitPage(
        expectedRevision: Long?,
        after: OrganizationUnitPageAnchor?,
        pageSize: Int,
    ): OrganizationUnitPageSlice

    /**
     * 一个直接或递归的关系页。递归遍历在数据库侧完成，绝不能仅仅为了找一页关系
     * 就物化完整的组织树。
     */
    fun listMemberPage(
        rootUnitId: String,
        recursive: Boolean,
        expectedRevision: Long?,
        after: OrganizationMemberPageAnchor?,
        pageSize: Int,
    ): OrganizationMemberPageSlice

    /** 命令/投影代码使用的内部有界收集器支持；绝不作为单个 RPC 暴露。 */
    fun listUnits(): List<OrganizationUnit>
    fun findUnit(unitId: String): OrganizationUnit?

    fun createUnit(
        transaction: PgWriteTransactionContext,
        unit: OrganizationUnit,
        enableGroup: Boolean,
    ): OrganizationCommandResult<OrganizationUnit>

    fun updateUnit(
        transaction: PgWriteTransactionContext,
        unitId: String,
        parentId: String?,
        name: String,
        leaderUid: String?,
        sortOrder: Int,
    ): OrganizationCommandResult<OrganizationUnit>

    fun archiveUnit(
        transaction: PgWriteTransactionContext,
        unitId: String,
    ): OrganizationCommandResult<Unit>

    fun assignMember(
        transaction: PgWriteTransactionContext,
        member: OrganizationMember,
    ): OrganizationCommandResult<OrganizationMember>

    fun removeMember(
        transaction: PgWriteTransactionContext,
        unitId: String,
        uid: String,
    ): OrganizationCommandResult<Unit>

    fun enableGroup(
        transaction: PgWriteTransactionContext,
        unitId: String,
    ): OrganizationCommandResult<OrganizationUnit>

    fun disableGroup(
        transaction: PgWriteTransactionContext,
        unitId: String,
    ): OrganizationCommandResult<OrganizationUnit>

    fun listMembers(unitIds: Set<String>): List<OrganizationMember>
    /** 一次查询返回各节点的直属成员数；不存在成员的节点由调用方补零。 */
    fun countDirectMembers(unitIds: Set<String>): Map<String, Int>
    fun listMemberships(uid: String): List<OrganizationMember>
}

/** 持久化修订 CAS 投影端口。每次变更都必须加入提供的 PG 工作单元。 */
interface OrganizationManagedChatProjectionStore {
    fun listPending(
        after: ManagedChatProjectionCursor?,
        limit: Int,
        includeDeferred: Boolean,
        nowMillis: Long,
    ): List<ManagedChatProjectionTask>

    fun apply(
        transaction: PgWriteTransactionContext,
        task: ManagedChatProjectionTask,
    ): ManagedChatProjectionMutation

    fun recordFailure(
        transaction: PgWriteTransactionContext,
        task: ManagedChatProjectionTask,
        detail: String,
        nowMillis: Long,
    )

    fun countPending(): Long
    fun currentFailure(): ManagedChatProjectionFailure?
}
