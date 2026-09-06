package com.virjar.tk.shared.repository

import com.sun.net.httpserver.HttpServer
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.shared.client.ServerConfig
import com.virjar.tk.shared.client.SessionHttpCredentials
import com.virjar.tk.shared.testkit.FakeLocalCache
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class HttpDeploymentCompatibilityTest {
    @Test
    fun `explicit private HTTP deployment is accepted and keeps its own identity`() {
        val http = ServerConfig("http://INTRANET.example:80/teamtalk/", "tcp.example", 5100)
            .deploymentIdentity()
        assertEquals("http://intranet.example/teamtalk", http.httpBaseUrl)
        assertNotEquals(http, ServerConfig("https://intranet.example/teamtalk", "tcp.example", 5100)
            .deploymentIdentity())
        assertEquals("http://192.168.1.20:8080", canonicalHttpServerBase("http://192.168.1.20:8080/"))
        listOf("ftp://intranet.example", "http://user:password@intranet.example", "http://intranet.example?token=x")
            .forEach { assertFailsWith<IllegalArgumentException> { canonicalHttpServerBase(it) } }
    }

    @Test
    fun `files and bot management use authenticated HTTP without a separate secure endpoint gate`() = runBlocking {
        // IPv6 完整写法不属于旧 HTTP 策略的三个例外；使用已存在的本地接口，不添加网卡 alias。
        val server = HttpServer.create(InetSocketAddress("::1", 0), 0)
        val observed = CopyOnWriteArrayList<String?>()
        val bytes = "private HTTP attachment".encodeToByteArray()
        server.createContext("/") { exchange ->
            observed += exchange.requestHeaders.getFirst("Authorization")
            val body = if (exchange.requestURI.path.endsWith("/bots")) "[]".encodeToByteArray() else bytes
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val base = "http://[0:0:0:0:0:0:0:1]:${server.address.port}"
        val credentials = { SessionHttpCredentials("owner", "fixture-token") }
        val files = FileRepository(base, "owner", credentials)
        val cache = FakeLocalCache()
        val bots = GroupBotManagementRepository(base, "owner", credentials, cache)
        try {
            val attachment = Attachment("2026/09/06/example.txt", "example.txt", "text/plain", bytes.size.toLong())
            assertContentEquals(bytes, files.downloadSmall(attachment).getOrThrow())
            assertEquals(emptyList(), bots.list("chat-fixture").getOrThrow())
            assertEquals(listOf("Bearer fixture-token", "Bearer fixture-token"), observed.toList())
        } finally {
            bots.close()
            files.close()
            cache.close()
            server.stop(0)
        }
    }
}
