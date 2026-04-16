package com.virjar.tk.integration

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.condition.EnabledIf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Conversation API 集成测试。
 */
@EnabledIf("isIntegrationTestsEnabled")
class ConversationApiTest {

    companion object {
        @JvmStatic
        fun isIntegrationTestsEnabled(): Boolean =
            System.getenv("RUN_INTEGRATION_TESTS")?.toBoolean() == true
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `conversation sync returns list`() = testApplication {
        val reg1 = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"conv-a","password":"pass123","name":"A"}""")
        }
        val (uid1, token1) = json.parseToJsonElement(reg1.bodyAsText()).let {
            it.jsonObject["uid"]!!.jsonPrimitive.content to it.jsonObject["accessToken"]!!.jsonPrimitive.content
        }
        val reg2 = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"conv-b","password":"pass123","name":"B"}""")
        }
        val uid2 = json.parseToJsonElement(reg2.bodyAsText()).jsonObject["uid"]!!.jsonPrimitive.content

        // Create channel to generate conversation
        client.post("/api/v1/channels/personal") {
            header("Authorization", "Bearer $token1")
            contentType(ContentType.Application.Json)
            setBody("""{"uid":"$uid2"}""")
        }

        val response = client.get("/api/v1/conversations/sync") {
            header("Authorization", "Bearer $token1")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `mark conversation as read`() = testApplication {
        val reg1 = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"read-a","password":"pass123","name":"A"}""")
        }
        val (_, token1) = json.parseToJsonElement(reg1.bodyAsText()).let {
            it.jsonObject["uid"]!!.jsonPrimitive.content to it.jsonObject["accessToken"]!!.jsonPrimitive.content
        }

        val response = client.put("/api/v1/conversations/test-channel/read") {
            header("Authorization", "Bearer $token1")
            contentType(ContentType.Application.Json)
            setBody("""{"readSeq":10}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `set conversation draft`() = testApplication {
        val reg1 = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"draft-a","password":"pass123","name":"A"}""")
        }
        val token1 = json.parseToJsonElement(reg1.bodyAsText()).jsonObject["accessToken"]!!.jsonPrimitive.content

        val response = client.put("/api/v1/conversations/test-channel/draft") {
            header("Authorization", "Bearer $token1")
            contentType(ContentType.Application.Json)
            setBody("""{"draft":"hello world"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `pin and mute conversation`() = testApplication {
        val reg1 = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"pinmute-a","password":"pass123","name":"A"}""")
        }
        val token1 = json.parseToJsonElement(reg1.bodyAsText()).jsonObject["accessToken"]!!.jsonPrimitive.content

        val pinResponse = client.put("/api/v1/conversations/test-channel/pin") {
            header("Authorization", "Bearer $token1")
            contentType(ContentType.Application.Json)
            setBody("""{"pinned":true}""")
        }
        assertEquals(HttpStatusCode.OK, pinResponse.status)

        val muteResponse = client.put("/api/v1/conversations/test-channel/mute") {
            header("Authorization", "Bearer $token1")
            contentType(ContentType.Application.Json)
            setBody("""{"muted":true}""")
        }
        assertEquals(HttpStatusCode.OK, muteResponse.status)
    }
}
