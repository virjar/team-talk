package com.virjar.tk.server.runtime

import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.netty.channel.ChannelOption
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real HTTP sockets exercise the same static-file pipeline used by installer downloads. */
@Timeout(20)
class ProtectedHttpIdleTimeoutTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `static file download keeps progressing beyond the connection idle timeout`() {
        val expected = ByteArray(512 * 1024) { (it % 251).toByte() }
        File(directory, "installer.zip").writeBytes(expected)

        withServer { port, _, _, _ ->
            connection(port).use { socket ->
                socket.getOutputStream().write(
                    "GET /downloads/installer.zip HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                        .toByteArray(),
                )
                val input = socket.getInputStream().buffered()
                assertTrue(input.readHttpLine().startsWith("HTTP/1.1 200"))
                val headers = generateSequence { input.readHttpLine().takeIf { it.isNotEmpty() } }.toList()
                assertTrue(headers.any { it.equals("Content-Length: ${expected.size}", ignoreCase = true) })
                val received = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                val started = System.nanoTime()
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    received.write(buffer, 0, count)
                    // Socket buffers are small on both sides, so this throttles actual server writes.
                    Thread.sleep(40)
                }
                assertTrue(System.nanoTime() - started > TimeUnit.SECONDS.toNanos(2))
                assertContentEquals(expected, received.toByteArray(), "a slow download must not be truncated")
            }
        }
    }

    @Test
    fun `stalled upload is closed and releases its admitted http call`() {
        withServer { port, executor, entered, finished ->
            connection(port).use { socket ->
                socket.getOutputStream().write(
                    "POST /upload HTTP/1.1\r\nHost: localhost\r\nContent-Length: 100\r\n\r\nx".toByteArray(),
                )
                assertTrue(entered.await(5, TimeUnit.SECONDS), "upload must enter the real HTTP boundary")
                assertEquals(1, executor.outstandingTaskCount)
                assertEquals(-1, socket.getInputStream().read(), "an idle partial body must be disconnected")
                assertTrue(finished.await(5, TimeUnit.SECONDS), "disconnection must unblock body reception")
            }
        }
    }

    private fun withServer(block: (Int, HttpBlockingExecutor, CountDownLatch, CountDownLatch) -> Unit) {
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        HttpBlockingExecutor(workerCount = 1, queueCapacity = 1).use { executor ->
            val server = embeddedServer(Netty, environment = applicationEnvironment {}, configure = {
                configureProtectedHttpEventLoops(connectionIdleTimeoutSeconds = 1)
                connector {
                    host = "127.0.0.1"
                    port = 0
                }
                val protectedBootstrap = configureBootstrap
                configureBootstrap = {
                    protectedBootstrap()
                    childOption(ChannelOption.SO_SNDBUF, 8192)
                }
            }) {
                installHttpBlockingBoundary(executor)
                routing {
                    staticFiles("/downloads", directory)
                    post("/upload") {
                        entered.countDown()
                        try {
                            call.receiveText()
                            call.respondText("uploaded")
                        } finally {
                            finished.countDown()
                        }
                    }
                }
            }
            try {
                server.start(wait = false)
                val port = runBlocking { server.engine.resolvedConnectors().single().port }
                block(port, executor, entered, finished)
            } finally {
                server.stop(gracePeriodMillis = 0, timeoutMillis = 5_000)
            }
        }
    }

    private fun connection(port: Int): Socket = Socket().apply {
        receiveBufferSize = 8192
        soTimeout = 5_000
        connect(InetSocketAddress("127.0.0.1", port), 5_000)
    }

    private fun InputStream.readHttpLine(): String = buildString {
        while (true) {
            val next = read()
            check(next >= 0) { "HTTP response ended before its headers" }
            if (next == '\n'.code) break
            if (next != '\r'.code) append(next.toChar())
        }
    }
}
