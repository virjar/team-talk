package com.virjar.tk.integration

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.condition.EnabledIf
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Message API 集成测试。
 */
@EnabledIf("isIntegrationTestsEnabled")
class MessageApiTest {

    companion object {
        @JvmStatic
        fun isIntegrationTestsEnabled(): Boolean =
            System.getenv("RUN_INTEGRATION_TESTS")?.toBoolean() == true
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `message sync returns list for new channel`() = testApplication {
        // Register two users
        val reg1 = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"sync-user-a","password":"pass123","name":"A"}""")
        }
        val (uid1, token1) = json.parseToJsonElement(reg1.bodyAsText()).let {
            it.jsonObject["uid"]!!.jsonPrimitive.content to it.jsonObject["accessToken"]!!.jsonPrimitive.content
        }
        val reg2 = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"sync-user-b","password":"pass123","name":"B"}""")
        }
        val uid2 = json.parseToJsonElement(reg2.bodyAsText()).jsonObject["uid"]!!.jsonPrimitive.content

        // Create channel
        val chResponse = client.post("/api/v1/channels/personal") {
            header("Authorization", "Bearer $token1")
            contentType(ContentType.Application.Json)
            setBody("""{"uid":"$uid2"}""")
        }
        val channelId = json.parseToJsonElement(chResponse.bodyAsText()).jsonObject["channelId"]!!.jsonPrimitive.content

        // Sync messages
        val response = client.post("/api/v1/channels/$channelId/messages/sync") {
            header("Authorization", "Bearer $token1")
            contentType(ContentType.Application.Json)
            setBody("""{"lastSeq":0,"limit":50,"pullMode":0}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `get messages returns list`() = testApplication {
        val reg1 = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"getmsg-user-a","password":"pass123","name":"A"}""")
        }
        val (uid1, token1) = json.parseToJsonElement(reg1.bodyAsText()).let {
            it.jsonObject["uid"]!!.jsonPrimitive.content to it.jsonObject["accessToken"]!!.jsonPrimitive.content
        }
        val reg2 = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"getmsg-user-b","password":"pass123","name":"B"}""")
        }
        val uid2 = json.parseToJsonElement(reg2.bodyAsText()).jsonObject["uid"]!!.jsonPrimitive.content

        val chResponse = client.post("/api/v1/channels/personal") {
            header("Authorization", "Bearer $token1")
            contentType(ContentType.Application.Json)
            setBody("""{"uid":"$uid2"}""")
        }
        val channelId = json.parseToJsonElement(chResponse.bodyAsText()).jsonObject["channelId"]!!.jsonPrimitive.content

        val response = client.get("/api/v1/channels/$channelId/messages?limit=20") {
            header("Authorization", "Bearer $token1")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }
}
