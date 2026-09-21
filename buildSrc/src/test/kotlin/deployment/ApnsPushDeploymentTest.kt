package deployment

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Properties
import kotlin.test.*

class ApnsPushDeploymentTest {
    @Test fun `Apple identity defaults follow private installation and explicit bundle remains stable`() {
        val identity = ClientDistributionIdentity("com.example.internal", "内部版", "Internal")
        assertEquals("com.example.internal.ios", identity.iosBundleId)
        assertEquals("com.virjar.tk.ios", ClientDistributionIdentity().iosBundleId)
        val configured = deployment {
            server { http { url = "https://private.example.com" } }
            client { identity {
                applicationId = "com.example.internal"; desktopName = "Internal"
                iosBundleId = "com.example.registered.iphone"
            } }
        }
        assertEquals("com.example.registered.iphone", configured.client.iosBundleId)
        assertFailsWith<IllegalArgumentException> { identity.copy(iosBundleId = "com.virjar.tk.ios") }
        assertFailsWith<IllegalArgumentException> { identity.copy(iosBundleId = "com.example.*") }
        assertEquals(mapOf("APNS_PUSH_ENABLED" to "false"), configured.apnsPushEnvironment())
    }

    @Test fun `deployment snapshots only key coordinates while env contains validated provider key`() {
        val root = Files.createTempDirectory("teamtalk-apns-config-").toFile()
        try {
            val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val privateKey = Base64.getEncoder().encodeToString(pair.private.encoded)
            val keyFile = File(root, "AuthKey_fixture.p8").apply {
                writeText("-----BEGIN PRIVATE KEY-----\n$privateKey\n-----END PRIVATE KEY-----")
            }
            val config = deployment {
                server { http { url = "https://private.example.com" } }
                client {
                    identity {
                        applicationId = "com.example.internal"; desktopName = "Internal"; displayName = "内部版"
                        iosBundleId = "com.example.registered.iphone"
                    }
                    apnsPush {
                        teamId = "ABCDEFGHIJ"; keyId = "KLMNOPQRST"; privateKeyFile = keyFile
                        environments = setOf("sandbox", "production")
                    }
                }
            }
            val snapshot = config.toCanonicalJson()
            val apple = Json.parseToJsonElement(snapshot).jsonObject["client"]!!.jsonObject["apnsPush"]!!.jsonObject
            assertEquals(keyFile.path, apple["privateKeyFile"]?.jsonPrimitive?.content)
            assertEquals("production,sandbox", apple["environments"]?.jsonPrimitive?.content)
            assertFalse(snapshot.contains(privateKey))
            assertFalse(config.toString().contains(privateKey))
            val environment = config.apnsPushEnvironment()
            assertEquals("com.example.registered.iphone", environment["APNS_PUSH_BUNDLE_ID"])
            assertEquals("内部版", environment["APNS_PUSH_TITLE"])
            assertEquals(keyFile.readText(), Base64.getDecoder().decode(environment.getValue("APNS_PUSH_PRIVATE_KEY_BASE64")).decodeToString())
            val secrets = Properties().apply {
                requiredDeploymentSecretKeys.forEach { setProperty(it, "fixture-value") }
                putAll(environment)
            }
            val env = generateEnvShContent(secrets, false, "443", "/opt/teamtalk", 8080, "5100")
            assertTrue(env.contains("APNS_PUSH_ENABLED=true"))
            assertTrue(env.contains("APNS_PUSH_PRIVATE_KEY_BASE64="))
            assertFalse(env.contains("BEGIN PRIVATE KEY"), "env.sh stores the key as one safely quoted line")
            keyFile.writeText("not a private key")
            assertFailsWith<IllegalArgumentException> { config.apnsPushEnvironment() }
        } finally {
            root.deleteRecursively()
        }
    }
}
