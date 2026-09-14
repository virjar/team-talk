package com.virjar.tk.server.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.http.content.resolveResource
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import java.io.File

/** 首页只公开这三个资源，不为未知地址、API 或下载目录提供 SPA fallback。 */
internal fun Route.publicSiteRoutes(staticDir: File) {
    publicSiteResource("/", staticDir, "index.html", ContentType.Text.Html)
    publicSiteResource("/css/home.css", staticDir, "css/home.css", ContentType.Text.CSS)
    publicSiteResource("/js/downloads.js", staticDir, "js/downloads.js", ContentType.Application.JavaScript)
}

private fun Route.publicSiteResource(path: String, staticDir: File, resource: String, type: ContentType) {
    val contentType = type.withCharset(Charsets.UTF_8)
    get(path) { call.respondPublicSiteResource(staticDir, resource, contentType, head = false) }
    head(path) { call.respondPublicSiteResource(staticDir, resource, contentType, head = true) }
}

private suspend fun ApplicationCall.respondPublicSiteResource(
    staticDir: File,
    resource: String,
    type: ContentType,
    head: Boolean,
) {
    // 发行包沿用安装根目录/static；开发或仅 JAR 运行时从同一 classpath 资源读取。
    val file = File(staticDir, resource)
    val content = if (file.isFile) LocalFileContent(file, type) else resolveResource(resource, "static") { type }
    if (content == null) return respond(HttpStatusCode.NotFound)
    // 资源地址没有内容哈希，允许浏览器保存但每次使用前都重新验证，避免升级后沿用旧脚本。
    response.headers.append(HttpHeaders.CacheControl, "no-cache")
    if (head) {
        respond(object : OutgoingContent.NoContent() {
            override val contentLength = content.contentLength
            override val contentType = content.contentType
            override val headers = content.headers
        })
    } else {
        respond(content)
    }
}
