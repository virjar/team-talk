package com.virjar.tk.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ApplicationErrorHandlingTest {
    @Test
    fun `unexpected HTTP failure uses a fixed envelope without echoing details`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            installSafeErrorHandling()
            routing {
                get("/failure") {
                    error("sensitive-test-marker")
                }
                get("/healthy") {
                    call.respond(mapOf("ok" to true))
                }
            }
        }

        val failure = client.get("/failure")
        assertEquals(HttpStatusCode.InternalServerError, failure.status)
        val failureBody = failure.bodyAsText()
        val envelope = Json.parseToJsonElement(failureBody).jsonObject
        assertEquals("internal server error", envelope.getValue("error").jsonPrimitive.content)
        UUID.fromString(envelope.getValue("errorId").jsonPrimitive.content)
        assertFalse(failureBody.contains("sensitive-test-marker"))

        assertEquals(HttpStatusCode.OK, client.get("/healthy").status)
    }
}
