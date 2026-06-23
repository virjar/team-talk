package com.virjar.tk.api

import com.virjar.tk.infra.storage.FileStore
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

fun Route.fileRoutes(fileStore: FileStore) {
    route("/api/v1/files") {
        get("/{path...}") {
            val path = call.parameters.getAll("path")?.joinToString("/") ?: return@get call.respond(HttpStatusCode.NotFound)
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
            // TODO: 从 token 中获取 uid，当前先用匿名
            val uid = call.request.header("X-Uid") ?: "anonymous"

            val multipart = call.receiveMultipart()
            var filePath: String? = null

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
                        filePath = fileStore.store(uid, originalName, contentType, tempFile)
                        tempFile.delete()
                    }
                    else -> {}
                }
                part.dispose()
            }

            if (filePath != null) {
                call.respondText(
                    Json.encodeToString(UploadResponse(filePath!!, fileStore.resolveUrl(filePath!!))),
                    ContentType.Application.Json,
                )
            } else {
                call.respond(HttpStatusCode.BadRequest, "No file uploaded")
            }
        }
    }
}

@Serializable
private data class UploadResponse(val path: String, val url: String)
