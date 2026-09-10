package com.virjar.tk.server.infra.push

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 华为/荣耀/OPPO/vivo/魅族发送器对真实 HTTP 形态的约束；令牌缓存行为单独验证。 */
class OemPushHttpClientsTest {
    private val huawei = OemPushVendorConfiguration(
        OemPushVendors.HUAWEI, "huawei-secret", "com.example.privateapp", "TeamTalk 团队",
        appId = "123456789", channelId = "huawei-chat",
    )
    private val honor = OemPushVendorConfiguration(
        OemPushVendors.HONOR, "honor-secret", "com.example.privateapp", "TeamTalk 团队",
        appId = "987654321", channelId = "honor-chat",
    )
    private val oppo = OemPushVendorConfiguration(
        OemPushVendors.OPPO, "oppo-secret", "com.example.privateapp", "TeamTalk 团队",
        appKey = "8899aa", channelId = "oppo-chat",
    )
    private val vivo = OemPushVendorConfiguration(
        OemPushVendors.VIVO, "vivo-secret", "com.example.privateapp", "团队通",
        appId = "10004", appKey = "25509283-3767-4b9e-83fe-b6e55ac6243e", category = "IM",
    )
    private val meizu = OemPushVendorConfiguration(
        OemPushVendors.MEIZU, "meizu-secret", "com.example.privateapp", "TeamTalk 团队",
        appId = "10000", appKey = "mk-fixture",
    )
    private val notification = OemPushNotification(
        OemPushVendors.HUAWEI, "fixture-token", "f".repeat(64), "dataset-identity",
        "user-identity", "chat-identity", "fixture_job-1",
    )

    private fun notification(vendor: String) = OemPushNotification(
        vendor, "fixture-token", "f".repeat(64), "dataset-identity",
        "user-identity", "chat-identity", "fixture_job-1",
    )

    private data class Case(val body: String, val reason: String, val invalid: Boolean, val refresh: Boolean)

    @Test
    fun `huawei send posts bearer oauth token and snake_case notification intent`() = runBlocking {
        val send = CompletableDeferred<Pair<String?, String>>()
        server("/v1/123456789/messages:send") { exchange ->
            send.complete(exchange.requestHeaders.getFirst("Authorization") to exchange.requestBody.readBytes().decodeToString())
            respond(exchange, 200, """{"code":"80000000","msg":"success","requestId":"fixture"}""")
        }.use { server ->
            val result = sendHuaweiStylePushRequest(
                huawei, notification, server.endpoint, server.endpoint,
            ) { "fixture-oauth-token" }
            assertTrue(result.accepted)
            val (authorization, body) = send.await()
            assertEquals("Bearer fixture-oauth-token", authorization)
            assertTrue(body.contains("\"token\":[\"fixture-token\"]"))
            assertTrue(body.contains("\"channel_id\":\"huawei-chat\""))
            assertTrue(body.contains("\"click_action\":{\"type\":1,\"intent\":\"intent://message/"))
            assertTrue(body.contains("action=com.example.privateapp.OPEN_MESSAGE"))
            assertTrue(body.contains("\"notify_id\":0"))
            assertTrue(body.contains("\"foreground_show\":false"))
            assertTrue(body.contains("\"ttl\":\"3600s\""))
            assertTrue(body.contains("\"title\":\"TeamTalk 团队\""))
            assertTrue(body.contains("你有新的未读消息，点击查看"))
        }
    }

    @Test
    fun `honor keeps the mirrored structure with camel_case fields`() = runBlocking {
        val send = CompletableDeferred<String>()
        server("/v2/987654321/messages:send") { exchange ->
            send.complete(exchange.requestBody.readBytes().decodeToString())
            respond(exchange, 200, """{"code":"80000000","msg":"success"}""")
        }.use { server ->
            val result = sendHuaweiStylePushRequest(
                honor, notification(OemPushVendors.HONOR), server.endpoint, server.endpoint,
            ) { "fixture-oauth-token" }
            assertTrue(result.accepted)
            val body = send.await()
            assertTrue(body.contains("\"clickAction\":{\"type\":1,\"intent\":\"intent://message/"))
            assertTrue(body.contains("\"channelId\":\"honor-chat\""))
            assertTrue(body.contains("\"notifyId\":0"))
            assertTrue(body.contains("\"foregroundShow\":false"))
            assertFalse(body.contains("channel_id"))
        }
    }

    @Test
    fun `huawei style code mapping separates invalid tokens expired oauth and configuration`() = runBlocking {
        val cases = listOf(
            Case("""{"code":"80300007","msg":"token invalid"}""", "INVALID_REGISTRATION", true, false),
            Case("""{"code":"80100003","msg":"wrong message body"}""", "PROVIDER_CONFIGURATION_REJECTED", false, false),
            Case("""{"code":"80200003","msg":"oauth token expired"}""", "PROVIDER_AUTH_TOKEN_EXPIRED", false, true),
            Case("""{"code":"80300006","msg":"duplicate"}""", "PROVIDER_RATE_LIMITED", false, false),
            Case("""{"code":"80100000","msg":"partial","illegal_tokens":["fixture-token"]}""", "INVALID_REGISTRATION", true, false),
            Case("""{"code":"80444444","msg":"unknown"}""", "PROVIDER_REJECTED", false, false),
        )
        for (fixtureCase in cases) {
            server("/v1/123456789/messages:send") { exchange ->
                exchange.requestBody.readBytes()
                respond(exchange, 200, fixtureCase.body)
            }.use { server ->
                val result = sendHuaweiStylePushRequest(huawei, notification, server.endpoint, server.endpoint) { "t" }
                assertFalse(result.accepted)
                assertEquals(fixtureCase.reason, result.reason)
                assertEquals(fixtureCase.invalid, result.invalidRegistration)
                assertEquals(fixtureCase.refresh, result.refreshToken)
            }
        }
    }

    @Test
    fun `huawei oauth token exchange posts client credentials`() = runBlocking {
        val request = CompletableDeferred<Map<String, String>>()
        server("/oauth2/v3/token") { exchange ->
            request.complete(readForm(exchange))
            respond(exchange, 200, """{"access_token":"fixture-token","expires_in":3600}""")
        }.use { server ->
            assertEquals("fixture-token", fetchHuaweiStyleAccessToken(huawei, server.endpoint)?.first)
            val fields = request.await()
            assertEquals("client_credentials", fields["grant_type"])
            assertEquals("123456789", fields["client_id"])
            assertEquals("huawei-secret", fields["client_secret"])
        }
    }

    @Test
    fun `oppo auth signs with sha256 and unicast posts intent click through message json`() = runBlocking {
        val auth = CompletableDeferred<Map<String, String>>()
        val send = CompletableDeferred<Map<String, String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/auth") { exchange ->
            auth.complete(readForm(exchange))
            respond(exchange, 200, """{"code":0,"msg":"ok","data":{"auth_token":"fixture-oppo-token","create_time":1}}""")
        }
        server.createContext("/unicast") { exchange ->
            send.complete(readForm(exchange))
            respond(exchange, 200, """{"code":0,"msg":"ok","data":{"message_id":"m-1"}}""")
        }
        server.start()
        try {
            val token = fetchOppoAuthToken(oppo, URI("http://127.0.0.1:${server.address.port}/auth"))
            assertEquals("fixture-oppo-token", token?.first)
            val (timestamp, sign) = auth.await().let { it.getValue("timestamp") to it.getValue("sign") }
            assertEquals("8899aa", auth.await()["app_key"])
            assertEquals(sha256Hex("8899aa$timestamp${oppo.appSecret}"), sign)

            val result = sendOppoPushRequest(
                oppo, notification(OemPushVendors.OPPO),
                URI("http://127.0.0.1:${server.address.port}/auth"),
                URI("http://127.0.0.1:${server.address.port}/unicast"),
            ) { token?.first }
            assertTrue(result.accepted)
            val fields = send.await()
            assertEquals("fixture-oppo-token", fields["auth_token"])
            assertEquals("2", fields["target_type"])
            assertEquals("fixture-token", fields["target_value"])
            val message = fields.getValue("message")
            assertTrue(message.contains("\"channel_id\":\"oppo-chat\""))
            assertTrue(message.contains("\"click_action_type\":5"))
            assertTrue(message.contains("\"click_action_url\":\"intent://message/"))
            assertTrue(message.contains("\"app_message_id\":\"fixture_job-1\""))
            assertTrue(message.contains("\"off_line_ttl\":3600"))
        } finally { server.stop(0) }
    }

    @Test
    fun `vivo auth signs with md5 and send posts intent skip with category`() = runBlocking {
        val auth = CompletableDeferred<String>()
        val send = CompletableDeferred<Pair<String?, String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/auth") { exchange ->
            auth.complete(exchange.requestBody.readBytes().decodeToString())
            respond(exchange, 200, """{"result":0,"desc":"请求成功","authToken":"fixture-vivo-token"}""")
        }
        server.createContext("/send") { exchange ->
            send.complete(exchange.requestHeaders.getFirst("authToken") to exchange.requestBody.readBytes().decodeToString())
            respond(exchange, 200, """{"result":0,"desc":"请求成功","taskId":"342982"}""")
        }
        server.start()
        try {
            val token = fetchVivoAuthToken(vivo, URI("http://127.0.0.1:${server.address.port}/auth"))
            assertEquals("fixture-vivo-token", token?.first)
            val authBody = auth.await()
            assertTrue(authBody.contains("\"appId\":10004"))
            assertTrue(authBody.contains("\"appKey\":\"25509283-3767-4b9e-83fe-b6e55ac6243e\""))
            val timestamp = Regex("\"timestamp\":(\\d+)").find(authBody)!!.groupValues[1].toLong()
            assertEquals(md5Hex("10004${vivo.appKey}$timestamp${vivo.appSecret}"), Regex("\"sign\":\"([a-f0-9]{32})\"").find(authBody)!!.groupValues[1])

            val result = sendVivoPushRequest(
                vivo, notification(OemPushVendors.VIVO),
                URI("http://127.0.0.1:${server.address.port}/auth"),
                URI("http://127.0.0.1:${server.address.port}/send"),
            ) { token?.first }
            assertTrue(result.accepted)
            val (header, body) = send.await()
            assertEquals("fixture-vivo-token", header)
            assertTrue(body.contains("\"regId\":\"fixture-token\""))
            assertTrue(body.contains("\"skipType\":4"))
            assertTrue(body.contains("action=com.example.privateapp.OPEN_MESSAGE"))
            assertTrue(body.contains("\"category\":\"IM\""))
            assertTrue(body.contains("\"requestId\":\"fixture_job-1\""))
            assertTrue(body.contains("\"pushMode\":0"))
        } finally { server.stop(0) }
    }

    @Test
    fun `vivo result mapping invalidates registration and expired tokens`() = runBlocking {
        val cases = listOf(
            """{"result":10302,"desc":"regId 不合法"}""" to "INVALID_REGISTRATION",
            """{"result":10000,"desc":"鉴权失败"}""" to "PROVIDER_AUTH_TOKEN_EXPIRED",
            """{"result":10070,"desc":"发送总量超出限制"}""" to "PROVIDER_QUOTA_EXCEEDED",
            """{"result":10072,"desc":"发送速度过快"}""" to "PROVIDER_RATE_LIMITED",
        )
        for ((body, reason) in cases) {
            server("/send") { exchange ->
                exchange.requestBody.readBytes()
                respond(exchange, 200, body)
            }.use { server ->
                val result = sendVivoPushRequest(vivo, notification(OemPushVendors.VIVO), server.endpoint, server.endpoint) { "t" }
                assertFalse(result.accepted)
                assertEquals(reason, result.reason)
            }
        }
    }

    @Test
    fun `meizu sign covers sorted raw parameters and value reports invalid push ids`() = runBlocking {
        val request = CompletableDeferred<Map<String, String>>()
        server("/pushByPushId") { exchange ->
            request.complete(readForm(exchange))
            respond(exchange, 200, """{"code":"200","message":"","value":{},"redirect":""}""")
        }.use { server ->
            val result = sendMeizuPushRequest(
                meizu, notification(OemPushVendors.MEIZU), server.endpoint,
            )
            assertTrue(result.accepted, "reason=${result.reason}")
            val fields = request.await()
            assertEquals("10000", fields["appId"])
            assertEquals("fixture-token", fields["pushIds"])
            val messageJson = fields.getValue("messageJson")
            assertTrue(messageJson.contains("\"noticeMsgType\":1"))
            assertTrue(messageJson.contains("\"clickType\":0"))
            assertTrue(messageJson.contains("\"notifyKey\":\"fixture_\""))
            val raw = sortedMapOf(
                "appId" to "10000",
                "pushIds" to "fixture-token",
                "messageJson" to messageJson,
            ).entries.joinToString("") { "${it.key}=${it.value}" } + meizu.appSecret
            assertEquals(md5Hex(raw), fields["sign"])
        }
    }

    @Test
    fun `meizu value map and top level codes reject with precise reasons`() = runBlocking {
        val cases = listOf(
            """{"code":"200","value":{"fixture-token":"110002"}}""" to "INVALID_REGISTRATION",
            """{"code":"200","value":{"fixture-token":"520"}}""" to null,
            """{"code":"1006","message":"签名认证失败"}""" to "PROVIDER_CONFIGURATION_REJECTED",
            """{"code":"110010","message":"推送速率过快"}""" to "PROVIDER_RATE_LIMITED",
            """{"code":"1003","message":"服务器忙"}""" to "PROVIDER_UNAVAILABLE",
        )
        for ((body, reason) in cases) {
            server("/pushByPushId") { exchange ->
                exchange.requestBody.readBytes()
                respond(exchange, 200, body)
            }.use { server ->
                val result = sendMeizuPushRequest(meizu, notification(OemPushVendors.MEIZU), server.endpoint)
                if (reason == null) assertTrue(result.accepted) else {
                    assertFalse(result.accepted)
                    assertEquals(reason, result.reason)
                }
            }
        }
    }

    @Test
    fun `token caches reuse a live token refetch after expiry and drop on invalidation`() = runBlocking {
        var now = 1_000L
        var fetched = 0
        val cache = OemPushTokenCache()
        suspend fun token(): String? = cache.accessToken(now) { fetched++; "token-$fetched" to now + 3_600_000L }
        assertEquals("token-1", token())
        assertEquals("token-1", token())
        now += 3_600_000L
        assertEquals("token-2", token())
        cache.invalidate()
        assertEquals("token-3", token())

        // 刷新失败时保留旧令牌，交由厂商拒绝触发 refreshToken 路径。
        val sticky = OemPushTokenCache()
        assertEquals("live", sticky.accessToken(0) { "live" to 10_000 })
        now = 20_000
        assertEquals("live", sticky.accessToken(now) { null }, "failed refresh keeps the stale token for provider rejection")
    }

    @Test
    fun `missing vendor token fails closed before any send request`() = runBlocking {
        server("/send") { exchange ->
            exchange.requestBody.readBytes()
            respond(exchange, 200, """{"result":0}""")
        }.use { server ->
            val result = sendVivoPushRequest(vivo, notification(OemPushVendors.VIVO), server.endpoint, server.endpoint) { null }
            assertFalse(result.accepted)
            assertEquals("PROVIDER_AUTH_TOKEN_UNAVAILABLE", result.reason)
        }
    }

    private fun readForm(exchange: HttpExchange): Map<String, String> = exchange.requestBody.use { input ->
        input.readAllBytes().toString(Charsets.UTF_8).split('&').associate { field ->
            val parts = field.split('=', limit = 2)
            URLDecoder.decode(parts[0], Charsets.UTF_8) to URLDecoder.decode(parts[1], Charsets.UTF_8)
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun server(path: String, handler: (HttpExchange) -> Unit): Fixture {
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        httpServer.createContext(path) { exchange ->
            try { handler(exchange) } finally { exchange.close() }
        }
        httpServer.start()
        return Fixture(URI("http://127.0.0.1:${httpServer.address.port}$path"), httpServer)
    }

    private class Fixture(val endpoint: URI, private val server: HttpServer) : AutoCloseable {
        override fun close() { server.stop(0) }
    }
}
