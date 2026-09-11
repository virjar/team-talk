package com.virjar.tk.server.api

import com.virjar.tk.server.application.admin.ClientTelemetryAdminService
import com.virjar.tk.server.domain.telemetry.TelemetryNumericRange
import com.virjar.tk.server.domain.telemetry.TelemetryOutgoingQueueQuery
import com.virjar.tk.server.domain.telemetry.TelemetrySearchUnavailableException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*

/** 管理台的客户端遥测查询/策略端点（PostgreSQL 控制面 + Lucene 七天可丢事实存储）。 */
internal fun Route.adminTelemetryRoutes(telemetry: ClientTelemetryAdminService) {
    get("/telemetry/events") {
        val q = call.request.queryParameters
        val pagination = call.adminPageRequestOrRespond(requireSearchOffset = true) ?: return@get
        call.respondTelemetry {
            telemetry.searchEvents(
                actor = call.requireAdminPrincipal(),
                keyword = q["keyword"],
                uid = q["uid"],
                deviceId = q["deviceId"],
                phone = q["phone"],
                platform = q["platform"],
                osName = q["osName"],
                osVersion = q["osVersion"],
                appVersion = q["appVersion"],
                gitCommit = q["gitCommit"],
                category = q["category"],
                eventName = q["eventName"],
                start = q.optionalLong("start"),
                end = q.optionalLong("end"),
                pagination = pagination,
                outgoingQueue = q.outgoingQueueQueryOrNull(),
            )
        }
    }
    get("/telemetry/events/{eventRecordId}/connection-traces") {
        val actor = call.requireAdminPrincipal()
        val eventRecordId = call.parameters["eventRecordId"]?.toLongOrNull()
            ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "invalid telemetry request"),
            )
        val response = try {
            telemetry.connectionTraces(eventRecordId, actor)
        } catch (_: TelemetrySearchUnavailableException) {
            return@get call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("error" to "telemetry search unavailable"),
            )
        } catch (error: IllegalArgumentException) {
            return@get call.respond(HttpStatusCode.BadRequest, publicTelemetryAdminBadRequest(error))
        }
        if (response == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "telemetry event not found"))
        } else {
            call.respond(response)
        }
    }
    get("/telemetry/devices") {
        call.requireAdminPrincipal()
        val pagination = call.adminPageRequestOrRespond() ?: return@get
        val q = call.request.queryParameters
        call.respondTelemetry {
            telemetry.pageDevices(q["query"], q["phone"], pagination)
        }
    }
    get("/telemetry/policies") {
        call.requireAdminPrincipal()
        val pagination = call.adminPageRequestOrRespond() ?: return@get
        call.respondTelemetry { telemetry.pagePolicies(pagination) }
    }
    post("/telemetry/policies") {
        val request = call.receiveBoundedJsonOrRespond<ClientTelemetryAdminService.EnablePolicyRequest>()
            ?: return@post
        call.respondTelemetry { telemetry.enablePolicy(request, call.requireAdminPrincipal()) }
    }
    delete("/telemetry/policies/{policyId}") {
        try {
            val policy = telemetry.disablePolicy(
                call.parameters["policyId"] ?: throw IllegalArgumentException("policyId required"),
                call.requireAdminPrincipal(),
            ) ?: return@delete call.respond(HttpStatusCode.NotFound, mapOf("error" to "policy not found"))
            call.respond(policy)
        } catch (error: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, publicTelemetryAdminBadRequest(error))
        }
    }
}

private fun Parameters.optionalLong(name: String): Long? {
    val raw = this[name] ?: return null
    return raw.toLongOrNull() ?: throw IllegalArgumentException("$name must be an integer timestamp")
}

internal fun Parameters.outgoingQueueQueryOrNull(): TelemetryOutgoingQueueQuery? {
    fun range(name: String): TelemetryNumericRange? {
        val minimum = optionalLong("${name}Min")
        val maximum = optionalLong("${name}Max")
        return if (minimum == null && maximum == null) null else TelemetryNumericRange(minimum, maximum)
    }
    val query = TelemetryOutgoingQueueQuery(
        pendingCount = range("pendingCount"),
        retryWaitCount = range("retryWaitCount"),
        terminalFailedCount = range("terminalFailedCount"),
        oldestActiveAgeMillis = range("oldestActiveAgeMillis"),
        maxAttemptCount = range("maxAttemptCount"),
    )
    return query.takeIf {
        it.pendingCount != null || it.retryWaitCount != null || it.terminalFailedCount != null ||
            it.oldestActiveAgeMillis != null || it.maxAttemptCount != null
    }
}

private suspend inline fun <reified T : Any> ApplicationCall.respondTelemetry(block: suspend () -> T) {
    val response = try {
        block()
    } catch (_: TelemetrySearchUnavailableException) {
        respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "telemetry search unavailable"))
        return
    } catch (error: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, publicTelemetryAdminBadRequest(error))
        return
    }
    respond(response)
}

/** 遥测适配器可能把请求或存储细节附加到校验失败信息上；绝不能把这些细节回显给客户端。 */
internal fun publicTelemetryAdminBadRequest(
    @Suppress("UNUSED_PARAMETER") error: IllegalArgumentException,
): Map<String, String> = mapOf("error" to "invalid telemetry request")
