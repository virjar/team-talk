package com.virjar.tk.application.presence

import com.virjar.tk.domain.presence.PresenceService
import com.virjar.tk.infra.sync.ClientRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Bridges connection lifecycle events into the presence use case.
 *
 * ClientRegistry invokes its callback on its serial looper. The coordinator
 * immediately hands work to an independent coroutine so presence delivery can
 * safely query the registry again without self-deadlocking that looper.
 */
class PresenceCoordinator(
    private val clientRegistry: ClientRegistry,
    private val presenceService: PresenceService,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(PresenceCoordinator::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        clientRegistry.onLastDeviceOffline = { uid ->
            scope.launch {
                runCatching { presenceService.broadcastOffline(uid) }
                    .onFailure { logger.warn("Failed to broadcast offline presence for uid={}", uid, it) }
            }
        }
    }

    override fun close() {
        clientRegistry.onLastDeviceOffline = null
        scope.cancel()
    }
}
