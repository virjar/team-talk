package com.virjar.tk.protocol.dispatcher

import com.virjar.tk.protocol.ServiceId
import com.virjar.tk.protocol.payload.InvokePayload
import com.virjar.tk.protocol.payload.ResponsePayload
import org.slf4j.LoggerFactory

class RpcDispatcher(
    private val userHandler: UserRouteHandler,
    private val contactHandler: ContactRouteHandler,
    private val chatHandler: ChatRouteHandler,
    private val messageHandler: MessageRouteHandler,
    private val conversationHandler: ConversationRouteHandler,
    private val deviceHandler: DeviceRouteHandler,
    private val authHandler: AuthRouteHandler,
    private val genericHandler: GenericRouteHandler,
) {
    private val logger = LoggerFactory.getLogger("RpcDispatcher")

    suspend fun dispatch(uid: String, invoke: InvokePayload): ResponsePayload {
        return try {
            val result = route(uid, invoke.serviceId, invoke.methodId, invoke.payload)
            ResponsePayload(invoke.requestId, 0, result)
        } catch (e: IllegalArgumentException) {
            // 业务校验错误（如用户名已存在、参数非法）—— 客户端可处理的预期错误
            logger.warn("RPC business error: service={} method={} uid={}: {}", invoke.serviceId, invoke.methodId, uid, e.message)
            ResponsePayload(invoke.requestId, 400, e.message?.encodeToByteArray())
        } catch (e: IndexOutOfBoundsException) {
            // 编解码错误（字段数量/类型/顺序不一致）—— 协议紊乱，连接不可靠，抛 FatalCodecException 让 ImAgent 断连
            throw FatalCodecException(invoke.serviceId.name, invoke.methodId, uid, e)
        } catch (e: Exception) {
            // 其他内部错误 —— 返回 500 但不断连（可能是 DB 等临时故障）
            logger.error("[RPC] internal error service={} method={} uid={}", invoke.serviceId, invoke.methodId, uid, e)
            ResponsePayload(invoke.requestId, 500, "服务器内部错误".encodeToByteArray())
        }
    }

    private suspend fun route(uid: String, service: ServiceId, methodId: Int, payload: ByteArray?): ByteArray {
        return when (service) {
            ServiceId.USER -> userHandler.route(uid, methodId, payload)
            ServiceId.CONTACT -> contactHandler.route(uid, methodId, payload)
            ServiceId.CHAT -> chatHandler.route(uid, methodId, payload)
            ServiceId.MESSAGE -> messageHandler.route(uid, methodId, payload)
            ServiceId.CONVERSATION -> conversationHandler.route(uid, methodId, payload)
            ServiceId.DEVICE -> deviceHandler.route(uid, methodId, payload)
            ServiceId.AUTH -> authHandler.route(uid, methodId, payload)
            ServiceId.GENERIC -> genericHandler.route(uid, methodId, payload)
        }
    }
}
