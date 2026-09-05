package com.virjar.tk.server.application.admin

import com.virjar.tk.server.domain.telemetry.ClientTelemetryPolicy
import com.virjar.tk.server.domain.telemetry.ClientTelemetryAdminAuditRepository
import com.virjar.tk.server.domain.telemetry.ClientTelemetryControlRepository
import com.virjar.tk.server.domain.telemetry.ClientTelemetryEventStore
import com.virjar.tk.server.domain.telemetry.ConnectionTraceContext
import com.virjar.tk.server.domain.telemetry.ConnectionTraceEventStore
import com.virjar.tk.server.domain.telemetry.ConnectionTraceQuery
import com.virjar.tk.server.domain.telemetry.ConnectionTraceStoragePolicy
import com.virjar.tk.server.domain.telemetry.StoredTelemetryEvent
import com.virjar.tk.server.domain.telemetry.StoredConnectionTraceEvent
import com.virjar.tk.server.domain.telemetry.TelemetryAdminAuditAction
import com.virjar.tk.server.domain.telemetry.TelemetryAdminAuditEntry
import com.virjar.tk.server.domain.telemetry.TelemetryAdminAuditResult
import com.virjar.tk.server.domain.telemetry.TelemetryCollectionMode
import com.virjar.tk.server.domain.telemetry.TelemetryDeviceFilter
import com.virjar.tk.server.domain.telemetry.TelemetryDeviceIdentity
import com.virjar.tk.server.domain.telemetry.TelemetrySearchQuery
import com.virjar.tk.server.domain.telemetry.TelemetryOutgoingQueueQuery
import com.virjar.tk.server.domain.telemetry.TelemetryStoragePolicy
import com.virjar.tk.server.domain.telemetry.TelemetryTextHighlight
import com.virjar.tk.server.domain.user.UserRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

/** 已提交的策略变更必须完成其在线连接 fence，即使 HTTP 已被取消。 */
internal suspend fun refreshCommittedConnectionTracePolicy(
    uid: String,
    deviceId: String?,
    refresher: suspend (String, String?) -> Unit,
) {
    withContext(NonCancellable) {
        refresher(uid, deviceId)
    }
}

/** 管理员查询/采集用例；手机号选择器在此边界终止。 */
class ClientTelemetryAdminService(
    private val repository: ClientTelemetryControlRepository,
    private val events: ClientTelemetryEventStore,
    private val users: UserRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val connectionTraces: ConnectionTraceEventStore? = null,
    private val audit: ClientTelemetryAdminAuditRepository = ClientTelemetryAdminAuditRepository { },
    private val policyRefresher: suspend (String, String?) -> Unit = { _, _ -> },
) {
    @Serializable
    data class EventItem(
        val id: Long,
        val eventId: String,
        val batchId: String,
        val uid: String,
        val deviceId: String,
        val receivedAt: Long,
        val occurredAt: Long,
        val platform: String,
        val osName: String,
        val osVersion: String,
        val architecture: String,
        val deviceModel: String,
        val appVersion: String,
        val buildNumber: String,
        val gitCommit: String,
        val buildIdentity: String,
        val buildTime: String,
        val protocolVersion: Int,
        val distribution: String,
        val category: String,
        val eventName: String,
        val runId: String,
        val sequence: Long,
        val message: String?,
        val outgoingQueue: OutgoingQueueItem?,
        val connectionTraceContext: ConnectionTraceContextItem?,
        val highlight: TextHighlightItem?,
    )

    @Serializable
    data class ConnectionTraceContextItem(
        val correlationId: String,
        val traceId: String,
        val sessionId: String,
        val connectionGeneration: Long,
        val policyRevision: Long,
    )

    @Serializable
    data class ConnectionTraceItem(
        val id: Long,
        val uid: String?,
        val deviceId: String?,
        val correlationId: String,
        val traceId: String,
        val sessionId: String,
        val connectionGeneration: Long,
        val policyRevision: Long,
        val occurredAt: Long,
        val phase: String,
        val outcome: String,
        val detail: String?,
    )

    @Serializable
    data class ConnectionTraceCorrelationResponse(
        val eventRecordId: Long,
        val context: ConnectionTraceContextItem?,
        val traces: List<ConnectionTraceItem>,
        val truncated: Boolean,
    )

    @Serializable
    data class OutgoingQueueItem(
        val pendingCount: Int,
        val retryWaitCount: Int,
        val terminalFailedCount: Int,
        val oldestActiveAgeMillis: Long,
        val maxAttemptCount: Long,
    )

    @Serializable
    data class TextHighlightItem(
        val text: String,
        val spans: List<HighlightSpanItem>,
    )

    @Serializable
    data class HighlightSpanItem(
        val start: Int,
        val end: Int,
    )

    @Serializable
    data class DeviceItem(
        val uid: String,
        val deviceId: String,
        val platform: String,
        val osName: String,
        val osVersion: String,
        val architecture: String,
        val deviceModel: String,
        val appVersion: String,
        val buildNumber: String,
        val gitCommit: String,
        val buildIdentity: String,
        val buildTime: String,
        val protocolVersion: Int,
        val distribution: String,
        val firstSeenAt: Long,
        val lastSeenAt: Long,
        val lastEventAt: Long?,
        val policyMode: String,
        val policyExpiresAt: Long?,
    )

    @Serializable
    data class PolicyItem(
        val policyId: String,
        val uid: String,
        val deviceId: String?,
        val mode: String,
        val reason: String?,
        val revision: Long,
        val expiresAt: Long?,
        val updatedAt: Long,
        val updatedBy: String,
        val active: Boolean,
    )

    @Serializable
    data class EnablePolicyRequest(
        val uid: String? = null,
        val phone: String? = null,
        val deviceId: String? = null,
        val reason: String,
        val durationMinutes: Int,
    )

    fun searchEvents(
        actor: String,
        keyword: String?,
        uid: String?,
        deviceId: String?,
        phone: String?,
        platform: String?,
        osName: String?,
        osVersion: String?,
        appVersion: String?,
        gitCommit: String?,
        category: String?,
        eventName: String?,
        start: Long?,
        end: Long?,
        pagination: AdminPageRequest,
        outgoingQueue: TelemetryOutgoingQueueQuery? = null,
    ): AdminPage<EventItem> = audited(
        actor = actor,
        action = TelemetryAdminAuditAction.EVENT_SEARCH,
        target = "events",
        resultOf = { if (it.total == 0L) TelemetryAdminAuditResult.EMPTY else TelemetryAdminAuditResult.SUCCESS },
    ) {
        require(start == null || end == null || start <= end) { "telemetry time range is inverted" }
        val now = clock()
        val resolvedUid = resolveOptionalUid(uid, phone) ?: if (!phone.isNullOrBlank()) {
            return@audited AdminPage(0L, emptyList())
        } else {
            null
        }
        val cutoff = now - TelemetryStoragePolicy.RETENTION_MILLIS
        val requestedFrom = start ?: cutoff
        val requestedUntil = end ?: now
        if (requestedUntil < cutoff || requestedFrom > now) return@audited AdminPage(0L, emptyList())
        val from = max(requestedFrom, cutoff)
        val until = min(requestedUntil, now)
        val result = events.search(
            TelemetrySearchQuery(
                keyword = normalizedFilter(keyword, MAX_KEYWORD_CHARS),
                uid = normalizedFilter(resolvedUid, MAX_UID_CHARS),
                deviceId = normalizedFilter(deviceId, MAX_DEVICE_ID_CHARS),
                platform = normalizedFilter(platform, MAX_RUNTIME_CHARS),
                osName = normalizedFilter(osName, MAX_RUNTIME_CHARS),
                osVersion = normalizedFilter(osVersion, MAX_RUNTIME_CHARS),
                appVersion = normalizedFilter(appVersion, MAX_RUNTIME_CHARS),
                gitCommit = normalizedFilter(gitCommit, MAX_GIT_COMMIT_CHARS),
                category = normalizedFilter(category, MAX_NAME_CHARS),
                eventName = normalizedFilter(eventName, MAX_NAME_CHARS),
                receivedAtFrom = from,
                receivedAtUntil = until,
                outgoingQueue = outgoingQueue,
            ),
            offset = pagination.searchOffset(),
            limit = pagination.size,
        )
        val items = result.hits.map { hit -> hit.event.toEventItem(hit.highlight) }
        AdminPage(result.total, items)
    }

    fun pageDevices(query: String?, phone: String?, pagination: AdminPageRequest): AdminPage<DeviceItem> {
        val normalizedQuery = normalizedFilter(query, MAX_DEVICE_QUERY_CHARS)
        val normalizedPhone = normalizedFilter(phone, MAX_PHONE_CHARS)
        // 手机号仅是精确身份选择器：将其解析为 uid，绝不传入 SQL/文本搜索。
        val exactUid = normalizedPhone?.let { users.findByPhone(it)?.uid }
        if (normalizedPhone != null && exactUid == null) return AdminPage(0L, emptyList())
        val result = repository.pageDevices(
            TelemetryDeviceFilter(text = normalizedQuery, exactUid = exactUid),
            pagination.offset,
            pagination.size,
        )
        val now = clock()
        val policies = repository.effectivePolicies(
            result.items.mapTo(linkedSetOf()) { TelemetryDeviceIdentity(it.uid, it.deviceId) },
            now,
        )
        return AdminPage(
            result.total,
            result.items.map { device ->
                val policy = checkNotNull(policies[TelemetryDeviceIdentity(device.uid, device.deviceId)])
                DeviceItem(
                    uid = device.uid,
                    deviceId = device.deviceId,
                    platform = device.runtime.platform,
                    osName = device.runtime.osName,
                    osVersion = device.runtime.osVersion,
                    architecture = device.runtime.architecture,
                    deviceModel = device.runtime.deviceModel,
                    appVersion = device.runtime.appVersion,
                    buildNumber = device.runtime.buildNumber,
                    gitCommit = device.runtime.gitCommit,
                    buildIdentity = device.runtime.buildIdentity,
                    buildTime = device.runtime.buildTime,
                    protocolVersion = device.runtime.protocolVersion,
                    distribution = device.runtime.distribution,
                    firstSeenAt = device.firstSeenAt,
                    lastSeenAt = device.lastSeenAt,
                    lastEventAt = device.lastEventAt,
                    policyMode = policy.effectiveMode(now).name,
                    policyExpiresAt = policy.expiresAt,
                )
            },
        )
    }

    fun pagePolicies(pagination: AdminPageRequest): AdminPage<PolicyItem> {
        val now = clock()
        val result = repository.pagePolicies(pagination.offset, pagination.size)
        return AdminPage(result.total, result.items.map { it.toPolicyItem(now) })
    }

    suspend fun enablePolicy(request: EnablePolicyRequest, actor: String): PolicyItem = auditedSuspend(
        actor = actor,
        action = TelemetryAdminAuditAction.POLICY_ENABLE,
        target = "policy:create",
        successAuditCommittedByBlock = true,
    ) { successAudit ->
        val uid = resolveRequiredUid(request.uid, request.phone)
        val deviceId = normalizedFilter(request.deviceId, MAX_DEVICE_ID_CHARS)
        val reason = request.reason.trim()
        require(reason.isNotEmpty() && reason.length <= TelemetryStoragePolicy.MAX_POLICY_REASON_CHARS) {
            "reason is required and must not exceed ${TelemetryStoragePolicy.MAX_POLICY_REASON_CHARS} characters"
        }
        require(request.durationMinutes in 1..MAX_POLICY_DURATION_MINUTES) {
            "durationMinutes must be between 1 and $MAX_POLICY_DURATION_MINUTES"
        }
        if (deviceId != null) {
            require(repository.findDevice(uid, deviceId) != null) { "telemetry device does not exist" }
        }
        val now = clock()
        val expiresAt = Math.addExact(now, request.durationMinutes.toLong() * 60_000L)
        val policy = repository.enableDiagnosticPolicy(
            uid = uid,
            deviceId = deviceId,
            reason = reason,
            expiresAt = expiresAt,
            actor = requireAdminActor(actor),
            now = now,
            successAudit = successAudit,
        )
        refreshCommittedConnectionTracePolicy(policy.uid, policy.deviceId, policyRefresher)
        policy.toPolicyItem(now)
    }

    suspend fun disablePolicy(policyId: String, actor: String): PolicyItem? = auditedSuspend(
        actor = actor,
        action = TelemetryAdminAuditAction.POLICY_DISABLE,
        target = "policy:${safePolicyTarget(policyId)}",
        resultOf = { if (it == null) TelemetryAdminAuditResult.NOT_FOUND else TelemetryAdminAuditResult.SUCCESS },
        successAuditCommittedByBlock = true,
    ) { successAudit ->
        val normalized = normalizedFilter(policyId, MAX_POLICY_ID_CHARS) ?: return@auditedSuspend null
        val now = clock()
        val policy = repository.disablePolicy(
            policyId = normalized,
            actor = requireAdminActor(actor),
            now = now,
            successAudit = successAudit,
        ) ?: return@auditedSuspend null
        refreshCommittedConnectionTracePolicy(policy.uid, policy.deviceId, policyRefresher)
        policy.toPolicyItem(now)
    }

    /** 内部事件 id 精确对应到同一物理连接身份；不存在模糊回退。 */
    fun connectionTraces(eventRecordId: Long, actor: String): ConnectionTraceCorrelationResponse? = audited(
        actor = actor,
        action = TelemetryAdminAuditAction.CONNECTION_TRACE_CORRELATE,
        target = "event:${eventRecordId.takeIf { it > 0L } ?: "invalid"}",
        resultOf = { response ->
            when {
                response == null -> TelemetryAdminAuditResult.NOT_FOUND
                response.traces.isEmpty() -> TelemetryAdminAuditResult.EMPTY
                else -> TelemetryAdminAuditResult.SUCCESS
            }
        },
    ) {
        require(eventRecordId > 0L) { "telemetry event id must be positive" }
        val stored = events.findEventById(eventRecordId) ?: return@audited null
        val context = stored.event.connectionTraceContext
            ?: return@audited ConnectionTraceCorrelationResponse(eventRecordId, null, emptyList(), false)
        val store = connectionTraces
            ?: return@audited ConnectionTraceCorrelationResponse(
                eventRecordId,
                context.toItem(),
                emptyList(),
                false,
            )
        val now = clock()
        val page = store.query(
            ConnectionTraceQuery(
                uid = stored.uid,
                deviceId = stored.deviceId,
                correlationId = context.correlationId,
                traceId = context.traceId,
                sessionId = context.sessionId,
                connectionGeneration = context.connectionGeneration,
                policyRevision = context.policyRevision,
                occurredAtFrom = (now - ConnectionTraceStoragePolicy.RETENTION_MILLIS).coerceAtLeast(0L),
                occurredAtUntil = now,
            ),
        )
        ConnectionTraceCorrelationResponse(
            eventRecordId = eventRecordId,
            context = context.toItem(),
            traces = page.events.map { it.toItem() },
            truncated = page.truncated,
        )
    }

    private fun resolveRequiredUid(uid: String?, phone: String?): String {
        val normalizedUid = normalizedFilter(uid, MAX_UID_CHARS)
        val normalizedPhone = normalizedFilter(phone, MAX_PHONE_CHARS)
        require((normalizedUid == null) xor (normalizedPhone == null)) { "exactly one of uid or phone is required" }
        if (normalizedUid != null) {
            require(users.findByUid(normalizedUid) != null) { "telemetry policy account does not exist" }
            return normalizedUid
        }
        return users.findByPhone(checkNotNull(normalizedPhone))?.uid
            ?: throw IllegalArgumentException("telemetry policy account does not exist")
    }

    private fun resolveOptionalUid(uid: String?, phone: String?): String? {
        val normalizedUid = normalizedFilter(uid, MAX_UID_CHARS)
        val normalizedPhone = normalizedFilter(phone, MAX_PHONE_CHARS)
        require(normalizedUid == null || normalizedPhone == null) { "uid and phone cannot be combined" }
        return normalizedUid ?: normalizedPhone?.let { users.findByPhone(it)?.uid }
    }

    private fun normalizedFilter(value: String?, maxLength: Int): String? =
        value?.trim()?.takeIf(String::isNotEmpty)?.also {
            require(it.length <= maxLength && it.none(Char::isISOControl)) { "invalid telemetry filter" }
        }

    private fun StoredTelemetryEvent.toEventItem(highlight: TelemetryTextHighlight?) = EventItem(
        id = id,
        eventId = event.eventId,
        batchId = batchId,
        uid = uid,
        deviceId = deviceId,
        receivedAt = receivedAt,
        occurredAt = event.occurredAt,
        platform = runtime.platform,
        osName = runtime.osName,
        osVersion = runtime.osVersion,
        architecture = runtime.architecture,
        deviceModel = runtime.deviceModel,
        appVersion = runtime.appVersion,
        buildNumber = runtime.buildNumber,
        gitCommit = runtime.gitCommit,
        buildIdentity = runtime.buildIdentity,
        buildTime = runtime.buildTime,
        protocolVersion = runtime.protocolVersion,
        distribution = runtime.distribution,
        category = event.category,
        eventName = event.eventName,
        runId = event.runId,
        sequence = event.sequence,
        message = event.message,
        outgoingQueue = event.outgoingQueue?.let { queue ->
            OutgoingQueueItem(
                pendingCount = queue.pendingCount,
                retryWaitCount = queue.retryWaitCount,
                terminalFailedCount = queue.terminalFailedCount,
                oldestActiveAgeMillis = queue.oldestActiveAgeMillis,
                maxAttemptCount = queue.maxAttemptCount,
            )
        },
        connectionTraceContext = event.connectionTraceContext?.toItem(),
        highlight = highlight?.let { value ->
            TextHighlightItem(
                text = value.text,
                spans = value.spans.map { span -> HighlightSpanItem(span.start, span.end) },
            )
        },
    )

    private fun ConnectionTraceContext.toItem() = ConnectionTraceContextItem(
        correlationId = correlationId,
        traceId = traceId,
        sessionId = sessionId,
        connectionGeneration = connectionGeneration,
        policyRevision = policyRevision,
    )

    private fun StoredConnectionTraceEvent.toItem() = ConnectionTraceItem(
        id = id,
        uid = event.uid,
        deviceId = event.deviceId,
        correlationId = event.correlationId,
        traceId = event.traceId,
        sessionId = event.sessionId,
        connectionGeneration = event.connectionGeneration,
        policyRevision = event.policyRevision,
        occurredAt = event.occurredAt,
        phase = event.phase.name,
        outcome = event.outcome.name,
        detail = event.detail,
    )

    private fun requireAdminActor(value: String): String = value.trim().also {
        require(it.length in 1..MAX_ADMIN_ACTOR_CHARS && it.none(Char::isISOControl)) {
            "invalid telemetry admin actor"
        }
    }

    private fun safePolicyTarget(value: String): String =
        value.trim().takeIf { it.length <= MAX_POLICY_ID_CHARS && it.all(Char::isDigit) } ?: "invalid"

    private fun <T> audited(
        actor: String,
        action: TelemetryAdminAuditAction,
        target: String,
        resultOf: (T) -> TelemetryAdminAuditResult = { TelemetryAdminAuditResult.SUCCESS },
        block: () -> T,
    ): T {
        val safeActor = requireAdminActor(actor)
        val occurredAt = clock()
        val value = try {
            block()
        } catch (failure: Exception) {
            val result = if (failure is IllegalArgumentException) {
                TelemetryAdminAuditResult.REJECTED
            } else {
                TelemetryAdminAuditResult.FAILED
            }
            // 若 PostgreSQL 也不可用，则保留应用失败。下面成功的
            // 读/写操作在其强制审计无法持久化时会 fail closed。
            runCatching {
                audit.append(TelemetryAdminAuditEntry(safeActor, action, target, result, occurredAt))
            }
            throw failure
        }
        audit.append(TelemetryAdminAuditEntry(safeActor, action, target, resultOf(value), occurredAt))
        return value
    }

    private suspend fun <T> auditedSuspend(
        actor: String,
        action: TelemetryAdminAuditAction,
        target: String,
        resultOf: (T) -> TelemetryAdminAuditResult = { TelemetryAdminAuditResult.SUCCESS },
        successAuditCommittedByBlock: Boolean = false,
        block: suspend (TelemetryAdminAuditEntry) -> T,
    ): T {
        val safeActor = requireAdminActor(actor)
        val occurredAt = clock()
        val successAudit = TelemetryAdminAuditEntry(
            safeActor,
            action,
            target,
            TelemetryAdminAuditResult.SUCCESS,
            occurredAt,
        )
        val value = try {
            block(successAudit)
        } catch (failure: Exception) {
            val result = if (failure is IllegalArgumentException) {
                TelemetryAdminAuditResult.REJECTED
            } else {
                TelemetryAdminAuditResult.FAILED
            }
            runCatching {
                audit.append(TelemetryAdminAuditEntry(safeActor, action, target, result, occurredAt))
            }
            throw failure
        }
        val result = resultOf(value)
        if (!successAuditCommittedByBlock || result != TelemetryAdminAuditResult.SUCCESS) {
            audit.append(TelemetryAdminAuditEntry(safeActor, action, target, result, occurredAt))
        }
        return value
    }

    private fun ClientTelemetryPolicy.effectiveMode(now: Long): TelemetryCollectionMode =
        if (mode == TelemetryCollectionMode.DIAGNOSTIC && expiresAt?.let { it > now } == true) mode
        else TelemetryCollectionMode.BASELINE

    private fun ClientTelemetryPolicy.toPolicyItem(now: Long) = PolicyItem(
        policyId = checkNotNull(policyId),
        uid = uid,
        deviceId = deviceId,
        mode = effectiveMode(now).name,
        reason = reason,
        revision = revision,
        expiresAt = expiresAt,
        updatedAt = updatedAt,
        updatedBy = updatedBy,
        active = effectiveMode(now) == TelemetryCollectionMode.DIAGNOSTIC,
    )

    private companion object {
        const val MAX_ADMIN_ACTOR_CHARS = 100
        const val MAX_UID_CHARS = 36
        const val MAX_DEVICE_ID_CHARS = 100
        const val MAX_PHONE_CHARS = 20
        const val MAX_RUNTIME_CHARS = 128
        const val MAX_GIT_COMMIT_CHARS = 80
        const val MAX_NAME_CHARS = 96
        const val MAX_KEYWORD_CHARS = 256
        const val MAX_DEVICE_QUERY_CHARS = 128
        const val MAX_POLICY_ID_CHARS = 32
        const val MAX_POLICY_DURATION_MINUTES = 24 * 60
    }
}
