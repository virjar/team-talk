package com.virjar.tk.server.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.defaultForFile
import io.ktor.http.headersOf
import io.ktor.server.application.install
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.route
import java.io.File

/** 安装器和更新客户端读取公开制品；Range 插件不进入另有鉴权与 Range 策略的业务附件路由。 */
internal fun Route.clientDownloadRoutes(downloadsDir: File) {
    route("/downloads") {
        install(PartialContent)

        get("/{filename}") {
            val filename = call.parameters["filename"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val file = resolveDirectDownload(downloadsDir, filename)
                ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(LocalFileContent(file, clientDownloadContentType(file)))
        }
        head("/{filename}") {
            val filename = call.parameters["filename"] ?: return@head call.respond(HttpStatusCode.BadRequest)
            val file = resolveDirectDownload(downloadsDir, filename)
                ?: return@head call.respond(HttpStatusCode.NotFound)
            // 直链保留同一文件准入；HEAD 返回 GET 的元数据，不打开并传输整个 APK。
            call.respond(file.downloadHeadResponse())
        }

        val desktopDir = File(downloadsDir, "desktop")
        val downloadPage = File(desktopDir, "download.html")
        // staticFiles 的 tailcard 优先级低于上面的直链参数；目录入口必须使用明确字面路由。
        for (path in listOf("/desktop", "/desktop/")) {
            get(path) {
                if (!downloadPage.isFile) return@get call.respond(HttpStatusCode.NotFound)
                call.respond(LocalFileContent(downloadPage, clientDownloadContentType(downloadPage)))
            }
            head(path) {
                if (!downloadPage.isFile) return@head call.respond(HttpStatusCode.NotFound)
                call.respond(downloadPage.downloadHeadResponse())
            }
        }
        // 没有 index/default/fallback，缺失的更新元数据必须诚实返回 404。
        staticFiles("/desktop", desktopDir, index = null) {
            enableAutoHeadResponse()
            contentType(::clientDownloadContentType)
        }
    }
}

private fun File.downloadHeadResponse(): OutgoingContent.NoContent = object : OutgoingContent.NoContent() {
    override val contentLength = length()
    override val contentType = clientDownloadContentType(this@downloadHeadResponse)
    override val headers = headersOf(HttpHeaders.AcceptRanges, "bytes")
}

/** Windows App Installer 要求正确的制品类型，不能全部退回 application/octet-stream。 */
private fun clientDownloadContentType(file: File): ContentType = when (file.extension.lowercase()) {
    "appinstaller" -> ContentType("application", "appinstaller")
    "msix" -> ContentType("application", "msix")
    "msixbundle" -> ContentType("application", "msixbundle")
    "appx" -> ContentType("application", "appx")
    "appxbundle" -> ContentType("application", "appxbundle")
    else -> ContentType.defaultForFile(file)
}

/** Only direct regular package files under the trusted downloads root are publicly exposed. */
internal fun resolveDirectDownload(downloadsDir: File, filename: String): File? {
    if (
        filename.length !in 1..255 ||
        filename == "." ||
        filename == ".." ||
        filename.any { it == '/' || it == '\\' || it == '\u0000' }
    ) {
        return null
    }
    return try {
        val canonicalRoot = downloadsDir.canonicalFile
        val candidate = File(canonicalRoot, filename).canonicalFile
        candidate.takeIf { it.isFile && it.parentFile == canonicalRoot }
    } catch (_: java.io.IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}
