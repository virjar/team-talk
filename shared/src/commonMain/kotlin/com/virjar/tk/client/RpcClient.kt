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

    /**
     * 自治重连 watcher：监听协程挂在连接 scope 上，断线时随 scope 消亡；
     * 重连成功（新 scope 就绪）时自动在新 scope 重启监听。
     * （历史 bug：监听只在 createSession 启动一次，断线重连后 RPC 应答无人处理 → 全部超时。）
     */
    private val lifecycleScope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, t ->
        logger.fault("RpcClient lifecycle watcher crashed", t)
    })
    private var watcherJob: Job? = null
    @Volatile
    private var started = false

    fun start() {
        started = true
        ensureListening()
        if (watcherJob?.isActive == true) return
        watcherJob = lifecycleScope.launch {
            imClient.state.collect { state ->
                if (state == ConnectionState.CONNECTED) {
                    // 新连接 scope 就绪；监听若已死（随旧 scope cancel）则重启
                    if (listenJob?.isActive != true) {
                        logger.trace("Connection restored, restarting RPC listener")
                        ensureListening()
                    }
                }
            }
        }
    }

    private fun ensureListening() {
        val scope = imClient.coroutineScope ?: run {
            logger.trace("Cannot listen: ImClient not connected")
            return
        }
        if (listenJob?.isActive == true) return  // 已在当前/存活 scope 上监听
        listenJob = scope.launch {
            try {
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
            } catch (e: CancellationException) {
                // 正常的协作式取消（断连/重连时 SupervisorJob 被 cancel），不是 crash
                throw e
            } catch (e: Exception) {
                // 根监听循环：记好日志后兜住，不让单次错误搞垮整个监听
                logger.fault("RpcClient listen loop crashed", e)
            }
        }
    }

    override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?): ResponsePayload {
        val scope = imClient.coroutineScope ?: throw IllegalStateException("Not connected")
        return withContext(scope.coroutineContext) {
            val requestId = nextRequestId++
            val deferred = CompletableDeferred<ResponsePayload>()
            pendingRequests[requestId] = deferred
            imClient.send(InvokePayload(requestId, service, methodId, payload))
            withTimeoutOrNull(10_000L) {
                deferred.await()
            } ?: run {
                pendingRequests.remove(requestId)
                ResponsePayload(requestId, 504, "Request timeout".encodeToByteArray())
            }
        }
    }

    fun stop() {
        started = false
        watcherJob?.cancel()
        listenJob?.cancel()
    }
}
