package com.virjar.tk.application.presence

import com.virjar.tk.domain.presence.PresenceService
import com.virjar.tk.infra.sync.ClientRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

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
    private val lifecycle = SupervisorJob()
    private val scope = CoroutineScope(lifecycle + Dispatchers.IO)
    private val changes = Channel<PresenceChange>(Channel.UNLIMITED)
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    private data class PresenceChange(val uid: String, val online: Boolean)

    fun start() {
        check(!closed.get()) { "PresenceCoordinator is already closed" }
        if (!started.compareAndSet(false, true)) return
        try {
            scope.launch {
                for (change in changes) {
                    runCatching {
                        if (change.online) {
                            presenceService.broadcastOnline(change.uid)
                        } else {
                            presenceService.broadcastOffline(change.uid)
                        }
                    }.onFailure {
                        logger.warn(
                            "Failed to broadcast {} presence for uid={}",
                            if (change.online) "online" else "offline",
                            change.uid,
                            it,
                        )
                    }
                }
            }
            clientRegistry.onFirstDeviceOnline = { uid -> changes.trySend(PresenceChange(uid, true)) }
            clientRegistry.onLastDeviceOffline = { uid -> changes.trySend(PresenceChange(uid, false)) }
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        clientRegistry.onFirstDeviceOnline = null
        clientRegistry.onLastDeviceOffline = null
        changes.close()
        runBlocking { lifecycle.cancelAndJoin() }
    }
}
