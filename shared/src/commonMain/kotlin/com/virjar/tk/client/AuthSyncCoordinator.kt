package com.virjar.tk.client

import com.virjar.tk.log.TkLoggerFactory
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.payload.AuthRequestPayload
import com.virjar.tk.protocol.payload.AuthResponsePayload
import com.virjar.tk.protocol.payload.NotifyPayload
import com.virjar.tk.protocol.payload.SyncBatchPayload
import com.virjar.tk.protocol.payload.SyncRequestPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the authentication and durable-event synchronization state machine.
 *
 * The coordinator is independent of Netty. Production calls every mutating method from the active
 * transport EventLoop; deterministic tests can therefore drive the same state machine with a
 * single-threaded test scope. Authentication material, terminal status, projection owner and sync
 * cursor live only here—transport and packet routing receive read-only callbacks.
 */
internal class AuthSyncCoordinator(
    private val connectionState: () -> ConnectionState,
    private val transitionTo: (ConnectionState) -> Unit,
    private val connectionScope: () -> CoroutineScope?,
    private val writeProtocol: (IProto) -> Boolean,
    private val closeTransport: (reason: String, cause: Throwable?) -> Unit,
    private val onAuthenticationAccepted: () -> Unit,
    private val publishAuthResponse: (AuthResponsePayload) -> Unit,
    private val onAuthResult: ((success: Boolean, uid: String?, username: String?, name: String?, refreshToken: String?, accessToken: String?, failureReason: String?) -> Unit)?,
) {
    private val logger = TkLoggerFactory.get("AuthSyncCoordinator")

    private data class EventSyncBinding(
        val owner: Any,
        val cursor: () -> Long,
        val processBatch: suspend (List<NotifyPayload>, reportProgress: (Long) -> Unit) -> Long,
        val reset: suspend () -> Long,
    )

    /** Reused after a transport reconnect; upgraded to refresh-token auth after a successful AUTH. */
    private var pendingAuth: AuthRequestPayload? = null

    /** A server-declared AUTH failure is terminal until an explicit new authentication attempt. */
    private var authenticationTerminal = false

    private var eventSyncBinding: EventSyncBinding? = null
    private var syncBatchInFlight = false
    private var syncResetApplied = false
    private var lastRequestedSyncCursor = -1L
    private var syncAttemptGeneration = 0L

    private val _authenticationFailure = MutableStateFlow<AuthenticationFailure?>(null)
    val authenticationFailure: StateFlow<AuthenticationFailure?> =
        _authenticationFailure.asStateFlow()

    /** Durable projection progress, or -1 while this transport is outside synchronization. */
    private val _eventSyncCursor = MutableStateFlow(-1L)
    val eventSyncCursor: StateFlow<Long> = _eventSyncCursor.asStateFlow()

    fun prepareAuthentication(auth: AuthRequestPayload) {
        pendingAuth = auth
        authenticationTerminal = false
        _authenticationFailure.value = null
    }

    fun authenticationPayload(): AuthRequestPayload? = pendingAuth

    fun isAuthenticationTerminal(): Boolean = authenticationTerminal

    fun installEventSync(
        owner: Any,
        cursor: () -> Long,
        processBatch: suspend (List<NotifyPayload>, reportProgress: (Long) -> Unit) -> Long,
        reset: suspend () -> Long,
    ) {
        val previous = eventSyncBinding
        eventSyncBinding = EventSyncBinding(owner, cursor, processBatch, reset)
        if (
            previous != null &&
            previous.owner !== owner &&
            connectionState() == ConnectionState.SYNCHRONIZING
        ) {
            closeForEventResync("Event sync projection owner changed during synchronization")
            return
        }
        beginEventSyncIfReady()
    }

    fun removeEventSync(owner: Any) {
        if (eventSyncBinding?.owner !== owner) return
        eventSyncBinding = null
        if (connectionState() == ConnectionState.SYNCHRONIZING) {
            closeForEventResync("Event sync projection owner was removed during synchronization")
        }
    }

    fun isEventSyncOwner(owner: Any): Boolean = eventSyncBinding?.owner === owner

    fun closeForEventResync(reason: String, cause: Throwable? = null) {
        closeTransport(reason, cause)
    }

    fun handleAuthResponse(response: AuthResponsePayload) {
        if (connectionState() != ConnectionState.CONNECTED) {
            closeForEventResync("Unexpected AUTH response in state=${connectionState()}")
            return
        }
        if (response.code == AuthResponsePayload.CODE_OK) {
            val uid = response.uid?.takeIf(String::isNotBlank)
            val username = response.username?.takeIf(String::isNotBlank)
            val name = response.name?.takeIf(String::isNotBlank)
            val refreshToken = response.refreshToken?.takeIf(String::isNotBlank)
            val accessToken = response.accessToken?.takeIf(String::isNotBlank)
            if (uid == null || username == null || name == null || refreshToken == null || accessToken == null) {
                val reason = "服务器认证成功响应缺少必需身份或令牌字段"
                pendingAuth = null
                authenticationTerminal = true
                _authenticationFailure.value = AuthenticationFailure(
                    kind = AuthenticationFailureKind.REJECTED,
                    reason = reason,
                )
                transitionTo(ConnectionState.AUTH_FAILED)
                onAuthResult?.invoke(false, null, null, null, null, null, reason)
                publishAuthResponse(response)
                return
            }
            _authenticationFailure.value = null
            // Login/register credentials are one-shot. Every network reconnect after success uses
            // the latest server-rotated refresh token without retaining the password.
            pendingAuth = pendingAuth?.copy(
                authType = 2,
                refreshToken = refreshToken,
                username = null,
                password = null,
                name = null,
            )
            onAuthResult?.invoke(
                true,
                uid,
                username,
                name,
                refreshToken,
                accessToken,
                null,
            )
            resetSyncAttempt()
            transitionTo(ConnectionState.SYNCHRONIZING)
            beginEventSyncIfReady()
            logger.trace(
                "Identity authenticated; synchronizing uid=${response.uid}, " +
                    "username=${response.username}",
            )
        } else {
            val failure = checkNotNull(response.toAuthenticationFailure())
            pendingAuth = null
            authenticationTerminal = true
            _authenticationFailure.value = failure
            transitionTo(ConnectionState.AUTH_FAILED)
            onAuthResult?.invoke(false, null, null, null, null, null, failure.reason)
            logger.trace("Auth failed (terminal): code=${response.code}, reason=${response.reason}")
        }
        publishAuthResponse(response)
    }

    fun handleSyncBatch(batch: SyncBatchPayload) {
        handleSyncEvents(batch.events)
    }

    /** A maximum-sized durable event may be sent as a standalone NOTIFY during replay. */
    fun handleSyncEvent(event: NotifyPayload) {
        handleSyncEvents(listOf(event))
    }

    fun handleSyncReady() {
        if (
            connectionState() != ConnectionState.SYNCHRONIZING ||
            syncBatchInFlight ||
            lastRequestedSyncCursor < 0L ||
            eventSyncBinding == null
        ) {
            closeForEventResync("Unexpected SYNC_READY")
            return
        }
        onAuthenticationAccepted()
        transitionTo(ConnectionState.AUTHENTICATED)
        logger.trace("Persistent event sync ready at cursor=$lastRequestedSyncCursor")
    }

    fun handleSyncReset() {
        if (
            connectionState() != ConnectionState.SYNCHRONIZING ||
            syncBatchInFlight ||
            syncResetApplied ||
            lastRequestedSyncCursor < 0L
        ) {
            closeForEventResync("Unexpected, overlapping, or repeated SYNC_RESET")
            return
        }
        val binding = eventSyncBinding
        val scope = connectionScope()
        if (binding == null || scope == null) {
            closeForEventResync("SYNC_RESET arrived without an active projection owner")
            return
        }
        syncResetApplied = true
        syncBatchInFlight = true
        val attemptGeneration = syncAttemptGeneration
        scope.launch {
            try {
                val resetCursor = binding.reset()
                if (!isCurrentAttempt(attemptGeneration, binding)) return@launch
                check(resetCursor == 0L) { "Projection reset returned cursor=$resetCursor" }
                check(connectionState() == ConnectionState.SYNCHRONIZING) {
                    "Connection left synchronization during projection reset"
                }
                lastRequestedSyncCursor = 0L
                _eventSyncCursor.value = 0L
                check(writeProtocol(SyncRequestPayload(0L))) {
                    "Connection closed during projection reset"
                }
                logger.trace("Server projection reset; event sync restarted from cursor=0")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (isCurrentAttempt(attemptGeneration, binding)) {
                    closeForEventResync("Failed to reset server projection", failure)
                }
            } finally {
                if (attemptGeneration == syncAttemptGeneration) {
                    syncBatchInFlight = false
                }
            }
        }
    }

    /** Called once by the transport owner whenever an attempt is superseded or disconnected. */
    fun onTransportDisconnected() {
        resetSyncAttempt()
    }

    private fun beginEventSyncIfReady() {
        if (
            connectionState() != ConnectionState.SYNCHRONIZING ||
            lastRequestedSyncCursor >= 0L
        ) {
            return
        }
        val binding = eventSyncBinding ?: return
        val initialCursor = binding.cursor()
        if (initialCursor < 0L) {
            closeForEventResync("Persistent event cursor is negative: $initialCursor")
            return
        }
        lastRequestedSyncCursor = initialCursor
        _eventSyncCursor.value = initialCursor
        if (!writeProtocol(SyncRequestPayload(initialCursor))) {
            closeForEventResync("Connection closed before the first sync request")
            return
        }
        logger.trace("Event sync requested after cursor=$initialCursor")
    }

    private fun handleSyncEvents(events: List<NotifyPayload>) {
        if (connectionState() != ConnectionState.SYNCHRONIZING || syncBatchInFlight) {
            closeForEventResync("Unexpected or overlapping sync batch")
            return
        }
        val binding = eventSyncBinding
        val scope = connectionScope()
        if (binding == null || scope == null) {
            closeForEventResync("Sync batch arrived without an active projection owner")
            return
        }
        val requestedAfter = lastRequestedSyncCursor
        val hasCursorOverflow = requestedAfter == Long.MAX_VALUE
        if (
            requestedAfter < 0L ||
            hasCursorOverflow ||
            events.isEmpty() ||
            events.any { it.eventId <= 0L } ||
            events.first().eventId != requestedAfter + 1L ||
            events.zipWithNext().any { (left, right) -> right.eventId != left.eventId + 1L }
        ) {
            closeForEventResync(
                "Sync events are not contiguous after requested cursor=$requestedAfter",
            )
            return
        }
        syncBatchInFlight = true
        val attemptGeneration = syncAttemptGeneration
        val expectedCursor = events.last().eventId
        val reportProgress: (Long) -> Unit = { cursor ->
            connectionScope()?.launch {
                if (!isCurrentAttempt(attemptGeneration, binding)) return@launch
                if (cursor <= requestedAfter || cursor > expectedCursor) {
                    closeForEventResync(
                        "Sync projection reported invalid progress=$cursor for " +
                            "requested=$requestedAfter expected=$expectedCursor",
                    )
                } else if (cursor > _eventSyncCursor.value) {
                    _eventSyncCursor.value = cursor
                }
            }
        }
        scope.launch {
            try {
                val persistedCursor = binding.processBatch(events, reportProgress)
                if (!isCurrentAttempt(attemptGeneration, binding)) return@launch
                check(persistedCursor == expectedCursor) {
                    "Sync projection stopped at $persistedCursor instead of $expectedCursor"
                }
                lastRequestedSyncCursor = persistedCursor
                _eventSyncCursor.value = persistedCursor
                check(writeProtocol(SyncRequestPayload(persistedCursor))) {
                    "Connection closed during event synchronization"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (isCurrentAttempt(attemptGeneration, binding)) {
                    closeForEventResync("Failed to persist sync batch", failure)
                }
            } finally {
                if (attemptGeneration == syncAttemptGeneration) {
                    syncBatchInFlight = false
                }
            }
        }
    }

    private fun resetSyncAttempt() {
        check(syncAttemptGeneration < Long.MAX_VALUE) { "Sync attempt generation exhausted" }
        syncAttemptGeneration += 1L
        syncBatchInFlight = false
        syncResetApplied = false
        lastRequestedSyncCursor = -1L
        _eventSyncCursor.value = -1L
    }

    private fun isCurrentAttempt(
        generation: Long,
        binding: EventSyncBinding,
    ): Boolean =
        generation == syncAttemptGeneration &&
            eventSyncBinding === binding &&
            connectionState() == ConnectionState.SYNCHRONIZING
}
