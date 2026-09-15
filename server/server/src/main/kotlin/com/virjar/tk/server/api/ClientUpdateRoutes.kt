package com.virjar.tk.server.api

import com.virjar.tk.protocol.http.AndroidReleaseManifest
import com.virjar.tk.protocol.http.ClientReleaseManifest
import com.virjar.tk.protocol.http.ClientUpdateContracts
import com.virjar.tk.server.application.admin.AdminSecurityService
import com.virjar.tk.server.infra.clientrelease.ClientReleaseConflictException
import com.virjar.tk.server.infra.clientrelease.ClientReleaseService
import com.virjar.tk.server.infra.clientrelease.ClientReleaseValidationException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 统一客户端更新体系的公开端点。
 *
 * 只读解析（check/manifest/制品下载）完全公开——与今天的静态下载目录同级信任；
 * 唯一的写入口是发布上传，凭管理会话或 CI 发布令牌（CLIENT_RELEASE_PUBLISH_TOKEN）。
 */
internal fun Route.clientUpdateRoutes(
    service: ClientReleaseService,
    adminAuth: AdminSecurityService,
    stagingDir: File,
) {
    route("/api/v1/client") {
        install(PartialContent)

        get("/updates/check") {
            val q = call.request.queryParameters
            val clientType = q["client"]?.takeIf { it.isNotBlank() }
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "client is required"))
            val platform = q["platform"]?.takeIf { it.isNotBlank() } ?: ClientUpdateContracts.PLATFORM_ANY
            val arch = q["arch"]?.takeIf { it.isNotBlank() } ?: ClientUpdateContracts.ARCH_ANY
            val channel = q["channel"]?.takeIf { it.isNotBlank() } ?: AndroidReleaseManifest.CHANNEL_STABLE
            val response = service.check(
                ClientReleaseService.CheckQuery(
                    clientType = clientType,
                    platform = platform,
                    arch = arch,
                    channel = channel,
                    version = q["version"]?.takeIf { it.isNotBlank() },
                    build = q["build"]?.toLongOrNull(),
                    shellAbi = q["shellAbi"]?.toIntOrNull(),
                    buildIdentity = q["buildIdentity"]?.takeIf { it.isNotBlank() },
                ),
            )
            call.response.headers.append("Cache-Control", "no-store")
            call.respond(response)
        }

        get("/releases/{id}/manifest.json") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid release id"))
            val manifest = service.manifest(id)
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "release not found"))
            call.response.headers.append("Cache-Control", "no-store")
            call.respondText(
                ClientUpdateContracts.json.encodeToString(ClientReleaseManifest.serializer(), manifest),
                ContentType.Application.Json,
            )
        }

        get("/releases/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid release id"))
            val info = service.releaseInfo(id)
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "release not found"))
            call.respond(info)
        }

        // 内容寻址制品：摘要即身份，可长期缓存；Range 供断点续传。
        get("/files/{sha256}") { call.respondClientReleaseArtifact(service, head = false) }
        head("/files/{sha256}") { call.respondClientReleaseArtifact(service, head = true) }

        // CI/管理台发布上传。与附件路由的严格分部解析不同，这里用 Ktor 标准
        // multipart：上游是受信的构建器或管理员，单分部 + 流式落盘 + 大小上限足够。
        post("/releases") {
            val actor = call.resolveUploadPrincipal(service, adminAuth)
                ?: return@post call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf("error" to "admin session or publish token required"),
                )
            if (!call.request.headers[HttpHeaders.ContentType].orEmpty()
                    .startsWith("multipart/form-data")
            ) {
                return@post call.respond(
                    HttpStatusCode.UnsupportedMediaType,
                    mapOf("error" to "multipart/form-data required"),
                )
            }
            stagingDir.mkdirs()
            val staging = File.createTempFile("client-release-upload-", ".zip", stagingDir)
            try {
                var sawFilePart = false
                call.receiveMultipart(formFieldLimit = MAX_RELEASE_UPLOAD_BYTES + 64 * 1024).forEachPart { part ->
                    try {
                        if (part is PartData.FileItem && !sawFilePart && part.name == "release") {
                            sawFilePart = true
                            part.streamProvider().use { input ->
                                staging.outputStream().buffered().use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    var written = 0L
                                    while (true) {
                                        val read = input.read(buffer)
                                        if (read < 0) break
                                        output.write(buffer, 0, read)
                                        written += read
                                        if (written > MAX_RELEASE_UPLOAD_BYTES) {
                                            throw ReleaseUploadTooLargeException()
                                        }
                                    }
                                }
                            }
                        }
                    } finally {
                        part.dispose()
                    }
                }
                if (!sawFilePart) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "a 'release' file part is required"),
                    )
                }
                val releaseId = service.importRelease(actor, staging)
                call.respond(mapOf("releaseId" to releaseId))
            } catch (tooLarge: ReleaseUploadTooLargeException) {
                call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "release upload exceeds 4 GiB"))
            } catch (validation: ClientReleaseValidationException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (validation.message ?: "invalid release upload")))
            } catch (conflict: ClientReleaseConflictException) {
                call.respond(HttpStatusCode.Conflict, mapOf("error" to (conflict.message ?: "release conflict")))
            } finally {
                staging.delete()
            }
        }
    }

    // 公开下载页数据源。
    get("/api/v1/public/downloads") {
        val payload = service.publicDownloads()
        call.response.headers.append("Cache-Control", "no-store")
        call.respondText(
            Json.encodeToString(ClientReleaseService.PublicDownloads.serializer(), payload),
            ContentType.Application.Json,
        )
    }
}

private class ReleaseUploadTooLargeException : RuntimeException()

private const val MAX_RELEASE_UPLOAD_BYTES = 4L * 1024 * 1024 * 1024

private suspend fun ApplicationCall.respondClientReleaseArtifact(service: ClientReleaseService, head: Boolean) {
    val sha = parameters["sha256"] ?: return respond(HttpStatusCode.BadRequest)
    val file = service.artifactFile(sha) ?: return respond(HttpStatusCode.NotFound)
    response.headers.append(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
    response.headers.append(HttpHeaders.ETag, "\"$sha\"")
    // 安装器直链按发布文件名落盘；否则浏览器把 URL 里的内容哈希当文件名保存，
    // Android 得到无 .apk 后缀的文件无法安装。payload 文件不设（更新器按哈希消费）。
    service.installerFilename(sha)?.let { filename ->
        val safe = filename.replace(Regex("[\"\\r\\n]"), "_")
        response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"$safe\"")
    }
    respond(
        if (head) file.downloadHeadResponse(ContentType.Application.OctetStream)
        else LocalFileContent(file, ContentType.Application.OctetStream),
    )
}

/** 管理会话优先；否则接受 X-Publish-Token / Bearer 形式的 CI 发布令牌。 */
private suspend fun ApplicationCall.resolveUploadPrincipal(
    service: ClientReleaseService,
    adminAuth: AdminSecurityService,
): String? {
    val publishToken = request.headers["X-Publish-Token"]
    if (publishToken != null && service.verifyPublishToken(publishToken)) return "ci-publish"
    val bearer = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
    if (!bearer.isNullOrEmpty()) {
        if (service.verifyPublishToken(bearer)) return "ci-publish"
        adminAuth.principal(bearer)?.let { return it }
    }
    return null
}
