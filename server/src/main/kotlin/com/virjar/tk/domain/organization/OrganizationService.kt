package com.virjar.tk.domain.organization

import com.virjar.tk.domain.chat.ChatService
import com.virjar.tk.domain.chat.RequiredChatParticipants
import com.virjar.tk.domain.user.UserStore
import com.virjar.tk.model.OrganizationMember
import com.virjar.tk.model.OrganizationUnit
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * 单组织目录的领域服务。
 *
 * 组织关系是部门群成员的唯一事实源。跨表操作采用“先提交目录事实、后幂等 reconciliation”，
 * 并在服务启动时重放全部受管群，因此中途崩溃不会形成永久分叉。
 */
class OrganizationService(
    private val repository: OrganizationRepository,
    private val users: UserStore,
    private val chats: ChatService,
    private val requiredParticipants: RequiredChatParticipants,
) {
    private val logger = LoggerFactory.getLogger(OrganizationService::class.java)

    fun listUnits(): List<OrganizationUnit> = repository.listUnits()

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
        if (parentId == null) {
            require(repository.listUnits().none { it.parentId == null }) { "单组织只能有一个根节点" }
        } else {
            requireUnit(parentId)
        }
        leaderUid?.let(::requireUser)

        val unit = repository.createUnit(
            OrganizationUnit(
                unitId = UUID.randomUUID().toString(),
                parentId = parentId,
                name = name.trim(),
                leaderUid = leaderUid,
                sortOrder = sortOrder,
            ),
        )
        if (leaderUid != null) {
            repository.upsertMember(OrganizationMember(unit.unitId, leaderUid, title = "负责人"))
        }
        if (enableGroup) enableDepartmentGroup(unit.unitId)
        return requireUnit(unit.unitId)
    }

    suspend fun updateUnit(
        unitId: String,
        parentId: String?,
        name: String,
        leaderUid: String?,
        sortOrder: Int,
    ): OrganizationUnit {
        val old = requireUnit(unitId)
        validateName(name)
        if (parentId != null) {
            requireUnit(parentId)
            require(parentId != unitId && parentId !in descendantIds(unitId)) { "组织节点不能移动到自己或后代节点下" }
        } else {
            require(repository.listUnits().none { it.parentId == null && it.unitId != unitId }) { "单组织只能有一个根节点" }
        }
        leaderUid?.let(::requireUser)

        val oldAncestors = ancestorIds(unitId)
        repository.updateUnit(old.copy(parentId = parentId, name = name.trim(), leaderUid = leaderUid, sortOrder = sortOrder))
        if (leaderUid != null && repository.listMembers(setOf(unitId)).none { it.uid == leaderUid }) {
            repository.upsertMember(OrganizationMember(unitId, leaderUid, title = "负责人"))
        }

        val updated = requireUnit(unitId)
        val affected = oldAncestors + ancestorIds(unitId) + descendantIds(unitId)
        reconcileManagedGroups(affected)
        return updated
    }

    suspend fun archiveUnit(unitId: String) {
        val unit = requireUnit(unitId)
        require(repository.listUnits().none { it.parentId == unitId }) { "请先移动或删除下级部门" }
        require(repository.listMembers(setOf(unitId)).isEmpty()) { "请先移出部门成员" }
        val affectedAncestors = ancestorIds(unitId) - unitId
        unit.groupChatId?.let { chats.adminDisableManagedGroup(it) }
        repository.archiveUnit(unitId)
        reconcileManagedGroups(affectedAncestors)
    }

    suspend fun assignMember(unitId: String, uid: String, title: String?, primary: Boolean): OrganizationMember {
        requireUnit(unitId)
        requireUser(uid)
        val member = OrganizationMember(
            unitId = unitId,
            uid = uid,
            title = title?.trim()?.takeIf(String::isNotEmpty),
            primary = primary,
            joinedAt = System.currentTimeMillis(),
        )
        repository.upsertMember(member)
        reconcileManagedGroups(ancestorIds(unitId))
        return member.copy(user = users.findByUid(uid))
    }

    suspend fun removeMember(unitId: String, uid: String) {
        val unit = requireUnit(unitId)
        require(unit.leaderUid != uid) { "请先变更部门负责人" }
        repository.removeMember(unitId, uid)
        reconcileManagedGroups(ancestorIds(unitId))
    }

    suspend fun enableDepartmentGroup(unitId: String): OrganizationUnit {
        val unit = requireUnit(unitId)
        val leaderUid = unit.leaderUid ?: throw IllegalArgumentException("启用部门群前必须设置负责人")
        requireUser(leaderUid)
        if (repository.listMembers(setOf(unitId)).none { it.uid == leaderUid }) {
            repository.upsertMember(OrganizationMember(unitId, leaderUid, title = "负责人"))
        }

        // unitId 本身是稳定 UUID，同时作为部门群 chatId；重复启用与崩溃恢复不会创建孤儿群。
        repository.setGroupChat(unitId, unit.unitId)
        reconcileUnitGroup(requireUnit(unitId))
        return requireUnit(unitId)
    }

    suspend fun disableDepartmentGroup(unitId: String): OrganizationUnit {
        val unit = requireUnit(unitId)
        unit.groupChatId?.let { chats.adminDisableManagedGroup(it) }
        repository.setGroupChat(unitId, null)
        return requireUnit(unitId)
    }

    /** 启动恢复入口；单个错误不阻断其他部门群，返回失败节点供健康日志观察。 */
    suspend fun reconcileAllManagedGroups(): List<String> {
        val failures = mutableListOf<String>()
        for (unit in repository.listUnits().filter { it.groupChatId != null }) {
            runCatching { reconcileUnitGroup(unit) }
                .onFailure {
                    failures += unit.unitId
                    logger.error("Failed to reconcile managed department group unitId={}", unit.unitId, it)
                }
        }
        return failures
    }

    private suspend fun reconcileManagedGroups(affectedUnitIds: Set<String>) {
        repository.listUnits()
            .filter { it.unitId in affectedUnitIds && it.groupChatId != null }
            .forEach { reconcileUnitGroup(it) }
    }

    private suspend fun reconcileUnitGroup(unit: OrganizationUnit) {
        val chatId = unit.groupChatId ?: return
        val ownerUid = unit.leaderUid ?: throw IllegalStateException("受管部门群缺少负责人: ${unit.unitId}")
        val desired = repository.listMembers(descendantIds(unit.unitId)).mapTo(linkedSetOf()) { it.uid }
        desired += requiredParticipants.forChat(chatId)
        chats.adminReconcileManagedGroup(chatId, "${unit.name}部门群", ownerUid, desired)
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

    private fun ancestorIds(unitId: String): Set<String> {
        val byId = repository.listUnits().associateBy { it.unitId }
        val result = linkedSetOf<String>()
        var cursor: String? = unitId
        while (cursor != null) {
            check(result.add(cursor)) { "组织架构存在循环: $cursor" }
            cursor = byId[cursor]?.parentId
        }
        return result
    }

    private fun requireUnit(unitId: String): OrganizationUnit =
        repository.findUnit(unitId) ?: throw IllegalArgumentException("组织节点不存在: $unitId")

    private fun requireUser(uid: String) {
        require(users.findByUid(uid) != null) { "用户不存在: $uid" }
    }

    private fun validateName(name: String) {
        require(name.isNotBlank()) { "组织节点名称不能为空" }
        require(name.trim().length <= 120) { "组织节点名称不能超过 120 个字符" }
    }
}
