package com.virjar.tk.server.integration

import com.virjar.tk.server.api.clientTelemetryRoutes
import com.virjar.tk.protocol.payload.AuthRequestPayload
import com.virjar.tk.protocol.telemetry.ClientRuntimeInfo
import com.virjar.tk.protocol.telemetry.TelemetryBatch
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CredentialHttpIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env
    private val authConnectionGeneration = AtomicLong()

    @Test
    fun `real postgres bearer follows ban unban and password reset epochs`() = testApplication {
        val username = uniqueUsername("http-credential")
        val oldPassword = "password123"
        val newPassword = "password456"
        val uid = ctx.registerHuman(username, oldPassword, "HTTP Credential User").uid
        val beforeBan = login(username, oldPassword)
        val oldAccess = requireNotNull(beforeBan.accessToken)
        val heartbeat = gzip(
            Json.encodeToString(
                TelemetryBatch(
                    batchId = "credential-http-heartbeat",
                    createdAtEpochMs = System.currentTimeMillis(),
                    runtimeInfo = ClientRuntimeInfo.unknown(),
                    events = emptyList(),
                    heartbeat = true,
                ),
            ),
        )

        application {
            install(ContentNegotiation) { json() }
            routing {
                clientTelemetryRoutes(
                    ctx.clientTelemetryControl,
                    ctx.clientTelemetryEvents,
                    ctx.accessTokenValidator,
                )
            }
        }

        suspend fun upload(accessToken: String) = client.post("/api/client-telemetry") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header(HttpHeaders.ContentEncoding, "gzip")
            setBody(heartbeat)
        }.status

        assertEquals(HttpStatusCode.OK, upload(oldAccess))
        assertNull(
            ctx.clientTelemetryEvents.findBatchReceipt(
                uid,
                "http-credential-device",
                "credential-http-heartbeat",
            ),
        )

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
    }

    private suspend fun login(username: String, password: String) =
        authConnectionGeneration.incrementAndGet().let { generation ->
            ctx.authService.handleAuth(
                AuthRequestPayload(
                    authType = 0,
                    username = username,
                    password = password,
                    deviceId = "http-credential-device",
                    deviceName = "HTTP Credential Test",
                    correlationId = "credential-http-auth-$generation",
                    connectionGeneration = generation,
                ),
            )
        }

    private fun gzip(value: String): ByteArray = ByteArrayOutputStream().also { output ->
        GZIPOutputStream(output).use { it.write(value.encodeToByteArray()) }
    }.toByteArray()
}
