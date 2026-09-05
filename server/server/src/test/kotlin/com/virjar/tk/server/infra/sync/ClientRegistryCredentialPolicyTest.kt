package com.virjar.tk.server.infra.sync

import com.virjar.tk.server.application.admin.refreshCommittedConnectionTracePolicy
import com.virjar.tk.server.domain.auth.CredentialSessionAuthority
import com.virjar.tk.server.domain.telemetry.TelemetryDeviceIdentity
import com.virjar.tk.server.protocol.trace.Recorder
import com.virjar.tk.server.protocol.trace.RecorderPolicyUpdate
import com.virjar.tk.server.protocol.trace.TraceRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientRegistryCredentialPolicyTest {
    @Test
    fun `app restart may reset generation when correlation identity is fresh`() {
        assertTrue(
            connectionIdentityIsFresh(
                candidateCorrelationId = "correlation_00000002",
                existingCorrelationId = "correlation_00000001",
            ),
        )
        assertFalse(
            connectionIdentityIsFresh(
                candidateCorrelationId = "correlation_00000001",
                existingCorrelationId = "correlation_00000001",
            ),
        )
        // connectionGeneration 有意不参与判断：它是进程本地的，可以重置回 1。
    }

    @Test
    fun `same-device replacement cannot regress either credential epoch`() {
        assertFalse(credentialEpochsDoNotRegress(2, 3, 2, 3))
        assertFalse(credentialEpochsDoNotRegress(3, 3, 2, 3))
        assertTrue(credentialEpochsDoNotRegress(2, 4, 2, 3))
        assertTrue(credentialEpochsDoNotRegress(3, 4, 2, 3))

        assertFalse(credentialEpochsDoNotRegress(1, 3, 2, 3))
        assertFalse(credentialEpochsDoNotRegress(2, 2, 2, 3))
        assertFalse(credentialEpochsDoNotRegress(3, 2, 2, 3))
    }

    @Test
    fun `authority snapshot and provisional index must both survive admission`() {
        assertTrue(
            credentialAdmissionCanComplete(
                authorityCurrent = true,
                provisionalIndexed = true,
                connectionActive = true,
                credentialTerminal = false,
            ),
        )

        // 快照之前提交的变更：PostgreSQL 拒绝旧的 epoch。
        assertFalse(
            credentialAdmissionCanComplete(
                authorityCurrent = false,
                provisionalIndexed = true,
                connectionActive = true,
                credentialTerminal = false,
            ),
        )
        // 快照之后提交的变更：串行化的失效流程让暂存索引退休。
        assertFalse(
            credentialAdmissionCanComplete(
                authorityCurrent = true,
                provisionalIndexed = false,
                connectionActive = true,
                credentialTerminal = true,
            ),
        )
    }

    @Test
    fun `credential invalidations retain no history when no session exists`() = runTest {
        val registry = ClientRegistry(
            CredentialSessionAuthority { _, _, _, _ -> error("No session should reach authority validation") },
        )
        try {
            repeat(512) { offset ->
                val epoch = offset.toLong() + 1L
                registry.invalidateUserCredentials("historical-user-$offset", epoch)
                registry.invalidateDeviceCredentials("historical-user-$offset", "device-$offset", epoch)
            }

            assertEquals(0, registry.retainedCredentialSessionCount())
        } finally {
            registry.stop()
        }
    }

    @Test
    fun `rotation during authority snapshot retires provisional before completion`() = runTest {
        val snapshotStarted = CompletableDeferred<Unit>()
        val releaseSnapshot = CompletableDeferred<Unit>()
        var provisionalIndexed = true

        val admission = async {
            completeProvisionalCredentialAdmission(
                authoritativeSnapshot = {
                    snapshotStarted.complete(Unit)
                    releaseSnapshot.await()
                    true
                },
                serializedCompletion = { authorityCurrent ->
                    credentialAdmissionCanComplete(
                        authorityCurrent = authorityCurrent,
                        provisionalIndexed = provisionalIndexed,
                        connectionActive = provisionalIndexed,
                        credentialTerminal = !provisionalIndexed,
                    )
                },
            )
        }

        snapshotStarted.await()
        // 模拟注册表 looper 在 DB 读取进行中发布更新的凭据 epoch。
        provisionalIndexed = false
        releaseSnapshot.complete(Unit)

        assertFalse(admission.await())
    }

    @Test
    fun `failed and incomplete policy snapshots terminally disable active writers`() {
        val clock = AtomicLong(1_000L)
        val runtime = TraceRuntime(threadName = "registry-policy-fail-closed-test", clock = clock::get)
        try {
            val failedIdentity = TelemetryDeviceIdentity("failed-owner", "failed-device")
            val omittedIdentity = TelemetryDeviceIdentity("omitted-owner", "omitted-device")
            val failedRecorder = activeRecorder(
                runtime = runtime,
                clock = clock,
                identity = failedIdentity,
                correlationId = "failed-correlation-0001",
                sessionId = "failed-session-0000001",
                traceId = "failed-trace-00000001",
            )
            val omittedRecorder = activeRecorder(
                runtime = runtime,
                clock = clock,
                identity = omittedIdentity,
                correlationId = "omitted-correlation-01",
                sessionId = "omitted-session-00001",
                traceId = "omitted-trace-0000001",
            )
            val connections = mapOf(
                failedIdentity to listOf(failedRecorder),
                omittedIdentity to listOf(omittedRecorder),
            )
            val updates = mutableListOf<RecorderPolicyUpdate>()
            assertEquals(2, runtime.snapshot().activeWriters)

            // null 模拟 effectivePolicies 在返回权威映射之前抛出异常。
            applyConnectionTracePolicySnapshot(
                targets = setOf(failedIdentity),
                policies = null,
                connectionsFor = { identity -> connections.getValue(identity) },
                applyPolicy = { _, _ -> error("failed reads cannot apply a policy") },
                terminalDisable = { recorder ->
                    updates += assertNotNull(recorder.terminalDisablePolicy())
                },
            )
            assertEquals(1, runtime.snapshot().activeWriters)

            // 遗漏了请求目标的权威批次同样是失败关闭的。
            applyConnectionTracePolicySnapshot(
                targets = setOf(omittedIdentity),
                policies = emptyMap(),
                connectionsFor = { identity -> connections.getValue(identity) },
                applyPolicy = { _, _ -> error("omitted targets cannot apply a policy") },
                terminalDisable = { recorder ->
                    updates += assertNotNull(recorder.terminalDisablePolicy())
                },
            )

            assertEquals(0, runtime.snapshot().activeWriters)
            assertEquals(listOf(Long.MAX_VALUE, Long.MAX_VALUE), updates.map { it.policyRevision })
            assertTrue(updates.all { it.context == null })
            assertNull(
                failedRecorder.applyDiagnosticPolicy(
                    failedIdentity.uid,
                    failedIdentity.deviceId,
                    policyRevision = 11L,
                    expiresAtEpochMs = Long.MAX_VALUE,
                ),
            )
            assertNull(
                omittedRecorder.applyDiagnosticPolicy(
                    omittedIdentity.uid,
                    omittedIdentity.deviceId,
                    policyRevision = 11L,
                    expiresAtEpochMs = Long.MAX_VALUE,
                ),
            )
            assertEquals(0, runtime.snapshot().activeWriters)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `committed refresh survives cancellation and retries rejected control admission`() = runTest {
        val clock = AtomicLong(1_000L)
        val runtime = TraceRuntime(threadName = "registry-policy-cancel-test", clock = clock::get)
        try {
            val identity = TelemetryDeviceIdentity("cancel-owner", "cancel-device")
            val recorder = activeRecorder(
                runtime = runtime,
                clock = clock,
                identity = identity,
                correlationId = "cancel-correlation-001",
                sessionId = "cancel-session-000001",
                traceId = "cancel-trace-00000001",
            )
            val enteredBackoff = CompletableDeferred<Unit>()
            val releaseRetry = CompletableDeferred<Unit>()
            val updates = mutableListOf<RecorderPolicyUpdate>()
            val observedBackoffs = mutableListOf<Long>()
            var attempts = 0

            val refresh = launch(start = CoroutineStart.UNDISPATCHED) {
                refreshCommittedConnectionTracePolicy(identity.uid, identity.deviceId) { _, _ ->
                    awaitConnectionTraceControlAdmission(
                        accepting = { true },
                        trySubmit = { command ->
                            attempts += 1
                            if (attempts == 1) {
                                false
                            } else {
                                command()
                                true
                            }
                        },
                        backoff = { millis ->
                            observedBackoffs += millis
                            enteredBackoff.complete(Unit)
                            releaseRetry.await()
                        },
                    ) {
                        applyConnectionTracePolicySnapshot(
                            targets = setOf(identity),
                            policies = null,
                            connectionsFor = { listOf(recorder) },
                            applyPolicy = { _, _ -> error("failed reads cannot apply a policy") },
                            terminalDisable = { target ->
                                updates += assertNotNull(target.terminalDisablePolicy())
                            },
                        )
                    }
                }
            }

            enteredBackoff.await()
            refresh.cancel()
            releaseRetry.complete(Unit)
            refresh.join()

            assertTrue(refresh.isCancelled)
            assertEquals(2, attempts)
            assertEquals(listOf(1L), observedBackoffs)
            assertEquals(0, runtime.snapshot().activeWriters)
            assertNull(updates.single().context)
            assertNull(
                recorder.applyDiagnosticPolicy(
                    identity.uid,
                    identity.deviceId,
                    policyRevision = 12L,
                    expiresAtEpochMs = Long.MAX_VALUE,
                ),
            )
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `activation republishes enable and disable decisions applied during synchronization`() {
        val clock = AtomicLong(1_000L)
        val runtime = TraceRuntime(
            threadName = "registry-policy-activation-race-test",
            maxWriters = 2,
            clock = clock::get,
        )
        try {
            val identity = TelemetryDeviceIdentity("activation-owner", "activation-device")
            val enabled = Recorder(
                runtime = runtime,
                clock = clock::get,
                idFactory = { "activation-enable-trace" },
            )
            assertTrue(
                enabled.bindAuthentication(
                    correlationId = "activation-enable-correlation",
                    connectionGeneration = 1L,
                    serverSessionId = "activation-enable-session",
                ),
            )
            assertNull(assertNotNull(enabled.disablePolicy(10L)).context)
            assertNull(enabled.context(), "AUTH_RESP was built while the connection was BASELINE")

            // 模拟在 AUTH_RESP 构建之后、SYNC_READY 之前发生的管理员 enable/change。
            assertNotNull(
                enabled.applyDiagnosticPolicy(
                    identity.uid,
                    identity.deviceId,
                    policyRevision = 11L,
                    expiresAtEpochMs = Long.MAX_VALUE,
                )?.context,
            )
            assertNull(
                enabled.applyDiagnosticPolicy(
                    identity.uid,
                    identity.deviceId,
                    policyRevision = 11L,
                    expiresAtEpochMs = Long.MAX_VALUE,
                ),
                "activation reconciliation of the same revision is intentionally a no-op",
            )
            val enabledFrames = mutableListOf<RecorderPolicyUpdate>()
            publishExactConnectionTracePolicyUpdate(
                expected = enabled,
                current = enabled,
                update = enabled.currentPolicyDecision(),
                publish = { _, update -> enabledFrames += update },
            )
            assertEquals(11L, enabledFrames.single().policyRevision)
            assertEquals(11L, assertNotNull(enabledFrames.single().context).policyRevision)

            val disabled = activeRecorder(
                runtime = runtime,
                clock = clock,
                identity = identity,
                correlationId = "activation-disable-correlation",
                sessionId = "activation-disable-session",
                traceId = "activation-disable-trace",
            )
            // 模拟在同一个 AUTH_RESP -> SYNC_READY 区间内发生 disable。
            assertNull(assertNotNull(disabled.disablePolicy(11L)).context)
            assertNull(
                disabled.disablePolicy(11L),
                "same-revision activation reconciliation cannot recreate the lost live frame",
            )
            val disabledFrames = mutableListOf<RecorderPolicyUpdate>()
            publishExactConnectionTracePolicyUpdate(
                expected = disabled,
                current = disabled,
                update = disabled.currentPolicyDecision(),
                publish = { _, update -> disabledFrames += update },
            )
            assertEquals(11L, disabledFrames.single().policyRevision)
            assertNull(disabledFrames.single().context)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `activation publishes capacity denial only to the exact current connection`() {
        val clock = AtomicLong(1_000L)
        val runtime = TraceRuntime(
            threadName = "registry-policy-activation-cap-test",
            maxWriters = 1,
            clock = clock::get,
        )
        try {
            val blockerIdentity = TelemetryDeviceIdentity("blocker-owner", "blocker-device")
            activeRecorder(
                runtime = runtime,
                clock = clock,
                identity = blockerIdentity,
                correlationId = "activation-blocker-correlation",
                sessionId = "activation-blocker-session",
                traceId = "activation-blocker-trace",
            )
            val denied = Recorder(
                runtime = runtime,
                clock = clock::get,
                idFactory = { "activation-denied-trace" },
            )
            assertTrue(
                denied.bindAuthentication(
                    correlationId = "activation-denied-correlation",
                    connectionGeneration = 1L,
                    serverSessionId = "activation-denied-session",
                ),
            )
            val provisionalDecision = assertNotNull(
                denied.applyDiagnosticPolicy(
                    uid = "denied-owner",
                    deviceId = "denied-device",
                    policyRevision = 10L,
                    expiresAtEpochMs = Long.MAX_VALUE,
                ),
            )
            assertNull(provisionalDecision.context)
            val activationDecision = assertNotNull(denied.currentPolicyDecision())
            assertEquals(10L, activationDecision.policyRevision)
            assertNull(activationDecision.context)

            val replacement = Any()
            val staleFrames = mutableListOf<RecorderPolicyUpdate>()
            publishExactConnectionTracePolicyUpdate(
                expected = denied,
                current = replacement,
                update = activationDecision,
                publish = { _, update -> staleFrames += update },
            )
            assertTrue(staleFrames.isEmpty(), "a replaced connection must not receive activation policy")

            val currentFrames = mutableListOf<RecorderPolicyUpdate>()
            publishExactConnectionTracePolicyUpdate(
                expected = denied,
                current = denied,
                update = activationDecision,
                publish = { _, update -> currentFrames += update },
            )
            assertEquals(listOf(activationDecision), currentFrames)
        } finally {
            runtime.close()
        }
    }

    private fun activeRecorder(
        runtime: TraceRuntime,
        clock: AtomicLong,
        identity: TelemetryDeviceIdentity,
        correlationId: String,
        sessionId: String,
        traceId: String,
    ): Recorder = Recorder(
        runtime = runtime,
        clock = clock::get,
        idFactory = { traceId },
    ).also { recorder ->
        assertTrue(recorder.bindAuthentication(correlationId, 1L, sessionId))
        assertNotNull(
            recorder.applyDiagnosticPolicy(
                uid = identity.uid,
                deviceId = identity.deviceId,
                policyRevision = 10L,
                expiresAtEpochMs = Long.MAX_VALUE,
            )?.context,
        )
    }
}
