package com.virjar.tk.api

import com.virjar.tk.dto.ApiError
import com.virjar.tk.env.Environment
import com.virjar.tk.storage.FileStore
import com.virjar.tk.storage.ReadRange
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val ALLOWED_CONTENT_PREFIXES = listOf(
    "image/", "video/", "audio/",
    "application/pdf", "application/zip",
    "application/x-zip-compressed",
    "text/plain", "text/csv",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument",
    "application/msword",
)

fun Routing.fileRoutes(maxFileSizeBytes: Long = 50 * 1024 * 1024) {
    route("/api/v1/files") {

        get("/{path...}") {
            val path =
                call.parameters.getAll("path")?.joinToString("/") ?: return@get call.respond(HttpStatusCode.BadRequest)
            val rangeHeader = call.request.header(HttpHeaders.Range)

            val meta = withContext(Dispatchers.IO) { FileStore.getMeta(path) }
            if (meta == null) {
                call.respond(HttpStatusCode.NotFound, ApiError(message = "file not found"))
                return@get
            }

            val range = call.request.requestRange(meta.size)

            if (rangeHeader != null && range != null && range.first >= meta.size) {
                call.response.header(HttpHeaders.ContentRange, "bytes */${meta.size}")
                call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)
                return@get
            }

            call.response.header(HttpHeaders.CacheControl, "public, max-age=86400")
            call.response.header(HttpHeaders.AcceptRanges, "bytes")

            val contentLength = if (range != null) range.second - range.first + 1 else meta.size

            if (range != null) {
                call.response.header(HttpHeaders.ContentRange, "bytes ${range.first}-${range.second}/${meta.size}")
            }
            call.response.header(HttpHeaders.ContentLength, contentLength)

            val readRange = range?.let { ReadRange(it.first, it.second) }
            call.respond(object : OutgoingContent.WriteChannelContent() {
                override val contentLength = contentLength
                override val contentType = ContentType.parse(meta.contentType)
                override val status = if (range != null) HttpStatusCode.PartialContent else HttpStatusCode.OK
                override suspend fun writeTo(channel: ByteWriteChannel) {
                    FileStore.streamTo(meta, channel, readRange)
                }
            })
        }

        authenticate("auth-jwt") {
            post("/upload") {
                val uid = call.principal<JWTPrincipal>()!!.payload.subject
                when (val upload = upload2Temp(call.receiveMultipart(), maxFileSizeBytes)) {
                    // 先写临时文件，再存储，在底层存在两种存储后端
                    //  若是小文件，则存储到RocksDb，避免os文件碎片（绝大部分场景）
                    //  若是大文件，则使用OS直接存储，避免RocksDb管理大文件结构导致IO带宽占用
                    is UploadResult.Ok -> {
                        val path = withContext(Dispatchers.IO) {
                            FileStore.store(uid, upload.fileName, upload.contentType, upload.tempFile)
                        }
                        call.respond(HttpStatusCode.Created, mapOf("path" to path, "thumbnailPath" to ""))
                    }

                    is UploadResult.Error -> {
                        call.respond(upload.status, ApiError(message = upload.message))
                    }
                }
            }
        }
    }
}

///////////// upload //////
private sealed class UploadResult {
    data class Error(val status: HttpStatusCode, val message: String) : UploadResult()
    data class Ok(val fileName: String, val contentType: String, val tempFile: File) : UploadResult()
}

private suspend fun upload2Temp(multipart: MultiPartData, maxSize: Long): UploadResult {
    var fileName = ""
    var contentType = ""
    var tempFile: File? = null

    try {
        var part = multipart.readPart()
        while (part != null) {
            if (part is PartData.FileItem) {
                fileName = part.originalFileName ?: "unknown"
                contentType = part.contentType?.toString() ?: "application/octet-stream"

                if (!ALLOWED_CONTENT_PREFIXES.any { contentType.startsWith(it, ignoreCase = true) }) {
                    part.dispose()
                    return UploadResult.Error(
                        HttpStatusCode.UnsupportedMediaType,
                        "file type not allowed: $contentType"
                    )
                }

                val cl = part.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                if (cl != null && cl > maxSize) {
                    part.dispose()
                    return UploadResult.Error(
                        HttpStatusCode.PayloadTooLarge,
                        "file too large: $cl bytes (max $maxSize)"
                    )
                }

                val tmp = withContext(Dispatchers.IO) {
                    File.createTempFile("upload-", ".tmp", Environment.fileStoreTmpDir)
                }
                tmp.outputStream().buffered().use { out ->
                    val rbc = java.nio.channels.Channels.newChannel(out)
                    part.provider().copyTo(rbc)
                    rbc.close()
                }

                if (tmp.length() > maxSize) {
                    tmp.delete()
                    part.dispose()
                    return UploadResult.Error(HttpStatusCode.PayloadTooLarge, "file too large (max $maxSize)")
                }

                tempFile = tmp
            }
            part.dispose()
            part = multipart.readPart()
        }
    } catch (e: Exception) {
        tempFile?.delete()
        throw e
    }

    val file = tempFile ?: return UploadResult.Error(HttpStatusCode.BadRequest, "no file provided")
    return UploadResult.Ok(fileName, contentType, file)
}

///////////// download //////
fun RoutingRequest.requestRange(fileLength: Long): Pair<Long, Long>? {
    val rangeHeader: String? = header(HttpHeaders.Range)
    if (rangeHeader.isNullOrBlank()) return null
    val prefix = "bytes="
    if (!rangeHeader.startsWith(prefix, ignoreCase = true)) return null
    val spec = rangeHeader.substring(prefix.length).trim().substringBefore(',')

    val parts = spec.split('-', limit = 2)
    if (parts.size != 2) return null
    val start = parts[0].trim()
    val end = parts[1].trim()
    return when {
        start.isEmpty() && end.isNotEmpty() -> {
            val suffix = end.toLongOrNull() ?: return null
            if (suffix <= 0 || fileLength == 0L) return null
            (fileLength - suffix).coerceAtLeast(0) to fileLength - 1
        }

        start.isNotEmpty() && end.isEmpty() -> {
            val start = start.toLongOrNull() ?: return null
            if (start >= fileLength) return null
            start to fileLength - 1
        }

        start.isNotEmpty() && end.isNotEmpty() -> {
            val start = start.toLongOrNull() ?: return null
            val end = end.toLongOrNull() ?: return null
            if (start > end || start >= fileLength) return null
            start to end.coerceAtMost(fileLength - 1)
        }

        else -> null
    }
}
