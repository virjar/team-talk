package release

import deployment.ClientDistributionIdentity
import deployment.DeploymentConfig
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertFalse

/** Small sealed fixtures exercise reuse checks without building or signing any platform package. */
class ReleaseBundleDeploymentTest {
    private val config = DeploymentConfig("https://im.example.com", "im.example.com:5100", "im.example.com")
    private val identity = BundleIdentity(ReleaseVersion("0.0.0", 0, 0, 0, 0), "0123456789abcdef", config)
    private val notes = "# TeamTalk 0.0.0\n\nKeep existing user data.\n"

    @Test
    fun `configuration fingerprints describe effective values while source identity stays independent`() {
        val explicitDefaults = DeploymentConfig(
            serverUrl = "https://im.example.com",
            tcpAddress = "im.example.com:5100",
            deployHost = "im.example.com",
            deployPort = 22,
            deployUser = "root",
            deployPath = "/opt/teamtalk",
            sslPort = 443,
            allowCustomServer = false,
            client = ClientDistributionIdentity("com.virjar.tk", "TeamTalk", "TeamTalk"),
            tcpTlsCertificatePem = null,
        )
        assertEquals(identity.deploymentSha256, identity.copy(deployment = explicitDefaults).deploymentSha256)
        listOf(
            config.copy(serverUrl = "https://other.example.com"),
            config.copy(tcpAddress = "tcp.example.com:15100"),
            config.copy(deployHost = "deploy.example.com", deployPort = 2222),
            config.copy(client = ClientDistributionIdentity("com.example.internal", "内部版", "TeamTalkInternal")),
        ).forEach { deployment ->
            val changed = identity.copy(deployment = deployment)
            assertEquals(identity.buildIdentity, changed.buildIdentity)
            assertNotEquals(identity.deploymentSha256, changed.deploymentSha256)
        }
    }

    @Test
    fun `sealed snapshot is included in checksums and reuses only the same effective deployment`() = bundle { directory ->
        sealFixture(directory)
        val manifest = ReleaseBundle.verify(directory, identity, notes)
        assertEquals(identity.deploymentSha256, sha256(File(directory, ReleaseBundle.DEPLOYMENT_CONFIG)))
        assertEquals(identity.deploymentSha256, manifest.getValue("deploymentSha256").jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> {
            ReleaseBundle.verify(directory, identity.copy(deployment = config.copy(deployHost = "other.example.com")), notes)
        }
    }

    @Test
    fun `legacy bundles without the actual deployment snapshot cannot be reused`() = bundle { directory ->
        sealFixture(directory, snapshot = null)
        val failure = assertFailsWith<IllegalArgumentException> { ReleaseBundle.verify(directory, identity, notes) }
        assertEquals(
            "Release bundle deployment snapshot is missing or differs from the effective deployment configuration",
            failure.message,
        )
    }

    @Test
    fun `resealing different or noncanonical snapshot bytes cannot mislabel the effective configuration`() = bundle { directory ->
        listOf(
            config.copy(deployHost = "other.example.com").toCanonicalJson(),
            Json.parseToJsonElement(config.toCanonicalJson()).toString(),
        ).forEach { snapshot ->
            sealFixture(directory, snapshot)
            assertFailsWith<IllegalArgumentException> { ReleaseBundle.verify(directory, identity, notes) }
        }
    }

    @Test
    fun `private delivery is sealed to its contract without claiming a public release tag`() = bundle { directory ->
        val privateIdentity = identity.copy(distributionKind = "private-first", protocolContractSha256 = "a".repeat(64))
        val privateNotes = "# TeamTalk private first delivery\n\nPrivate site and frozen protocol contract.\n"
        sealFixture(directory, identity = privateIdentity, notes = privateNotes)
        val manifest = ReleaseBundle.verify(directory, privateIdentity, privateNotes)
        assertEquals("private-first", manifest.getValue("distributionKind").jsonPrimitive.content)
        assertFalse("tag" in manifest)
        assertFailsWith<IllegalArgumentException> { ReleaseBundle.verify(directory, identity, privateNotes) }
        assertFailsWith<IllegalArgumentException> {
            ReleaseBundle.verify(directory, privateIdentity.copy(protocolContractSha256 = "b".repeat(64)), privateNotes)
        }
        assertFailsWith<IllegalArgumentException> { ReleaseBundle.verify(directory, privateIdentity, notes) }
        // Recomputing checksums cannot turn a private delivery into a public tag claim.
        val manifestFile = File(directory, ReleaseBundle.MANIFEST)
        manifestFile.writeText(buildJsonObject {
            Json.parseToJsonElement(manifestFile.readText()).jsonObject.forEach { (key, value) -> put(key, value) }
            put("tag", identity.version.tag)
        }.toString())
        writeChecksums(directory)
        assertFailsWith<IllegalArgumentException> { ReleaseBundle.verify(directory, privateIdentity, privateNotes) }
    }

    @Test
    fun `public bundle cannot be reused as a private first delivery`() = bundle { directory ->
        sealFixture(directory)
        val manifest = ReleaseBundle.verify(directory, identity, notes)
        assertFalse("distributionKind" in manifest)
        assertFalse("protocolContractSha256" in manifest)
        assertFailsWith<IllegalArgumentException> {
            ReleaseBundle.verify(directory, identity.copy(distributionKind = "private-first", protocolContractSha256 = "a".repeat(64)), notes)
        }
    }

    private fun sealFixture(
        directory: File,
        snapshot: String? = config.toCanonicalJson(),
        identity: BundleIdentity = this.identity,
        notes: String = this.notes,
    ) {
        File(directory, "desktop").mkdirs()
        File(directory, "assets").mkdirs()
        File(directory, "desktop/download.html").writeText("Fixture download page")
        listOf("client.apk", "server.zip", "desktop-site.zip").forEach { File(directory, "assets/$it").writeText(it) }
        File(directory, "RELEASE_NOTES.md").writeText(notes)
        File(directory, "COMMITS.md").writeText("# Fixture commits\n")
        snapshot?.let { File(directory, ReleaseBundle.DEPLOYMENT_CONFIG).writeText(it, Charsets.UTF_8) }
        val manifest = buildJsonObject {
            put("format", 1)
            put("version", identity.version.name)
            put("buildNumber", identity.version.buildNumber)
            put("protocolMajor", identity.version.protocolMajor)
            put("protocolMinor", identity.version.protocolMinor)
            put("minimumProtocolMinor", identity.version.minimumProtocolMinor)
            put("sourceCommit", identity.sourceCommit)
            put("buildIdentity", identity.buildIdentity)
            if (identity.distributionKind == "release") put("tag", identity.version.tag)
            else {
                put("distributionKind", identity.distributionKind)
                put("protocolContractSha256", identity.protocolContractSha256)
            }
            put("deploymentSha256", identity.deploymentSha256)
            put("notesSha256", sha256(File(directory, "RELEASE_NOTES.md")))
            putJsonArray("files") {
                regularFiles(directory).filterNot { it.name in setOf(ReleaseBundle.MANIFEST, ReleaseBundle.CHECKSUMS) }.forEach { file ->
                    add(buildJsonObject {
                        put("path", file.relativeTo(directory).invariantSeparatorsPath)
                        put("size", file.length())
                        put("sha256", sha256(file))
                    })
                }
            }
        }
        File(directory, ReleaseBundle.MANIFEST).writeText(Json.encodeToString(JsonObject.serializer(), manifest) + "\n")
        writeChecksums(directory)
    }

    private fun writeChecksums(directory: File) {
        File(directory, ReleaseBundle.CHECKSUMS).writeText(
            regularFiles(directory).filterNot { it.name == ReleaseBundle.CHECKSUMS }.joinToString("") {
                "${sha256(it)}  ${it.relativeTo(directory).invariantSeparatorsPath}\n"
            },
        )
    }

    private fun regularFiles(directory: File) = directory.walkTopDown().filter(File::isFile)
        .sortedBy { it.relativeTo(directory).invariantSeparatorsPath }.toList()

    private fun bundle(action: (File) -> Unit) {
        val directory = Files.createTempDirectory("teamtalk-sealed-config-").toFile()
        try {
            action(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
