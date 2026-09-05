package com.virjar.tk.server.protocol.dispatcher

import com.virjar.tk.server.domain.chat.ChatAccessDeniedException
import com.virjar.tk.server.domain.chat.GroupCreationConflictException
import com.virjar.tk.server.domain.command.ReliableCommandCapacityException
import com.virjar.tk.server.domain.command.ReliableCommandConflictException
import com.virjar.tk.server.domain.command.ReliableCommandExpiredException
import com.virjar.tk.server.domain.document.DocumentAccessDeniedException
import com.virjar.tk.server.domain.document.DocumentCustodyConflictException
import com.virjar.tk.server.domain.document.DocumentHierarchyConflictException
import com.virjar.tk.server.domain.document.DocumentNotFoundException
import com.virjar.tk.server.domain.document.DocumentRevisionConflictException
import com.virjar.tk.server.protocol.rpc.RpcStubRegistry
import com.virjar.tk.server.protocol.rpc.RpcSessionContext
import com.virjar.tk.protocol.payload.InvokePayload
import com.virjar.tk.protocol.payload.MAX_RPC_ENVELOPE_BODY_BYTES
import com.virjar.tk.protocol.payload.ResponsePayload
import com.virjar.tk.protocol.ProtocolCorruptionException
import com.virjar.tk.protocol.ProtocolEncodingException
import com.virjar.tk.protocol.ProtocolVersion
import com.virjar.tk.protocol.ProtocolVersions
import com.virjar.tk.protocol.rpc.RpcProtocolUnavailableException
import com.virjar.tk.protocol.rpc.gen.RpcServiceRegistry
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * RPC 派发器：字符串 serviceId → StubRegistry → 每请求 Stub（uid 注入）→ dispatch。
 * 方法定义/编解码由 rpc-processor 从 protocol/rpc/def 的 IDL 生成。
 */
class RpcDispatcher(
    private val registry: RpcStubRegistry,
) {
    private val logger = LoggerFactory.getLogger("RpcDispatcher")

    suspend fun dispatch(
        uid: String,
        deviceId: String,
        deviceCredentialEpoch: Long,
        sessionId: String,
        invoke: InvokePayload,
        protocolVersion: ProtocolVersion = ProtocolVersions.CURRENT,
    ): ResponsePayload {
        return try {
            RpcServiceRegistry.requireMethodSupported(invoke.serviceId, invoke.methodId, protocolVersion)
            val result = registry.dispatchSuspend(
                RpcSessionContext(
                    uid = uid,
                    deviceId = deviceId,
                    deviceCredentialEpoch = deviceCredentialEpoch,
                    sessionId = sessionId,
                    protocolVersion = protocolVersion,
                ),
                invoke.serviceId,
                invoke.methodId,
                invoke.payload,
            )
            if (result != null && result.size > MAX_RPC_ENVELOPE_BODY_BYTES) {
                throw ProtocolEncodingException(
                    "RPC result length ${result.size} exceeds limit $MAX_RPC_ENVELOPE_BODY_BYTES",
                )
            }
            ResponsePayload(invoke.requestId, 0, result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unsupported: RpcProtocolUnavailableException) {
            ResponsePayload(invoke.requestId, unsupported.status, "RPC is unavailable at the negotiated protocol version".encodeToByteArray())
        } catch (e: DocumentRevisionConflictException) {
            logger.info(
                "RPC document conflict: service={} method={} uid={}: {}",
                invoke.serviceId,
                invoke.methodId,
                uid,
                e.message,
            )
            ResponsePayload(invoke.requestId, 409, e.message?.encodeToByteArray())
        } catch (e: DocumentCustodyConflictException) {
            logger.info(
                "RPC document custody conflict: service={} method={} uid={}: {}",
                invoke.serviceId,
                invoke.methodId,
                uid,
                e.message,
            )
            ResponsePayload(invoke.requestId, 409, e.message?.encodeToByteArray())
        } catch (e: DocumentHierarchyConflictException) {
            logger.info(
                "RPC document hierarchy conflict: service={} method={} uid={}: {}",
                invoke.serviceId,
                invoke.methodId,
                uid,
                e.message,
            )
            ResponsePayload(invoke.requestId, 409, e.message?.encodeToByteArray())
        } catch (e: GroupCreationConflictException) {
            logger.info(
                "RPC group creation conflict: service={} method={} uid={}",
                invoke.serviceId,
                invoke.methodId,
                uid,
            )
            ResponsePayload(invoke.requestId, 409, e.message?.encodeToByteArray())
        } catch (e: ReliableCommandConflictException) {
            logger.info(
                "RPC reliable-command conflict: service={} method={} uid={}",
                invoke.serviceId,
                invoke.methodId,
                uid,
            )
            ResponsePayload(invoke.requestId, 409, e.message?.encodeToByteArray())
        } catch (e: ReliableCommandExpiredException) {
            logger.info(
                "RPC reliable-command expired: service={} method={} uid={}",
                invoke.serviceId,
                invoke.methodId,
                uid,
            )
            ResponsePayload(invoke.requestId, 410, e.message?.encodeToByteArray())
        } catch (e: ReliableCommandCapacityException) {
            logger.info(
                "RPC reliable-command capacity: service={} method={} uid={}",
                invoke.serviceId,
                invoke.methodId,
                uid,
            )
            ResponsePayload(invoke.requestId, 429, e.message?.encodeToByteArray())
        } catch (e: DocumentAccessDeniedException) {
            logger.info(
                "RPC document permission denied: service={} method={} uid={}",
                invoke.serviceId,
                invoke.methodId,
                uid,
            )
            ResponsePayload(invoke.requestId, 403, e.message?.encodeToByteArray())
        } catch (e: DocumentNotFoundException) {
            logger.info(
                "RPC document not found: service={} method={} uid={}: {}",
                invoke.serviceId,
                invoke.methodId,
                uid,
                e.message,
            )
            ResponsePayload(invoke.requestId, 404, e.message?.encodeToByteArray())
        } catch (e: ProtocolCorruptionException) {
            // 长度预算、规范标记与尾部多余字节都是连接级失败。
            throw FatalCodecException(invoke.serviceId, invoke.methodId, uid, e)
        } catch (e: ProtocolEncodingException) {
            // 已鉴权请求是有效的，但权威结果违反了
            // 服务器输出预算。绝不能把实现/契约故障误分类为 400。
            logger.error(
                "[RPC] result encoding contract violated service={} method={} uid={}",
                invoke.serviceId,
                invoke.methodId,
                uid,
                e,
            )
            ResponsePayload(invoke.requestId, 500, "服务器内部错误".encodeToByteArray())
        } catch (e: ChatAccessDeniedException) {
            logger.info(
                "RPC permission denied: service={} method={} uid={}",
                invoke.serviceId,
                invoke.methodId,
                uid,
            )
            ResponsePayload(invoke.requestId, 403, e.message?.encodeToByteArray())
        } catch (e: IllegalArgumentException) {
            // 业务校验错误（如用户名已存在、参数非法）—— 客户端可处理的预期错误
            logger.warn("RPC business error: service={} method={} uid={}: {}", invoke.serviceId, invoke.methodId, uid, e.message)
            ResponsePayload(invoke.requestId, 400, e.message?.encodeToByteArray())
        } catch (e: IndexOutOfBoundsException) {
            // 编解码错误（字段数量/类型/顺序不一致）—— 协议紊乱，连接不可靠，断连
            throw FatalCodecException(invoke.serviceId, invoke.methodId, uid, e)
        } catch (e: Exception) {
            // 其他内部错误 —— 返回 500 但不断连（可能是 DB 等临时故障）
            logger.error("[RPC] internal error service={} method={} uid={}", invoke.serviceId, invoke.methodId, uid, e)
            ResponsePayload(invoke.requestId, 500, "服务器内部错误".encodeToByteArray())
        }
    }
}
