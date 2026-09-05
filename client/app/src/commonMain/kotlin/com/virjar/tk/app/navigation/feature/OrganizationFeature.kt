package com.virjar.tk.app.navigation.feature

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.client.ClientSession
import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.shared.client.OrganizationMemberProjection
import com.virjar.tk.shared.client.OrganizationUnitProjection
import com.virjar.tk.app.navigation.UiLocalDataBoundary
import com.virjar.tk.protocol.model.OrganizationMember
import com.virjar.tk.protocol.model.OrganizationUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 终端用户只读的组织目录状态；组织结构写入统一由管理端治理。 */
class OrganizationFeature internal constructor(
    private val loadUnits: suspend () -> Outcome<List<OrganizationUnit>>,
    private val loadMembers: suspend (String) -> Outcome<OrganizationMemberProjection>,
    private val reportError: (Throwable, String) -> Unit,
    private val observeUnits: () -> Flow<OrganizationUnitProjection>,
    private val cachedUnits: () -> OrganizationUnitProjection,
    private val observeMembers: (String) -> Flow<OrganizationMemberProjection>,
    private val cachedMembers: (String) -> OrganizationMemberProjection,
    private val organizationEvents: Flow<Long>,
    private val connectionState: StateFlow<ConnectionState>,
    private val scope: CoroutineScope,
    private val localData: UiLocalDataBoundary,
) {
    internal constructor(
        session: ClientSession,
        scope: CoroutineScope,
        reportError: (Throwable, String) -> Unit,
        localData: UiLocalDataBoundary,
    ) : this(
        loadUnits = { session.organizationRepo.refreshUnits() },
        loadMembers = { unitId -> session.organizationRepo.refreshMemberProjection(unitId) },
        reportError = reportError,
        observeUnits = session.organizationRepo::observeUnitProjection,
        cachedUnits = session.organizationRepo::cachedUnitProjection,
        observeMembers = session.organizationRepo::observeMemberProjection,
        cachedMembers = session.organizationRepo::cachedMemberProjection,
        organizationEvents = session.eventProcessor.organizationEvents,
        connectionState = session.connectionState,
        scope = scope,
        localData = localData,
    )

    var units by mutableStateOf(emptyList<OrganizationUnit>())
        private set
    var initialized by mutableStateOf(false)
        private set
    var unitSnapshotKnown by mutableStateOf(false)
        private set
    var revision by mutableStateOf(0L)
        private set
    var selectedUnitId by mutableStateOf<String?>(null)
        private set
    var members by mutableStateOf(emptyList<OrganizationMember>())
        private set
    var memberSnapshotKnown by mutableStateOf(false)
        private set
    var memberRevision by mutableStateOf(0L)
        private set
    var membersLoading by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set

    private val refreshGate = GenerationGate()
    private val membersGate = LatestRequestGate<String?>()
    private val refreshMutex = Mutex()
    private var memberProjectionJob: Job? = null
    private var reconnectRefreshJob: Job? = null
    private val eventRefreshSignals = Channel<Unit>(Channel.CONFLATED)
    private var latestEventRevision = 0L
    private var handledEventRevision = 0L

    init {
        scope.launch {
            localData.projection(observeUnits).collect { projection ->
                applyUnitProjection(projection)
            }
        }
        scope.launch {
            organizationEvents.collect { eventRevision ->
                if (eventRevision <= latestEventRevision) return@collect
                latestEventRevision = eventRevision
                if (connectionState.value == ConnectionState.AUTHENTICATED) {
                    eventRefreshSignals.trySend(Unit)
                }
            }
        }
        scope.launch {
            for (ignored in eventRefreshSignals) refreshPendingEvents()
        }
        scope.launch {
            var wasAuthenticated = false
            connectionState.collect { state ->
                val nowAuthenticated = state == ConnectionState.AUTHENTICATED
                if (nowAuthenticated && !wasAuthenticated) {
                    reconnectRefreshJob?.cancel()
                    reconnectRefreshJob = scope.launch {
                        val refreshed = refreshFromServer(reportFailures = false)
                        if (refreshed) markCoveredEventsHandled()
                    }
                }
                wasAuthenticated = nowAuthenticated
            }
        }
    }

    suspend fun refresh() {
        refreshFromServer(reportFailures = true)
    }

    private suspend fun refreshFromServer(
        reportFailures: Boolean,
        requiredRevision: Long? = null,
    ): Boolean {
        if (connectionState.value != ConnectionState.AUTHENTICATED) return false
        val generation = refreshGate.next()
        loading = true
        try {
            return refreshMutex.withLock {
                if (connectionState.value != ConnectionState.AUTHENTICATED) return@withLock false
                if (!refreshGate.isCurrent(generation)) return@withLock false
                if (requiredRevision != null && coversRevision(requiredRevision)) {
                    return@withLock true
                }

                val mustRefreshUnits = requiredRevision == null ||
                    !unitSnapshotKnown ||
                    revision < requiredRevision
                val target = if (mustRefreshUnits) {
                    when (val result = localData.run { loadUnits() }) {
                        is Outcome.Success -> if (refreshGate.isCurrent(generation)) {
                            applyUnitProjection(localData.run(cachedUnits))
                        } else {
                            return@withLock false
                        }

                        is Outcome.Failure -> {
                            if (
                                refreshGate.isCurrent(generation) &&
                                reportFailures &&
                                shouldReportCacheRefreshFailure(result.error, units.isNotEmpty())
                            ) {
                                reportError(result.error, "加载组织架构失败")
                            }
                            return@withLock false
                        }
                    }
                } else {
                    selectedUnitId
                }

                refreshSelectedUnit(target, reportFailures)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            if (refreshGate.isCurrent(generation)) loading = false
        }
    }

    suspend fun selectUnit(unitId: String?) {
        refreshSelectedUnit(unitId, reportFailures = true)
    }

    private suspend fun refreshSelectedUnit(unitId: String?, reportFailures: Boolean): Boolean {
        val token = bindUnitProjection(unitId)
        if (unitId == null) return true

        // 选择是本地优先的，而不仅仅是最终本地化。在存储边界读取持久快照，
        // 可以防止一次立即失败的 RPC 在长寿命投影收集器收到它的第一个值之前闪现错误。
        val cached = localData.run { cachedMembers(unitId) }
        if (!membersGate.isCurrent(token) || selectedUnitId != unitId) return false
        publishMemberProjection(cached)
        if (connectionState.value != ConnectionState.AUTHENTICATED) return false
        membersLoading = true
        try {
            return when (val result = localData.run { loadMembers(unitId) }) {
                is Outcome.Success -> if (membersGate.isCurrent(token) && selectedUnitId == unitId) {
                    // repository 已经把这个值提交到 LocalCache。这里也立即赋值，
                    // 这样发起请求无需等待投影收集器。
                    publishMemberProjection(result.value)
                    true
                } else {
                    false
                }
                is Outcome.Failure -> if (
                    membersGate.isCurrent(token) &&
                    selectedUnitId == unitId &&
                    reportFailures &&
                    shouldReportCacheRefreshFailure(
                        result.error,
                        memberSnapshotKnown || members.isNotEmpty(),
                    )
                ) {
                    reportError(result.error, "加载部门成员失败")
                    false
                } else {
                    false
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            if (membersGate.isCurrent(token) && selectedUnitId == unitId) {
                membersLoading = false
            }
        }
    }

    private fun applyUnitProjection(projection: OrganizationUnitProjection): String? {
        initialized = true
        unitSnapshotKnown = projection.snapshotKnown
        revision = projection.revision
        units = projection.units
        val target = selectedUnitId?.takeIf { selected -> units.any { it.unitId == selected } }
            ?: units.firstOrNull { it.parentId == null }?.unitId
            ?: units.firstOrNull()?.unitId
        if (target != selectedUnitId || (target == null && members.isNotEmpty())) {
            bindUnitProjection(target)
        }
        return target
    }

    private fun bindUnitProjection(unitId: String?): LatestRequestGate.Token<String?> {
        val token = membersGate.begin(unitId)
        val targetChanged = selectedUnitId != unitId
        selectedUnitId = unitId
        if (targetChanged || unitId == null) {
            members = emptyList()
            memberSnapshotKnown = false
            memberRevision = 0L
        }
        membersLoading = false
        memberProjectionJob?.cancel()
        memberProjectionJob = null
        if (unitId != null) {
            memberProjectionJob = scope.launch {
                localData.projection { observeMembers(unitId) }.collect { projection ->
                    if (membersGate.isCurrent(token) && selectedUnitId == unitId) {
                        publishMemberProjection(projection)
                    }
                }
            }
        }
        return token
    }

    private fun publishMemberProjection(projection: OrganizationMemberProjection) {
        members = projection.members
        memberSnapshotKnown = projection.snapshotKnown
        memberRevision = projection.revision
    }

    /**
     * 组织通知是持久投影的提示。一个 worker 消费观察到的最高 revision；
     * RPC 期间到达的事件由那个响应覆盖，或至多由一次后续遍历覆盖。
     * 失败遍历刻意停止，直到另一个事件/重连。
     */
    private suspend fun refreshPendingEvents() {
        while (connectionState.value == ConnectionState.AUTHENTICATED) {
            val targetRevision = latestEventRevision
            if (targetRevision <= handledEventRevision) return
            if (coversRevision(targetRevision)) {
                handledEventRevision = targetRevision
            } else if (!refreshFromServer(
                    reportFailures = false,
                    requiredRevision = targetRevision,
                )
            ) {
                return
            } else if (coversRevision(targetRevision)) {
                handledEventRevision = targetRevision
            } else {
                return
            }
            if (latestEventRevision <= handledEventRevision) return
        }
    }

    private fun coversRevision(targetRevision: Long): Boolean =
        initialized &&
            unitSnapshotKnown &&
            revision >= targetRevision &&
            (selectedUnitId == null || (
                memberSnapshotKnown && memberRevision >= targetRevision
                ))

    private fun markCoveredEventsHandled() {
        val targetRevision = latestEventRevision
        if (targetRevision > handledEventRevision && coversRevision(targetRevision)) {
            handledEventRevision = targetRevision
        }
    }
}
