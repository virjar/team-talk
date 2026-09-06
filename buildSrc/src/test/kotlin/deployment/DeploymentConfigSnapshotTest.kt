package deployment

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.Date
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DeploymentConfigSnapshotTest {
    private fun config() = DeploymentConfig(
        serverUrl = "http://192.0.2.10:8080",
        tcpAddress = "192.0.2.10:5100",
        deployHost = "192.0.2.10",
    )

    @Test
    fun `Kotlin construction and copy both validate deployment inputs`() {
        assertFailsWith<IllegalArgumentException> {
            DeploymentConfig("ftp://192.0.2.10", "192.0.2.10:5100", "192.0.2.10")
        }
        val invalidChanges: List<(DeploymentConfig) -> DeploymentConfig> = listOf(
            { it.copy(serverUrl = "https:///missing-host") },
            { it.copy(tcpAddress = "192.0.2.10") },
            { it.copy(tcpAddress = "192.0.2.10:0") },
            { it.copy(deployHost = "host;command") },
            { it.copy(deployPort = 65536) },
            { it.copy(deployUser = "root;command") },
            { it.copy(deployPath = "/opt/../teamtalk") },
            { it.copy(sslPort = 0) },
            { it.copy(serverUrl = "https://192.0.2.10:8443", sslPort = 443) },
        )
        invalidChanges.forEach { change -> assertFailsWith<IllegalArgumentException> { change(config()) } }
    }

    @Test
    fun `canonical snapshot includes effective values and defaults in a fixed order`() {
        val explicitDefaults = DeploymentConfig(
            deployHost = "192.0.2.10",
            tcpAddress = "192.0.2.10:5100",
            serverUrl = "http://192.0.2.10:8080",
            deployPort = 22,
            deployUser = "root",
            deployPath = "/opt/teamtalk",
            sslPort = 443,
            allowCustomServer = false,
            client = ClientDistributionIdentity("com.virjar.tk", "TeamTalk", "TeamTalk"),
            tcpTlsCertificatePem = null,
        )
        assertEquals(config().toCanonicalJson(), explicitDefaults.toCanonicalJson())
        val objectValue = Json.parseToJsonElement(config().toCanonicalJson()).jsonObject
        assertEquals(
            listOf("serverUrl", "tcpAddress", "deployHost", "deployPort", "deployUser", "deployPath", "sslPort",
                "allowCustomServer", "client", "tcpTlsCertificatePem"),
            objectValue.keys.toList(),
        )
        assertEquals("http://192.0.2.10:8080", objectValue.getValue("serverUrl").jsonPrimitive.content)
        assertEquals("192.0.2.10:5100", objectValue.getValue("tcpAddress").jsonPrimitive.content)
        assertEquals("192.0.2.10", objectValue.getValue("deployHost").jsonPrimitive.content)
        assertEquals(22, objectValue.getValue("deployPort").jsonPrimitive.int)
        assertEquals("root", objectValue.getValue("deployUser").jsonPrimitive.content)
        assertEquals("/opt/teamtalk", objectValue.getValue("deployPath").jsonPrimitive.content)
        assertEquals(443, objectValue.getValue("sslPort").jsonPrimitive.int)
        assertFalse(objectValue.getValue("allowCustomServer").jsonPrimitive.boolean)
        val client = objectValue.getValue("client").jsonObject
        assertEquals(listOf("applicationId", "displayName", "desktopName"), client.keys.toList())
        assertEquals("com.virjar.tk", client.getValue("applicationId").jsonPrimitive.content)
        assertEquals("TeamTalk", client.getValue("displayName").jsonPrimitive.content)
        assertEquals("TeamTalk", client.getValue("desktopName").jsonPrimitive.content)
        assertEquals(JsonNull, objectValue.getValue("tcpTlsCertificatePem"))
    }

    @Test
    fun `snapshot preserves escaped private names and public TCP certificate bytes without requiring HTTPS`() {
        val configured = config().copy(
            client = ClientDistributionIdentity("com.example.internal", "内部版 \"研发\\测试\"", "TeamTalkInternal"),
            tcpTlsCertificatePem = publicCertificatePem,
        )
        assertFalse(configured.sslEnabled)
        val snapshot = Json.parseToJsonElement(configured.toCanonicalJson()).jsonObject
        val client = snapshot.getValue("client").jsonObject
        assertEquals("com.example.internal", client.getValue("applicationId").jsonPrimitive.content)
        assertEquals("内部版 \"研发\\测试\"", client.getValue("displayName").jsonPrimitive.content)
        assertEquals("TeamTalkInternal", client.getValue("desktopName").jsonPrimitive.content)
        assertEquals(publicCertificatePem, snapshot.getValue("tcpTlsCertificatePem").jsonPrimitive.content)
        assertNull(config().tcpTlsCertificatePem) // Certificate preparation must be possible before a pin exists.
    }

    @Test
    fun `TCP certificate input rejects private keys certificate chains and malformed public data`() {
        val privateKey = "-----BEGIN PRIVATE KEY-----\nZm9v\n-----END PRIVATE KEY-----"
        listOf(
            "", "not a certificate", privateKey,
            "-----BEGIN CERTIFICATE-----\nZm9v\n-----END CERTIFICATE-----",
            "$publicCertificatePem\n$publicCertificatePem",
            "$publicCertificatePem\n$privateKey",
        ).forEach { pem ->
            assertFailsWith<IllegalArgumentException> { config().copy(tcpTlsCertificatePem = pem) }
        }
    }

    companion object {
        // Fresh test-only key material stays in memory; only the generated public certificate enters the fixture.
        private val publicCertificatePem: String by lazy {
            val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
            val name = X500Name("CN=TeamTalk deployment fixture")
            val certificate = JcaX509v3CertificateBuilder(
                name, BigInteger.ONE, Date(0), Date(4_102_444_800_000L), name, keys.public,
            ).build(JcaContentSignerBuilder("SHA256withECDSA").build(keys.private))
            "-----BEGIN CERTIFICATE-----\n" +
                Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(certificate.encoded) +
                "\n-----END CERTIFICATE-----\n"
        }
    }
}
