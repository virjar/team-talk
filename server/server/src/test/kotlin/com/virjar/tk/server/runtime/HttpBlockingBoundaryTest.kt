package com.virjar.tk.server.runtime

import com.virjar.tk.server.env.ThreadIOGuard
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpBlockingBoundaryTest {
    @Test
    fun `downstream routing executes on the owned blocking worker`() = testApplication {
        val executor = HttpBlockingExecutor(workerCount = 1, queueCapacity = 2)
        application {
            monitor.subscribe(ApplicationStopped) { executor.close() }
            installHttpBlockingBoundary(executor)
            routing {
                get("/thread") {
                    ThreadIOGuard.check("HTTP route probe")
                    call.respondText(Thread.currentThread().name)
                }
            }
        }

        val response = client.get("/thread")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().startsWith("teamtalk-http-blocking-"))
    }

    @Test
    fun `health bypasses saturation while ordinary routes remain admission controlled`() = testApplication {
        val executor = HttpBlockingExecutor(workerCount = 1, queueCapacity = 1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val first = scope.async {
                executor.tryExecute {
                    entered.countDown()
                    release.await()
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = scope.async { executor.tryExecute { Unit } }
            withTimeout(5_000) {
                while (executor.outstandingTaskCount < 2) yield()
            }

            application {
                monitor.subscribe(ApplicationStopped) { executor.close() }
                installHttpBlockingBoundary(executor)
                routing {
                    get(HEALTH_CHECK_PATH) { call.respondText("health") }
                    get("/health-check") { error("rejected route must not run") }
                }
            }

            val healthResponse = client.get("$HEALTH_CHECK_PATH?probe=readiness")
            assertEquals(HttpStatusCode.OK, healthResponse.status)
            assertEquals("health", healthResponse.bodyAsText())
            assertEquals(2, executor.outstandingTaskCount)

            val ordinaryResponse = client.get("/health-check")
            assertEquals(HttpStatusCode.ServiceUnavailable, ordinaryResponse.status)
            assertEquals("1", ordinaryResponse.headers[HttpHeaders.RetryAfter])
            release.countDown()
            first.await()
            second.await()
        } finally {
            release.countDown()
            scope.cancel()
            executor.close()
        }
    }

    @Test
    fun `HTTP worker saturation cancels the exact unread request channel`() = testApplication {
        val executor = HttpBlockingExecutor(workerCount = 1, queueCapacity = 1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val first = scope.async {
                executor.tryExecute {
                    entered.countDown()
                    release.await()
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = scope.async { executor.tryExecute { Unit } }
            withTimeout(5_000) {
                while (executor.outstandingTaskCount < 2) yield()
            }
            application {
                monitor.subscribe(ApplicationStopped) { executor.close() }
                installHttpBlockingBoundary(executor)
                routing { post("/rejected-body") { error("rejected route must not run") } }
            }

            coroutineScope {
                val requestBody = ByteChannel()
                val producerTerminal = CompletableDeferred<Throwable?>()
                val producer = launch {
                    var terminal: Throwable? = null
                    try {
                        while (true) {
                            requestBody.writeFully(ByteArray(1024))
                            delay(10)
                        }
                    } catch (failure: Throwable) {
                        terminal = failure
                    } finally {
                        producerTerminal.complete(terminal)
                    }
                }
                val request = launch {
                    client.post("/rejected-body") {
                        setBody(object : OutgoingContent.ReadChannelContent() {
                            override val contentType = ContentType.Application.OctetStream
                            override val contentLength = 1024L * 1024L
                            override fun readFrom(): ByteReadChannel = requestBody
                        })
                    }
                }
                try {
                    val terminal = withTimeout(5_000) { producerTerminal.await() }
                    assertTrue(terminal is kotlinx.coroutines.CancellationException)
                    assertEquals(HTTP_BLOCKING_OVERLOAD_BODY_CANCELLATION_MESSAGE, terminal?.message)
                } finally {
                    requestBody.cancel()
                    request.cancelAndJoin()
                    producer.cancelAndJoin()
                }
            }
            release.countDown()
            first.await()
            second.await()
        } finally {
            release.countDown()
            scope.cancel()
            executor.close()
        }
    }
}
