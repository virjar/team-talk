package com.virjar.tk.server.api

import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.net.URI
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PublicSiteRoutesTest {
    @Test
    fun `classpath homepage and its linked resources support UTF8 GET and bodyless HEAD`() {
        val staticDir = Files.createTempDirectory("teamtalk-public-site-").toFile()
        try {
            testApplication {
                application { routing { publicSiteRoutes(staticDir) } }
                val resources = linkedMapOf(
                    "/" to ("index.html" to "text/html; charset=UTF-8"),
                    "/css/home.css" to ("css/home.css" to "text/css; charset=UTF-8"),
                    "/js/downloads.js" to ("js/downloads.js" to "application/javascript; charset=UTF-8"),
                )
                for ((url, resource) in resources) {
                    val expected = checkNotNull(javaClass.getResource("/static/${resource.first}"))
                        .readText(Charsets.UTF_8)
                    val response = client.get(url)
                    assertEquals(HttpStatusCode.OK, response.status, url)
                    assertEquals(expected, response.bodyAsText(), url)
                    assertEquals(resource.second, response.headers[HttpHeaders.ContentType], url)
                    assertEquals(expected.toByteArray(Charsets.UTF_8).size.toString(), response.headers[HttpHeaders.ContentLength], url)
                    assertEquals("no-cache", response.headers[HttpHeaders.CacheControl], url)
                    val head = client.head(url) { header(HttpHeaders.Range, "bytes=0-3") }
                    assertEquals(HttpStatusCode.OK, head.status, url)
                    assertEquals("", head.bodyAsText(), url)
                    assertNull(head.headers[HttpHeaders.ContentRange], url)
                    for (header in listOf(HttpHeaders.ContentType, HttpHeaders.ContentLength, HttpHeaders.CacheControl)) {
                        assertEquals(response.headers[header], head.headers[header], "$url $header")
                    }
                }
                val homepage = client.get("/").bodyAsText()
                val linkedPaths = Regex("""(?:href|src)="([^"]+)"""").findAll(homepage)
                    .map { URI("http://localhost/").resolve(it.groupValues[1]).path }.toSet()
                assertTrue("/css/home.css" in linkedPaths)
                assertTrue("/js/downloads.js" in linkedPaths)
            }
        } finally {
            staticDir.deleteRecursively()
        }
    }

    @Test
    fun `deployed files take precedence and the homepage is refreshed without a resource singleton`() {
        val staticDir = Files.createTempDirectory("teamtalk-public-site-deployed-").toFile()
        try {
            val contents = linkedMapOf(
                "index.html" to "<html>部署首页</html>",
                "css/home.css" to "/* 部署样式 */ body { color: black; }",
                "js/downloads.js" to "// 部署脚本\nconst message = '你好';",
            )
            contents.forEach { (path, content) ->
                staticDir.resolve(path).apply { parentFile.mkdirs(); writeText(content, Charsets.UTF_8) }
            }
            testApplication {
                application { routing { publicSiteRoutes(staticDir) } }
                for ((path, content) in contents) {
                    val url = if (path == "index.html") "/" else "/$path"
                    assertEquals(content, client.get(url).bodyAsText(), url)
                    val head = client.head(url)
                    assertEquals(HttpStatusCode.OK, head.status, url)
                    assertEquals(content.toByteArray(Charsets.UTF_8).size.toString(), head.headers[HttpHeaders.ContentLength], url)
                    assertEquals("", head.bodyAsText(), url)
                }
                staticDir.resolve("index.html").writeText("<html>更新后的首页</html>", Charsets.UTF_8)
                assertEquals("<html>更新后的首页</html>", client.get("/").bodyAsText())
            }
        } finally {
            staticDir.deleteRecursively()
        }
    }

    @Test
    fun `public resource routes are bounded and do not intercept API or unknown paths`() {
        val staticDir = Files.createTempDirectory("teamtalk-public-site-scope-").toFile()
        try {
            for (path in listOf("css/private.css", "js/private.js", "private.txt")) {
                staticDir.resolve(path).apply { parentFile.mkdirs(); writeText("not public") }
            }
            testApplication {
                application {
                    routing {
                        publicSiteRoutes(staticDir)
                        get("/api/v1/public/downloads") { call.respondText("download data") }
                    }
                }
                assertEquals("download data", client.get("/api/v1/public/downloads").bodyAsText())
                for (path in listOf("/css/private.css", "/js/private.js", "/private.txt", "/index.html", "/unknown", "/api/missing")) {
                    assertEquals(HttpStatusCode.NotFound, client.get(path).status, path)
                    assertEquals(HttpStatusCode.NotFound, client.head(path).status, path)
                }
                assertEquals(HttpStatusCode.MethodNotAllowed, client.head("/api/v1/public/downloads").status)
            }
        } finally {
            staticDir.deleteRecursively()
        }
    }
}
