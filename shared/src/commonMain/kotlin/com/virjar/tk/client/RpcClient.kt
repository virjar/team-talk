package com.virjar.tk.client

import com.virjar.tk.protocol.*
import com.virjar.tk.protocol.payload.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.virjar.tk.log.TkLoggerFactory

/**
 * RPC 客户端。所有状态操作在 ImClient 的 EventLoop 上执行。
 */
class RpcClient(
    private val imClient: ImClient,
) : RpcInvoker {
    private val logger = TkLoggerFactory.get("RpcClient")
    private var nextRequestId = 1
    private val pendingRequests = mutableMapOf<Int, CompletableDeferred<ResponsePayload>>()
    private var listenJob: Job? = null

    fun start() {
        val scope = imClient.coroutineScope ?: run {
            logger.trace("Cannot start: ImClient not connected")
            return
        }
        listenJob = scope.launch {
            launch {
                imClient.packets.collect { proto ->
                    if (proto is ResponsePayload) {
                        pendingRequests.remove(proto.requestId)?.complete(proto)
                    }
                }
            }
            // 监听断连，清理残留请求
            imClient.state.first { it == ConnectionState.DISCONNECTED }
            pendingRequests.forEach { (_, d) ->
                d.completeExceptionally(CancellationException("Connection closed"))
            }
            pendingRequests.clear()
        }
    }

    override suspend fun invoke(serviceId: ServiceId, methodId: Int, payload: ByteArray?): ResponsePayload {
        val scope = imClient.coroutineScope ?: throw IllegalStateException("Not connected")
        return withContext(scope.coroutineContext) {
            val requestId = nextRequestId++
            val deferred = CompletableDeferred<ResponsePayload>()
            pendingRequests[requestId] = deferred
            imClient.send(InvokePayload(requestId, serviceId, methodId, payload))
            withTimeoutOrNull(10_000L) {
                deferred.await()
            } ?: run {
                pendingRequests.remove(requestId)
                ResponsePayload(requestId, 504, "Request timeout".encodeToByteArray())
            }
        }
    }

    fun stop() {
        listenJob?.cancel()
    }
}
