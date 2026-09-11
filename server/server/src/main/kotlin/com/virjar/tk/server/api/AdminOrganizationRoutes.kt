package com.virjar.tk.server.api

import com.virjar.tk.server.application.admin.AdminService
import com.virjar.tk.server.domain.organization.OrganizationMemberRemovalConflictException
import com.virjar.tk.server.domain.organization.OrganizationUnitArchiveConflictException
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class OrganizationUnitRequest(
    val parentId: String? = null,
    val name: String,
    val leaderUid: String? = null,
    val sortOrder: Int = 0,
    val enableGroup: Boolean = false,
)

@Serializable
data class OrganizationMemberRequest(
    val uid: String,
    val title: String? = null,
    val primary: Boolean = false,
)

@Serializable
internal data class OrganizationReconcileResponse(
    val ok: Boolean,
    val failedUnitIds: List<String>,
)

/** 管理台的组织架构端点：部门树维护、成员分配与部门群对账。 */
internal fun Route.adminOrganizationRoutes(adminService: AdminService) {
    get("/organization/units") {
        call.respond(adminService.listOrganizationUnits())
    }
    post("/organization/units") {
        val req = call.receiveBoundedJsonOrRespond<OrganizationUnitRequest>() ?: return@post
        call.respond(adminService.createOrganizationUnit(
            req.parentId, req.name, req.leaderUid, req.sortOrder, req.enableGroup,
        ))
    }
    put("/organization/units/{unitId}") {
        val req = call.receiveBoundedJsonOrRespond<OrganizationUnitRequest>() ?: return@put
        call.respond(adminService.updateOrganizationUnit(
            call.parameters["unitId"]!!, req.parentId, req.name, req.leaderUid, req.sortOrder,
        ))
    }
    delete("/organization/units/{unitId}") {
        try {
            adminService.archiveOrganizationUnit(call.parameters["unitId"]!!)
            call.respond(mapOf("ok" to true))
        } catch (_: OrganizationUnitArchiveConflictException) {
            call.respond(
                HttpStatusCode.Conflict,
                mapOf("error" to "organization unit still owns active document spaces"),
            )
        }
    }
    get("/organization/units/{unitId}/members") {
        val recursive = call.request.queryParameters["recursive"]?.toBooleanStrictOrNull() ?: false
        call.respond(adminService.listOrganizationMembers(call.parameters["unitId"]!!, recursive))
    }
    post("/organization/units/{unitId}/members") {
        val req = call.receiveBoundedJsonOrRespond<OrganizationMemberRequest>() ?: return@post
        call.respond(adminService.assignOrganizationMember(
            call.parameters["unitId"]!!, req.uid, req.title, req.primary,
        ))
    }
    delete("/organization/units/{unitId}/members/{uid}") {
        try {
            adminService.removeOrganizationMember(call.parameters["unitId"]!!, call.parameters["uid"]!!)
            call.respond(mapOf("ok" to true))
        } catch (_: OrganizationMemberRemovalConflictException) {
            call.respond(
                HttpStatusCode.Conflict,
                mapOf("error" to "请先在编辑组织节点时变更部门负责人"),
            )
        }
    }
    post("/organization/units/{unitId}/group/enable") {
        call.respond(adminService.enableDepartmentGroup(call.parameters["unitId"]!!))
    }
    post("/organization/units/{unitId}/group/disable") {
        call.respond(adminService.disableDepartmentGroup(call.parameters["unitId"]!!))
    }
    post("/organization/reconcile") {
        val failures = adminService.reconcileDepartmentGroups()
        call.respond(OrganizationReconcileResponse(failures.isEmpty(), failures))
    }
}
