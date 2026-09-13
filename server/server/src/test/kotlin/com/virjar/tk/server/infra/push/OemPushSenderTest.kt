package com.virjar.tk.server.infra.push

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OemPushSenderTest {
    @Test
    fun `same vendor application owners fetch distinct tokens and closing one preserves the other`() = runBlocking {
        val first = configuration("11")
        val second = configuration("22")
        val credentials = CopyOnWriteArrayList<Map<String, String>>()
        val authorizations = CopyOnWriteArrayList<String?>()
        Fixture(
            token = { exchange ->
                val form = readForm(exchange)
                credentials += form
                respond(exchange, """{"access_token":"token-${form.getValue("client_id")}","expires_in":3600}""")
            },
            send = { exchange ->
                authorizations += exchange.requestHeaders.getFirst("Authorization")
                exchange.requestBody.readAllBytes()
                respond(exchange, """{"code":"80000000"}""")
            },
        ).use { fixture ->
            owner(first).use { senderA ->
                owner(second).use { senderB ->
                    assertTrue(fixture.send(senderA, first).accepted)
                    assertTrue(fixture.send(senderB, second).accepted)
                    assertTrue(fixture.send(senderA, first).accepted)
                    assertTrue(fixture.send(senderB, second).accepted)
                    assertEquals(listOf("11", "22"), credentials.map { it["client_id"] })
                    assertEquals(listOf("secret-11", "secret-22"), credentials.map { it["client_secret"] })

                    senderA.close()
                    assertFailsWith<IllegalStateException> { fixture.send(senderA, first) }
                    assertTrue(fixture.send(senderB, second).accepted)
                    assertEquals(2, credentials.size, "B retains its own live token after A closes")
                    assertEquals(
                        listOf<String?>("Bearer token-11", "Bearer token-22", "Bearer token-11", "Bearer token-22", "Bearer token-22"),
                        authorizations.toList(),
                    )
                }
            }
        }
    }

    @Test
    fun `closing an owner terminates its stalled response while another owner can still send`() = runBlocking {
        val first = configuration("11")
        val second = configuration("22")
        val receiving = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        Fixture(
            token = { exchange ->
                val id = readForm(exchange).getValue("client_id")
                respond(exchange, """{"access_token":"token-$id","expires_in":3600}""")
            },
            send = { exchange ->
                exchange.requestBody.readAllBytes()
                if (exchange.requestHeaders.getFirst("Authorization") == "Bearer token-11") {
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.write('{'.code)
                    exchange.responseBody.flush()
                    receiving.complete(Unit)
                    check(release.await(15, TimeUnit.SECONDS)) { "Stalled response fixture was not released" }
                } else {
                    respond(exchange, """{"code":"80000000"}""")
                }
            },
        ).use { fixture ->
            try {
                owner(first).use { senderA ->
                    owner(second).use { senderB ->
                        val pending = async(Dispatchers.IO) { fixture.send(senderA, first) }
                        try {
                            withTimeout(5_000) { receiving.await() }
                            assertTrue(fixture.send(senderB, second).accepted)
                            withTimeout(8_000) { async(Dispatchers.IO) { senderA.close() }.await() }
                            assertFalse(withTimeout(5_000) { pending.await() }.accepted)
                            assertTrue(fixture.send(senderB, second).accepted)
                        } finally {
                            release.countDown()
                            pending.cancel()
                        }
                    }
                }
            } finally {
                release.countDown()
            }
        }
    }

    private fun configuration(appId: String) = OemPushVendorConfiguration(
        OemPushVendors.HUAWEI, "secret-$appId", "com.example.app$appId", "TeamTalk",
        appId = appId, channelId = "chat",
    )

    private fun owner(configuration: OemPushVendorConfiguration) =
        OemPushSender(OemPushConfiguration(mapOf(configuration.vendor to configuration)))

    private class Fixture(token: (HttpExchange) -> Unit, send: (HttpExchange) -> Unit) : AutoCloseable {
        private val executor = Executors.newFixedThreadPool(3)
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@Fixture.executor
            createContext("/token") { exchange -> try { token(exchange) } finally { exchange.close() } }
            createContext("/send") { exchange -> try { send(exchange) } finally { exchange.close() } }
            start()
        }

        suspend fun send(sender: OemPushSender, configuration: OemPushVendorConfiguration): OemPushDeliveryResult =
            sender.sendHuaweiStylePush(
                configuration,
                OemPushNotification(configuration.vendor, "registration", "f".repeat(64), "dataset", "uid", "chat", "job"),
                URI("http://127.0.0.1:${server.address.port}/token"),
                URI("http://127.0.0.1:${server.address.port}/send"),
            )

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
            check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "OEM fixture workers did not terminate" }
        }
    }

    private fun readForm(exchange: HttpExchange): Map<String, String> = exchange.requestBody.use { input ->
        input.readAllBytes().decodeToString().split('&').associate { field ->
            val parts = field.split('=', limit = 2)
            URLDecoder.decode(parts[0], Charsets.UTF_8) to URLDecoder.decode(parts[1], Charsets.UTF_8)
        }
    }

    private fun respond(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
