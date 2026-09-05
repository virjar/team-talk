package com.virjar.tk.protocol.payload

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.ProtocolCorruptionException
import com.virjar.tk.protocol.ProtocolEncodingException
import com.virjar.tk.protocol.telemetry.ConnectionTraceContext
import com.virjar.tk.protocol.telemetry.ConnectionTraceContextPolicy

/**
 * 面向一条已认证物理连接的瞬时服务端到客户端 trace 策略更新。
 * 当更新的策略 revision 对该连接禁用采集时，[context] 为 null。
 */
data class ConnectionTraceContextPayload(
    val correlationId: String,
    val connectionGeneration: Long,
    val policyRevision: Long,
    val context: ConnectionTraceContext?,
) : IProto {
    init {
        requireValid()
    }

    override fun writeTo(buf: PacketBuffer) {
        try {
            requireValid()
        } catch (failure: IllegalArgumentException) {
            throw ProtocolEncodingException(failure.message ?: "Invalid connection trace update")
        }
        buf.writeString(correlationId)
        buf.writeVarLong(connectionGeneration)
        buf.writeVarLong(policyRevision)
        buf.writeBoolean(context != null)
        context?.writeTo(buf)
    }

    override fun toString(): String =
        "ConnectionTraceContextPayload(" +
            "connectionGeneration=$connectionGeneration, " +
            "policyRevision=$policyRevision, enabled=${context != null}, identifiers=redacted)"

    private fun requireValid() {
        ConnectionTraceContextPolicy.requireToken(correlationId, "traceUpdate.correlationId")
        ConnectionTraceContextPolicy.requirePositive(
            connectionGeneration,
            "traceUpdate.connectionGeneration",
        )
        ConnectionTraceContextPolicy.requirePositive(policyRevision, "traceUpdate.policyRevision")
        context?.let {
            require(it.correlationId == correlationId) {
                "trace update/context correlationId mismatch"
            }
            require(it.connectionGeneration == connectionGeneration) {
                "trace update/context connectionGeneration mismatch"
            }
            require(it.policyRevision == policyRevision) {
                "trace update/context policyRevision mismatch"
            }
        }
    }

    companion object : IProtoReader<ConnectionTraceContextPayload> {
        override fun readFrom(buf: PacketBuffer): ConnectionTraceContextPayload {
            val correlationId = ConnectionTraceContextPolicy.readToken(
                buf,
                "traceUpdate.correlationId",
            )
            val connectionGeneration = ConnectionTraceContextPolicy.readPositive(
                buf,
                "traceUpdate.connectionGeneration",
            )
            val policyRevision = ConnectionTraceContextPolicy.readPositive(
                buf,
                "traceUpdate.policyRevision",
            )
            val context = if (buf.readBoolean("traceUpdate.enabled")) {
                ConnectionTraceContext.readFrom(buf)
            } else {
                null
            }
            return try {
                ConnectionTraceContextPayload(
                    correlationId = correlationId,
                    connectionGeneration = connectionGeneration,
                    policyRevision = policyRevision,
                    context = context,
                )
            } catch (failure: IllegalArgumentException) {
                throw ProtocolCorruptionException(failure.message ?: "Invalid connection trace update")
            }
        }
    }
}
