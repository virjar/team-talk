package com.virjar.tk.domain.organization

import com.virjar.tk.domain.chat.ManagedChatPolicy
import com.virjar.tk.model.OrganizationMember
import com.virjar.tk.model.OrganizationUnit

/** PostgreSQL-backed port for the single-organization directory. */
interface OrganizationRepository : ManagedChatPolicy {
    fun listUnits(): List<OrganizationUnit>
    fun findUnit(unitId: String): OrganizationUnit?
    fun createUnit(unit: OrganizationUnit): OrganizationUnit
    fun updateUnit(unit: OrganizationUnit): OrganizationUnit
    fun archiveUnit(unitId: String)
    fun setGroupChat(unitId: String, chatId: String?)

    fun listMembers(unitIds: Set<String>): List<OrganizationMember>
    fun listMemberships(uid: String): List<OrganizationMember>
    fun upsertMember(member: OrganizationMember)
    fun removeMember(unitId: String, uid: String)
}
