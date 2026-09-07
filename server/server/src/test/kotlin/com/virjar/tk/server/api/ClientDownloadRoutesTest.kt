package com.virjar.tk.server.api

import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondFile
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClientDownloadRoutesTest {
    @Test
    fun `installer GET and HEAD retain content type length and range support`() {
        val downloads = Files.createTempDirectory("teamtalk-download-routes-").toFile()
        try {
            val payload = "0123456789abcdefghijklmnopqrstuvwxyz"
            val desktop = downloads.resolve("desktop").apply { mkdir() }
            val files = linkedMapOf(
                "desktop/teamtalk.appinstaller" to "application/appinstaller",
                "desktop/teamtalk.msix" to "application/msix",
                "desktop/teamtalk.msixbundle" to "application/msixbundle",
                "desktop/teamtalk.appx" to "application/appx",
                "desktop/teamtalk.appxbundle" to "application/appxbundle",
                "TeamTalk-android.apk" to "application/vnd.android.package-archive",
            )
            files.keys.forEach { downloads.resolve(it).writeText(payload) }
            desktop.resolve("download.html").writeText("<html>download</html>")

            testApplication {
                application { routing { clientDownloadRoutes(downloads) } }
                files.forEach { (name, mime) ->
                    val response = client.get("/downloads/$name")
                    assertEquals(HttpStatusCode.OK, response.status, name)
                    assertEquals(payload, response.bodyAsText(), name)
                    assertEquals(mime, response.headers[HttpHeaders.ContentType], name)
                    assertEquals(payload.length.toString(), response.headers[HttpHeaders.ContentLength], name)
                    assertEquals("bytes", response.headers[HttpHeaders.AcceptRanges], name)

                    val head = client.head("/downloads/$name")
                    assertEquals(HttpStatusCode.OK, head.status, name)
                    assertEquals("", head.bodyAsText(), name)
                    assertEquals(mime, head.headers[HttpHeaders.ContentType], name)
                    assertEquals(payload.length.toString(), head.headers[HttpHeaders.ContentLength], name)
                    assertEquals("bytes", head.headers[HttpHeaders.AcceptRanges], name)
                }
            }
        } finally {
            downloads.deleteRecursively()
        }
    }

    @Test
    fun `installer byte ranges and unsatisfiable ranges follow HTTP semantics`() {
        val downloads = Files.createTempDirectory("teamtalk-download-ranges-").toFile()
        try {
            val payload = "0123456789abcdefghijklmnopqrstuvwxyz"
            downloads.resolve("desktop").mkdir()
            val names = listOf("desktop/teamtalk.appinstaller", "desktop/teamtalk.msix", "TeamTalk-android.apk")
            names.forEach { downloads.resolve(it).writeText(payload) }

            testApplication {
                application { routing { clientDownloadRoutes(downloads) } }
                names.forEach { name ->
                    val first = client.get("/downloads/$name") { header(HttpHeaders.Range, "bytes=0-15") }
                    assertEquals(HttpStatusCode.PartialContent, first.status, name)
                    assertEquals(payload.take(16), first.bodyAsText(), name)
                    assertEquals("16", first.headers[HttpHeaders.ContentLength], name)
                    assertEquals("bytes 0-15/${payload.length}", first.headers[HttpHeaders.ContentRange], name)
                    assertEquals("bytes", first.headers[HttpHeaders.AcceptRanges], name)

                    val suffix = client.get("/downloads/$name") { header(HttpHeaders.Range, "bytes=-5") }
                    assertEquals(HttpStatusCode.PartialContent, suffix.status, name)
                    assertEquals(payload.takeLast(5), suffix.bodyAsText(), name)
                    assertEquals("5", suffix.headers[HttpHeaders.ContentLength], name)
                    assertEquals("bytes 31-35/${payload.length}", suffix.headers[HttpHeaders.ContentRange], name)

                    val beyond = client.get("/downloads/$name") { header(HttpHeaders.Range, "bytes=100-110") }
                    assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, beyond.status, name)
                    assertEquals("bytes */${payload.length}", beyond.headers[HttpHeaders.ContentRange], name)
                }
            }
        } finally {
            downloads.deleteRecursively()
        }
    }

    @Test
    fun `desktop index has explicit directory URLs while unknown paths remain not found`() {
        val downloads = Files.createTempDirectory("teamtalk-download-index-").toFile()
        try {
            val html = "<html>download</html>"
            downloads.resolve("desktop").apply { mkdir() }.resolve("download.html").writeText(html)
            downloads.resolve("hidden").apply { mkdir() }.resolve("private.bin").writeText("not public")

            testApplication {
                application { routing { clientDownloadRoutes(downloads) } }
                for (url in listOf("/downloads/desktop", "/downloads/desktop/")) {
                    val response = client.get(url)
                    assertEquals(HttpStatusCode.OK, response.status, url)
                    assertEquals(html, response.bodyAsText(), url)
                    assertEquals("bytes", response.headers[HttpHeaders.AcceptRanges], url)
                    val head = client.head(url)
                    assertEquals(HttpStatusCode.OK, head.status, url)
                    assertEquals(html.length.toString(), head.headers[HttpHeaders.ContentLength], url)
                    assertEquals("bytes", head.headers[HttpHeaders.AcceptRanges], url)
                    assertEquals("", head.bodyAsText(), url)
                }
                for (url in listOf(
                    "/downloads/desktop/missing.appinstaller",
                    "/downloads/desktop/appcast.xml",
                    "/downloads/desktop/unknown/Packages",
                    "/downloads/missing.apk",
                    "/downloads/hidden/private.bin",
                )) {
                    assertEquals(HttpStatusCode.NotFound, client.get(url).status, url)
                    assertEquals(HttpStatusCode.NotFound, client.head(url).status, url)
                }
            }
        } finally {
            downloads.deleteRecursively()
        }
    }

    @Test
    fun `download plugins do not change sibling business file responses`() {
        val downloads = Files.createTempDirectory("teamtalk-download-scope-").toFile()
        try {
            val businessFile = downloads.resolve("fixture.bin").apply { writeText("business content") }
            testApplication {
                application {
                    routing {
                        clientDownloadRoutes(downloads)
                        // A sibling response demonstrates plugin scope without introducing database/auth fixtures.
                        get("/api/v1/files/fixture") { call.respondFile(businessFile) }
                    }
                }
                val response = client.get("/api/v1/files/fixture") { header(HttpHeaders.Range, "bytes=0-3") }
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("business content", response.bodyAsText())
                assertNull(response.headers[HttpHeaders.ContentRange])
                assertNull(response.headers[HttpHeaders.AcceptRanges])
                assertEquals(HttpStatusCode.MethodNotAllowed, client.head("/api/v1/files/fixture").status)
            }
        } finally {
            downloads.deleteRecursively()
        }
    }
}
