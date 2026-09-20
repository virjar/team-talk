package com.virjar.tk.server.api

import com.virjar.tk.server.domain.command.ReliableCommandConflictException
import com.virjar.tk.server.domain.document.DocumentCustodyAdministrationService
import com.virjar.tk.server.domain.document.DocumentCustodyPlanConflictException
import com.virjar.tk.server.domain.groupfile.GroupFileService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * 用户资产域管理端点（/api/admin 鉴权域内，CONTENT-07）：Document 盘点/批量交接，
 * 以及离职盘点的群文件只读侧。群文件属于群资产，不随文档交接转移。
 */
internal fun Route.adminUserAssetRoutes(
    documentCustody: DocumentCustodyAdministrationService,
    groupFiles: GroupFileService,
) {
    get("/users/{uid}/group-file-inventory") {
        val uid = call.parameters["uid"] ?: return@get call.respond(
            HttpStatusCode.BadRequest,
            mapOf("error" to "invalid uid"),
        )
        try {
            val usage = groupFiles.userOffboardingInventory(uid)
            call.respond(
                mapOf(
                    "chats" to usage,
                    "totalEntries" to usage.sumOf { it.activeEntries },
                    "totalBytes" to usage.sumOf { it.activeVersionBytes },
                ),
            )
        } catch (_: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid uid"))
        }
    }
    get("/users/{uid}/document-custody-plan") {
        call.respondDocumentCustody {
            val query = call.request.queryParameters
            documentCustody.plan(
                sourceUid = call.parameters["uid"] ?: throw IllegalArgumentException("uid required"),
                targetOwnerPrincipalType = query["targetOwnerPrincipalType"]?.toIntOrNull()
                    ?: throw IllegalArgumentException("targetOwnerPrincipalType required"),
                targetOwnerPrincipalId = query["targetOwnerPrincipalId"]
                    ?: throw IllegalArgumentException("targetOwnerPrincipalId required"),
                targetStewardUid = query["targetStewardUid"]
                    ?: throw IllegalArgumentException("targetStewardUid required"),
            )
        }
    }
    post("/users/{uid}/document-custody-transfer") {
        val request = call.receiveBoundedJsonOrRespond<DocumentCustodyTransferRequest>() ?: return@post
        call.respondDocumentCustody {
            documentCustody.transfer(
                adminPrincipal = call.requireAdminPrincipal(),
                sourceUid = call.parameters["uid"] ?: throw IllegalArgumentException("uid required"),
                operationId = request.operationId,
                expectedPlanFingerprint = request.expectedPlanFingerprint,
                targetOwnerPrincipalType = request.targetOwnerPrincipalType
                    ?: throw IllegalArgumentException("targetOwnerPrincipalType required"),
                targetOwnerPrincipalId = request.targetOwnerPrincipalId
                    ?: throw IllegalArgumentException("targetOwnerPrincipalId required"),
                targetStewardUid = request.targetStewardUid
                    ?: throw IllegalArgumentException("targetStewardUid required"),
            )
        }
    }
}

@Serializable
data class DocumentCustodyTransferRequest(
    val operationId: String,
    val expectedPlanFingerprint: String,
    val targetOwnerPrincipalType: Int? = null,
    val targetOwnerPrincipalId: String? = null,
    val targetStewardUid: String? = null,
)

private suspend inline fun <reified T : Any> ApplicationCall.respondDocumentCustody(
    block: suspend () -> T,
) {
    val result = try {
        block()
    } catch (_: DocumentCustodyPlanConflictException) {
        respond(HttpStatusCode.Conflict, mapOf("error" to "document custody plan changed"))
        return
    } catch (_: ReliableCommandConflictException) {
        respond(HttpStatusCode.Conflict, mapOf("error" to "document custody operation conflict"))
        return
    } catch (_: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid document custody request"))
        return
    }
    respond(result)
}
