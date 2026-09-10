package com.virjar.tk.server.api

import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidDownloadIdentityTest {
    @Test
    fun `published identity gives actual display name and unique names for GET HEAD and ranges`() = withDownloads { downloads ->
        val payload = "0123456789abcdefghijklmnopqrstuvwxyz"
        publishFixture(downloads, payload, "TK苹果版", "TeamTalkApple", snapshot = true)
        val name = "TeamTalkApple-0.0.1-${hash(payload.toByteArray()).take(12)}-android.apk"
        testApplication {
            application { routing { clientDownloadRoutes(downloads) } }
            val metadata = client.get("/downloads/android.json")
            assertEquals(HttpStatusCode.OK, metadata.status)
            assertEquals("no-store", metadata.headers[HttpHeaders.CacheControl])
            val identity = Json.parseToJsonElement(metadata.bodyAsText()).jsonObject
            assertEquals("TK苹果版", identity.getValue("displayName").jsonPrimitive.content)
            assertEquals("0.0.1", identity.getValue("version").jsonPrimitive.content)
            assertEquals("snapshot", identity.getValue("channel").jsonPrimitive.content)
            assertEquals(name, identity.getValue("filename").jsonPrimitive.content)
            assertEquals("/downloads/$name", identity.getValue("url").jsonPrimitive.content)
            for (url in listOf("/downloads/$name", "/downloads/$ANDROID_DOWNLOAD_ALIAS")) {
                val response = client.get(url)
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals(payload, response.bodyAsText())
                assertEquals("attachment; filename=\"$name\"", response.headers[HttpHeaders.ContentDisposition])
                assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
                val head = client.head(url)
                assertEquals(HttpStatusCode.OK, head.status)
                assertEquals("", head.bodyAsText())
                assertEquals(payload.length.toString(), head.headers[HttpHeaders.ContentLength])
                assertEquals(response.headers[HttpHeaders.ContentDisposition], head.headers[HttpHeaders.ContentDisposition])
                assertEquals("no-store", head.headers[HttpHeaders.CacheControl])
                val range = client.get(url) { header(HttpHeaders.Range, "bytes=3-7") }
                assertEquals(HttpStatusCode.PartialContent, range.status)
                assertEquals(payload.substring(3, 8), range.bodyAsText())
                assertEquals("bytes 3-7/${payload.length}", range.headers[HttpHeaders.ContentRange])
                assertEquals("attachment; filename=\"$name\"", range.headers[HttpHeaders.ContentDisposition])
            }
        }
    }

    @Test
    fun `old identity URLs cannot return a newer same version package`() = withDownloads { downloads ->
        publishFixture(downloads, "old-package")
        val oldName = openAndroidDownload(downloads)!!.use { it.filename }
        assertEquals("stable", openAndroidDownload(downloads)!!.use { it.channelKind })
        testApplication {
            application { routing { clientDownloadRoutes(downloads) } }
            assertEquals("old-package", client.get("/downloads/$oldName").bodyAsText())
            publishFixture(downloads, "new-package")
            // Even an unrelated leftover regular file must not turn this virtual identity path into a fallback.
            downloads.resolve(oldName).writeText("untrusted leftover")
            assertEquals(HttpStatusCode.NotFound, client.get("/downloads/$oldName").status)
            assertEquals(HttpStatusCode.NotFound, client.head("/downloads/$oldName").status)
            val currentName = openAndroidDownload(downloads)!!.use { it.filename }
            assertEquals("new-package", client.get("/downloads/$currentName").bodyAsText())
        }
    }

    @Test
    fun `unmanaged legacy packages remain downloadable without a fabricated version`() = withDownloads { downloads ->
        downloads.resolve(ANDROID_DOWNLOAD_ALIAS).writeText("legacy-package")
        testApplication {
            application { routing { clientDownloadRoutes(downloads) } }
            val metadata = Json.parseToJsonElement(client.get("/downloads/android.json").bodyAsText()).jsonObject
            assertEquals("null", metadata.getValue("version").toString())
            assertNull(metadata["channel"])
            assertEquals("/downloads/$ANDROID_DOWNLOAD_ALIAS", metadata.getValue("url").jsonPrimitive.content)
            val response = client.get("/downloads/$ANDROID_DOWNLOAD_ALIAS")
            assertEquals("legacy-package", response.bodyAsText())
            assertEquals("attachment; filename=\"Android-${hash("legacy-package".toByteArray()).take(12)}.apk\"",
                response.headers[HttpHeaders.ContentDisposition])
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            assertEquals(HttpStatusCode.NotFound, client.get("/downloads/TeamTalk-0.0.1-unknown-android.apk").status)
        }
    }

    @Test
    fun `damaged managed receipt manifest or APK is unavailable rather than legacy success`() = withDownloads { downloads ->
        testApplication {
            application { routing { clientDownloadRoutes(downloads) } }
            for (damage in listOf<(File) -> Unit>(
                { it.resolve(".teamtalk-client-release.json").writeText("broken") },
                { it.resolve(".teamtalk-client-releases/v0.0.1/metadata/release-manifest.json").writeText("broken") },
                { it.resolve(ANDROID_DOWNLOAD_ALIAS).writeText("different package") },
                { it.resolve(ANDROID_DOWNLOAD_ALIAS).delete() },
                {
                    // Matching alias + receipt cannot claim another sealed manifest's app identity.
                    it.resolve(ANDROID_DOWNLOAD_ALIAS).writeText("other-application")
                    val receipt = it.resolve(".teamtalk-client-release.json")
                    receipt.writeText(receipt.readText().replace(hash("valid-package".toByteArray()),
                        hash("other-application".toByteArray())))
                },
            )) {
                publishFixture(downloads, "valid-package")
                damage(downloads)
                for (url in listOf("/downloads/android.json", "/downloads/$ANDROID_DOWNLOAD_ALIAS")) {
                    val response = client.get(url)
                    assertEquals(HttpStatusCode.ServiceUnavailable, response.status, url)
                    assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
                    assertEquals("1", response.headers[HttpHeaders.RetryAfter])
                }
                assertEquals(HttpStatusCode.ServiceUnavailable, client.head("/downloads/$ANDROID_DOWNLOAD_ALIAS").status)
            }
        }
    }

    @Test
    fun `verified descriptor retains its bytes across atomic publication replacement`() = withDownloads { downloads ->
        publishFixture(downloads, "original")
        openAndroidDownload(downloads)!!.use { selected ->
            val replacement = downloads.resolve("replacement.apk").apply { writeText("replacement") }
            Files.move(replacement.toPath(), downloads.resolve(ANDROID_DOWNLOAD_ALIAS).toPath(), StandardCopyOption.REPLACE_EXISTING)
            val bytes = ByteBuffer.allocate(selected.size.toInt())
            assertEquals(8, selected.channel.read(bytes, 0))
            assertEquals("original", bytes.array().toString(Charsets.UTF_8))
            assertEquals(hash(bytes.array()), selected.sha256)
        }
    }

    @Test
    fun `absent download has no identity and returns not found`() = withDownloads { downloads ->
        assertNull(openAndroidDownload(downloads))
        testApplication {
            application { routing { clientDownloadRoutes(downloads) } }
            assertEquals(HttpStatusCode.NotFound, client.get("/downloads/android.json").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/downloads/$ANDROID_DOWNLOAD_ALIAS").status)
        }
    }

    private fun publishFixture(downloads: File, payload: String, displayName: String = "TeamTalk",
        desktopName: String = "TeamTalk", snapshot: Boolean = false) {
        downloads.resolve(ANDROID_DOWNLOAD_ALIAS).writeText(payload)
        val manifest = buildJsonObject {
            put("version", "0.0.1")
            put("buildNumber", 1)
            put("client", buildJsonObject { put("displayName", displayName); put("desktopName", desktopName) })
            put("files", buildJsonArray { add(buildJsonObject {
                put("path", "assets/$desktopName-0.0.1-android.apk")
                put("size", payload.toByteArray().size)
                put("sha256", hash(payload.toByteArray()))
            }) })
        }.toString()
        val manifestHash = hash(manifest.toByteArray())
        val history = if (snapshot) "snapshot/v0.0.1/revision-12" else "v0.0.1"
        downloads.resolve(".teamtalk-client-releases/$history/metadata").apply { mkdirs() }
            .resolve("release-manifest.json").writeText(manifest)
        downloads.resolve(".teamtalk-client-release.json").writeText(buildJsonObject {
            put("version", "0.0.1")
            put("releaseBuildNumber", 1)
            if (snapshot) { put("distributionKind", "snapshot"); put("desktopRevision", 12) }
            put("manifestSha256", manifestHash)
            put("files", buildJsonObject {
                put(ANDROID_DOWNLOAD_ALIAS, hash(payload.toByteArray()))
                put("metadata/release-manifest.json", manifestHash)
            })
        }.toString())
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun withDownloads(block: (File) -> Unit) {
        val downloads = Files.createTempDirectory("teamtalk-android-download-").toFile()
        try { block(downloads) } finally { downloads.deleteRecursively() }
    }
}
