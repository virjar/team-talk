package com.virjar.tk.server.infra.turn

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * TURN 短期凭据（coturn `use-auth-secret` 同款机制）。
 *
 * username 是 UNIX 过期秒，password = base64(HMAC-SHA1(secret, username))。凭据由服务端
 * 在呼叫建立时签发并随信令下发，客户端当作普通 TURN long-term credential 使用
 * （wire 上的校验 key 仍是 MD5(username:realm:password)，RFC 5389 §15.4）。
 * 过期凭据在 allocate/refresh 时被拒绝，正在使用的 relay 由 allocation 生命周期约束。
 */
class TurnCredentials(private val secret: ByteArray, val realm: String) {

    data class Issued(val username: String, val password: String)

    private val random = SecureRandom()

    /** 签发一份在 [validForSec] 秒后过期的凭据。 */
    fun issue(validForSec: Long): Issued {
        require(validForSec in 1..MAX_VALID_FOR_SEC) { "凭据有效期越界" }
        val username = ((System.currentTimeMillis() / 1000) + validForSec).toString()
        return Issued(username, passwordFor(username))
    }

    /** 校验请求携带的凭据；用户名过期、格式非法或摘要不匹配返回 null。 */
    fun verify(username: String, password: String, nowEpochSec: Long): ByteArray? {
        val expiry = username.toLongOrNull() ?: return null
        if (expiry < nowEpochSec) return null
        if (password != passwordFor(username)) return null
        // 标准 long-term credential key：MD5(username:realm:password)
        return Stun.md5Utf8("$username:$realm:$password")
    }

    /**
     * 由请求的 username 直接派生 long-term key（password 从 secret 推导，客户端不回传）。
     * 用户名过期或格式非法返回 null。
     */
    fun longTermKey(username: String, nowEpochSec: Long): ByteArray? {
        val expiry = username.toLongOrNull() ?: return null
        if (expiry < nowEpochSec) return null
        return verify(username, passwordFor(username), nowEpochSec)
    }

    /** 进程内 nonce：启动随机，重启轮换；客户端带回不匹配时按 STALE_NONCE 重给。 */
    fun newNonce(): String = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    private fun passwordFor(username: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        return Base64.getEncoder().encodeToString(mac.doFinal(username.toByteArray(StandardCharsets.UTF_8)))
    }

    companion object {
        const val MAX_VALID_FOR_SEC = 24 * 60 * 60L
        const val NONCE_BYTES = 16
    }
}
