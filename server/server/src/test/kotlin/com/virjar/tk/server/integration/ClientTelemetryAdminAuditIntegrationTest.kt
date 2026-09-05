package com.virjar.tk.server.integration

import com.virjar.tk.server.application.admin.AdminPageRequest
import com.virjar.tk.server.application.admin.ClientTelemetryAdminService
import com.virjar.tk.server.domain.telemetry.ClientTelemetryEventStore
import com.virjar.tk.server.domain.telemetry.ConnectionTraceContext
import com.virjar.tk.server.domain.telemetry.ConnectionTraceEventDraft
import com.virjar.tk.server.domain.telemetry.ConnectionTraceOutcome
import com.virjar.tk.server.domain.telemetry.ConnectionTracePhase
import com.virjar.tk.server.domain.telemetry.TelemetryBatchDraft
import com.virjar.tk.server.domain.telemetry.TelemetryCollectionMode
import com.virjar.tk.server.domain.telemetry.TelemetryEventDraft
import com.virjar.tk.server.domain.telemetry.TelemetryRuntimeSnapshot
import com.virjar.tk.server.domain.telemetry.TelemetrySearchPage
import com.virjar.tk.server.domain.telemetry.TelemetrySearchQuery
import com.virjar.tk.server.domain.telemetry.TelemetrySearchUnavailableException
import com.virjar.tk.server.infra.db.ClientTelemetryAdminAudits
import com.virjar.tk.server.infra.db.repository.ExposedClientTelemetryAdminAuditRepository
import com.virjar.tk.protocol.telemetry.TelemetryEventKind
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClientTelemetryAdminAuditIntegrationTest {
    companion object {
        private const val ATOMIC_AUDIT_CONSTRAINT = "ck_test_client_telemetry_admin_audit_atomic"
        private const val ATOMIC_AUDIT_FAILURE_ACTOR = "security-atomic-audit-failure"

        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `real admin actor audits success rejection policy mutation and exact correlation`() = runTest {
        val owner = ctx.registerHuman(uniqueUsername("telemetry-audit"), "pass123", "Telemetry Audit")
        val now = System.currentTimeMillis()
        val context = ConnectionTraceContext(
            correlationId = token("audit-correlation"),
            traceId = token("audit-trace"),
            sessionId = token("audit-session"),
            connectionGeneration = 3L,
            policyRevision = 5L,
        )
        ctx.clientTelemetryEvents.ingest(
            owner.uid,
            "audit-device",
            batch(now, context),
            now,
            1_024,
        )
        assertTrue(
            ctx.connectionTraceEvents.tryAppend(
                ConnectionTraceEventDraft(
                    uid = owner.uid,
                    deviceId = "audit-device",
                    correlationId = context.correlationId,
                    traceId = context.traceId,
                    sessionId = context.sessionId,
                    connectionGeneration = context.connectionGeneration,
                    policyRevision = context.policyRevision,
                    occurredAt = now,
                    phase = ConnectionTracePhase.RPC,
                    outcome = ConnectionTraceOutcome.SUCCEEDED,
                    detail = "event=adminCorrelation",
                ),
            ),
        )
        await { ctx.connectionTraceEvents.snapshot().documentCount == 1L }

        val event = ctx.clientTelemetryEvents.search(
            TelemetrySearchQuery(receivedAtFrom = now, receivedAtUntil = now),
            0,
            10,
        ).hits.single().event
        val service = ctx.clientTelemetryAdminService
        val page = service.searchEvents(
            actor = "security-search-admin",
            keyword = null,
            uid = owner.uid,
            deviceId = null,
            phone = null,
            platform = null,
            osName = null,
            osVersion = null,
            appVersion = null,
            gitCommit = null,
            category = null,
            eventName = null,
            start = now,
            end = now,
            pagination = AdminPageRequest(1, 20),
        )
        assertEquals(context, page.items.single().connectionTraceContext?.let {
            ConnectionTraceContext(
                it.correlationId,
                it.traceId,
                it.sessionId,
                it.connectionGeneration,
                it.policyRevision,
            )
        })
        assertFailsWith<IllegalArgumentException> {
            service.searchEvents(
                actor = "security-rejected-admin",
                keyword = null,
                uid = null,
                deviceId = null,
                phone = null,
                platform = null,
                osName = null,
                osVersion = null,
                appVersion = null,
                gitCommit = null,
                category = null,
                eventName = null,
                start = now,
                end = now - 1L,
                pagination = AdminPageRequest(1, 20),
            )
        }

        val correlation = assertNotNull(service.connectionTraces(event.id, "security-correlation-admin"))
        assertEquals(1, correlation.traces.size)
        assertEquals(context.connectionGeneration, correlation.traces.single().connectionGeneration)
        assertEquals(null, service.connectionTraces(Long.MAX_VALUE, "security-missing-admin"))

        ctx.clientTelemetryEvents.ingest(
            owner.uid,
            "audit-device",
            batch(now + 1L, null),
            now + 1L,
            1_024,
        )
        val contextless = ctx.clientTelemetryEvents.search(
            TelemetrySearchQuery(receivedAtFrom = now, receivedAtUntil = now + 1L),
            0,
            10,
        ).hits.single { it.event.event.connectionTraceContext == null }.event
        val emptyCorrelation = assertNotNull(
            service.connectionTraces(contextless.id, "security-contextless-admin"),
        )
        assertEquals(null, emptyCorrelation.context)
        assertTrue(emptyCorrelation.traces.isEmpty())

        val enabled = service.enablePolicy(
            ClientTelemetryAdminService.EnablePolicyRequest(
                uid = owner.uid,
                reason = "bounded diagnosis",
                durationMinutes = 5,
            ),
            actor = "security-enable-admin",
        )
        assertEquals("security-enable-admin", enabled.updatedBy)
        val disabled = assertNotNull(service.disablePolicy(enabled.policyId, "security-disable-admin"))
        assertEquals("security-disable-admin", disabled.updatedBy)

        val unavailableEvents = object : ClientTelemetryEventStore by ctx.clientTelemetryEvents {
            override fun search(query: TelemetrySearchQuery, offset: Int, limit: Int): TelemetrySearchPage =
                throw TelemetrySearchUnavailableException()
        }
        val failingService = ClientTelemetryAdminService(
            repository = ctx.clientTelemetryControl,
            events = unavailableEvents,
            users = ctx.userRepo,
            clock = { now + 2L },
            connectionTraces = ctx.connectionTraceEvents,
            audit = ExposedClientTelemetryAdminAuditRepository(ctx.database),
        )
        assertFailsWith<TelemetrySearchUnavailableException> {
            failingService.searchEvents(
                actor = "security-failed-admin",
                keyword = null,
                uid = null,
                deviceId = null,
                phone = null,
                platform = null,
                osName = null,
                osVersion = null,
                appVersion = null,
                gitCommit = null,
                category = null,
                eventName = null,
                start = now,
                end = now + 2L,
                pagination = AdminPageRequest(1, 20),
            )
        }

        val audits = transaction(ctx.database) {
            ClientTelemetryAdminAudits.selectAll()
                .orderBy(ClientTelemetryAdminAudits.id, SortOrder.ASC)
                .map {
                    Triple(
                        it[ClientTelemetryAdminAudits.actor],
                        it[ClientTelemetryAdminAudits.action],
                        it[ClientTelemetryAdminAudits.result],
                    )
                }
        }
        assertTrue(Triple("security-search-admin", "EVENT_SEARCH", "SUCCESS") in audits)
        assertTrue(Triple("security-rejected-admin", "EVENT_SEARCH", "REJECTED") in audits)
        assertTrue(Triple("security-correlation-admin", "CONNECTION_TRACE_CORRELATE", "SUCCESS") in audits)
        assertTrue(Triple("security-missing-admin", "CONNECTION_TRACE_CORRELATE", "NOT_FOUND") in audits)
        assertTrue(Triple("security-contextless-admin", "CONNECTION_TRACE_CORRELATE", "EMPTY") in audits)
        assertTrue(Triple("security-enable-admin", "POLICY_ENABLE", "SUCCESS") in audits)
        assertTrue(Triple("security-disable-admin", "POLICY_DISABLE", "SUCCESS") in audits)
        assertTrue(Triple("security-failed-admin", "EVENT_SEARCH", "FAILED") in audits)
    }

    @Test
    fun `policy mutation rolls back when its mandatory admin audit cannot commit`() = runTest {
        val rejectedOwner = ctx.registerHuman(
            uniqueUsername("telemetry-audit-rollback-a"),
            "pass123",
            "Telemetry Audit Rollback A",
        )
        val retainedOwner = ctx.registerHuman(
            uniqueUsername("telemetry-audit-rollback-b"),
            "pass123",
            "Telemetry Audit Rollback B",
        )
        val retained = ctx.clientTelemetryAdminService.enablePolicy(
            ClientTelemetryAdminService.EnablePolicyRequest(
                uid = retainedOwner.uid,
                reason = "retain on audit failure",
                durationMinutes = 5,
            ),
            actor = "security-atomic-audit-setup",
        )

        transaction(ctx.database) {
            exec(
                """ALTER TABLE client_telemetry_admin_audits
                    ADD CONSTRAINT $ATOMIC_AUDIT_CONSTRAINT
                    CHECK (actor <> '$ATOMIC_AUDIT_FAILURE_ACTOR')
                """.trimIndent(),
            )
        }
        try {
            assertFailsWith<Exception> {
                ctx.clientTelemetryAdminService.enablePolicy(
                    ClientTelemetryAdminService.EnablePolicyRequest(
                        uid = rejectedOwner.uid,
                        reason = "must roll back",
                        durationMinutes = 5,
                    ),
                    actor = ATOMIC_AUDIT_FAILURE_ACTOR,
                )
            }
            assertTrue(
                ctx.clientTelemetryControl.pagePolicies(0, 100).items.none { it.uid == rejectedOwner.uid },
                "a failed mandatory audit must roll back the newly inserted policy",
            )

            assertFailsWith<Exception> {
                ctx.clientTelemetryAdminService.disablePolicy(
                    retained.policyId,
                    ATOMIC_AUDIT_FAILURE_ACTOR,
                )
            }
            val afterFailedDisable = ctx.clientTelemetryControl.pagePolicies(0, 100).items
                .single { it.policyId == retained.policyId }
            assertEquals(retained.revision, afterFailedDisable.revision)
            assertEquals(TelemetryCollectionMode.DIAGNOSTIC, afterFailedDisable.mode)
            assertEquals(
                "security-atomic-audit-setup",
                afterFailedDisable.updatedBy,
                "a failed mandatory audit must roll back the terminal policy update",
            )
        } finally {
            transaction(ctx.database) {
                exec(
                    "ALTER TABLE client_telemetry_admin_audits " +
                        "DROP CONSTRAINT IF EXISTS $ATOMIC_AUDIT_CONSTRAINT",
                )
            }
        }
    }

    private fun batch(now: Long, context: ConnectionTraceContext?) = TelemetryBatchDraft(
        batchId = UUID.randomUUID().toString(),
        payloadSha256 = "b".repeat(64),
        createdAt = now,
        runtime = TelemetryRuntimeSnapshot(
            platform = "desktop",
            osName = "macOS",
            osVersion = "15",
            architecture = "arm64",
            deviceModel = "Mac",
            appVersion = "1",
            buildNumber = "1",
            gitCommit = "abcdef",
            buildIdentity = "test",
            buildTime = "2026-01-01T00:00:00Z",
            protocolVersion = 1,
            distribution = "test",
        ),
        events = listOf(
            TelemetryEventDraft(
                eventId = UUID.randomUUID().toString(),
                runId = UUID.randomUUID().toString(),
                sequence = 1L,
                occurredAt = now,
                category = TelemetryEventKind.ACTION.name,
                eventName = "audit-action",
                message = "audit action succeeded",
                searchText = "audit action succeeded",
                connectionTraceContext = context,
            ),
        ),
    )

    private fun token(value: String) = value.replace("-", "_").padEnd(16, 'x')

    private fun await(assertion: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!assertion()) {
            check(System.nanoTime() < deadline) { "timed out waiting for connection trace integration" }
            Thread.sleep(10L)
        }
    }

}
