package com.virjar.tk.server.api

import com.virjar.tk.protocol.http.AndroidReleaseManifest
import com.virjar.tk.server.infra.clientrelease.ClientReleaseService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.defaultForFile
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
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
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 客户端下载入口（/downloads）。
 *
 * Android 走发布注册中心优先、旧收据目录兜底（服务端升级但尚未重新发布时的
 * 过渡期兼容）；桌面目录是冻结的 Conveyor 遗留站点，只读保留给存量客户端。
 */
internal fun Route.clientDownloadRoutes(
    downloadsDir: File,
    clientReleases: ClientReleaseService? = null,
) {
    route("/downloads") {
        install(PartialContent)

        get("/android.json") {
            // 注册中心已发布时它是唯一权威；否则回落到旧收据语义。
            val registryManifest = clientReleases?.androidLegacyManifest()
            if (registryManifest != null) {
                call.response.headers.append(io.ktor.http.HttpHeaders.CacheControl, "no-store")
                call.respondText(Json.encodeToString(registryManifest), ContentType.Application.Json)
                return@get
            }
            if (clientReleases?.managesAndroidDownloads() == true) return@get call.respond(HttpStatusCode.NotFound)
            call.withAndroidDownload(downloadsDir) { download ->
                // 发布通道标记（T030）：stable/preview/snapshot；无收据的历史目录不声明通道。
                val manifest = AndroidReleaseManifest(
                    displayName = download.displayName ?: "Android",
                    version = download.version,
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
                return@get call.respondAndroidDownload(downloadsDir, clientReleases, filename, head = false)
            }
            val file = resolveDirectDownload(downloadsDir, filename)
                ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(LocalFileContent(file, clientDownloadContentType(file)))
        }
        head("/{filename}") {
            val filename = call.parameters["filename"] ?: return@head call.respond(HttpStatusCode.BadRequest)
            if (filename == ANDROID_DOWNLOAD_ALIAS || filename.endsWith("-android.apk")) {
                return@head call.respondAndroidDownload(downloadsDir, clientReleases, filename, head = true)
            }
            val file = resolveDirectDownload(downloadsDir, filename)
                ?: return@head call.respond(HttpStatusCode.NotFound)
            // 直链保留同一文件准入；HEAD 返回 GET 的元数据，不打开并传输整个 APK。
            call.respond(file.downloadHeadResponse())
        }

        // 中文「下载与更新」页：注册中心数据 + 首页同款风格（classpath 资源懒加载缓存）。
        for (path in listOf("", "/")) {
            get(path) { call.respondDownloadsPage(head = false) }
            head(path) { call.respondDownloadsPage(head = true) }
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

private suspend fun ApplicationCall.respondDownloadsPage(head: Boolean) {
    val page = downloadsPageHtml() ?: return respond(HttpStatusCode.NotFound)
    val pageContentType = ContentType.Text.Html.withCharset(Charsets.UTF_8)
    response.headers.append(io.ktor.http.HttpHeaders.CacheControl, "no-store")
    if (head) {
        // 首页用 HEAD 探测下载卡片；长度与 GET 的 UTF-8 正文一致，不发送页面内容。
        respond(object : io.ktor.http.content.OutgoingContent.NoContent() {
            override val contentLength = page.toByteArray(Charsets.UTF_8).size.toLong()
            override val contentType = pageContentType
        })
    } else {
        respondText(page, pageContentType)
    }
}

private suspend fun ApplicationCall.respondAndroidDownload(
    downloads: File,
    clientReleases: ClientReleaseService?,
    filename: String,
    head: Boolean,
) {
    // 注册中心的 APK：文件句柄来自内容寻址仓，身份来自发布行，无需逐请求重算哈希。
    val registryInstaller = when {
        clientReleases == null -> null
        filename == ANDROID_DOWNLOAD_ALIAS -> clientReleases.findAndroidInstaller(null)
        else -> clientReleases.findAndroidInstaller(filename)
    }
    if (registryInstaller != null) {
        response.headers.append(
            io.ktor.http.HttpHeaders.ContentDisposition,
            "attachment; filename=\"${registryInstaller.filename}\"",
        )
        if (head) {
            respond(
                object : io.ktor.http.content.OutgoingContent.NoContent() {
                    override val contentLength = registryInstaller.size
                    override val contentType = ContentType("application", "vnd.android.package-archive")
                    override val headers = io.ktor.http.headersOf(io.ktor.http.HttpHeaders.AcceptRanges, "bytes")
                },
            )
        } else {
            respond(
                LocalFileContent(
                    registryInstaller.file,
                    ContentType("application", "vnd.android.package-archive"),
                ),
            )
        }
        return
    }
    if (clientReleases?.managesAndroidDownloads() == true) return respond(HttpStatusCode.NotFound)
    withAndroidDownload(downloads) { snapshot ->
        if (filename != ANDROID_DOWNLOAD_ALIAS && (!snapshot.managed || filename != snapshot.filename)) {
            respond(HttpStatusCode.NotFound)
            return@withAndroidDownload
        }
        response.headers.append(
            io.ktor.http.HttpHeaders.ContentDisposition,
            "attachment; filename=\"${snapshot.filename}\"",
        )
        if (head) {
            respond(
                object : io.ktor.http.content.OutgoingContent.NoContent() {
                    override val contentLength = snapshot.size
                    override val contentType = ContentType("application", "vnd.android.package-archive")
                    override val headers = io.ktor.http.headersOf(io.ktor.http.HttpHeaders.AcceptRanges, "bytes")
                },
            )
        } else coroutineScope {
            respond(AndroidDownloadContent(snapshot, this))
        }
    }
}

private suspend fun ApplicationCall.withAndroidDownload(
    downloads: File,
    block: suspend (AndroidDownloadSnapshot) -> Unit,
) {
    response.headers.append(io.ktor.http.HttpHeaders.CacheControl, "no-store")
    val snapshot = try {
        openAndroidDownload(downloads)
    } catch (_: java.io.IOException) {
        null.also { response.headers.append(io.ktor.http.HttpHeaders.RetryAfter, "1") }
    } catch (_: IllegalArgumentException) {
        null.also { response.headers.append(io.ktor.http.HttpHeaders.RetryAfter, "1") }
    } catch (_: IllegalStateException) {
        null.also { response.headers.append(io.ktor.http.HttpHeaders.RetryAfter, "1") }
    } catch (_: NoSuchElementException) {
        null.also { response.headers.append(io.ktor.http.HttpHeaders.RetryAfter, "1") }
    }
    if (snapshot == null) {
        respond(
            if (response.headers[io.ktor.http.HttpHeaders.RetryAfter] != null) {
                HttpStatusCode.ServiceUnavailable
            } else {
                HttpStatusCode.NotFound
            },
        )
        return
    }
    snapshot.use { block(it) }
}

private class AndroidDownloadContent(
    private val snapshot: AndroidDownloadSnapshot,
    private val scope: kotlinx.coroutines.CoroutineScope,
) : io.ktor.http.content.OutgoingContent.ReadChannelContent() {
    override val contentLength = snapshot.size
    override val contentType = ContentType("application", "vnd.android.package-archive")
    override fun readFrom(): ByteReadChannel = readFrom(0 until snapshot.size)
    override fun readFrom(range: LongRange): ByteReadChannel = scope.writer() {
        val buffer = java.nio.ByteBuffer.allocate(64 * 1024)
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

internal fun File.downloadHeadResponse(
    downloadContentType: ContentType = clientDownloadContentType(this),
): io.ktor.http.content.OutgoingContent.NoContent =
    object : io.ktor.http.content.OutgoingContent.NoContent() {
        override val contentLength = length()
        override val contentType = downloadContentType
        override val headers = io.ktor.http.headersOf(io.ktor.http.HttpHeaders.AcceptRanges, "bytes")
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

private const val DOWNLOADS_PAGE_RESOURCE = "static/downloads/index.html"

private object DownloadsPageResource {
    val html: String? by lazy {
        DownloadsPageResource::class.java.classLoader
            .getResourceAsStream(DOWNLOADS_PAGE_RESOURCE)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}

internal fun downloadsPageHtml(): String? = DownloadsPageResource.html
