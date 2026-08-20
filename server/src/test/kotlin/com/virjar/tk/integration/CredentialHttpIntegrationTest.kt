package com.virjar.tk.integration

import com.virjar.tk.api.clientLogRoutes
import com.virjar.tk.infra.storage.ClientLogStore
import com.virjar.tk.protocol.payload.AuthRequestPayload
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals

class CredentialHttpIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `real postgres bearer follows ban unban and password reset epochs`() = testApplication {
        val root = Files.createTempDirectory("teamtalk-real-token-route-").toFile()
        try {
            val username = uniqueUsername("http-credential")
            val oldPassword = "password123"
            val newPassword = "password456"
            val uid = ctx.userService.register(username, oldPassword, "HTTP Credential User").uid
            val beforeBan = login(username, oldPassword)
            val oldAccess = requireNotNull(beforeBan.accessToken)

            application {
                routing {
                    clientLogRoutes(ClientLogStore(root.resolve("logs")), ctx.accessTokenValidator)
                }
            }

            suspend fun upload(accessToken: String) = client.post("/api/client-logs") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                setBody(gzip("credential epoch route check"))
            }.status

            assertEquals(HttpStatusCode.OK, upload(oldAccess))

            ctx.adminService.banUser(uid)
            assertEquals(HttpStatusCode.Unauthorized, upload(oldAccess))

            ctx.adminService.unbanUser(uid)
            assertEquals(
                HttpStatusCode.Unauthorized,
                upload(oldAccess),
                "unban must never resurrect a credential from the previous user epoch",
            )

            ctx.adminService.resetPassword(uid, newPassword)
            assertEquals(1, login(username, oldPassword).code)
            val afterReset = login(username, newPassword)
            assertEquals(HttpStatusCode.OK, upload(requireNotNull(afterReset.accessToken)))
        } finally {
            root.deleteRecursively()
        }
    }

    private suspend fun login(username: String, password: String) = ctx.authService.handleAuth(
        AuthRequestPayload(
            authType = 0,
            username = username,
            password = password,
            deviceId = "http-credential-device",
            deviceName = "HTTP Credential Test",
        ),
    )

    private fun gzip(value: String): ByteArray = ByteArrayOutputStream().also { output ->
        GZIPOutputStream(output).use { it.write(value.encodeToByteArray()) }
    }.toByteArray()
}
