package com.virjar.tk.shared.client

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.PingSignal
import com.virjar.tk.protocol.PongSignal
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class TransportConnectionOwnerTest {
    @Test
    fun offlinePreparationDoesNotCreateAChannelAndStartsAtMostOnce() {
        val backend = ControlledTransportBackend()
        var negotiated = 0
        val owner = owner(backend, negotiate = { negotiated++; true })
        owner.prepareInitialConnect("localhost", 5100, admitAndPrepare = { prepare -> prepare(); true })
        backend.drain()
        assertTrue(owner.currentOwnerGeneration > 0)
        assertEquals(0, backend.channels.size)
        assertEquals(ConnectionState.DISCONNECTED, owner.state.value)

        repeat(2) { owner.startPreparedInitialConnect { start -> start(); true } }
        backend.drain()
        assertEquals(1, backend.channels.size)
        assertEquals(1, backend.channels.single().starts)
        assertEquals(0, negotiated)
        backend.channels.single().ready()
        backend.drain()
        assertEquals(1, negotiated)
        owner.destroy()
        backend.drain()
        assertTrue(backend.closed)
    }

    @Test
    fun replacedChannelCallbacksAndQueuedOldSendsCannotCrossIntoTheNewOwner() {
        val backend = ControlledTransportBackend()
        val routed = mutableListOf<IProto>()
        var ended = 0
        val owner = owner(backend, packet = { routed += it }, ended = { ended++; true })
        owner.connect("localhost", 5100)
        backend.drain()
        val old = backend.channels.single()
        val oldOwner = owner.currentOwnerGeneration
        val oldConnection = owner.currentConnectionGeneration
        owner.connect("localhost", 5101)
        backend.drain()
        val replacement = backend.channels.last()
        old.ready() // A native completion can already have been queued when close won.
        old.packet(PongSignal)
        replacement.ready()
        backend.drain()
        assertEquals(ConnectionState.CONNECTED, owner.state.value)
        assertTrue(routed.isEmpty())
        assertEquals(0, ended, "closing a replaced channel must not consume its successor's AUTH attempt")

        var staleSent: Boolean? = null
        var currentSent: Boolean? = null
        val admission = SessionOutboundLease()
        owner.sendIfOwned(oldOwner, oldConnection, admission, PingSignal) { staleSent = it }
        owner.sendIfOwned(owner.currentOwnerGeneration, owner.currentConnectionGeneration, admission, PingSignal) { currentSent = it }
        owner.disconnectIfOwned(oldOwner)
        backend.drain()
        assertEquals(false, staleSent)
        assertEquals(true, currentSent)
        assertEquals<List<IProto>>(listOf(PingSignal), replacement.writes)
        assertEquals(ConnectionState.CONNECTED, owner.state.value)
        owner.destroy()
        backend.drain()
    }

    @Test
    fun retryRetainsLogicalOwnerAndStaleTimerCannotReviveDestroyedTransport() {
        val backend = ControlledTransportBackend()
        var retry = true
        var ended = 0
        val owner = owner(backend, ended = { ended++; retry })
        owner.connect("localhost", 5100)
        backend.drain()
        val logicalOwner = owner.currentOwnerGeneration
        backend.channels.single().close()
        backend.drain()
        assertEquals(1, ended)
        assertEquals(1, backend.timers.size)
        backend.fireTimer()
        backend.drain()
        assertEquals(logicalOwner, owner.currentOwnerGeneration)
        assertEquals(2, backend.channels.size)

        retry = false // One-shot AUTH terminal failure must stop even though the socket can reconnect.
        backend.channels.last().close()
        backend.drain()
        assertEquals(2, ended)
        assertTrue(backend.timers.isEmpty())
        owner.connect("localhost", 5100)
        backend.drain()
        retry = true
        backend.channels.last().close()
        backend.drain()
        val queuedTimer = backend.timers.single()
        val count = backend.channels.size
        owner.destroy()
        backend.drain()
        backend.runOnLoop(queuedTimer) // Exercise a timer already dispatched before cancellation.
        assertEquals(count, backend.channels.size)
        assertEquals(ConnectionState.DISCONNECTED, owner.state.value)
        assertFalse(owner.execute { error("destroyed owner ran work") })
    }

    private fun owner(
        backend: ControlledTransportBackend,
        negotiate: (Long) -> Boolean = { true },
        packet: (IProto) -> Unit = {},
        ended: () -> Boolean = { false },
    ) = TransportConnectionOwner(
        "localhost", 5100, negotiate, { null }, { ended() }, {}, { false },
        { _, value -> packet(value) }, {}, backend,
    )
}

/** Tests control callback order at the actual backend seam, without sockets or timing sleeps. */
private class ControlledTransportBackend : ClientTransportBackend {
    private val queue = ArrayDeque<() -> Unit>()
    val timers = ArrayDeque<() -> Unit>()
    val channels = mutableListOf<Connection>()
    var closed = false
        private set
    private var running = false
    override val dispatcher = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) { check(enqueue { block.run() }) }
    }
    override fun inEventLoop(): Boolean = running
    override fun enqueue(task: () -> Unit): Boolean {
        if (closed) return false
        queue.addLast(task)
        return true
    }
    override fun schedule(delayMillis: Long, task: () -> Unit): ClientTransportTimer {
        assertTrue(delayMillis > 0)
        timers.addLast(task)
        return ClientTransportTimer { timers.remove(task) }
    }
    override fun createChannel(host: String, port: Int, events: ClientTransportEvents): ClientTransportChannel =
        Connection(events).also(channels::add)
    override fun shutdown() { closed = true }
    fun drain() { while (queue.isNotEmpty()) runOnLoop(queue.removeFirst()) }
    fun runOnLoop(task: () -> Unit) { running = true; try { task() } finally { running = false } }
    fun fireTimer() { queue.addLast(timers.removeFirst()) }

    inner class Connection(private val events: ClientTransportEvents) : ClientTransportChannel {
        override var isActive = false
        var starts = 0
        val writes = mutableListOf<IProto>()
        override fun start() { starts++ }
        fun ready() { enqueue { isActive = true; events.ready(this) } }
        fun packet(packet: IProto) { enqueue { events.packet(this, packet) } }
        override fun writeAndFlush(proto: IProto) { writes += proto }
        override fun onAuthenticationAccepted() = Unit
        override fun close() { isActive = false; enqueue { events.closed(this, null) } }
    }
}
