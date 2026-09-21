package com.virjar.tk.server.infra.push

import java.io.File
import java.net.URI
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/** Environment is part of a token's identity; sandbox tokens must never reach the production endpoint. */
internal enum class ApnsEnvironment(val wireName: String, val endpoint: URI) {
    SANDBOX("sandbox", URI("https://api.sandbox.push.apple.com")),
    PRODUCTION("production", URI("https://api.push.apple.com"));

    val channel: String get() = "apns-$wireName"

    companion object {
        fun fromWire(value: String): ApnsEnvironment? = entries.firstOrNull { it.wireName == value }
        fun fromChannel(value: String): ApnsEnvironment? = entries.firstOrNull { it.channel == value }
    }
}

/** Server-only key material. Neither the key nor a provider JWT participates in logs or snapshots. */
internal class ApnsPushConfiguration(
    val teamId: String,
    val keyId: String,
    val bundleId: String,
    val title: String,
    privateKeyPem: String,
    val environments: Set<ApnsEnvironment> = setOf(ApnsEnvironment.PRODUCTION),
) {
    val privateKey: ECPrivateKey

    init {
        require(teamId.matches(Regex("[A-Z0-9]{10}"))) { "APNS_PUSH_TEAM_ID is invalid" }
        require(keyId.matches(Regex("[A-Z0-9]{10}"))) { "APNS_PUSH_KEY_ID is invalid" }
        require(bundleId.length <= 255 && bundleId.matches(Regex("[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+"))) {
            "APNS_PUSH_BUNDLE_ID is invalid"
        }
        require(title.isNotBlank() && title.length <= 80 && title.none(Char::isISOControl)) { "APNS_PUSH_TITLE is invalid" }
        require(environments.isNotEmpty()) { "APNS_PUSH_ENVIRONMENTS is empty" }
        privateKey = parseApnsPrivateKey(privateKeyPem)
    }

    companion object {
        fun fromEnvironment(environment: (String) -> String? = System::getenv): ApnsPushConfiguration? {
            when (environment("APNS_PUSH_ENABLED")) {
                null, "false" -> return null
                "true" -> Unit
                else -> error("APNS_PUSH_ENABLED must be true or false")
            }
            val keyFile = environment("APNS_PUSH_PRIVATE_KEY_FILE")?.takeIf(String::isNotBlank)
            val keyBase64 = environment("APNS_PUSH_PRIVATE_KEY_BASE64")?.takeIf(String::isNotBlank)
            require((keyFile == null) != (keyBase64 == null)) {
                "Configure exactly one APNS_PUSH_PRIVATE_KEY_FILE or APNS_PUSH_PRIVATE_KEY_BASE64"
            }
            val pem = if (keyFile != null) {
                val file = File(keyFile)
                require(file.isFile && file.length() in 1..16_384) { "APNs private key file is missing or too large" }
                file.readText(Charsets.US_ASCII)
            } else {
                require(keyBase64!!.length <= 24_000) { "APNs private key is too large" }
                try { Base64.getDecoder().decode(keyBase64).toString(Charsets.US_ASCII) }
                catch (_: IllegalArgumentException) { error("APNs private key encoding is invalid") }
            }
            return ApnsPushConfiguration(
                teamId = environment("APNS_PUSH_TEAM_ID").orEmpty(),
                keyId = environment("APNS_PUSH_KEY_ID").orEmpty(),
                bundleId = environment("APNS_PUSH_BUNDLE_ID").orEmpty(),
                title = environment("APNS_PUSH_TITLE").orEmpty(),
                privateKeyPem = pem,
                environments = (environment("APNS_PUSH_ENVIRONMENTS") ?: "production").split(',').map { value ->
                    requireNotNull(ApnsEnvironment.fromWire(value)) { "APNS_PUSH_ENVIRONMENTS is invalid" }
                }.toSet(),
            )
        }
    }
}

internal fun parseApnsPrivateKey(pem: String): ECPrivateKey {
    require(pem.length <= 16_384 && Regex(
        "-----BEGIN PRIVATE KEY-----[A-Za-z0-9+/=\\s]+-----END PRIVATE KEY-----",
    ).matches(pem.trim())) { "APNs requires one PKCS#8 .p8 private key" }
    return try {
        val encoded = pem.removePrefix("\uFEFF").trim()
            .removePrefix("-----BEGIN PRIVATE KEY-----").removeSuffix("-----END PRIVATE KEY-----")
            .filterNot(Char::isWhitespace)
        val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)))
        require(key is ECPrivateKey && key.params.order.toString(16) ==
            "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551")
        key
    } catch (_: Exception) {
        throw IllegalArgumentException("APNs private key must use the P-256 curve")
    }
}
