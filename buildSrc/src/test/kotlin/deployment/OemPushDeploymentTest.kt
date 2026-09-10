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
import kotlin.test.assertTrue

class OemPushDeploymentTest {
    @Test
    fun `default deployment needs neither credentials nor vendor SDK and disables every vendor`() = fixture { root ->
        val config = deployment { server { http { url = "https://private.example.com" } } }
        assertTrue(config.oemPush.isEmpty())
        assertEquals(
            OemPushVendors.ALL.associate { "${it.uppercase()}_PUSH_ENABLED" to "false" },
            config.oemPushEnvironment(),
        )
        assertFalse("oemPush" in Json.parseToJsonElement(config.toCanonicalJson()).jsonObject
            .getValue("client").jsonObject)
        assertEquals(emptyList(), root.listFiles().orEmpty().toList())
    }

    @Test
    fun `configured package snapshot and server environment use their respective non interchangeable identities`() = fixture { root ->
        val credentials = credentials(root, "xiaomi.secrets")
        val sdk = sdk(root, "MiPush_SDK_Client_fixture.aar", "first SDK input")
        val config = configured(credentials, sdk)
        val push = requireNotNull(config.oemPush[OemPushVendors.XIAOMI])
        val snapshot = config.toCanonicalJson()
        val client = Json.parseToJsonElement(snapshot).jsonObject.getValue("client").jsonObject
        val pushSnapshot = client.getValue("oemPush").jsonObject.getValue("xiaomi").jsonObject
        assertEquals("com.example.internal", client.getValue("androidApplicationId").jsonPrimitive.content)
        assertEquals("123456789", pushSnapshot.getValue("appId").jsonPrimitive.content)
        assertEquals("12345", pushSnapshot.getValue("channelId").jsonPrimitive.content)
        assertEquals("67890", pushSnapshot.getValue("templateId").jsonPrimitive.content)
        assertEquals(sdk.path, pushSnapshot.getValue("sdkFiles").jsonPrimitive.content)
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
            "XIAOMI_PUSH_APP_ID" to "123456789",
            "XIAOMI_PUSH_CHANNEL_ID" to "12345",
            "XIAOMI_PUSH_TEMPLATE_ID" to "67890",
            "XIAOMI_PUSH_TITLE" to "内部版",
        ) + disabledExcept(OemPushVendors.XIAOMI), config.oemPushEnvironment())
        assertFalse(config.oemPushEnvironment().values.contains(CLIENT_KEY))
    }

    @Test
    fun `huawei honor oppo vivo and meizu blocks produce vendor-specific environments and snapshots`() = fixture { root ->
        val huaweiSdk = sdk(root, "push-huawei.aar", "huawei sdk")
        val honorSdk = sdk(root, "push-honor.aar", "honor sdk")
        val oppoSdks = sdk(root, "push-oppo.aar", "oppo sdk")
        val vivoSdk = sdk(root, "push-vivo.aar", "vivo sdk")
        val meizuSdk = sdk(root, "push-meizu.aar", "meizu sdk")
        val config = deployment {
            server { http { url = "https://private.example.com" } }
            client {
                identity {
                    applicationId = "com.example.internal"
                    androidApplicationId = "com.example.internal"
                    displayName = "内部版"
                    desktopName = "Internal"
                }
                huaweiPush {
                    appId = "111222333"
                    channelId = "hw-chat"
                    credentialsFile = credentials(root, "huawei.secrets", "appSecret=huawei-server-secret\n")
                    sdkFile = huaweiSdk
                }
                honorPush {
                    appId = "444555666"
                    channelId = "honor-chat"
                    credentialsFile = credentials(root, "honor.secrets", "appSecret=honor-server-secret\n")
                    sdkFile = honorSdk
                }
                oppoPush {
                    appKey = "8899aa"
                    channelId = "oppo-chat"
                    credentialsFile = credentials(root, "oppo.secrets", "appSecret=oppo-server-secret\n")
                    sdkFile = oppoSdks
                }
                vivoPush {
                    appId = "10004"
                    appKey = "vivo-app-key"
                    category = "IM"
                    credentialsFile = credentials(root, "vivo.secrets", "appSecret=vivo-server-secret\n")
                    sdkFile = vivoSdk
                }
                meizuPush {
                    appId = "10000"
                    appKey = "meizu-app-key"
                    credentialsFile = credentials(root, "meizu.secrets", "appSecret=meizu-server-secret\n")
                    sdkFile = meizuSdk
                }
            }
        }
        val environment = config.oemPushEnvironment()
        assertEquals("true", environment["HUAWEI_PUSH_ENABLED"])
        assertEquals("huawei-server-secret", environment["HUAWEI_PUSH_APP_SECRET"])
        assertEquals("111222333", environment["HUAWEI_PUSH_APP_ID"])
        assertEquals("hw-chat", environment["HUAWEI_PUSH_CHANNEL_ID"])
        assertEquals("true", environment["HONOR_PUSH_ENABLED"])
        assertEquals("honor-server-secret", environment["HONOR_PUSH_APP_SECRET"])
        assertEquals("oppo-server-secret", environment["OPPO_PUSH_APP_SECRET"])
        assertEquals("8899aa", environment["OPPO_PUSH_APP_KEY"])
        assertEquals("vivo-server-secret", environment["VIVO_PUSH_APP_SECRET"])
        assertEquals("IM", environment["VIVO_PUSH_CATEGORY"])
        assertEquals("meizu-server-secret", environment["MEIZU_PUSH_APP_SECRET"])
        assertEquals("meizu-app-key", environment["MEIZU_PUSH_APP_KEY"])
        assertEquals("false", environment["XIAOMI_PUSH_ENABLED"])
        assertEquals("内部版", environment["VIVO_PUSH_TITLE"])
        assertEquals("内部版", environment["HUAWEI_PUSH_TITLE"])

        val vendors = Json.parseToJsonElement(config.toCanonicalJson()).jsonObject
            .getValue("client").jsonObject.getValue("oemPush").jsonObject.keys
        assertEquals(setOf("huawei", "honor", "oppo", "vivo", "meizu"), vendors)
        assertFalse(config.toCanonicalJson().contains("server-secret"))
    }

    @Test
    fun `vivo deployments cap the shared display title at twenty characters`() = fixture { root ->
        assertFailsWith<IllegalArgumentException> {
            deployment {
                server { http { url = "https://private.example.com" } }
                client {
                    identity { displayName = "这个展示名称确实超过了二十个字符的严格限制" }
                    vivoPush {
                        appId = "10004"
                        appKey = "vivo-app-key"
                        category = "IM"
                        credentialsFile = credentials(root, "vivo.secrets", "appSecret=s\n")
                        sdkFile = sdk(root, "push-vivo.aar", "vivo sdk")
                    }
                }
            }
        }
    }

    @Test
    fun `snapshot reads SDK identity without reading or disclosing deployment credentials`() = fixture { root ->
        val credentials = File(root, "not-created.secrets")
        val sdk = sdk(root, "MiPush_SDK_Client_fixture.aar", "first SDK input")
        val first = configured(credentials, sdk)
        assertEquals(digest(sdk), requireNotNull(first.oemPush[OemPushVendors.XIAOMI]).sdkSha256)
        val firstSnapshot = first.toCanonicalJson()
        assertFalse(credentials.exists())
        credentials.writeText("appKey=$CLIENT_KEY\nappSecret=$SERVER_SECRET\n")
        assertEquals(firstSnapshot, first.toCanonicalJson())
        assertEquals(SERVER_SECRET, first.oemPushEnvironment()["XIAOMI_PUSH_APP_SECRET"])
        sdk.writeText("different SDK input")
        val second = configured(credentials, sdk)
        assertNotEquals(firstSnapshot, second.toCanonicalJson())
        assertEquals(digest(sdk), requireNotNull(second.oemPush[OemPushVendors.XIAOMI]).sdkSha256)
    }

    @Test
    fun `enabled deployment rejects missing SDK and cannot use a client key as a server secret`() = fixture { root ->
        val credentials = credentials(root, "xiaomi.secrets")
        assertFailsWith<IllegalArgumentException> { configured(credentials, File(root, "missing.aar")) }
        val sdk = sdk(root, "MiPush_SDK_Client_fixture.aar", "SDK input")
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
        assertEquals(CLIENT_KEY, requireNotNull(config.oemPush[OemPushVendors.XIAOMI]).readCredential("appKey"))
        val failure = assertFailsWith<IllegalArgumentException> { config.oemPushEnvironment() }
        assertEquals("OEM push xiaomi credentials file is missing a valid appSecret", failure.message)
        assertFalse(failure.message.orEmpty().contains(CLIENT_KEY))
    }

    private fun disabledExcept(vendor: String) =
        (OemPushVendors.ALL - vendor).associate { "${it.uppercase()}_PUSH_ENABLED" to "false" }

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

    private fun credentials(root: File, name: String, content: String = "appKey=$CLIENT_KEY\nappSecret=$SERVER_SECRET\n") =
        File(root, name).apply { writeText(content) }

    // Configuration only fingerprints this input; these tests do not build an Android SDK or APK.
    private fun sdk(root: File, name: String, content: String) = File(root, name).apply { writeText(content) }

    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("teamtalk-oem-deployment-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }

    private companion object {
        const val CLIENT_KEY = "fixture-client-app-key"
        const val SERVER_SECRET = "fixture-server-app-secret"
    }
}
