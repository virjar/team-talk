package com.virjar.tk.server.domain.command

import com.virjar.tk.protocol.ReliableCommandContract
import java.security.MessageDigest
import java.util.UUID

/** 响应可能丢失的命令的、归一化的客户端持有身份。 */
fun canonicalOperationId(operationId: String, label: String): String {
    val canonical = operationId.takeIf { it.length == UUID_TEXT_LENGTH }
        ?.let { runCatching { UUID.fromString(it).toString() }.getOrNull() }
        ?.takeIf { it == operationId }
    require(canonical != null) { "${label}操作标识非法" }
    return canonical
}

/** 带长度前缀的 SHA-256 防止持久化命令回执中出现歧义拼接。 */
fun reliableCommandFingerprint(vararg fields: String?): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fields.forEach { field ->
        if (field == null) {
            digest.update(0.toByte())
        } else {
            digest.update(1.toByte())
            val bytes = field.encodeToByteArray()
            digest.update((bytes.size ushr 24).toByte())
            digest.update((bytes.size ushr 16).toByte())
            digest.update((bytes.size ushr 8).toByte())
            digest.update(bytes.size.toByte())
            digest.update(bytes)
        }
    }
    return digest.digest().joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

/** 一个操作身份不能被重新分配给不同的不可变命令载荷。 */
class ReliableCommandConflictException(message: String) : IllegalArgumentException(message)

/** 一条客户端保留的命令已越过有限重放契约，绝不能再执行一次。 */
class ReliableCommandExpiredException(message: String) : IllegalArgumentException(message)

/** 所有保留的身份都仍然可重放，因此新变更不能驱逐其中任何一个。 */
class ReliableCommandCapacityException(message: String) : IllegalStateException(message)

/**
 * 客户端持有命令身份的共享有限生命周期契约。
 *
 * 签发时间是不可变指纹的一部分。一旦越过 [RETRY_HORIZON_MILLIS]，即使其回执已经被回收，
 * 服务器也拒绝该命令。这正是有界回执存储之所以安全的原因：回收永远不会把一次旧的
 * ACK 丢失重试变成新的变更。一个适度的未来容差容忍普通工作站/手机的时钟偏差，
 * 而不让客户端无限期地占用存储。
 */
object ReliableCommandPolicy {
    const val RETRY_HORIZON_MILLIS: Long = ReliableCommandContract.RETRY_HORIZON_MILLIS
    const val MAX_FUTURE_CLOCK_SKEW_MILLIS: Long =
        ReliableCommandContract.MAX_FUTURE_CLOCK_SKEW_MILLIS

    fun requireActiveIssuedAt(issuedAt: Long, nowMillis: Long, label: String): Long {
        require(nowMillis >= 0L) { "服务器时钟非法" }
        require(issuedAt >= 0L) { "${label}签发时间非法" }
        require(issuedAt <= saturatingAdd(nowMillis, MAX_FUTURE_CLOCK_SKEW_MILLIS)) {
            "${label}签发时间超出允许的时钟偏差"
        }
        if (issuedAt < saturatingSubtract(nowMillis, RETRY_HORIZON_MILLIS)) {
            throw ReliableCommandExpiredException("${label}已超过可靠重试期限")
        }
        return issuedAt
    }

    fun expiresAt(issuedAt: Long): Long {
        require(issuedAt >= 0L) { "可靠命令签发时间非法" }
        return ReliableCommandContract.lastActiveAt(issuedAt)
    }

    private fun saturatingAdd(value: Long, increment: Long): Long =
        if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment

    private fun saturatingSubtract(value: Long, decrement: Long): Long =
        if (value < Long.MIN_VALUE + decrement) Long.MIN_VALUE else value - decrement
}

private const val UUID_TEXT_LENGTH = 36
