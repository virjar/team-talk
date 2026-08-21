package com.virjar.tk.domain.organization

import com.virjar.tk.domain.transaction.PgUnitOfWork
import com.virjar.tk.domain.user.UserStore
import com.virjar.tk.model.OrganizationMember
import com.virjar.tk.model.OrganizationUnit
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Single-organization command service.
 *
 * Every fact mutation, global revision advance and desired managed-chat state is committed in one
 * PostgreSQL unit of work. Projection is intentionally a second, revision-fenced transaction: a
 * crash leaves a durable pending row, and access fails closed until startup/runtime drain applies it.
 */
class OrganizationService(
    private val repository: OrganizationRepository,
    private val users: UserStore,
    private val unitOfWork: PgUnitOfWork,
    private val projector: OrganizationManagedChatProjector,
) {
    fun listUnits(): List<OrganizationUnit> {
        val units = repository.listUnits()
        val counts = repository.countDirectMembers(units.mapTo(linkedSetOf()) { it.unitId })
        return units.map { unit -> unit.copy(directMemberCount = counts[unit.unitId] ?: 0) }
    }

    fun listMembers(unitId: String, recursive: Boolean): List<OrganizationMember> {
        requireUnit(unitId)
        val unitIds = if (recursive) descendantIds(unitId) else setOf(unitId)
        return repository.listMembers(unitIds).map { member ->
            member.copy(user = users.findByUid(member.uid))
        }
    }

    suspend fun createUnit(
        parentId: String?,
        name: String,
        leaderUid: String?,
        sortOrder: Int = 0,
        enableGroup: Boolean = false,
    ): OrganizationUnit {
        validateName(name)
        validateSortOrder(sortOrder)
        val result = executeCommand {
            repository.createUnit(
                transaction,
                OrganizationUnit(
                    unitId = UUID.randomUUID().toString(),
                    parentId = parentId,
                    name = name.trim(),
                    leaderUid = leaderUid,
                    sortOrder = sortOrder,
                ),
                enableGroup,
            )
        }
        return result.value
    }

    suspend fun updateUnit(
        unitId: String,
        parentId: String?,
        name: String,
        leaderUid: String?,
        sortOrder: Int,
    ): OrganizationUnit {
        validateName(name)
        validateSortOrder(sortOrder)
        val result = executeCommand {
            repository.updateUnit(transaction, unitId, parentId, name.trim(), leaderUid, sortOrder)
        }
        return result.value
    }

    suspend fun archiveUnit(unitId: String) {
        executeCommand { repository.archiveUnit(transaction, unitId) }
    }

    suspend fun assignMember(
        unitId: String,
        uid: String,
        title: String?,
        primary: Boolean,
    ): OrganizationMember {
        val displayUser = users.findByUid(uid) ?: throw IllegalArgumentException("用户不存在: $uid")
        val member = OrganizationMember(
            unitId = unitId,
            uid = uid,
            title = title?.trim()?.takeIf(String::isNotEmpty),
            primary = primary,
            joinedAt = System.currentTimeMillis(),
        )
        val result = executeCommand { repository.assignMember(transaction, member) }
        return result.value.copy(user = displayUser)
    }

    suspend fun removeMember(unitId: String, uid: String) {
        executeCommand { repository.removeMember(transaction, unitId, uid) }
    }

    suspend fun enableDepartmentGroup(unitId: String): OrganizationUnit {
        return executeCommand { repository.enableGroup(transaction, unitId) }.value
    }

    suspend fun disableDepartmentGroup(unitId: String): OrganizationUnit {
        return executeCommand { repository.disableGroup(transaction, unitId) }.value
    }

    /** Startup/admin drain. Deferred poison rows are included so startup can never report ready. */
    suspend fun reconcileAllManagedGroups(): List<String> =
        projector.drainPending(includeDeferred = true).failures.sorted()

    /** Runtime drain. Failed rows keep their persisted backoff instead of being retried every tick. */
    suspend fun reconcileDueManagedGroups(): List<String> =
        projector.drainPending(includeDeferred = false).failures.sorted()

    private suspend fun project(tasks: List<ManagedChatProjectionTask>) {
        tasks.forEach { projector.project(it) }
    }

    /** Once admitted, fact commit and best-effort projection scheduling are one terminal stage. */
    private suspend fun <T> executeCommand(
        command: suspend com.virjar.tk.domain.transaction.PgWriteScope.() -> OrganizationCommandResult<T>,
    ): OrganizationCommandResult<T> {
        coroutineContext.ensureActive()
        return withContext(NonCancellable) {
            val result = unitOfWork.write(command)
            project(result.projections)
            result
        }
    }

    private fun descendantIds(unitId: String): Set<String> {
        val units = repository.listUnits()
        val children = units.groupBy { it.parentId }
        val result = linkedSetOf<String>()
        fun visit(id: String) {
            check(result.add(id)) { "组织架构存在循环: $id" }
            children[id].orEmpty().forEach { visit(it.unitId) }
        }
        visit(unitId)
        return result
    }

    private fun requireUnit(unitId: String): OrganizationUnit =
        repository.findUnit(unitId) ?: throw IllegalArgumentException("组织节点不存在: $unitId")

    private fun validateName(name: String) {
        require(name.isNotBlank()) { "组织节点名称不能为空" }
        require(name.trim().length <= 120) { "组织节点名称不能超过 120 个字符" }
    }

    private fun validateSortOrder(sortOrder: Int) {
        require(sortOrder >= 0) { "sortOrder 不能为负数" }
    }
}
