package com.virjar.tk.s3

import java.net.URLEncoder
import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature V4 签名工具，纯函数式 object 单例，线程安全。
 */
object AwsV4Signer {

    private const val ALGORITHM = "AWS4-HMAC-SHA256"
    private const val REGION = "us-east-1"
    private const val SERVICE = "s3"
    private const val TERMINATOR = "aws4_request"

    private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val DATETIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    const val EMPTY_PAYLOAD_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"

    data class SigningResult(
        val authorization: String,
        val dateTime: String,
        val date: String,
    )

    /**
     * 为 S3 请求生成 AWS Signature V4 签名。
     *
     * @param method HTTP 方法（GET, PUT, HEAD, DELETE）
     * @param path 请求路径（如 /bucket/objectKey）
     * @param queryParams 查询参数（可为空 map）
     * @param headers 请求头（必须包含 Host、X-Amz-Content-Sha256 等参与签名的头）
     * @param payloadHash 载荷 SHA256 哈希的十六进制字符串，或 [UNSIGNED_PAYLOAD]
     * @param accessKey Access Key
     * @param secretKey Secret Key
     * @param timestamp 签名时间戳（UTC），默认当前时间
     */
    fun sign(
        method: String,
        path: String,
        queryParams: Map<String, String> = emptyMap(),
        headers: Map<String, String>,
        payloadHash: String,
        accessKey: String,
        secretKey: String,
        timestamp: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC),
    ): SigningResult {
        val dateTime = timestamp.format(DATETIME_FORMAT)
        val date = timestamp.format(DATE_FORMAT)

        val credentialScope = "$date/$REGION/$SERVICE/$TERMINATOR"

        // 确定参与签名的头（小写排序）
        val signedHeaderNames = headers.keys.map { it.lowercase() }.sorted()
        val signedHeaders = signedHeaderNames.joinToString(";")

        // Canonical Request
        val canonicalQueryString = queryParams.entries
            .sortedBy { it.key }
            .joinToString("&") { "${uriEncode(it.key, encodeSlash = false)}=${uriEncode(it.value, encodeSlash = false)}" }

        val canonicalHeaders = signedHeaderNames.joinToString("\n", postfix = "\n") { h ->
            val key = headers.entries.first { it.key.equals(h, ignoreCase = true) }
            "${h.lowercase()}:${key.value.trim()}"
        }

        val canonicalRequest = buildString {
            append(method).append('\n')
            append(path).append('\n')
            append(canonicalQueryString).append('\n')
            append(canonicalHeaders).append('\n')
            append(signedHeaders).append('\n')
            append(payloadHash)
        }

        // String to Sign
        val stringToSign = buildString {
            append(ALGORITHM).append('\n')
            append(dateTime).append('\n')
            append(credentialScope).append('\n')
            append(sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8)))
        }

        // HMAC 密钥派生链
        val signingKey = deriveSigningKey(secretKey, date)

        // 签名
        val signature = hexHmacSha256(signingKey, stringToSign.toByteArray(Charsets.UTF_8))

        val authorization = "$ALGORITHM Credential=$accessKey/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature"

        return SigningResult(authorization, dateTime, date)
    }

    fun deriveSigningKey(secretKey: String, date: String): ByteArray {
        val dateKey = hmacSha256("AWS4$secretKey".toByteArray(Charsets.UTF_8), date)
        val regionKey = hmacSha256(dateKey, REGION)
        val serviceKey = hmacSha256(regionKey, SERVICE)
        return hmacSha256(serviceKey, TERMINATOR)
    }

    fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return hex(digest.digest(data))
    }

    fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun hexHmacSha256(key: ByteArray, data: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return hex(mac.doFinal(data))
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /**
     * URI 编码：S3 要求对除 `/` 以外的非字母数字字符编码。
     */
    fun uriEncode(input: String, encodeSlash: Boolean = true): String {
        val result = StringBuilder()
        for (ch in input) {
            when {
                ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '_' || ch == '-' || ch == '~' || ch == '.' ->
                    result.append(ch)
                ch == '/' && !encodeSlash ->
                    result.append(ch)
                else ->
                    result.append(URLEncoder.encode(ch.toString(), Charsets.UTF_8.name()))
            }
        }
        return result.toString()
    }
}
