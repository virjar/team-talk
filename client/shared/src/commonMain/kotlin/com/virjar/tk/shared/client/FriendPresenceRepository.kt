package com.virjar.tk.shared.client

import com.virjar.tk.shared.log.TkLogger
import com.virjar.tk.protocol.model.FriendPresenceSnapshot
import com.virjar.tk.protocol.PresencePayload
import com.virjar.tk.shared.log.PlatformOnlyTkLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * 会话本地的好友在线状态投影。
 *
 * 在线状态保持临时性：断开连接会清除它，下一条已认证边界获取一个完整快照。实时事件在构造期间、
 * EventProcessor 能够启动之前就已订阅，而 [start] 刻意分开，以便 RPC 响应 owner 可以先启动。
 */
internal class FriendPresenceRepository(
    private val connectionState: StateFlow<ConnectionState>,
    presenceEvents: Flow<PresencePayload>,
    contactEvents: Flow<Unit>,
    private val loadSnapshot: suspend () -> FriendPresenceSnapshot,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val stateLock = Any()
    private var reducer = FriendPresenceReducerState()
    private var authenticated = false
    private var refreshGeneration = 0L
    private var started = false
    private var closed = false

    @Volatile
    private var logger: TkLogger = PlatformOnlyTkLogger("FriendPresenceRepository")

    private val _presenceByUid = MutableStateFlow<Map<String, FriendPresence>>(emptyMap())
    val presenceByUid: StateFlow<Map<String, FriendPresence>> = _presenceByUid.asStateFlow()

    private val refreshSignals = Channel<Long>(Channel.CONFLATED)
    private val scope = CoroutineScope(
        dispatcher +
            SupervisorJob() +
            CoroutineExceptionHandler { _, failure ->
                logger.fault("Friend presence worker crashed", failure)
            },
    )

    init {
        // UNDISPATCHED 会在构造返回之前到达 SharedFlow.collect。因此 EventProcessor 可以紧接着
        // 启动，而不会丢失其 replay=0 的临时事件。
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            presenceEvents.collect(::onPresenceEvent)
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            contactEvents.collect { requestSnapshotForContactChange() }
        }
    }

    internal fun bindLogger(sessionLogger: TkLogger) = synchronized(stateLock) {
        check(!started) { "FriendPresenceRepository logger must bind before start" }
        logger = sessionLogger
    }

    /** 只有在 RpcClient 安装了其响应收集器之后才启动。 */
    fun start() {
        synchronized(stateLock) {
            check(!closed) { "FriendPresenceRepository is session-owned and cannot restart after close" }
            if (started) return
            started = true
        }

        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            connectionState.collect(::onConnectionState)
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            refreshSignals.receiveAsFlow().collectLatest(::refreshSnapshot)
        }
    }

    private fun onConnectionState(state: ConnectionState) {
        val signal = synchronized(stateLock) {
            if (closed) return
            if (state == ConnectionState.AUTHENTICATED) {
                if (authenticated) return
                authenticated = true
                nextRefreshGenerationLocked()
            } else {
                if (!authenticated && reducer == FriendPresenceReducerState()) return
                authenticated = false
                reducer = reducer.disconnected()
                _presenceByUid.value = emptyMap()
                // 使来自已退役连接的在途请求失效并取消它。
                nextRefreshGenerationLocked()
            }
        }
        refreshSignals.trySend(signal)
    }

    private fun onPresenceEvent(event: PresencePayload) {
        val signal = synchronized(stateLock) {
            if (
                closed ||
                !authenticated ||
                connectionState.value != ConnectionState.AUTHENTICATED
            ) {
                return
            }
            val epochMismatch = reducer.serverEpoch != event.serverEpoch
            reducer = reducer.reduce(event)
            _presenceByUid.value = reducer.presenceByUid
            if (epochMismatch) nextRefreshGenerationLocked() else null
        }
        signal?.let(refreshSignals::trySend)
    }

    private fun requestSnapshotForContactChange() {
        val signal = synchronized(stateLock) {
            if (
                closed ||
                !authenticated ||
                connectionState.value != ConnectionState.AUTHENTICATED
            ) {
                return
            }
            nextRefreshGenerationLocked()
        }
        refreshSignals.trySend(signal)
    }

    private suspend fun refreshSnapshot(generation: Long) {
        val mayLoad = synchronized(stateLock) {
            !closed &&
                authenticated &&
                refreshGeneration == generation &&
                connectionState.value == ConnectionState.AUTHENTICATED
        }
        if (!mayLoad) return

        val snapshot = try {
            loadSnapshot()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val stillCurrent = synchronized(stateLock) {
                !closed && authenticated && refreshGeneration == generation
            }
            if (stillCurrent) {
                logger.trace(
                    "Friend presence snapshot unavailable (${failure::class.simpleName}); " +
                        "waiting for a later refresh edge",
                )
            }
            return
        }

        synchronized(stateLock) {
            // RPC 取消是建议性的。代际检查同样会隔断那些在断开、联系人变更或 epoch 变更之后
            // 无视取消的 loader 的响应。
            if (
                closed ||
                !authenticated ||
                refreshGeneration != generation ||
                connectionState.value != ConnectionState.AUTHENTICATED
            ) {
                return
            }
            reducer = reducer.reduce(snapshot)
            _presenceByUid.value = reducer.presenceByUid
        }
    }

    private fun nextRefreshGenerationLocked(): Long {
        refreshGeneration += 1L
        return refreshGeneration
    }

    fun close() {
        val newlyClosed = synchronized(stateLock) {
            if (closed) return
            closed = true
            authenticated = false
            nextRefreshGenerationLocked()
            reducer = reducer.disconnected()
            _presenceByUid.value = emptyMap()
            true
        }
        if (!newlyClosed) return
        refreshSignals.close()
        scope.cancel()
    }
}
