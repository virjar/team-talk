package com.virjar.tk.shared.client

import com.virjar.tk.shared.testkit.FakeLocalCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(InternalCoroutinesApi::class, ExperimentalForInheritanceCoroutinesApi::class)
class ClientSessionRecoveryShutdownTest {
    @Test
    fun `session close drains cancelled recovery before closing its cache`() {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cacheClosed = AtomicBoolean()
        val cleanupRead = CompletableFuture<Unit>()
        val delegate = FakeLocalCache()
        val recoveryFlow = recoveryChanges {
            entered.countDown()
            try {
                awaitCancellation()
            } finally {
                cancelled.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                // 模拟取消到达时仍在进行的同步缓存工作；关闭必须等到它真正退出。
                runCatching { delegate.getConversations() }
                    .fold({ cleanupRead.complete(Unit) }, cleanupRead::completeExceptionally)
            }
        }
        val cache = object : LocalCache by delegate {
            override val chatDrafts = object : LocalChatDrafts by delegate.chatDrafts {
                override val changes = recoveryFlow
            }
            override fun close() {
                cacheClosed.set(true)
                delegate.close()
            }
        }
        val fixture = openOfflineSession(cache)
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val close = executor.submit { fixture.session.close() }
            assertTrue(cancelled.await(5, TimeUnit.SECONDS))
            assertFalse(cacheClosed.get(), "cancel() alone must not release a worker's cache")
            assertFalse(close.isDone)
            release.countDown()
            close.get(5, TimeUnit.SECONDS)
            cleanupRead.get(5, TimeUnit.SECONDS)
            assertTrue(cacheClosed.get())
            assertNull(fixture.session.resourceRetirementFailure)
        } finally {
            release.countDown()
            fixture.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `recovery auth notification can synchronously close the same session without waiting for itself`() {
        val start = CompletableDeferred<Unit>()
        val notification = SessionHttpAuthExpiredRouter()
        val delegate = FakeLocalCache()
        val cache = object : LocalCache by delegate {
            override val chatDrafts = object : LocalChatDrafts by delegate.chatDrafts {
                override val changes = recoveryChanges {
                    start.await()
                    notification.reportFromRecovery("rejected-test-bearer")
                    awaitCancellation()
                }
            }
        }
        val fixture = openOfflineSession(cache)
        val closed = CompletableFuture<Unit>()
        val binding = notification.bind {
            runCatching { fixture.session.close(SessionEndReason.AUTH_REVOKED) }
                .fold({ closed.complete(Unit) }, closed::completeExceptionally)
        }
        try {
            binding.activate()
            start.complete(Unit)
            closed.get(5, TimeUnit.SECONDS)
            assertTrue(fixture.session.lifecyclePhase == SessionLifecyclePhase.CLOSED)
            assertNull(fixture.session.resourceRetirementFailure)
        } finally {
            binding.close()
            notification.close()
            fixture.close()
        }
    }

    private fun recoveryChanges(onCollect: suspend () -> Nothing): StateFlow<Long> =
        object : StateFlow<Long> by MutableStateFlow(0L) {
            override suspend fun collect(collector: FlowCollector<Long>): Nothing = onCollect()
        }

    private fun openOfflineSession(cache: LocalCache): Fixture = runBlocking {
        val client = ImClient()
        try {
            val user = UserSession().apply {
                restorePersistedLogin("shutdown-owner", "offline-refresh", TEST_SYNC_DATASET_ID)
            }
            client.prepareAuthentication(
                uid = user.uid,
                token = "offline-refresh",
                deviceId = "shutdown-test",
                deviceName = "Shutdown test",
                host = "203.0.113.1",
                port = 5100,
            )
            withTimeout(5_000) { client.awaitTransportOwnerStart() }
            Fixture(client, createSession(
                imClient = client,
                userSession = user,
                deploymentIdentity = DeploymentIdentity.from("203.0.113.1", 5100, "https://offline.test.example"),
                createCache = { _, _, _ -> cache },
                deviceId = "shutdown-test",
                logUploadEnabled = false,
            ))
        } catch (failure: Throwable) {
            client.destroy()
            throw failure
        }
    }

    private class Fixture(val client: ImClient, val session: ClientSession) : AutoCloseable {
        override fun close() {
            try { session.close() } finally { client.destroy() }
        }
    }
}
