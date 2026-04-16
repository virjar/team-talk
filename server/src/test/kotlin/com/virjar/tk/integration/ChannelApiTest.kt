package com.virjar.tk.integration

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.condition.EnabledIf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Channel API 集成测试。
 */
@EnabledIf("isIntegrationTestsEnabled")
class ChannelApiTest {

    companion object {
        @JvmStatic
        fun isIntegrationTestsEnabled(): Boolean =
            System.getenv("RUN_INTEGRATION_TESTS")?.toBoolean() == true
    }

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun io.ktor.client.HttpClient.registerUser(username: String): Pair<String, String> {
        val response = post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$username","password":"pass123","name":"$username"}""")
        }
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        return body["uid"]!!.jsonPrimitive.content to body["accessToken"]!!.jsonPrimitive.content
    }

    @Test
    fun `create personal channel succeeds`() = testApplication {
        val (uid1, token1) = client.registerUser("ch-user-a")
        val (uid2, _) = client.registerUser("ch-user-b")

        val response = client.post("/api/v1/channels/personal") {
            header("Authorization", "Bearer $token1")
            contentType(ContentType.Application.Json)
            setBody("""{"uid":"$uid2"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertNotNull(body["channelId"]?.jsonPrimitive?.content)
        assertEquals(1, body["channelType"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun `create group channel succeeds`() = testApplication {
        val (uid1, token1) = client.registerUser("grp-user-a")
        val (uid2, _) = client.registerUser("grp-user-b")
        val (uid3, _) = client.registerUser("grp-user-c")

        val response = client.post("/api/v1/channels") {
            header("Authorization", "Bearer $token1")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Test Group","members":["$uid2","$uid3"]}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertNotNull(body["channelId"]?.jsonPrimitive?.content)
        assertEquals(2, body["channelType"]?.jsonPrimitive?.intOrNull)
        assertEquals("Test Group", body["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `get channel info succeeds`() = testApplication {
        val (uid1, token1) = client.registerUser("getch-user-a")
        val (uid2, _) = client.registerUser("getch-user-b")

        val createResponse = client.post("/api/v1/channels/personal") {
            header("Authorization", "Bearer $token1")
            contentType(ContentType.Application.Json)
            setBody("""{"uid":"$uid2"}""")
        }
        val channelId = json.parseToJsonElement(createResponse.bodyAsText()).jsonObject["channelId"]!!.jsonPrimitive.content

        val response = client.get("/api/v1/channels/$channelId") {
            header("Authorization", "Bearer $token1")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(channelId, body["channelId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `update group channel info succeeds`() = testApplication {
        val (uid1, token1) = client.registerUser("updch-user-a")
        val (uid2, _) = client.registerUser("updch-user-b")

        val createResponse = client.post("/api/v1/channels") {
            header("Authorization", "Bearer $token1")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Original","members":["$uid2"]}""")
        }
        val channelId = json.parseToJsonElement(createResponse.bodyAsText()).jsonObject["channelId"]!!.jsonPrimitive.content

        val response = client.put("/api/v1/channels/$channelId") {
            header("Authorization", "Bearer $token1")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Updated Name","notice":"New notice"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `get channel members succeeds`() = testApplication {
        val (uid1, token1) = client.registerUser("member-user-a")
        val (uid2, _) = client.registerUser("member-user-b")

        val createResponse = client.post("/api/v1/channels") {
            header("Authorization", "Bearer $token1")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Member Group","members":["$uid2"]}""")
        }
        val channelId = json.parseToJsonElement(createResponse.bodyAsText()).jsonObject["channelId"]!!.jsonPrimitive.content

        val response = client.get("/api/v1/channels/$channelId/members") {
            header("Authorization", "Bearer $token1")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }
}
