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
import kotlin.test.assertTrue

/**
 * Auth API 集成测试。
 *
 * 需要 Docker 环境运行（PostgreSQL）。
 * 仅在环境变量 RUN_INTEGRATION_TESTS=true 时执行。
 */
@EnabledIf("isIntegrationTestsEnabled")
class AuthApiTest {

    companion object {
        @JvmStatic
        fun isIntegrationTestsEnabled(): Boolean =
            System.getenv("RUN_INTEGRATION_TESTS")?.toBoolean() == true
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `register succeeds with valid data`() = testApplication {
        val response = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"test-reg-user","password":"pass123","name":"Test User"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertNotNull(body["uid"]?.jsonPrimitive?.content)
        assertNotNull(body["accessToken"]?.jsonPrimitive?.content)
        assertEquals("Test User", body["user"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
    }

    @Test
    fun `register fails with missing password`() = testApplication {
        val response = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"no-pass-user","name":"No Pass"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `login succeeds with valid credentials`() = testApplication {
        // Register first
        client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"login-user","password":"pass123","name":"Login User"}""")
        }

        // Login
        val response = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"login-user","password":"pass123"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertNotNull(body["accessToken"]?.jsonPrimitive?.content)
    }

    @Test
    fun `login fails with wrong password`() = testApplication {
        client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"wrong-pass-user","password":"pass123","name":"Wrong Pass"}""")
        }

        val response = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"wrong-pass-user","password":"wrong"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `login fails for non-existent user`() = testApplication {
        val response = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"nonexistent-user","password":"pass123"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `get me with valid token returns user info`() = testApplication {
        val regResponse = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"me-user","password":"pass123","name":"Me User"}""")
        }
        val regBody = json.parseToJsonElement(regResponse.bodyAsText()).jsonObject
        val token = regBody["accessToken"]?.jsonPrimitive?.content ?: ""

        val response = client.get("/api/v1/users/me") {
            header("Authorization", "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("me-user", body["username"]?.jsonPrimitive?.content)
    }

    @Test
    fun `get me without token returns unauthorized`() = testApplication {
        val response = client.get("/api/v1/users/me")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `get me with invalid token returns unauthorized`() = testApplication {
        val response = client.get("/api/v1/users/me") {
            header("Authorization", "Bearer invalid-token-value")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
