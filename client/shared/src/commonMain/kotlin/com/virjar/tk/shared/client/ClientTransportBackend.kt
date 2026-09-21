package com.virjar.tk.shared.client

import com.virjar.tk.protocol.IProto
import kotlinx.coroutines.CoroutineDispatcher

/** Platform IO only; the common owner alone decides authentication, generations and reconnects. */
internal interface ClientTransportBackend {
    val dispatcher: CoroutineDispatcher
    fun inEventLoop(): Boolean
    /** Always enqueue, including when already on the loop. Rejected work must never run inline. */
    fun enqueue(task: () -> Unit): Boolean
    fun schedule(delayMillis: Long, task: () -> Unit): ClientTransportTimer
    /** Creation does not touch the network. start callbacks must cross a queue boundary. */
    fun createChannel(host: String, port: Int, events: ClientTransportEvents): ClientTransportChannel
    fun shutdown()
}

internal fun interface ClientTransportTimer {
    fun cancel()
}

internal interface ClientTransportChannel {
    val isActive: Boolean
    fun start()
    fun writeAndFlush(proto: IProto)
    fun onAuthenticationAccepted()
    fun close()
}

/** Delivered on the backend's serial loop, with the exact channel identity attached. */
internal interface ClientTransportEvents {
    fun ready(source: ClientTransportChannel)
    fun packet(source: ClientTransportChannel, packet: IProto)
    fun closed(source: ClientTransportChannel, failure: Throwable?)
    fun writeIdle(source: ClientTransportChannel)
}

internal expect fun createClientTransportBackend(tcpTlsCertificatePem: String?): ClientTransportBackend
