package com.virjar.tk.protocol.telemetry

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.ProtocolCorruptionException
import com.virjar.tk.protocol.ProtocolEncodingException
import kotlinx.serialization.Serializable

/**
 * 服务端签发的标识，将一条物理客户端连接与其有界的服务端 trace 关联起来。
 *
 * 所有标识都是 opaque 的、类似 bearer 的令牌。它们被序列化用于关联，
 * 但有意不出现在 [toString] 中，这样通用诊断无法把它们复制进普通日志。
 */
@Serializable
data class ConnectionTraceContext(
    val correlationId: String,
    val traceId: String,
    val sessionId: String,
    val connectionGeneration: Long,
    val policyRevision: Long,
    val expiresAtEpochMs: Long,
) : IProto {
    init {
        ConnectionTraceContextPolicy.requireValid(this)
    }

    fun isExpired(nowEpochMs: Long): Boolean = nowEpochMs >= expiresAtEpochMs

    override fun writeTo(buf: PacketBuffer) {
        ConnectionTraceContextPolicy.requireEncodable(this)
        buf.writeString(correlationId)
        buf.writeString(traceId)
        buf.writeString(sessionId)
        buf.writeVarLong(connectionGeneration)
        buf.writeVarLong(policyRevision)
        buf.writeVarLong(expiresAtEpochMs)
    }

    override fun toString(): String =
        "ConnectionTraceContext(" +
            "connectionGeneration=$connectionGeneration, " +
            "policyRevision=$policyRevision, " +
            "expiresAtEpochMs=$expiresAtEpochMs, identifiers=redacted)"

    companion object : IProtoReader<ConnectionTraceContext> {
        override fun readFrom(buf: PacketBuffer): ConnectionTraceContext {
            val correlationId = ConnectionTraceContextPolicy.readToken(buf, "trace.correlationId")
            val traceId = ConnectionTraceContextPolicy.readToken(buf, "trace.traceId")
            val sessionId = ConnectionTraceContextPolicy.readToken(buf, "trace.sessionId")
            val connectionGeneration = ConnectionTraceContextPolicy.readPositive(
                buf,
                "trace.connectionGeneration",
            )
            val policyRevision = ConnectionTraceContextPolicy.readPositive(buf, "trace.policyRevision")
            val expiresAtEpochMs = ConnectionTraceContextPolicy.readPositive(
                buf,
                "trace.expiresAtEpochMs",
            )
            return try {
                ConnectionTraceContext(
                    correlationId = correlationId,
                    traceId = traceId,
                    sessionId = sessionId,
                    connectionGeneration = connectionGeneration,
                    policyRevision = policyRevision,
                    expiresAtEpochMs = expiresAtEpochMs,
                )
            } catch (failure: IllegalArgumentException) {
                throw ProtocolCorruptionException(failure.message ?: "Invalid connection trace context")
            }
        }
    }
}

/** 客户端 correlation id 与服务端 trace/session id 共享的严格语法。 */
object ConnectionTraceContextPolicy {
    const val MIN_TOKEN_LENGTH: Int = 16
    const val MAX_TOKEN_LENGTH: Int = 128

    private val SAFE_TOKEN = Regex("^[A-Za-z0-9_-]+$")

    fun requireToken(value: String, fieldName: String) {
        require(value.length in MIN_TOKEN_LENGTH..MAX_TOKEN_LENGTH) {
            "$fieldName length must be in $MIN_TOKEN_LENGTH..$MAX_TOKEN_LENGTH"
        }
        require(SAFE_TOKEN.matches(value)) {
            "$fieldName must contain only ASCII letters, digits, '_' or '-'"
        }
    }

    fun requirePositive(value: Long, fieldName: String) {
        require(value > 0L) { "$fieldName must be positive" }
    }

    fun requireValid(context: ConnectionTraceContext) {
        requireToken(context.correlationId, "trace.correlationId")
        requireToken(context.traceId, "trace.traceId")
        requireToken(context.sessionId, "trace.sessionId")
        requirePositive(context.connectionGeneration, "trace.connectionGeneration")
        requirePositive(context.policyRevision, "trace.policyRevision")
        requirePositive(context.expiresAtEpochMs, "trace.expiresAtEpochMs")
    }

    internal fun requireEncodable(context: ConnectionTraceContext) {
        try {
            requireValid(context)
        } catch (failure: IllegalArgumentException) {
            throw ProtocolEncodingException(failure.message ?: "Invalid connection trace context")
        }
    }

    internal fun readToken(buf: PacketBuffer, fieldName: String): String {
        val value = buf.readRequiredString(MAX_TOKEN_LENGTH, fieldName)
        try {
            requireToken(value, fieldName)
        } catch (failure: IllegalArgumentException) {
            throw ProtocolCorruptionException(failure.message ?: "Invalid $fieldName")
        }
        return value
    }

    internal fun readPositive(buf: PacketBuffer, fieldName: String): Long {
        val value = buf.readVarLong()
        if (value <= 0L) throw ProtocolCorruptionException("$fieldName must be positive")
        return value
    }
}
