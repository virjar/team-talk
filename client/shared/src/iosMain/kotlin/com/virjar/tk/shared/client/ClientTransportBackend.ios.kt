package com.virjar.tk.shared.client

import com.virjar.tk.protocol.IProto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal actual fun createClientTransportBackend(tcpTlsCertificatePem: String?): ClientTransportBackend =
    IosClientTransportBackend(tcpTlsCertificatePem)

/** The GCD queue and Network.framework channels have one platform owner, with no auth/retry policy. */
private class IosClientTransportBackend(private val certificatePem: String?) : ClientTransportBackend {
    private val executor = IosSerialExecutor("teamtalk.tcp")
    private val timerScope = CoroutineScope(executor + SupervisorJob())
    override val dispatcher get() = executor
    override fun inEventLoop(): Boolean = executor.inExecutor()
    override fun enqueue(task: () -> Unit): Boolean = executor.execute(task)
    override fun schedule(delayMillis: Long, task: () -> Unit): ClientTransportTimer {
        val job = timerScope.launch { delay(delayMillis); task() }
        return ClientTransportTimer { job.cancel() }
    }
    override fun shutdown() {
        timerScope.cancel()
        executor.closeAfterQueuedWork()
    }
    override fun createChannel(host: String, port: Int, events: ClientTransportEvents): ClientTransportChannel =
        IosConnection(host, port, events)

    private inner class IosConnection(host: String, port: Int, events: ClientTransportEvents) : ClientTransportChannel {
        private val channel = IosTcpChannel(
            executor, host, port, certificatePem,
            onReady = { events.ready(this) },
            onPacket = { _, packet -> events.packet(this, packet) },
            onClosed = { _, failure -> events.closed(this, failure) },
            onWriteIdle = { events.writeIdle(this) },
        )
        override val isActive: Boolean get() = channel.isActive
        override fun start() {
            try { channel.start() } catch (failure: Throwable) {
                // Invalid TLS material must retire the connection after AUTH installation returns.
                executor.execute { channel.close(failure) }
            }
        }
        override fun writeAndFlush(proto: IProto) = channel.writeAndFlush(proto)
        override fun onAuthenticationAccepted() { channel.authenticated = true }
        override fun close() = channel.close()
    }
}
