package com.virjar.tk.infra.db.repository

import com.virjar.tk.domain.organization.OrganizationRepository
import com.virjar.tk.infra.db.OrganizationMemberships
import com.virjar.tk.infra.db.OrganizationUnits
import com.virjar.tk.model.OrganizationMember
import com.virjar.tk.model.OrganizationUnit
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

class ExposedOrganizationRepository : OrganizationRepository {
    override fun listUnits(): List<OrganizationUnit> = transaction {
        OrganizationUnits.selectAll()
            .where { OrganizationUnits.status eq OrganizationUnit.STATUS_ACTIVE }
            .orderBy(OrganizationUnits.sortOrder to SortOrder.ASC, OrganizationUnits.name to SortOrder.ASC)
            .map(ResultRow::toOrganizationUnit)
    }

    override fun findUnit(unitId: String): OrganizationUnit? = transaction {
        OrganizationUnits.selectAll()
            .where {
                (OrganizationUnits.unitId eq unitId) and
                    (OrganizationUnits.status eq OrganizationUnit.STATUS_ACTIVE)
            }
            .singleOrNull()
            ?.toOrganizationUnit()
    }

    override fun createUnit(unit: OrganizationUnit): OrganizationUnit = transaction {
        val now = System.currentTimeMillis()
        OrganizationUnits.insert {
            it[unitId] = unit.unitId
            it[parentId] = unit.parentId
            it[name] = unit.name
            it[leaderUid] = unit.leaderUid
            it[sortOrder] = unit.sortOrder
            it[groupChatId] = unit.groupChatId
            it[status] = OrganizationUnit.STATUS_ACTIVE
            it[createdAt] = now
            it[updatedAt] = now
        }
        unit.copy(status = OrganizationUnit.STATUS_ACTIVE)
    }

    override fun updateUnit(unit: OrganizationUnit): OrganizationUnit = transaction {
        val updated = OrganizationUnits.update({
            (OrganizationUnits.unitId eq unit.unitId) and
                (OrganizationUnits.status eq OrganizationUnit.STATUS_ACTIVE)
        }) {
            it[parentId] = unit.parentId
            it[name] = unit.name
            it[leaderUid] = unit.leaderUid
            it[sortOrder] = unit.sortOrder
            it[updatedAt] = System.currentTimeMillis()
        }
        require(updated == 1) { "组织节点不存在: ${unit.unitId}" }
        unit
    }

    override fun archiveUnit(unitId: String) {
        transaction {
            OrganizationUnits.update({ OrganizationUnits.unitId eq unitId }) {
                it[status] = OrganizationUnit.STATUS_ARCHIVED
                it[groupChatId] = null
                it[updatedAt] = System.currentTimeMillis()
            }
        }
    }

    override fun setGroupChat(unitId: String, chatId: String?) {
        transaction {
            OrganizationUnits.update({ OrganizationUnits.unitId eq unitId }) {
                it[groupChatId] = chatId
                it[updatedAt] = System.currentTimeMillis()
            }
        }
    }

    override fun listMembers(unitIds: Set<String>): List<OrganizationMember> {
        if (unitIds.isEmpty()) return emptyList()
        return transaction {
            OrganizationMemberships.selectAll()
                .where { OrganizationMemberships.unitId inList unitIds }
                .orderBy(OrganizationMemberships.joinedAt to SortOrder.ASC)
                .map(ResultRow::toOrganizationMember)
        }
    }

    override fun countDirectMembers(unitIds: Set<String>): Map<String, Int> {
        if (unitIds.isEmpty()) return emptyMap()
        return transaction {
            val memberCount = OrganizationMemberships.id.count()
            OrganizationMemberships
                .select(OrganizationMemberships.unitId, memberCount)
                .where { OrganizationMemberships.unitId inList unitIds }
                .groupBy(OrganizationMemberships.unitId)
                .associate { row ->
                    row[OrganizationMemberships.unitId] to row[memberCount].toInt()
                }
        }
    }

    override fun listMemberships(uid: String): List<OrganizationMember> = transaction {
        OrganizationMemberships.selectAll()
            .where { OrganizationMemberships.uid eq uid }
            .map(ResultRow::toOrganizationMember)
    }

    override fun upsertMember(member: OrganizationMember) {
        transaction {
            if (member.primary) {
                OrganizationMemberships.update({ OrganizationMemberships.uid eq member.uid }) {
                    it[primary] = false
                    it[updatedAt] = System.currentTimeMillis()
                }
            }
            val existing = OrganizationMemberships.selectAll().where {
                (OrganizationMemberships.unitId eq member.unitId) and
                    (OrganizationMemberships.uid eq member.uid)
            }.singleOrNull()
            val now = System.currentTimeMillis()
            if (existing == null) {
                OrganizationMemberships.insert {
                    it[unitId] = member.unitId
                    it[uid] = member.uid
                    it[title] = member.title
                    it[primary] = member.primary
                    it[joinedAt] = if (member.joinedAt > 0) member.joinedAt else now
                    it[updatedAt] = now
                }
            } else {
                OrganizationMemberships.update({
                    (OrganizationMemberships.unitId eq member.unitId) and
                        (OrganizationMemberships.uid eq member.uid)
                }) {
                    it[title] = member.title
                    it[primary] = member.primary
                    it[updatedAt] = now
                }
            }
        }
    }

    override fun removeMember(unitId: String, uid: String) {
        transaction {
            OrganizationMemberships.deleteWhere {
                (OrganizationMemberships.unitId eq unitId) and
                    (OrganizationMemberships.uid eq uid)
            }
        }
    }

    override fun managedBy(chatId: String): String? = transaction {
        OrganizationUnits.selectAll()
            .where {
                (OrganizationUnits.groupChatId eq chatId) and
                    (OrganizationUnits.status eq OrganizationUnit.STATUS_ACTIVE)
            }
            .singleOrNull()
            ?.get(OrganizationUnits.name)
            ?.let { "$it 部门" }
    }
}

private fun ResultRow.toOrganizationUnit() = OrganizationUnit(
    unitId = this[OrganizationUnits.unitId],
    parentId = this[OrganizationUnits.parentId],
    name = this[OrganizationUnits.name],
    leaderUid = this[OrganizationUnits.leaderUid],
    sortOrder = this[OrganizationUnits.sortOrder],
    groupChatId = this[OrganizationUnits.groupChatId],
    status = this[OrganizationUnits.status],
)

private fun ResultRow.toOrganizationMember() = OrganizationMember(
    unitId = this[OrganizationMemberships.unitId],
    uid = this[OrganizationMemberships.uid],
    title = this[OrganizationMemberships.title],
    primary = this[OrganizationMemberships.primary],
    joinedAt = this[OrganizationMemberships.joinedAt],
)
