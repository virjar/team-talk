package deployment

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class XiaomiPushDeploymentTest {
    @Test
    fun `default deployment needs neither credentials nor vendor SDK and disables server push`() = fixture { root ->
        val config = deployment { server { http { url = "https://private.example.com" } } }
        assertNull(config.xiaomiPush)
        assertEquals(mapOf("XIAOMI_PUSH_ENABLED" to "false"), config.xiaomiPushEnvironment())
        assertFalse("xiaomiPush" in Json.parseToJsonElement(config.toCanonicalJson()).jsonObject
            .getValue("client").jsonObject)
        assertEquals(emptyList(), root.listFiles().orEmpty().toList())
    }

    @Test
    fun `configured package snapshot and server environment use their respective non interchangeable identities`() = fixture { root ->
        val credentials = credentials(root)
        val sdk = sdk(root, "first SDK input")
        val config = configured(credentials, sdk)
        val push = requireNotNull(config.xiaomiPush)
        val snapshot = config.toCanonicalJson()
        val client = Json.parseToJsonElement(snapshot).jsonObject.getValue("client").jsonObject
        val pushSnapshot = client.getValue("xiaomiPush").jsonObject
        assertEquals("com.example.internal", client.getValue("androidApplicationId").jsonPrimitive.content)
        assertEquals("123456789", pushSnapshot.getValue("appId").jsonPrimitive.content)
        assertEquals("12345", pushSnapshot.getValue("channelId").jsonPrimitive.content)
        assertEquals("67890", pushSnapshot.getValue("templateId").jsonPrimitive.content)
        assertEquals(sdk.path, pushSnapshot.getValue("sdkFile").jsonPrimitive.content)
        assertEquals(digest(sdk), pushSnapshot.getValue("sdkSha256").jsonPrimitive.content)
        assertEquals(credentials.path, pushSnapshot.getValue("credentialsFile").jsonPrimitive.content)
        assertFalse("appKey" in pushSnapshot)
        assertFalse("appSecret" in pushSnapshot)
        assertFalse(snapshot.contains(CLIENT_KEY))
        assertFalse(snapshot.contains(SERVER_SECRET))
        assertEquals(CLIENT_KEY, push.readCredential("appKey"))
        assertEquals(mapOf(
            "XIAOMI_PUSH_ENABLED" to "true",
            "XIAOMI_PUSH_APP_SECRET" to SERVER_SECRET,
            "XIAOMI_PUSH_PACKAGE_NAME" to "com.example.internal",
            "XIAOMI_PUSH_CHANNEL_ID" to "12345",
            "XIAOMI_PUSH_TEMPLATE_ID" to "67890",
            "XIAOMI_PUSH_TITLE" to "内部版",
        ), config.xiaomiPushEnvironment())
        assertFalse(config.xiaomiPushEnvironment().values.contains(CLIENT_KEY))
    }

    @Test
    fun `snapshot reads SDK identity without reading or disclosing deployment credentials`() = fixture { root ->
        val credentials = File(root, "not-created.secrets")
        val sdk = sdk(root, "first SDK input")
        val first = configured(credentials, sdk)
        assertEquals(digest(sdk), requireNotNull(first.xiaomiPush).sdkSha256)
        val firstSnapshot = first.toCanonicalJson()
        assertFalse(credentials.exists())
        credentials.writeText("appKey=$CLIENT_KEY\nappSecret=$SERVER_SECRET\n")
        assertEquals(firstSnapshot, first.toCanonicalJson())
        assertEquals(SERVER_SECRET, first.xiaomiPushEnvironment()["XIAOMI_PUSH_APP_SECRET"])
        sdk.writeText("different SDK input")
        val second = configured(credentials, sdk)
        assertNotEquals(firstSnapshot, second.toCanonicalJson())
        assertEquals(digest(sdk), requireNotNull(second.xiaomiPush).sdkSha256)
    }

    @Test
    fun `enabled deployment rejects missing SDK and cannot use a client key as a server secret`() = fixture { root ->
        val credentials = credentials(root)
        assertFailsWith<IllegalArgumentException> { configured(credentials, File(root, "missing.aar")) }
        val sdk = sdk(root, "SDK input")
        assertFailsWith<IllegalArgumentException> {
            deployment {
                server { http { url = "https://private.example.com" } }
                client { xiaomiPush {
                    appId = "123456789"
                    channelId = "12345"
                    templateId = "67890"
                    credentialsFile = credentials
                } }
            }
        }
        val config = configured(credentials, sdk)
        credentials.writeText("appKey=$CLIENT_KEY\n")
        assertEquals(CLIENT_KEY, requireNotNull(config.xiaomiPush).readCredential("appKey"))
        val failure = assertFailsWith<IllegalArgumentException> { config.xiaomiPushEnvironment() }
        assertEquals("Xiaomi push credentials file is missing a valid appSecret", failure.message)
        assertFalse(failure.message.orEmpty().contains(CLIENT_KEY))
    }

    private fun configured(credentials: File, sdk: File) = deployment {
        server { http { url = "https://private.example.com" } }
        client {
            xiaomiPush {
                appId = "123456789"
                channelId = "12345"
                templateId = "67890"
                credentialsFile = credentials
                sdkFile = sdk
            }
            identity {
                applicationId = "com.example.internal"
                androidApplicationId = "com.example.internal"
                displayName = "内部版"
                desktopName = "Internal"
            }
        }
    }

    private fun credentials(root: File) = File(root, "xiaomi.secrets").apply {
        writeText("appKey=$CLIENT_KEY\nappSecret=$SERVER_SECRET\n")
    }

    // Configuration only fingerprints this input; these tests do not build an Android SDK or APK.
    private fun sdk(root: File, content: String) = File(root, "MiPush_SDK_Client_fixture.aar").apply { writeText(content) }

    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("teamtalk-xiaomi-deployment-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }

    private companion object {
        const val CLIENT_KEY = "fixture-client-app-key"
        const val SERVER_SECRET = "fixture-server-app-secret"
    }
}
