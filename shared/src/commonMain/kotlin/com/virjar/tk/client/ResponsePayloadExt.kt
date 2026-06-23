package com.virjar.tk.client

import com.virjar.tk.AppError
import com.virjar.tk.protocol.payload.ResponsePayload

/**
 * 把 [ResponsePayload] 的 status 映射到 [AppError]：
 * - 0：成功，直接返回
 * - 401：[AppError.AuthExpired]（触发登出而非重试）
 * - 504：[AppError.Timeout]
 * - 其他：[AppError.Business]
 *
 * 注意：服务端遇编解码错误会直接断连（不返回 500），客户端通过连接断开感知。
 * 客户端自身的 decode 越界由 outcome{} 构造器映射为 [AppError.FatalCodec]。
 */
fun ResponsePayload.ensureSuccess() {
    if (status == 0) return
    val msg = payload?.decodeToString() ?: "RPC failed (status=$status)"
    throw when (status) {
        401 -> AppError.AuthExpired
        504 -> AppError.Timeout
        else -> AppError.Business(status, msg)
    }
}
