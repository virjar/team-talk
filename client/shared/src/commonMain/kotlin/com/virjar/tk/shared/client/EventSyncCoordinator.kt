package com.virjar.tk.shared.client

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.payload.NotifyPayload
import com.virjar.tk.protocol.payload.SyncBatchPayload
import com.virjar.tk.protocol.payload.SyncRequestPayload
import com.virjar.tk.protocol.payload.SyncResetPayload
import com.virjar.tk.shared.log.PlatformOnlyTkLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 在 AUTH 选定 dataset 之后拥有持久事件同步尝试。
 *
 * 认证决定哪个身份与 dataset 可以同步。该协作者拥有投影绑定、检查点 reset、连续尾部重放，以及
 * 精确的在途准入——它防止即时 loopback 响应被误认为重叠页。入站变更仍经由 [AuthSyncCoordinator]
 * 的 transport EventLoop 到达；异步投影完成在触及尝试状态之前通过精确线格式准入重新进入。
 */
internal class EventSyncCoordinator(
    private val connectionState: () -> ConnectionState,
    private val transitionTo: (ConnectionState) -> Unit,
    private val connectionScope: () -> CoroutineScope?,
    private val writeProtocol: (IProto) -> Boolean,
    private val closeTransport: (reason: String, cause: Throwable?) -> Unit,
    private val onSynchronizationReady: () -> Unit,
) {
    private val logger = PlatformOnlyTkLogger("EventSyncCoordinator")

    private data class Binding(
        val owner: Any,
        val expectedUid: String?,
        val wireAdmission: WireSendAdmission,
        val datasetId: () -> String,
        val cursor: () -> Long,
        val processBatch: suspend (List<NotifyPayload>, reportProgress: (Long) -> Unit) -> Long,
        val applyCheckpoint: suspend (String, reportProgress: () -> Unit) -> Long,
    )

    private var binding: Binding? = null
    /** 抑制排在同步退役会话绑定后面的控制包。 */
    private var bindingRetired = false
    private var batchInFlight = false
    private var resetApplied = false
    private var lastRequestedCursor = -1L
    private var attemptGeneration = 0L
    /** 本次 transport 尝试的 AUTH 确认权威；同步之外为 null。 */
    private var authenticatedDatasetId: String? = null

    private val _cursor = MutableStateFlow(-1L)
    val cursor: StateFlow<Long> = _cursor.asStateFlow()

    /**
     * 检查点页的进程内单调进度脉冲，这些页在持久游标可以推进之前先被暂存。它不持久化，也不携带
     * 线格式权威。
     */
    private val _progress = MutableStateFlow(0L)
    val progress: StateFlow<Long> = _progress.asStateFlow()

    val expectedUid: String?
        get() = binding?.expectedUid

    fun install(
        owner: Any,
        expectedUid: String?,
        wireAdmission: WireSendAdmission,
        datasetId: () -> String,
        cursor: () -> Long,
        processBatch: suspend (List<NotifyPayload>, reportProgress: (Long) -> Unit) -> Long,
        applyCheckpoint: suspend (String, reportProgress: () -> Unit) -> Long,
    ) {
        val previous = binding
        require(expectedUid == null || expectedUid.isNotBlank()) {
            "Event sync owner uid must not be blank"
        }
        bindingRetired = false
        binding = Binding(
            owner,
            expectedUid,
            wireAdmission,
            datasetId,
            cursor,
            processBatch,
            applyCheckpoint,
        )
        if (
            previous != null &&
            previous.owner !== owner &&
            connectionState() == ConnectionState.SYNCHRONIZING
        ) {
            closeForResync("Event sync projection owner changed during synchronization")
            return
        }
        beginIfReady()
    }

    fun remove(owner: Any) {
        val removed = binding?.takeIf { it.owner === owner } ?: return
        binding = null
        bindingRetired = !removed.wireAdmission.isActive()
        if (!bindingRetired && connectionState() == ConnectionState.SYNCHRONIZING) {
            closeForResync("Event sync projection owner was removed during synchronization")
        }
    }

    fun isOwner(owner: Any): Boolean = binding?.owner === owner

    /** 只有在 AUTH 凭据/dataset 准入完成之后才启动新的同步尝试。 */
    fun beginAuthenticatedAttempt(datasetId: String) {
        resetAttempt()
        authenticatedDatasetId = datasetId
        transitionTo(ConnectionState.SYNCHRONIZING)
        beginIfReady()
    }

    fun handleBatch(batch: SyncBatchPayload) {
        handleEvents(batch.events)
    }

    /** 最大尺寸的持久事件在重放期间可能作为独立的 NOTIFY 发送。 */
    fun handleEvent(event: NotifyPayload) {
        handleEvents(listOf(event))
    }

    fun handleReady() {
        val currentBinding = binding
        if (currentBinding == null) {
            if (bindingRetired) return
            closeForResync("Unexpected SYNC_READY")
            return
        }
        var admitted = false
        var stillCurrent = false
        currentBinding.wireAdmission.use {
            admitted = true
            stillCurrent = binding === currentBinding &&
                connectionState() == ConnectionState.SYNCHRONIZING &&
                !batchInFlight &&
                lastRequestedCursor >= 0L
            if (stillCurrent) {
                onSynchronizationReady()
                transitionTo(ConnectionState.AUTHENTICATED)
            } else {
                closeForResync("Unexpected SYNC_READY")
            }
            true
        }
        if (!admitted || !stillCurrent) return
        logger.trace("Persistent event sync ready at cursor=$lastRequestedCursor")
    }

    fun handleReset(payload: SyncResetPayload) {
        val currentBinding = binding
        val scope = connectionScope()
        if (currentBinding == null || scope == null) {
            if (currentBinding == null && bindingRetired) return
            closeForResync("SYNC_RESET arrived without an active projection owner")
            return
        }
        var admitted = false
        var valid = false
        currentBinding.wireAdmission.use {
            admitted = true
            valid = binding === currentBinding &&
                connectionState() == ConnectionState.SYNCHRONIZING &&
                !batchInFlight &&
                !resetApplied &&
                lastRequestedCursor >= 0L &&
                payload.datasetId == authenticatedDatasetId
            if (valid) {
                resetApplied = true
                batchInFlight = true
            } else {
                closeForResync("Unexpected, overlapping, or repeated SYNC_RESET")
            }
            true
        }
        if (!admitted || !valid) return
        val generation = attemptGeneration
        var resetAdmissionOwned = true
        scope.launch {
            try {
                val checkpointCursor = currentBinding.applyCheckpoint(payload.datasetId) {
                    reportCheckpointProgress(generation, currentBinding)
                }
                if (!isCurrentAttempt(generation, currentBinding)) return@launch
                check(checkpointCursor >= 0L) {
                    "Projection checkpoint returned a negative cursor=$checkpointCursor"
                }
                check(connectionState() == ConnectionState.SYNCHRONIZING) {
                    "Connection left synchronization during projection checkpoint"
                }
                var admitted = false
                var current = false
                currentBinding.wireAdmission.use {
                    admitted = true
                    current = isCurrentAttempt(generation, currentBinding)
                    if (!current) return@use true
                    // 在发布下一次线格式请求之前释放。loopback 服务器可能立即应答；finally 绝不能
                    // 清除更新页的准入。
                    batchInFlight = false
                    resetAdmissionOwned = false
                    lastRequestedCursor = checkpointCursor
                    _cursor.value = checkpointCursor
                    reportProgress()
                    if (!writeProtocol(SyncRequestPayload(checkpointCursor, payload.datasetId))) {
                        closeForResync("Connection closed during projection checkpoint")
                    }
                    true
                }
                if (!admitted || !current) return@launch
                logger.trace(
                    "Server projection checkpoint applied; event sync restarted from " +
                        "cursor=$checkpointCursor",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                currentBinding.wireAdmission.use {
                    if (isCurrentAttempt(generation, currentBinding)) {
                        closeForResync("Failed to apply server projection checkpoint", failure)
                    }
                    true
                }
            } finally {
                currentBinding.wireAdmission.use {
                    if (
                        resetAdmissionOwned &&
                        generation == attemptGeneration &&
                        binding === currentBinding
                    ) {
                        batchInFlight = false
                    }
                    true
                }
            }
        }
    }

    /** 每当一次尝试被取代或断开时，由 transport owner 调用一次。 */
    fun onTransportDisconnected() {
        resetAttempt()
        authenticatedDatasetId = null
    }

    private fun reportCheckpointProgress(
        generation: Long,
        currentBinding: Binding,
    ) {
        connectionScope()?.launch {
            currentBinding.wireAdmission.use {
                if (isCurrentAttempt(generation, currentBinding)) reportProgress()
                true
            }
        }
    }

    private fun reportProgress() {
        check(_progress.value < Long.MAX_VALUE) { "Event sync progress exhausted" }
        _progress.value += 1L
    }

    private fun beginIfReady() {
        if (
            connectionState() != ConnectionState.SYNCHRONIZING ||
            lastRequestedCursor >= 0L
        ) {
            return
        }
        val currentBinding = binding ?: return
        val serverDatasetId = authenticatedDatasetId ?: return
        var admitted = false
        var attemptedWrite = false
        var initialCursor = -1L
        currentBinding.wireAdmission.use {
            admitted = true
            if (
                binding !== currentBinding ||
                connectionState() != ConnectionState.SYNCHRONIZING ||
                lastRequestedCursor >= 0L
            ) return@use true
            if (currentBinding.datasetId() != serverDatasetId) {
                // AUTH 已持久安装一个替代 dataset。认证根必须先退役旧 dataset 范围的 cache/平台图
                // 并安装替代者，之后才允许该连接把任何数值游标放上线。
                return@use true
            }
            initialCursor = currentBinding.cursor()
            if (initialCursor < 0L) {
                closeForResync("Persistent event cursor is negative: $initialCursor")
                return@use true
            }
            lastRequestedCursor = initialCursor
            _cursor.value = initialCursor
            attemptedWrite = true
            if (!writeProtocol(SyncRequestPayload(initialCursor, serverDatasetId))) {
                closeForResync("Connection closed before the first sync request")
            }
            true
        }
        if (!admitted || initialCursor < 0L || !attemptedWrite) return
        logger.trace("Event sync requested after cursor=$initialCursor")
    }

    private fun handleEvents(events: List<NotifyPayload>) {
        val currentBinding = binding
        val scope = connectionScope()
        if (currentBinding == null || scope == null) {
            if (currentBinding == null && bindingRetired) return
            closeForResync("Sync batch arrived without an active projection owner")
            return
        }
        var admitted = false
        var valid = false
        var requestedAfter = -1L
        currentBinding.wireAdmission.use {
            admitted = true
            requestedAfter = lastRequestedCursor
            valid = binding === currentBinding &&
                connectionState() == ConnectionState.SYNCHRONIZING &&
                !batchInFlight &&
                isContiguousSyncBatch(events, requestedAfter)
            if (valid) {
                batchInFlight = true
            } else {
                closeForResync(
                    "Sync events are not contiguous after requested cursor=$requestedAfter",
                )
            }
            true
        }
        if (!admitted || !valid) return
        val generation = attemptGeneration
        val expectedCursor = events.last().eventId
        var batchAdmissionOwned = true
        val reportProgress: (Long) -> Unit = { cursor ->
            reportSyncBatchProgress(currentBinding, generation, cursor, requestedAfter, expectedCursor)
        }
        scope.launch {
            try {
                val persistedCursor = currentBinding.processBatch(events, reportProgress)
                if (!isCurrentAttempt(generation, currentBinding)) return@launch
                check(persistedCursor == expectedCursor) {
                    "Sync projection stopped at $persistedCursor instead of $expectedCursor"
                }
                var admitted = false
                var current = false
                currentBinding.wireAdmission.use {
                    admitted = true
                    current = isCurrentAttempt(generation, currentBinding)
                    if (!current) return@use true
                    // 一旦这次写入到达 EventLoop，下一条响应可能立即到来。
                    // 先释放这一页，并让 finally 块感知 ownership。
                    batchInFlight = false
                    batchAdmissionOwned = false
                    lastRequestedCursor = persistedCursor
                    _cursor.value = persistedCursor
                    val serverDatasetId = authenticatedDatasetId
                    if (
                        serverDatasetId == null || currentBinding.datasetId() != serverDatasetId ||
                        !writeProtocol(SyncRequestPayload(persistedCursor, serverDatasetId))
                    ) {
                        closeForResync("Connection closed during event synchronization")
                    }
                    true
                }
                if (!admitted || !current) return@launch
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                currentBinding.wireAdmission.use {
                    if (isCurrentAttempt(generation, currentBinding)) {
                        closeForResync("Failed to persist sync batch", failure)
                    }
                    true
                }
            } finally {
                currentBinding.wireAdmission.use {
                    if (
                        batchAdmissionOwned &&
                        generation == attemptGeneration &&
                        binding === currentBinding
                    ) {
                        batchInFlight = false
                    }
                    true
                }
            }
        }
    }

    /** 批次有效性谓词：事件非空、eventId 全为正、从 requestedAfter+1 起严格逐条连续。 */
    private fun isContiguousSyncBatch(events: List<NotifyPayload>, requestedAfter: Long): Boolean {
        if (requestedAfter < 0L || requestedAfter == Long.MAX_VALUE) return false
        if (events.isEmpty() || events.any { it.eventId <= 0L }) return false
        if (events.first().eventId != requestedAfter + 1L) return false
        return events.zipWithNext().all { (left, right) -> right.eventId == left.eventId + 1L }
    }

    /** 投影进度上报：越界进度触发 resync，单调前进才推进公开游标。 */
    private fun reportSyncBatchProgress(
        currentBinding: Binding,
        generation: Long,
        cursor: Long,
        requestedAfter: Long,
        expectedCursor: Long,
    ) {
        connectionScope()?.launch {
            var invalidProgress = false
            val progressAdmitted = currentBinding.wireAdmission.use {
                if (!isCurrentAttempt(generation, currentBinding)) return@use true
                if (cursor <= requestedAfter || cursor > expectedCursor) {
                    invalidProgress = true
                    closeForResync(
                        "Sync projection reported invalid progress=$cursor for " +
                            "requested=$requestedAfter expected=$expectedCursor",
                    )
                } else if (cursor > _cursor.value) {
                    _cursor.value = cursor
                }
                true
            }
            if (!progressAdmitted || invalidProgress) return@launch
        }
    }

    private fun resetAttempt() {
        check(attemptGeneration < Long.MAX_VALUE) { "Sync attempt generation exhausted" }
        attemptGeneration += 1L
        batchInFlight = false
        resetApplied = false
        lastRequestedCursor = -1L
        _cursor.value = -1L
    }

    private fun isCurrentAttempt(
        generation: Long,
        currentBinding: Binding,
    ): Boolean =
        generation == attemptGeneration &&
            binding === currentBinding &&
            connectionState() == ConnectionState.SYNCHRONIZING

    private fun closeForResync(reason: String, cause: Throwable? = null) {
        closeTransport(reason, cause)
    }
}
