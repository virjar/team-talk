package com.virjar.tk.api

import com.virjar.tk.infra.storage.FileStore
import com.virjar.tk.domain.attachment.AttachmentAccess
import com.virjar.tk.domain.auth.AccessTokenValidator
import com.virjar.tk.http.UploadResult
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.Json
import java.io.File

private val responseJson = Json { encodeDefaults = true }

fun Route.fileRoutes(
    fileStore: FileStore,
    accessTokens: AccessTokenValidator,
    attachmentAccess: AttachmentAccess,
    thumbnailService: com.virjar.tk.infra.media.ThumbnailService = com.virjar.tk.infra.media.ThumbnailService(),
) {
    route("/api/v1/files") {
        get("/{path...}") {
            val path = call.parameters.getAll("path")?.joinToString("/") ?: return@get call.respond(HttpStatusCode.NotFound)
            val token = call.bearerToken()
            val info = token?.let { accessTokens.validateAccessToken(it) }
                ?: return@get call.respond(HttpStatusCode.Unauthorized, "invalid or missing token")
            if (!attachmentAccess.canRead(info.uid, path)) {
                return@get call.respond(HttpStatusCode.Forbidden, "attachment access denied")
            }
            val meta = fileStore.getMeta(path) ?: return@get call.respond(HttpStatusCode.NotFound)

            // 尝试从文件系统层获取（大文件）
            val file = fileStore.getFile(meta)
            if (file != null) {
                call.respondFile(file)
            } else {
                // 小文件从 RocksDB 读取，需要流式写入
                call.respond(object : OutgoingContent.WriteChannelContent() {
                    override val contentType = ContentType.parse(meta.contentType)
                    override val contentLength = meta.size
                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        fileStore.streamTo(meta, channel)
                    }
                })
            }
        }

        post("/upload") {
            // 鉴权：Bearer accessToken（TCP 认证时下发，PG epoch 校验）。上传必须已认证。
            val token = call.bearerToken()
            val info = token?.let { accessTokens.validateAccessToken(it) }
            if (info == null) return@post call.respond(HttpStatusCode.Unauthorized, "invalid or missing token")
            val uid = info.uid

            val multipart = call.receiveMultipart()
            var filePath: String? = null
            var mediaInfo: com.virjar.tk.infra.media.ThumbnailService.MediaInfo? = null
            var thumbPath: String? = null

            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        val originalName = part.originalFileName ?: "unknown"
                        val contentType = part.contentType?.toString() ?: "application/octet-stream"
                        val tempFile = File.createTempFile("upload", ".tmp")
                        tempFile.deleteOnExit()
                        val channel = part.provider()
                        tempFile.outputStream().use { out ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val read = channel.readAvailable(buffer)
                                if (read == -1) break
                                out.write(buffer, 0, read)
                            }
                        }
                        // 缩略图/元数据必须在 store 之前生成：FileStore.store 会消费（move）临时文件，
                        // 之后再访问 tempFile 会 FileNotFoundException（曾现 bug，F24）
                        val isImage = contentType.startsWith("image/")
                        val isVideo = contentType.startsWith("video/")
                        if (isImage || isVideo) {
                            mediaInfo = if (isImage) thumbnailService.processImage(tempFile)
                            else thumbnailService.processVideo(tempFile)
                            mediaInfo?.thumbFile?.let { tf ->
                                thumbPath = fileStore.store(uid, "thumb_${originalName}.jpg", "image/jpeg", tf)
                                tf.delete()
                            }
                        }

                        filePath = fileStore.store(uid, originalName, contentType, tempFile)
                        tempFile.delete()
                    }
                    else -> {}
                }
                part.dispose()
            }

            val storedPath = filePath
            if (storedPath != null) {
                val mi = mediaInfo
                call.respondText(
                    responseJson.encodeToString(
                        UploadResult(
                            file = fileStore.getAttachment(storedPath)
                                ?: error("Stored attachment metadata missing: $storedPath"),
                            thumbnail = thumbPath?.let { path ->
                                fileStore.getAttachment(path)
                                    ?: error("Stored thumbnail metadata missing: $path")
                            },
                            width = mi?.width ?: 0,
                            height = mi?.height ?: 0,
                            durationSec = mi?.durationSec,
                        ),
                    ),
                    ContentType.Application.Json,
                )
            } else {
                call.respond(HttpStatusCode.BadRequest, "No file uploaded")
            }
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.bearerToken(): String? =
    request.header(HttpHeaders.Authorization)
        ?.removePrefix("Bearer ")
        ?.takeIf { it.isNotBlank() }
