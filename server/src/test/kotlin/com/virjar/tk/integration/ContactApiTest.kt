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
 * Contact API 集成测试。
 */
@EnabledIf("isIntegrationTestsEnabled")
class ContactApiTest {

    companion object {
        @JvmStatic
        fun isIntegrationTestsEnabled(): Boolean =
            System.getenv("RUN_INTEGRATION_TESTS")?.toBoolean() == true
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `friend apply and accept flow`() = testApplication {
        // Register two users
        val reg1 = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"contact-a","password":"pass123","name":"A"}""")
        }
        val (uid1, token1) = json.parseToJsonElement(reg1.bodyAsText()).let {
            it.jsonObject["uid"]!!.jsonPrimitive.content to it.jsonObject["accessToken"]!!.jsonPrimitive.content
        }
        val reg2 = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"contact-b","password":"pass123","name":"B"}""")
        }
        val (_, token2) = json.parseToJsonElement(reg2.bodyAsText()).let {
            it.jsonObject["uid"]!!.jsonPrimitive.content to it.jsonObject["accessToken"]!!.jsonPrimitive.content
        }

        // Apply
        val applyResponse = client.post("/api/v1/contacts/apply") {
            header("Authorization", "Bearer $token1")
            contentType(ContentType.Application.Json)
            setBody("""{"toUid":"${json.parseToJsonElement(reg2.bodyAsText()).jsonObject["uid"]!!.jsonPrimitive.content}","remark":"hi"}""")
        }
        assertEquals(HttpStatusCode.Created, applyResponse.status)
        val token = json.parseToJsonElement(applyResponse.bodyAsText()).jsonObject["token"]?.jsonPrimitive?.content
        assertNotNull(token)

        // Accept
        val acceptResponse = client.post("/api/v1/contacts/accept") {
            header("Authorization", "Bearer $token2")
            contentType(ContentType.Application.Json)
            setBody("""{"token":"$token"}""")
        }
        assertEquals(HttpStatusCode.OK, acceptResponse.status)
    }

    @Test
    fun `get friend list returns list`() = testApplication {
        val reg1 = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"flist-a","password":"pass123","name":"A"}""")
        }
        val token1 = json.parseToJsonElement(reg1.bodyAsText()).jsonObject["accessToken"]!!.jsonPrimitive.content

        val response = client.get("/api/v1/contacts") {
            header("Authorization", "Bearer $token1")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `get friend applies returns list`() = testApplication {
        val reg1 = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"apply-a","password":"pass123","name":"A"}""")
        }
        val token1 = json.parseToJsonElement(reg1.bodyAsText()).jsonObject["accessToken"]!!.jsonPrimitive.content

        val response = client.get("/api/v1/contacts/applies") {
            header("Authorization", "Bearer $token1")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `blacklist add and remove`() = testApplication {
        val reg1 = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"bl-a","password":"pass123","name":"A"}""")
        }
        val (uid1, token1) = json.parseToJsonElement(reg1.bodyAsText()).let {
            it.jsonObject["uid"]!!.jsonPrimitive.content to it.jsonObject["accessToken"]!!.jsonPrimitive.content
        }
        val reg2 = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"bl-b","password":"pass123","name":"B"}""")
        }
        val uid2 = json.parseToJsonElement(reg2.bodyAsText()).jsonObject["uid"]!!.jsonPrimitive.content

        // Add to blacklist
        val addResponse = client.post("/api/v1/blacklist/$uid2") {
            header("Authorization", "Bearer $token1")
        }
        assertEquals(HttpStatusCode.OK, addResponse.status)

        // Get blacklist
        val listResponse = client.get("/api/v1/blacklist") {
            header("Authorization", "Bearer $token1")
        }
        assertEquals(HttpStatusCode.OK, listResponse.status)

        // Remove from blacklist
        val removeResponse = client.delete("/api/v1/blacklist/$uid2") {
            header("Authorization", "Bearer $token1")
        }
        assertEquals(HttpStatusCode.OK, removeResponse.status)
    }
}
