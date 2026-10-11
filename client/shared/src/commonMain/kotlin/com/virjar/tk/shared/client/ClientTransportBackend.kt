package com.virjar.tk.shared.client

import com.virjar.tk.protocol.IProto
import kotlinx.coroutines.CoroutineDispatcher

/** 仅承担平台 IO；认证、generation 与重连一律由公共 owner 决定。 */
internal interface ClientTransportBackend {
    val dispatcher: CoroutineDispatcher
    fun inEventLoop(): Boolean
    /** 一律入队，包括已在事件循环内时；被拒绝的任务绝不能内联执行。 */
    fun enqueue(task: () -> Unit): Boolean
    fun schedule(delayMillis: Long, task: () -> Unit): ClientTransportTimer
    /** 创建时不触碰网络；start 回调必须经过一次队列边界。 */
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

/** 在 backend 的串行循环上投递，并附带确切的 channel identity。 */
internal interface ClientTransportEvents {
    fun ready(source: ClientTransportChannel)
    fun packet(source: ClientTransportChannel, packet: IProto)
    fun closed(source: ClientTransportChannel, failure: Throwable?)
    fun writeIdle(source: ClientTransportChannel)
}

internal expect fun createClientTransportBackend(tcpTlsCertificatePem: String?): ClientTransportBackend
