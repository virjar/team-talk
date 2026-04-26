package com.virjar.tk.api

import com.virjar.tk.dto.ApiError
import com.virjar.tk.service.FileService
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.copyTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

private val ALLOWED_CONTENT_PREFIXES = listOf(
    "image/", "video/", "audio/",
    "application/pdf", "application/zip",
    "application/x-zip-compressed",
    "text/plain", "text/csv",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument",
    "application/msword",
)

fun Routing.fileRoutes(fileService: FileService, maxFileSizeBytes: Long = 50 * 1024 * 1024) {
    route("/api/v1/files") {
        // File download: stream via server proxy, supports HTTP Range
        get("/{path...}") {
            val path = call.parameters.getAll("path")?.joinToString("/") ?: return@get call.respond(HttpStatusCode.BadRequest)
            val rangeHeader = call.request.header(HttpHeaders.Range)

            // S3 client uses Netty Promise + .get() which blocks — must run off the Ktor EventLoop
            val fileStream = withContext(Dispatchers.IO) {
                fileService.streamFileWithRange(path, rangeHeader)
            }
            if (fileStream != null) {
                // Validate Range request against actual file size
                if (rangeHeader != null && fileStream.rangeStart >= fileStream.totalSize) {
                    call.response.header(HttpHeaders.ContentRange, "bytes */${fileStream.totalSize}")
                    call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)
                    return@get
                }

                call.response.header(HttpHeaders.CacheControl, "public, max-age=86400")
                call.response.header(HttpHeaders.AcceptRanges, "bytes")

                if (fileStream.isRange) {
                    call.response.header(
                        HttpHeaders.ContentRange,
                        "bytes ${fileStream.rangeStart}-${fileStream.rangeEnd}/${fileStream.totalSize}"
                    )
                    call.response.header(HttpHeaders.ContentLength, fileStream.contentLength)
                    call.respondOutputStream(
                        status = HttpStatusCode.PartialContent,
                        contentType = ContentType.parse(fileStream.contentType),
                    ) {
                        try {
                            fileStream.inputStream.copyTo(this, 8192)
                        } catch (_: java.io.IOException) {
                            // 客户端提前断开（视频播放 seek、缓冲关闭等），属于正常行为
                        } finally {
                            fileStream.inputStream.close()
                        }
                    }
                } else {
                    call.response.header(HttpHeaders.ContentLength, fileStream.contentLength)
                    call.respondOutputStream(contentType = ContentType.parse(fileStream.contentType)) {
                        try {
                            fileStream.inputStream.copyTo(this, 8192)
                        } catch (_: java.io.IOException) {
                            // 客户端提前断开（视频播放 seek、缓冲关闭等），属于正常行为
                        } finally {
                            fileStream.inputStream.close()
                        }
                    }
                }
            } else {
                call.respond(HttpStatusCode.NotFound, ApiError(message = "file not found"))
            }
        }

        authenticate("auth-jwt") {
            post("/upload") {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                val multipart = call.receiveMultipart()
                var filePath = ""
                var fileContentType = ""
                var fileBytes: ByteArray? = null

                var part = multipart.readPart()
                while (part != null) {
                    when (part) {
                        is PartData.FileItem -> {
                            val originalFileName = part.originalFileName ?: "unknown"
                            fileContentType = part.contentType?.toString() ?: "application/octet-stream"

                            // Validate content type
                            val contentTypeAllowed = ALLOWED_CONTENT_PREFIXES.any { prefix ->
                                fileContentType.startsWith(prefix, ignoreCase = true)
                            }
                            if (!contentTypeAllowed) {
                                part.dispose()
                                call.respond(HttpStatusCode.UnsupportedMediaType, ApiError(message = "file type not allowed: $fileContentType"))
                                return@post
                            }

                            val channel = part.provider()
                            val baos = java.io.ByteArrayOutputStream()
                            val wbc = java.nio.channels.Channels.newChannel(baos)
                            channel.copyTo(wbc)
                            wbc.close()
                            val bytes = baos.toByteArray()

                            // Validate file size
                            if (bytes.size > maxFileSizeBytes) {
                                part.dispose()
                                call.respond(HttpStatusCode.PayloadTooLarge, ApiError(message = "file too large: ${bytes.size} bytes (max $maxFileSizeBytes)"))
                                return@post
                            }

                            filePath = fileService.upload(uid, originalFileName, fileContentType, bytes)
                            fileBytes = bytes
                        }
                        else -> {}
                    }
                    part.dispose()
                    part = multipart.readPart()
                }

                if (filePath.isNotEmpty()) {
                    // Generate thumbnail for image types
                    var thumbnailPath = ""
                    if (fileContentType.startsWith("image/") && fileBytes != null) {
                        thumbnailPath = fileService.generateAndUploadThumbnail(filePath, fileBytes) ?: ""
                    }
                    call.respond(
                        HttpStatusCode.Created,
                        mapOf("path" to filePath, "thumbnailPath" to thumbnailPath)
                    )
                } else {
                    call.respond(HttpStatusCode.BadRequest, ApiError(message = "no file provided"))
                }
            }
        }
    }
}
