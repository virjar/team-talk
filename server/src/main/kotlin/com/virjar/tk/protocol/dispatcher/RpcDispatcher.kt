package com.virjar.tk.protocol.dispatcher

import com.virjar.tk.protocol.rpc.RpcStubRegistry
import com.virjar.tk.protocol.payload.InvokePayload
import com.virjar.tk.protocol.payload.ResponsePayload
import io.netty.handler.codec.CorruptedFrameException
import org.slf4j.LoggerFactory

/**
 * RPC 派发器：字符串 serviceId → StubRegistry → 每请求 Stub（uid 注入）→ dispatch。
 * 方法定义/编解码由 rpc-processor 从 IDL 生成（shared/.../rpc/gen）。
 */
class RpcDispatcher(
    private val registry: RpcStubRegistry,
) {
    private val logger = LoggerFactory.getLogger("RpcDispatcher")

    suspend fun dispatch(uid: String, invoke: InvokePayload): ResponsePayload {
        return try {
            val result = registry.dispatchSuspend(uid, invoke.serviceId, invoke.methodId, invoke.payload)
            ResponsePayload(invoke.requestId, 0, result)
        } catch (e: IllegalArgumentException) {
            // 业务校验错误（如用户名已存在、参数非法）—— 客户端可处理的预期错误
            logger.warn("RPC business error: service={} method={} uid={}: {}", invoke.serviceId, invoke.methodId, uid, e.message)
            ResponsePayload(invoke.requestId, 400, e.message?.encodeToByteArray())
        } catch (e: IndexOutOfBoundsException) {
            // 编解码错误（字段数量/类型/顺序不一致）—— 协议紊乱，连接不可靠，断连
            throw FatalCodecException(invoke.serviceId, invoke.methodId, uid, e)
        } catch (e: CorruptedFrameException) {
            // 长度预算、presence marker 或尾随字节非法，同样是连接级协议错误。
            throw FatalCodecException(invoke.serviceId, invoke.methodId, uid, e)
        } catch (e: Exception) {
            // 其他内部错误 —— 返回 500 但不断连（可能是 DB 等临时故障）
            logger.error("[RPC] internal error service={} method={} uid={}", invoke.serviceId, invoke.methodId, uid, e)
            ResponsePayload(invoke.requestId, 500, "服务器内部错误".encodeToByteArray())
        }
    }
}
