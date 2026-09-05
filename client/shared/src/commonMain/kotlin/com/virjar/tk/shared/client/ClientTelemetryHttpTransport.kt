package com.virjar.tk.shared.client

/** 阻止已退役的 uploader 借用不同身份或同 uid 重登录的 token。 */
internal fun ownedHttpAccessToken(
    ownerUid: String,
    credentials: SessionHttpCredentials,
    ownerIdentityEpoch: Long? = null,
): String {
    check(ownerUid.isNotBlank()) { "HTTP resource owner uid must not be blank" }
    check(credentials.uid == ownerUid) { "Authenticated HTTP identity changed" }
    if (ownerIdentityEpoch != null) {
        check(credentials.identityEpoch == ownerIdentityEpoch) { "Authenticated HTTP session changed" }
    }
    val token = credentials.accessToken?.takeIf(String::isNotBlank)
        ?: error("No authenticated access token for HTTP request")
    require(token.all { it.code in 0x21..0x7e }) { "HTTP access token contains illegal characters" }
    return token
}

/** 针对响应终态的精确 bearer 比较；畸形或被替换的凭据按失败关闭处理。 */
internal fun ownedHttpAccessTokenMatches(
    ownerUid: String,
    ownerIdentityEpoch: Long,
    rejectedAccessToken: String,
    credentials: SessionHttpCredentials,
): Boolean = try {
    ownedHttpAccessToken(ownerUid, credentials, ownerIdentityEpoch) == rejectedAccessToken
} catch (_: Exception) {
    false
}

internal interface PlatformTelemetryHttpTransport {
    /** 阻塞式 gzip-JSON POST；每个平台实现都禁止跟随重定向。 */
    fun postGzipJson(
        url: String,
        compressed: ByteArray,
        headers: Map<String, String>,
    ): PlatformTelemetryHttpResponse

    fun close()
}

internal data class PlatformTelemetryHttpResponse(
    val statusCode: Int,
    val body: String?,
)

internal expect fun createPlatformTelemetryHttpTransport(): PlatformTelemetryHttpTransport

/** 用于阻塞式遥测 HTTP 与持久假脱机 IO 的、会话拥有的单个有界 worker。 */
internal interface PlatformTelemetryHttpIoWorker {
    fun execute(task: () -> Unit): Boolean

    fun closeAndDrain()
}

internal expect fun createPlatformTelemetryHttpIoWorker(): PlatformTelemetryHttpIoWorker

internal class SessionResourceCloseException(
    owner: String,
    val failures: List<Throwable>,
) : IllegalStateException("$owner close failed in ${failures.size} operation(s)", failures.firstOrNull())
