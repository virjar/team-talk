package com.virjar.tk.api

import com.virjar.tk.infra.storage.ClientLogStore
import com.virjar.tk.infra.storage.TokenStore
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClientLogRoutesTest {

    @Test
    fun `logs require access token and use its identity`() = testApplication {
        val root = Files.createTempDirectory("teamtalk-client-logs-").toFile()
        val tokens = TokenStore(root.resolve("tokens").absolutePath)
        val logs = ClientLogStore(root.resolve("logs"))
        val (accessToken, _) = tokens.generateTokens("user-1", "device-1", 0)
        application {
            monitor.subscribe(ApplicationStopped) { tokens.close() }
            routing { clientLogRoutes(logs, tokens) }
        }

        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/api/client-logs") { setBody(gzip("unauthorized")) }.status,
        )

        val response = client.post("/api/client-logs") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("X-Device-Id", "../../forged")
            setBody(gzip("authenticated diagnostic"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val files = root.resolve("logs/user-1/device-1").walkTopDown().filter(File::isFile).toList()
        assertEquals(1, files.size)
        assertTrue("authenticated diagnostic" in files.single().readText())
        assertTrue(!root.resolve("forged").exists())
    }

    @Test
    fun `gzip expansion is bounded`() {
        val compressed = gzip("x".repeat(1024))
        assertFailsWith<IllegalArgumentException> {
            decodeClientLogPayload(compressed, maxBytes = 128)
        }
    }

    private fun gzip(value: String): ByteArray = ByteArrayOutputStream().also { output ->
        GZIPOutputStream(output).use { it.write(value.encodeToByteArray()) }
    }.toByteArray()
}
