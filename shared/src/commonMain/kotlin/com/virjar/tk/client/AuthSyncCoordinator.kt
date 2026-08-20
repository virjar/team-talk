package com.virjar.tk.client

import com.virjar.tk.util.PlatformOnlyTkLogger
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
    private val logger = PlatformOnlyTkLogger("AuthSyncCoordinator")

    private data class EventSyncBinding(
        val owner: Any,
        val expectedUid: String?,
        val wireAdmission: WireSendAdmission,
        val cursor: () -> Long,
        val processBatch: suspend (List<NotifyPayload>, reportProgress: (Long) -> Unit) -> Long,
        val reset: suspend () -> Long,
    )

    /** Reused after a transport reconnect; upgraded to refresh-token auth after a successful AUTH. */
    private var pendingAuth: AuthRequestPayload? = null
    /** Caller-bound uid for refresh authentication and every later reconnect of that identity. */
    private var pendingExpectedUid: String? = null

    /** A server-declared AUTH failure is terminal until an explicit new authentication attempt. */
    private var authenticationTerminal = false

    private var eventSyncBinding: EventSyncBinding? = null
    /** Suppresses control packets queued behind a synchronously retired session binding. */
    private var eventSyncRetired = false
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

    fun prepareAuthentication(auth: AuthRequestPayload, expectedUid: String? = null) {
        require(expectedUid == null || expectedUid.isNotBlank()) { "Expected auth uid must not be blank" }
        pendingAuth = auth
        pendingExpectedUid = expectedUid
        authenticationTerminal = false
        _authenticationFailure.value = null
    }

    fun authenticationPayload(): AuthRequestPayload? = pendingAuth

    fun isAuthenticationTerminal(): Boolean = authenticationTerminal

    fun installEventSync(
        owner: Any,
        expectedUid: String?,
        wireAdmission: WireSendAdmission,
        cursor: () -> Long,
        processBatch: suspend (List<NotifyPayload>, reportProgress: (Long) -> Unit) -> Long,
        reset: suspend () -> Long,
    ) {
        val previous = eventSyncBinding
        require(expectedUid == null || expectedUid.isNotBlank()) { "Event sync owner uid must not be blank" }
        eventSyncRetired = false
        eventSyncBinding = EventSyncBinding(
            owner,
            expectedUid,
            wireAdmission,
            cursor,
            processBatch,
            reset,
        )
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
        val removed = eventSyncBinding?.takeIf { it.owner === owner } ?: return
        eventSyncBinding = null
        eventSyncRetired = !removed.wireAdmission.isActive()
        if (!eventSyncRetired && connectionState() == ConnectionState.SYNCHRONIZING) {
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
                pendingExpectedUid = null
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
            val expectedAuthUid = pendingExpectedUid
            if (expectedAuthUid != null && expectedAuthUid != uid) {
                val reason = "认证响应 uid 与 refresh credential owner 不一致"
                pendingAuth = null
                pendingExpectedUid = null
                authenticationTerminal = true
                _authenticationFailure.value = AuthenticationFailure(
                    kind = AuthenticationFailureKind.REJECTED,
                    reason = reason,
                )
                transitionTo(ConnectionState.AUTH_FAILED)
                onAuthResult?.invoke(false, null, null, null, null, null, reason)
                closeTransport("Authentication uid rejected by credential owner", null)
                return
            }
            val expectedProjectionUid = eventSyncBinding?.expectedUid
            if (expectedProjectionUid != null && expectedProjectionUid != uid) {
                val reason = "认证身份与已安装的事件投影 owner 不一致"
                pendingAuth = null
                pendingExpectedUid = null
                authenticationTerminal = true
                _authenticationFailure.value = AuthenticationFailure(
                    kind = AuthenticationFailureKind.REJECTED,
                    reason = reason,
                )
                transitionTo(ConnectionState.AUTH_FAILED)
                onAuthResult?.invoke(false, null, null, null, null, null, reason)
                closeTransport("Authentication uid rejected by event projection owner", null)
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
            pendingExpectedUid = uid
            try {
                onAuthResult?.invoke(
                    true,
                    uid,
                    username,
                    name,
                    refreshToken,
                    accessToken,
                    null,
                )
            } catch (failure: Throwable) {
                // Credential admission is part of AUTH acceptance. A client which cannot commit
                // rotated durable credentials must never enter sync/ready with an unusable token.
                pendingAuth = null
                pendingExpectedUid = null
                authenticationTerminal = true
                transitionTo(ConnectionState.AUTH_FAILED)
                closeTransport("Authentication credential admission failed", failure)
                return
            }
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
            pendingExpectedUid = null
            authenticationTerminal = true
            _authenticationFailure.value = failure
            transitionTo(ConnectionState.AUTH_FAILED)
            onAuthResult?.invoke(false, null, null, null, null, null, failure.reason)
            // The server reason is intentionally not journaled: auth diagnostics must never echo
            // attacker-controlled or accidentally credential-bearing text.
            logger.trace("Auth failed (terminal): code=${response.code}")
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
        val binding = eventSyncBinding
        if (binding == null) {
            if (eventSyncRetired) return
            closeForEventResync("Unexpected SYNC_READY")
            return
        }
        var admitted = false
        var stillCurrent = false
        binding.wireAdmission.use {
            admitted = true
            stillCurrent = eventSyncBinding === binding &&
                connectionState() == ConnectionState.SYNCHRONIZING &&
                !syncBatchInFlight &&
                lastRequestedSyncCursor >= 0L
            if (stillCurrent) {
                onAuthenticationAccepted()
                transitionTo(ConnectionState.AUTHENTICATED)
            } else {
                closeForEventResync("Unexpected SYNC_READY")
            }
            true
        }
        if (!admitted) return
        if (!stillCurrent) return
        logger.trace("Persistent event sync ready at cursor=$lastRequestedSyncCursor")
    }

    fun handleSyncReset() {
        val binding = eventSyncBinding
        val scope = connectionScope()
        if (binding == null || scope == null) {
            if (binding == null && eventSyncRetired) return
            closeForEventResync("SYNC_RESET arrived without an active projection owner")
            return
        }
        var admitted = false
        var valid = false
        binding.wireAdmission.use {
            admitted = true
            valid = eventSyncBinding === binding &&
                connectionState() == ConnectionState.SYNCHRONIZING &&
                !syncBatchInFlight &&
                !syncResetApplied &&
                lastRequestedSyncCursor >= 0L
            if (valid) {
                syncResetApplied = true
                syncBatchInFlight = true
            } else {
                closeForEventResync("Unexpected, overlapping, or repeated SYNC_RESET")
            }
            true
        }
        if (!admitted) return
        if (!valid) return
        val attemptGeneration = syncAttemptGeneration
        scope.launch {
            try {
                val resetCursor = binding.reset()
                if (!isCurrentAttempt(attemptGeneration, binding)) return@launch
                check(resetCursor == 0L) { "Projection reset returned cursor=$resetCursor" }
                check(connectionState() == ConnectionState.SYNCHRONIZING) {
                    "Connection left synchronization during projection reset"
                }
                var admitted = false
                var current = false
                binding.wireAdmission.use {
                    admitted = true
                    current = isCurrentAttempt(attemptGeneration, binding)
                    if (!current) return@use true
                    lastRequestedSyncCursor = 0L
                    _eventSyncCursor.value = 0L
                    if (!writeProtocol(SyncRequestPayload(0L))) {
                        closeForEventResync("Connection closed during projection reset")
                    }
                    true
                }
                if (!admitted || !current) return@launch
                logger.trace("Server projection reset; event sync restarted from cursor=0")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                binding.wireAdmission.use {
                    if (isCurrentAttempt(attemptGeneration, binding)) {
                        closeForEventResync("Failed to reset server projection", failure)
                    }
                    true
                }
            } finally {
                binding.wireAdmission.use {
                    if (attemptGeneration == syncAttemptGeneration && eventSyncBinding === binding) {
                        syncBatchInFlight = false
                    }
                    true
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
        var admitted = false
        var attemptedWrite = false
        var initialCursor = -1L
        binding.wireAdmission.use {
            admitted = true
            if (
                eventSyncBinding !== binding ||
                connectionState() != ConnectionState.SYNCHRONIZING ||
                lastRequestedSyncCursor >= 0L
            ) return@use true
            initialCursor = binding.cursor()
            if (initialCursor < 0L) {
                closeForEventResync("Persistent event cursor is negative: $initialCursor")
                return@use true
            }
            lastRequestedSyncCursor = initialCursor
            _eventSyncCursor.value = initialCursor
            attemptedWrite = true
            if (!writeProtocol(SyncRequestPayload(initialCursor))) {
                closeForEventResync("Connection closed before the first sync request")
            }
            true
        }
        if (!admitted) return
        if (initialCursor < 0L) return
        if (!attemptedWrite) return
        logger.trace("Event sync requested after cursor=$initialCursor")
    }

    private fun handleSyncEvents(events: List<NotifyPayload>) {
        val binding = eventSyncBinding
        val scope = connectionScope()
        if (binding == null || scope == null) {
            if (binding == null && eventSyncRetired) return
            closeForEventResync("Sync batch arrived without an active projection owner")
            return
        }
        var admitted = false
        var valid = false
        var requestedAfter = -1L
        binding.wireAdmission.use {
            admitted = true
            requestedAfter = lastRequestedSyncCursor
            val hasCursorOverflow = requestedAfter == Long.MAX_VALUE
            valid = eventSyncBinding === binding &&
                connectionState() == ConnectionState.SYNCHRONIZING &&
                !syncBatchInFlight &&
                requestedAfter >= 0L &&
                !hasCursorOverflow &&
                events.isNotEmpty() &&
                events.none { it.eventId <= 0L } &&
                events.first().eventId == requestedAfter + 1L &&
                events.zipWithNext().all { (left, right) -> right.eventId == left.eventId + 1L }
            if (valid) {
                syncBatchInFlight = true
            } else {
                closeForEventResync(
                    "Sync events are not contiguous after requested cursor=$requestedAfter",
                )
            }
            true
        }
        if (!admitted || !valid) return
        val attemptGeneration = syncAttemptGeneration
        val expectedCursor = events.last().eventId
        val reportProgress: (Long) -> Unit = { cursor ->
            connectionScope()?.launch {
                var invalidProgress = false
                val admitted = binding.wireAdmission.use {
                    if (!isCurrentAttempt(attemptGeneration, binding)) return@use true
                    if (cursor <= requestedAfter || cursor > expectedCursor) {
                        invalidProgress = true
                        closeForEventResync(
                            "Sync projection reported invalid progress=$cursor for " +
                                "requested=$requestedAfter expected=$expectedCursor",
                        )
                    } else if (cursor > _eventSyncCursor.value) {
                        _eventSyncCursor.value = cursor
                    }
                    true
                }
                if (!admitted) return@launch
                if (invalidProgress) return@launch
            }
        }
        scope.launch {
            try {
                val persistedCursor = binding.processBatch(events, reportProgress)
                if (!isCurrentAttempt(attemptGeneration, binding)) return@launch
                check(persistedCursor == expectedCursor) {
                    "Sync projection stopped at $persistedCursor instead of $expectedCursor"
                }
                var admitted = false
                var current = false
                binding.wireAdmission.use {
                    admitted = true
                    current = isCurrentAttempt(attemptGeneration, binding)
                    if (!current) return@use true
                    lastRequestedSyncCursor = persistedCursor
                    _eventSyncCursor.value = persistedCursor
                    if (!writeProtocol(SyncRequestPayload(persistedCursor))) {
                        closeForEventResync("Connection closed during event synchronization")
                    }
                    true
                }
                if (!admitted || !current) return@launch
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                binding.wireAdmission.use {
                    if (isCurrentAttempt(attemptGeneration, binding)) {
                        closeForEventResync("Failed to persist sync batch", failure)
                    }
                    true
                }
            } finally {
                binding.wireAdmission.use {
                    if (attemptGeneration == syncAttemptGeneration && eventSyncBinding === binding) {
                        syncBatchInFlight = false
                    }
                    true
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
