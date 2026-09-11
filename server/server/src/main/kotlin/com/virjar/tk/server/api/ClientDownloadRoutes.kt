package com.virjar.tk.server.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import com.virjar.tk.protocol.http.AndroidReleaseManifest
import io.ktor.http.content.OutgoingContent
import io.ktor.http.defaultForFile
import io.ktor.http.headersOf
import io.ktor.server.application.install
import io.ktor.server.application.ApplicationCall
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.route
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer

/** 安装器和更新客户端读取公开制品；Range 插件不进入另有鉴权与 Range 策略的业务附件路由。 */
internal fun Route.clientDownloadRoutes(downloadsDir: File) {
    route("/downloads") {
        install(PartialContent)

        get("/android.json") {
            call.withAndroidDownload(downloadsDir) { download ->
                // 发布通道标记（T030）：stable/preview/snapshot；无收据的历史目录不声明通道。
                val manifest = AndroidReleaseManifest(
                    displayName = download.displayName ?: "Android",
                    version = download.version.orEmpty(),
                    channel = download.channelKind,
                    filename = download.filename,
                    url = download.url,
                )
                call.respondText(Json.encodeToString(manifest), ContentType.Application.Json)
            }
        }

        get("/{filename}") {
            val filename = call.parameters["filename"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            if (filename == ANDROID_DOWNLOAD_ALIAS || filename.endsWith("-android.apk")) {
                return@get call.respondAndroidDownload(downloadsDir, filename, head = false)
            }
            val file = resolveDirectDownload(downloadsDir, filename)
                ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(LocalFileContent(file, clientDownloadContentType(file)))
        }
        head("/{filename}") {
            val filename = call.parameters["filename"] ?: return@head call.respond(HttpStatusCode.BadRequest)
            if (filename == ANDROID_DOWNLOAD_ALIAS || filename.endsWith("-android.apk")) {
                return@head call.respondAndroidDownload(downloadsDir, filename, head = true)
            }
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

private suspend fun ApplicationCall.withAndroidDownload(
    downloads: File,
    block: suspend (AndroidDownloadSnapshot) -> Unit,
) = withContext(Dispatchers.IO) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    val snapshot = try {
        openAndroidDownload(downloads)
    } catch (_: java.io.IOException) {
        null.also { response.headers.append(HttpHeaders.RetryAfter, "1") }
    } catch (_: IllegalArgumentException) {
        null.also { response.headers.append(HttpHeaders.RetryAfter, "1") }
    } catch (_: IllegalStateException) {
        null.also { response.headers.append(HttpHeaders.RetryAfter, "1") }
    } catch (_: NoSuchElementException) {
        null.also { response.headers.append(HttpHeaders.RetryAfter, "1") }
    }
    if (snapshot == null) {
        respond(if (response.headers[HttpHeaders.RetryAfter] != null) HttpStatusCode.ServiceUnavailable else HttpStatusCode.NotFound)
        return@withContext
    }
    snapshot.use { block(it) }
}

private suspend fun ApplicationCall.respondAndroidDownload(downloads: File, filename: String, head: Boolean) {
    withAndroidDownload(downloads) { snapshot ->
        if (filename != ANDROID_DOWNLOAD_ALIAS && (!snapshot.managed || filename != snapshot.filename)) {
            respond(HttpStatusCode.NotFound)
            return@withAndroidDownload
        }
        response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"${snapshot.filename}\"")
        if (head) {
            respond(object : OutgoingContent.NoContent() {
                override val contentLength = snapshot.size
                override val contentType = ContentType("application", "vnd.android.package-archive")
                override val headers = headersOf(HttpHeaders.AcceptRanges, "bytes")
            })
        } else coroutineScope {
            respond(AndroidDownloadContent(snapshot, this))
        }
    }
}

private class AndroidDownloadContent(
    private val snapshot: AndroidDownloadSnapshot,
    private val scope: CoroutineScope,
) : OutgoingContent.ReadChannelContent() {
    override val contentLength = snapshot.size
    override val contentType = ContentType("application", "vnd.android.package-archive")
    override fun readFrom(): ByteReadChannel = readFrom(0 until snapshot.size)
    override fun readFrom(range: LongRange): ByteReadChannel = scope.writer(Dispatchers.IO) {
        val buffer = ByteBuffer.allocate(64 * 1024)
        var offset = range.first
        while (offset <= range.last) {
            buffer.clear().limit(minOf(buffer.capacity().toLong(), range.last - offset + 1).toInt())
            val count = snapshot.channel.read(buffer, offset)
            check(count > 0) { "Android download ended before the selected range" }
            channel.writeFully(buffer.array(), 0, count)
            offset += count
        }
    }.channel
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
