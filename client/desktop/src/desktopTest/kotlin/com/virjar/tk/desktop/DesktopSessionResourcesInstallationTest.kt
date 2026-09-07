package com.virjar.tk.desktop

import com.virjar.tk.app.navigation.feature.document.DocumentDraftOwnerKey
import com.virjar.tk.app.navigation.feature.document.DocumentDraftPayload
import com.virjar.tk.desktop.media.DesktopMediaDownloader
import com.virjar.tk.desktop.media.DesktopSessionResources
import com.virjar.tk.shared.client.DeploymentIdentity
import com.virjar.tk.shared.client.SessionHttpCredentials
import com.virjar.tk.shared.log.NoopLogger
import java.nio.file.Files
import java.io.Closeable
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopSessionResourcesInstallationTest {
    @Test
    fun `Main UI construction failure closes IO candidate and same owner can retry`() = runBlocking {
        Fixture().use { fixture ->
            val storageThread = AtomicReference<Thread>()
            val mainThread = AtomicReference<Thread>()
            Executors.newSingleThreadExecutor {
                Thread(it, "candidate-storage").also(storageThread::set)
            }.asCoroutineDispatcher().use { storage ->
                Executors.newSingleThreadExecutor {
                    Thread(it, "candidate-main").also(mainThread::set)
                }.asCoroutineDispatcher().use { main ->
                    val discarded = mutableListOf<String>()
                    val installation = DesktopSessionResourcesInstallation(
                        storageDispatcher = storage,
                        createResources = {
                            assertSame(storageThread.get(), Thread.currentThread())
                            fixture.createCandidate()
                        },
                        discardUnboundUi = { ui: String -> discarded += ui },
                    )
                    val constructionFailure = IllegalStateException("navigation construction failed")
                    try {
                        withContext(main) {
                            val failed = installation.install({ true }) {
                                assertSame(mainThread.get(), Thread.currentThread())
                                throw constructionFailure
                            }
                            val reportedFailure = assertIs<DesktopSessionResourcesInstallationResult.Failed>(failed).failure
                            assertIs<IllegalStateException>(reportedFailure)
                            assertEquals(constructionFailure.message, reportedFailure.message)
                            fixture.assertAllCandidatesClosed()
                            val ready = installation.install({ true }) {
                                assertSame(mainThread.get(), Thread.currentThread())
                                "complete navigation"
                            }
                            assertEquals("complete navigation", assertIs<DesktopSessionResourcesInstallationResult.Ready<String>>(ready).uiOwner)
                            assertEquals(2, fixture.candidates.size)
                            assertEquals(2, fixture.leases.snapshot().retainedReferenceCount)
                            assertNull(installation.abandonIfUnbound())
                        }
                        assertEquals(listOf("complete navigation"), discarded)
                        fixture.assertAllCandidatesClosed()
                    } finally {
                        withContext(main) { installation.close() }
                    }
                }
            }
        }
    }

    @Test
    fun `owner replacement while IO opens resources discards candidate before UI construction`() {
        Fixture().use { fixture ->
            val main = ManualDispatcher()
            val storage = ManualDispatcher()
            var current = true
            val scope = CoroutineScope(SupervisorJob() + main)
            val installation = DesktopSessionResourcesInstallation(
                storageDispatcher = storage,
                createResources = fixture::createCandidate,
                discardUnboundUi = { _: String -> error("UI must not exist") },
            )
            var result: DesktopSessionResourcesInstallationResult<String>? = null
            val operation = scope.async {
                result = installation.install({ current }) { error("superseded owner created UI") }
            }
            try {
                main.runNext()
                storage.runNext()
                current = false
                drain(main, storage)
                assertTrue(operation.isCompleted)
                assertSame(DesktopSessionResourcesInstallationResult.Superseded, result)
                fixture.assertAllCandidatesClosed()
            } finally {
                installation.close()
                scope.coroutineContext[Job]?.cancel()
            }
        }
    }

    @Test
    fun `cancelled return to Main closes unpublished writer without deleting stored draft`() {
        Fixture().use { fixture ->
            val main = ManualDispatcher()
            val storage = ManualDispatcher()
            val scope = CoroutineScope(SupervisorJob() + main)
            val installation = DesktopSessionResourcesInstallation(
                storageDispatcher = storage,
                createResources = fixture::createCandidate,
                discardUnboundUi = { _: String -> error("UI must not exist") },
            )
            val operation = scope.async { installation.install({ true }) { error("cancelled caller created UI") } }
            try {
                main.runNext()
                storage.runNext()
                operation.cancel()
                drain(main, storage)
                assertTrue(operation.isCompleted)
                assertTrue(operation.isCancelled)
                fixture.assertAllCandidatesClosed()
            } finally {
                installation.close()
                scope.coroutineContext[Job]?.cancel()
            }
        }
    }

    @Test
    fun `owner recheck after Main construction destroys UI before closing its candidate`() = runBlocking {
        Fixture().use { fixture ->
            var current = true
            var discarded = false
            val installation = DesktopSessionResourcesInstallation(
                createResources = fixture::createCandidate,
                discardUnboundUi = { _: String ->
                    // UI 尚在借用候选时，媒体凭证门禁必须仍有效。
                    assertEquals("test-token", fixture.candidates.single().media.credentialGate.requireAccessToken())
                    discarded = true
                },
            )
            try {
                val result = installation.install({ current }) {
                    current = false
                    "never published"
                }
                assertSame(DesktopSessionResourcesInstallationResult.Superseded, result)
                assertTrue(discarded)
                fixture.assertAllCandidatesClosed()
            } finally {
                installation.close()
            }
        }
    }

    @Test
    fun `cancellation during superseded UI disposal still closes candidate and remains cancellation`() = runBlocking {
        Fixture().use { fixture ->
            var current = true
            val cancellation = CancellationException("UI disposal cancelled")
            val installation = DesktopSessionResourcesInstallation(
                createResources = fixture::createCandidate,
                discardUnboundUi = { _: String -> throw cancellation },
            )
            try {
                val terminal = assertFailsWith<CancellationException> {
                    installation.install({ current }) { current = false; "unpublished" }
                }
                // 协程调试可能为恢复调用栈复制 CancellationException，取消类型与原因应保留。
                assertEquals(cancellation.message, terminal.message)
                fixture.assertAllCandidatesClosed()
            } finally {
                installation.close()
            }
        }
    }

    @Test
    fun `bound draft disposition remains available for later logout upgrade`() = runBlocking {
        Fixture().use { fixture ->
            val installation = DesktopSessionResourcesInstallation(
                createResources = fixture::createCandidate,
                discardUnboundUi = { _: String -> error("bound UI belongs to retirement") },
            )
            try {
                val result = assertIs<DesktopSessionResourcesInstallationResult.Ready<String>>(
                    installation.install({ true }) { "bound UI" },
                )
                assertNotNull(installation.bindLifecycle(result.resources) { Closeable {} })
                assertNull(installation.abandonIfUnbound())
                val draft = fixture.candidates.single().documentDraftPersistence
                assertTrue(draft.retirePreservingDraft())
                installation.close()
                assertEquals(1, fixture.leases.snapshot().retainedReferenceCount)
                assertEquals("retained draft", fixture.storage.content)

                // Compose 的 preserve 边沿之后，AuthController 的 USER_LOGOUT 仍能升级为 discard。
                assertTrue(draft.retireAndDelete())
                assertNull(fixture.storage.content)
                assertEquals(0, fixture.leases.snapshot().retainedReferenceCount)
            } finally {
                installation.close()
                fixture.candidates.forEach { it.documentDraftPersistence.sealPreservedDraft() }
            }
        }
    }

    @Test
    fun `retirement claimed during binding waits until draft responsibility transfers`(): Unit = runBlocking {
        Fixture().use { fixture ->
            val registry = DesktopRetirementBindingRegistry<Any, String>()
            val owner = Any()
            val installation = DesktopSessionResourcesInstallation(
                createResources = fixture::createCandidate,
                discardUnboundUi = { _: String -> error("retirement already owns UI") },
            )
            val ready = assertIs<DesktopSessionResourcesInstallationResult.Ready<String>>(
                installation.install({ true }) { "navigation" },
            )
            val draft = fixture.candidates.single().documentDraftPersistence
            val attemptingClose = CountDownLatch(1)
            val finishedClose = CountDownLatch(1)
            val threadFailure = AtomicReference<Throwable?>()
            val retiree = Thread {
                try {
                    assertEquals("navigation", registry.claim(owner))
                    assertTrue(draft.retirePreservingDraft())
                    attemptingClose.countDown()
                    installation.close()
                } catch (failure: Throwable) {
                    threadFailure.set(failure)
                } finally {
                    finishedClose.countDown()
                }
            }
            var binding: Closeable? = null
            try {
                binding = installation.bindLifecycle(ready.resources) {
                    registry.bind(owner, ready.uiOwner).also {
                        retiree.start()
                        assertTrue(attemptingClose.await(5, TimeUnit.SECONDS))
                        // 强制覆盖旧代码的 bind/mark 窗口：close 已到达安装器锁，仍不能提前 seal 草稿。
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                        while (retiree.state != Thread.State.BLOCKED && retiree.isAlive && System.nanoTime() < deadline) {
                            Thread.yield()
                        }
                        assertEquals(Thread.State.BLOCKED, retiree.state)
                        assertEquals(1L, finishedClose.count)
                    }
                }
                assertNotNull(binding)
                assertTrue(finishedClose.await(5, TimeUnit.SECONDS))
                threadFailure.get()?.let { throw it }
                assertEquals(1, fixture.leases.snapshot().retainedReferenceCount)
                assertTrue(draft.retireAndDelete(), "claim 后送达的注销仍能执行 discard")
                assertNull(fixture.storage.content)
                assertEquals(0, fixture.leases.snapshot().retainedReferenceCount)
                assertNotNull(registry.complete(owner))
            } finally {
                retiree.join(5_000)
                installation.close()
                binding?.close()
            }
        }
    }

    @Test
    fun `draft constructor failure releases already constructed media`() {
        Fixture().use { fixture ->
            val media = fixture.createMedia()
            val failure = CancellationException("draft construction cancelled")
            assertSame(failure, assertFailsWith<CancellationException> {
                DesktopSessionResourceCandidate.create({ media }, { throw failure })
            })
            assertFailsWith<IllegalStateException> { media.credentialGate.requireAccessToken() }
        }
    }

    private class Fixture : AutoCloseable {
        private val directory = Files.createTempDirectory("desktop-owner-candidate").toFile()
        private val deployment = DeploymentIdentity.from("test.example", 5100, "https://test.example")
        private val owner = DocumentDraftOwnerKey(deployment.fingerprint, "00000000-0000-4000-8000-000000000001", "test-owner")
        val leases = DesktopDocumentDraftLeaseRegistry(maxNamespaces = 1)
        val storage = RetainedDraftStorage()
        val candidates = mutableListOf<DesktopSessionResourceCandidate>()

        fun createMedia() = DesktopSessionResources(
            ownerUid = owner.uid,
            datasetId = owner.datasetId,
            deploymentIdentity = deployment,
            credentialProvider = { SessionHttpCredentials(owner.uid, "test-token") },
            dataDir = directory,
            diagnosticLogger = NoopLogger,
            downloader = DesktopMediaDownloader { _, _, _ -> error("no HTTP expected") },
        )

        fun createCandidate() = DesktopSessionResourceCandidate.create(
            createMedia = ::createMedia,
            createDocumentDraftPersistence = { DesktopDocumentDraftPersistence(storage, owner, leaseSource = leases) },
        ).also(candidates::add)

        fun assertAllCandidatesClosed() {
            assertTrue(candidates.isNotEmpty())
            candidates.forEach { candidate ->
                assertFailsWith<IllegalStateException> { candidate.media.credentialGate.requireAccessToken() }
                assertFalse(candidate.documentDraftPersistence.write(owner) { error("closed writer evaluated payload") })
            }
            assertEquals(0, leases.snapshot().retainedReferenceCount)
            assertEquals(0, leases.snapshot().namespaceCount)
            assertEquals("retained draft", storage.content)
        }

        override fun close() {
            candidates.forEach {
                it.media.close()
                // 已验收的候选可能早已 seal 或 discard；清理测试夹具不再断言它仍拥有 writer lease。
                try { it.documentDraftPersistence.retirePreservingDraft() }
                finally { it.documentDraftPersistence.sealPreservedDraft() }
            }
            directory.deleteRecursively()
        }
    }

    private class RetainedDraftStorage : DesktopDocumentDraftStorage {
        override val coordinationIdentity = Any()
        var content: String? = "retained draft"
        override fun read(limits: DesktopDocumentDraftLimits, consume: (DesktopDocumentDraftStoredRecordSource) -> Unit) =
            DesktopDocumentDraftStorageReadStatus.ABSENT
        override fun replace(payload: DocumentDraftPayload, limits: DesktopDocumentDraftLimits) {
            content = payload.manifest
        }
        override fun tombstone(recoveryKeys: Set<String>, limits: DesktopDocumentDraftLimits) = Unit
        override fun delete(limits: DesktopDocumentDraftLimits) { content = null }
    }

    /** 手动推进 IO 返回与取消边沿，避免靠 sleep 撞到竞态。 */
    private class ManualDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun runNext(): Boolean = tasks.poll()?.let { it.run(); true } ?: false
    }

    private fun drain(main: ManualDispatcher, storage: ManualDispatcher) {
        repeat(20) {
            val ranMain = main.runNext()
            val ranStorage = storage.runNext()
            if (!ranMain && !ranStorage) return
        }
        error("candidate handoff did not settle")
    }
}
