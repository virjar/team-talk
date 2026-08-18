package com.virjar.tk.navigation.feature

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.virjar.tk.client.ClientSession
import com.virjar.tk.model.OrganizationMember
import com.virjar.tk.model.OrganizationUnit

/** 终端用户只读的组织目录状态；组织结构写入统一由管理端治理。 */
class OrganizationFeature internal constructor(
    private val session: ClientSession,
    private val reportError: (Throwable, String) -> Unit,
) {
    var units by mutableStateOf(emptyList<OrganizationUnit>())
        private set
    var selectedUnitId by mutableStateOf<String?>(null)
        private set
    var members by mutableStateOf(emptyList<OrganizationMember>())
        private set
    var loading by mutableStateOf(false)
        private set

    suspend fun refresh() {
        loading = true
        try {
            units = session.organizationRepo.listUnits().getOrThrow()
            val target = selectedUnitId?.takeIf { selected -> units.any { it.unitId == selected } }
                ?: units.firstOrNull { it.parentId == null }?.unitId
                ?: units.firstOrNull()?.unitId
            selectUnit(target)
        } catch (e: Exception) {
            reportError(e, "加载组织架构失败")
        } finally {
            loading = false
        }
    }

    suspend fun selectUnit(unitId: String?) {
        selectedUnitId = unitId
        members = if (unitId == null) {
            emptyList()
        } else {
            try {
                session.organizationRepo.listMembers(unitId).getOrThrow()
            } catch (e: Exception) {
                reportError(e, "加载部门成员失败")
                emptyList()
            }
        }
    }
}
