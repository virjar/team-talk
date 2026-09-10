package com.virjar.tk.server.api

import com.virjar.tk.server.domain.auth.AccessTokenValidator
import com.virjar.tk.server.domain.document.DocumentAccessDeniedException
import com.virjar.tk.server.domain.document.DocumentNotFoundException
import com.virjar.tk.server.domain.document.DocumentSpaceExportPlan
import com.virjar.tk.server.domain.document.DocumentSpaceExportService
import com.virjar.tk.server.infra.db.AdminFeatureSettingsStore
import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.jvm.javaio.toOutputStream

/** zip 以附件形式下发；打包期间边读边写，不把整个空间缓冲进内存。 */
internal suspend fun ApplicationCall.respondSpaceExportZip(
    plan: DocumentSpaceExportPlan,
    export: suspend (DocumentSpaceExportPlan, java.io.OutputStream) -> Unit,
) {
    response.header(
        HttpHeaders.ContentDisposition,
        ContentDisposition.Attachment.withParameter(
            ContentDisposition.Parameters.FileName, "${plan.spaceName}-export.zip",
        ).toString(),
    )
    response.header(HttpHeaders.CacheControl, "private, no-store")
    respond(object : OutgoingContent.WriteChannelContent() {
        override val contentType = io.ktor.http.ContentType.parse("application/zip")
        override suspend fun writeTo(channel: ByteWriteChannel) {
            export(plan, channel.toOutputStream())
        }
    })
}

/**
 * 空间责任人（唯一管理员）的文档空间导出入口。
 *
 * 授权经 EXPORT_SPACE 能力（只有 OWNER 通过）；后台开关关闭时整体停用，
 * 超级管理员走 /api/admin 的独立入口不受此开关约束。
 */
internal fun Route.documentExportRoutes(
    exportService: DocumentSpaceExportService,
    exportPolicy: AdminFeatureSettingsStore,
    accessTokens: AccessTokenValidator,
) {
    get("/api/v1/documents/spaces/{spaceId}/export") {
        val token = call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")
        val info = token?.let { accessTokens.validateAccessToken(it) }
            ?: return@get call.respond(HttpStatusCode.Unauthorized, "invalid or missing token")
        val spaceId = call.parameters["spaceId"] ?: return@get call.respond(HttpStatusCode.NotFound)
        if (!exportPolicy.isEnabled()) {
            return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "document export disabled"))
        }
        val plan = try {
            exportService.buildStewardPlan(info.uid, spaceId)
        } catch (_: DocumentAccessDeniedException) {
            return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "document space access denied"))
        } catch (_: DocumentNotFoundException) {
            return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "document space not found"))
        } ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "document space not found"))
        call.respondSpaceExportZip(plan) { p, out -> exportService.writeZip(p, out) }
    }
}
