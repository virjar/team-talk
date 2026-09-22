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
import com.virjar.tk.server.domain.organization.OrganizationAccessDeniedException
import com.virjar.tk.server.domain.search.ContentSearchUnavailableException
import com.virjar.tk.server.domain.task.TaskAccessDeniedException
import com.virjar.tk.server.domain.task.TaskNotFoundException
import com.virjar.tk.server.domain.task.TaskRevisionConflictException
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
        } catch (corruption: ProtocolCorruptionException) {
            // 长度预算、规范标记与尾部多余字节都是连接级失败。
            throw FatalCodecException(invoke.serviceId, invoke.methodId, uid, corruption)
        } catch (encoding: ProtocolEncodingException) {
            // 已鉴权请求是有效的，但权威结果违反了
            // 服务器输出预算。绝不能把实现/契约故障误分类为 400。
            logger.error(
                "[RPC] result encoding contract violated service={} method={} uid={}",
                invoke.serviceId,
                invoke.methodId,
                uid,
                encoding,
            )
            ResponsePayload(invoke.requestId, 500, "服务器内部错误".encodeToByteArray())
        } catch (index: IndexOutOfBoundsException) {
            // 编解码错误（字段数量/类型/顺序不一致）—— 协议紊乱，连接不可靠，断连
            throw FatalCodecException(invoke.serviceId, invoke.methodId, uid, index)
        } catch (e: Exception) {
            // 领域异常按映射表裁决；顺序先于 IllegalArgumentException，保证表内
            // 类型（含未来可能的 IAE 子类）不被误归为通用业务校验错误。
            domainErrorResponseFor(e)?.let { mapped ->
                if (mapped.logLabel != null) {
                    logger.info(
                        "RPC ${'$'}{mapped.logLabel}: service={} method={} uid={}: {}",
                        invoke.serviceId,
                        invoke.methodId,
                        uid,
                        e.message,
                    )
                }
                return ResponsePayload(invoke.requestId, mapped.status, e.message?.encodeToByteArray())
            }
            when (e) {
                is IllegalArgumentException -> {
                    // 业务校验错误（如用户名已存在、参数非法）—— 客户端可处理的预期错误
                    logger.warn("RPC business error: service={} method={} uid={}: {}", invoke.serviceId, invoke.methodId, uid, e.message)
                    ResponsePayload(invoke.requestId, 400, e.message?.encodeToByteArray())
                }
                else -> {
                    // 其他内部错误 —— 返回 500 但不断连（可能是 DB 等临时故障）
                    logger.error("[RPC] internal error service={} method={} uid={}", invoke.serviceId, invoke.methodId, uid, e)
                    ResponsePayload(invoke.requestId, 500, "服务器内部错误".encodeToByteArray())
                }
            }
        }
    }

    /** 领域异常 → RPC 状态码与路由日志标签；logLabel 为 null 时只应答不记日志。 */
    private data class DomainErrorResponse(
        val type: Class<out Exception>,
        val status: Int,
        val logLabel: String?,
    )

    /** 新增领域异常时在此追加一行，不再复制同构 catch 块。 */
    private val domainErrorResponses = listOf(
        DomainErrorResponse(ContentSearchUnavailableException::class.java, 503, null),
        DomainErrorResponse(TaskRevisionConflictException::class.java, 409, null),
        DomainErrorResponse(TaskAccessDeniedException::class.java, 403, null),
        DomainErrorResponse(TaskNotFoundException::class.java, 404, null),
        DomainErrorResponse(DocumentRevisionConflictException::class.java, 409, "document conflict"),
        DomainErrorResponse(DocumentCustodyConflictException::class.java, 409, "document custody conflict"),
        DomainErrorResponse(DocumentHierarchyConflictException::class.java, 409, "document hierarchy conflict"),
        DomainErrorResponse(GroupCreationConflictException::class.java, 409, "group creation conflict"),
        DomainErrorResponse(ReliableCommandConflictException::class.java, 409, "reliable-command conflict"),
        DomainErrorResponse(ReliableCommandExpiredException::class.java, 410, "reliable-command expired"),
        DomainErrorResponse(ReliableCommandCapacityException::class.java, 429, "reliable-command capacity"),
        DomainErrorResponse(DocumentAccessDeniedException::class.java, 403, "document permission denied"),
        DomainErrorResponse(DocumentNotFoundException::class.java, 404, "document not found"),
        DomainErrorResponse(ChatAccessDeniedException::class.java, 403, "permission denied"),
        DomainErrorResponse(OrganizationAccessDeniedException::class.java, 403, "permission denied"),
    )

    private fun domainErrorResponseFor(e: Exception): DomainErrorResponse? =
        domainErrorResponses.firstOrNull { it.type.isInstance(e) }
}
