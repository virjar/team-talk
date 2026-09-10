package com.virjar.tk.server.infra.push

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XiaomiPushHttpClientTest {
    private val configuration = OemPushVendorConfiguration(
        OemPushVendors.XIAOMI, "fixture-secret", "com.example.privateapp", "私有 TeamTalk",
        channelId = "private-chat", templateId = "fixed-template",
    )
    private val notification = OemPushNotification(
        OemPushVendors.XIAOMI, "registration+with/slash=", "a".repeat(64), "dataset-identity",
        "user-identity", "chat-identity", "fixture_job-1",
    )

    @Test
    fun `real HTTP request uses fixed generic content and explicit private application intent`() = runBlocking {
        val request = CompletableDeferred<Pair<String?, Map<String, String>>>()
        fixture { exchange ->
            request.complete(exchange.requestHeaders.getFirst("Authorization") to readForm(exchange))
            respond(exchange, 200, """{"result":"ok","code":0,"data":{"id":"fixture-message"}}""")
        }.use { server ->
            assertTrue(sendXiaomiPushRequest(configuration, notification, server.endpoint).accepted)
            val (authorization, fields) = request.await()
            assertEquals("key=fixture-secret", authorization)
            assertEquals(notification.registrationId, fields["registration_id"])
            assertEquals(configuration.packageName, fields["restricted_package_name"])
            assertEquals("unread", fields["payload"])
            assertEquals("私有 TeamTalk", fields["title"])
            assertEquals("你有新的未读消息，点击查看", fields["description"])
            assertEquals("{}", fields["extra.template_param"])
            assertEquals("private-chat", fields["extra.channel_id"])
            assertEquals("fixed-template", fields["extra.template_id"])
            assertEquals("0", fields["extra.notify_foreground"])
            assertEquals("0", fields["notify_id"])
            assertEquals(notification.jobKey, fields["extra.jobkey"])
            assertEquals(
                "intent://message/${notification.deploymentFingerprint}/dataset-identity/user-identity/chat-identity" +
                    "#Intent;scheme=teamtalk-local;action=com.example.privateapp.OPEN_MESSAGE;" +
                    "component=com.example.privateapp/com.virjar.tk.android.MainActivity;launchFlags=0x24000000;end",
                fields["extra.intent_uri"],
            )
        }
    }

    @Test
    fun `HTTP success alone does not accept a push or invalidate an offline device`() = runBlocking {
        val cases = listOf(
            Triple(200, """{"result":"error","code":66007,"reason":"private echo"}""", "INVALID_REGISTRATION"),
            Triple(200, """{"result":"error","code":65003,"reason":"private echo"}""", "PROVIDER_REJECTED"),
            Triple(200, """{"result":"error","code":10002}""", "PROVIDER_UNAVAILABLE"),
            Triple(200, """{"result":"ok","code":"0"}""", "INVALID_RESPONSE"),
            Triple(200, """{"result":"error","code":0}""", "INVALID_RESPONSE"),
            Triple(200, "not-json private echo", "INVALID_RESPONSE"),
            Triple(401, "private echo", "HTTP_AUTHENTICATION_FAILED"),
            Triple(429, "private echo", "HTTP_RATE_LIMITED"),
            Triple(503, "private echo", "HTTP_UNAVAILABLE"),
            Triple(302, "private echo", "HTTP_REJECTED"),
        )
        for ((status, body, expectedReason) in cases) {
            fixture { exchange ->
                readForm(exchange)
                if (status == 302) exchange.responseHeaders.add("Location", "http://127.0.0.1:1/must-not-follow")
                respond(exchange, status, body)
            }.use { server ->
                val result = sendXiaomiPushRequest(configuration, notification, server.endpoint)
                assertFalse(result.accepted)
                assertEquals(expectedReason, result.reason)
                assertEquals(expectedReason == "INVALID_REGISTRATION", result.invalidRegistration)
            }
        }
    }

    @Test
    fun `chunked oversized responses are rejected before JSON parsing`() = runBlocking {
        fixture { exchange ->
            readForm(exchange)
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(ByteArray(32 * 1024) { 'x'.code.toByte() }) }
        }.use { server ->
            val result = sendXiaomiPushRequest(configuration, notification, server.endpoint)
            assertFalse(result.accepted)
            assertEquals("RESPONSE_TOO_LARGE", result.reason)
            assertFalse(result.invalidRegistration)
        }
    }

    @Test
    fun `caller cancellation does not wait for a stalled body or become provider rejection`() = runBlocking {
        val receiving = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        fixture { exchange ->
            readForm(exchange)
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write('{'.code)
            exchange.responseBody.flush()
            receiving.complete(Unit)
            release.await(5, TimeUnit.SECONDS)
        }.use { server ->
            val request = async(Dispatchers.Default) {
                sendXiaomiPushRequest(configuration, notification, server.endpoint)
            }
            try {
                withTimeout(5_000) { receiving.await() }
                withTimeout(2_000) { request.cancelAndJoin() }
                assertTrue(request.isCancelled)
            } finally {
                release.countDown()
                request.cancelAndJoin()
            }
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

    private fun fixture(handler: (HttpExchange) -> Unit): Fixture {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/regid") { exchange ->
            try { handler(exchange) } finally { exchange.close() }
        }
        server.start()
        return Fixture(server)
    }

    private class Fixture(private val server: HttpServer) : AutoCloseable {
        val endpoint: URI = URI("http://127.0.0.1:${server.address.port}/regid")
        override fun close() { server.stop(0) }
    }
}
