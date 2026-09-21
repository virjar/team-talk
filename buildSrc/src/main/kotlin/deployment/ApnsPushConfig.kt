package deployment

import java.io.File
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/** Only public Apple IDs and a local file path belong in Deployment.kt. Never embed the .p8 contents. */
data class ApnsPushDeployment(
    val teamId: String,
    val keyId: String,
    val privateKeyFile: File,
    val environments: Set<String> = setOf("production"),
) {
    init {
        require(teamId.matches(Regex("[A-Z0-9]{10}"))) { "client.apnsPush.teamId must be an Apple Team ID" }
        require(keyId.matches(Regex("[A-Z0-9]{10}"))) { "client.apnsPush.keyId must be an APNs key ID" }
        require(environments.isNotEmpty() && environments.all { it in setOf("sandbox", "production") }) {
            "client.apnsPush.environments must contain sandbox and/or production"
        }
    }

    /** Validate before acquiring a remote deployment lease; return a single-line env.sh secret. */
    internal fun encodedPrivateKey(): String {
        require(privateKeyFile.isFile && privateKeyFile.length() in 1..16_384) { "APNs .p8 key file is missing or too large" }
        val pem = privateKeyFile.readText(Charsets.US_ASCII).trim()
        require(Regex("-----BEGIN PRIVATE KEY-----[A-Za-z0-9+/=\\s]+-----END PRIVATE KEY-----").matches(pem)) {
            "APNs key must be a PKCS#8 .p8 PEM file"
        }
        try {
            val bytes = Base64.getDecoder().decode(pem.removePrefix("-----BEGIN PRIVATE KEY-----")
                .removeSuffix("-----END PRIVATE KEY-----").filterNot(Char::isWhitespace))
            val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes))
            require(key is ECPrivateKey && key.params.order.toString(16) ==
                "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551")
        } catch (_: Exception) {
            throw IllegalArgumentException("APNs .p8 key must use the P-256 curve")
        }
        return Base64.getEncoder().encodeToString(pem.toByteArray(Charsets.US_ASCII))
    }
}

@DeploymentDsl
class ApnsPushDeploymentBuilder internal constructor() {
    var teamId: String = ""
    var keyId: String = ""
    var privateKeyFile: File? = null
    var environments: Set<String> = setOf("production")

    internal fun build() = ApnsPushDeployment(teamId, keyId,
        requireNotNull(privateKeyFile) { "client.apnsPush.privateKeyFile must be configured" }, environments.toSet())
}

internal fun DeploymentConfig.apnsPushEnvironment(): Map<String, String> {
    val push = apnsPush ?: return mapOf("APNS_PUSH_ENABLED" to "false")
    return linkedMapOf(
        "APNS_PUSH_ENABLED" to "true",
        "APNS_PUSH_TEAM_ID" to push.teamId,
        "APNS_PUSH_KEY_ID" to push.keyId,
        "APNS_PUSH_BUNDLE_ID" to client.iosBundleId,
        "APNS_PUSH_TITLE" to client.displayName,
        "APNS_PUSH_ENVIRONMENTS" to push.environments.sorted().joinToString(","),
        "APNS_PUSH_PRIVATE_KEY_BASE64" to push.encodedPrivateKey(),
    )
}
