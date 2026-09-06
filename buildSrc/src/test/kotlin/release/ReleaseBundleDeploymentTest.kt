package release

import deployment.ClientDistributionIdentity
import deployment.DeploymentConfig
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
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

    @Test
    fun `snapshot advances Desktop revision while preserving the root version and Android build`() = bundle { directory ->
        val snapshot = identity.copy(distributionKind = "snapshot",
            protocolContractSha256 = "a".repeat(64), desktopRevision = 8)
        val snapshotNotes = "# Snapshot 0.0.0 Desktop revision 8\n\nPrivate test update.\n"
        sealFixture(directory, identity = snapshot, notes = snapshotNotes)
        val manifest = ReleaseBundle.verify(directory, snapshot, snapshotNotes)
        assertEquals("snapshot", manifest.getValue("distributionKind").jsonPrimitive.content)
        assertEquals("0.0.0", manifest.getValue("version").jsonPrimitive.content)
        assertEquals("0", manifest.getValue("buildNumber").jsonPrimitive.content)
        assertEquals("8", manifest.getValue("desktopRevision").jsonPrimitive.content)
        assertFalse("tag" in manifest)
        listOf(
            identity,
            snapshot.copy(version = snapshot.version.copy(buildNumber = 1)),
            snapshot.copy(desktopRevision = 9),
            snapshot.copy(distributionKind = "private-first", desktopRevision = 1),
            snapshot.copy(protocolContractSha256 = "b".repeat(64)),
        ).forEach { wrongIdentity ->
            assertFailsWith<IllegalArgumentException> { ReleaseBundle.verify(directory, wrongIdentity, snapshotNotes) }
        }
        rewriteManifest(directory) { put("tag", snapshot.version.tag) }
        assertFailsWith<IllegalArgumentException> { ReleaseBundle.verify(directory, snapshot, snapshotNotes) }
    }

    @Test
    fun `snapshot cannot omit its frozen contract or adopt an existing public bundle`() = bundle { directory ->
        assertFailsWith<IllegalArgumentException> { identity.copy(distributionKind = "snapshot") }
        val snapshot = identity.copy(distributionKind = "snapshot", protocolContractSha256 = "a".repeat(64))
        sealFixture(directory)
        assertFailsWith<IllegalArgumentException> { ReleaseBundle.verify(directory, snapshot, notes) }
    }

    @Test
    fun `independent Desktop revision is bounded and only belongs to snapshots`() = bundle { directory ->
        assertFailsWith<IllegalArgumentException> { identity.copy(desktopRevision = 2) }
        val snapshot = identity.copy(distributionKind = "snapshot", protocolContractSha256 = "a".repeat(64))
        listOf(0, 65536).forEach { revision ->
            assertFailsWith<IllegalArgumentException> { snapshot.copy(desktopRevision = revision) }
        }
        sealFixture(directory)
        assertFalse("desktopRevision" in ReleaseBundle.verify(directory, identity, notes))
        rewriteManifest(directory) { put("desktopRevision", 1) }
        assertFailsWith<IllegalArgumentException> { ReleaseBundle.verify(directory, identity, notes) }
    }

    @Test
    fun `resealed client metadata cannot disguise a different installation identity`() = bundle { directory ->
        sealFixture(directory)
        rewriteManifest(directory) {
            putJsonObject("client") {
                put("applicationId", "com.example.other")
                put("androidApplicationId", "com.example.other.android")
                put("displayName", "Another app")
                put("desktopName", "AnotherApp")
            }
        }
        val failure = assertFailsWith<IllegalArgumentException> { ReleaseBundle.verify(directory, identity, notes) }
        assertEquals("Bundle client identity differs from the effective deployment configuration", failure.message)
    }

    @Test
    fun `Conveyor metadata for a newer snapshot cannot relabel previous build packages`() = bundle { directory ->
        val next = identity.copy(distributionKind = "snapshot", protocolContractSha256 = "a".repeat(64), desktopRevision = 8)
        val site = File(directory, "desktop")
        desktopFixture(site, identity)
        assertFailsWith<IllegalArgumentException> { ReleaseBundle.verifyDesktop(site, next.version, next.client, next.desktopRevision) }
        File(site, "metadata.properties").writeText("app.version=0.0.0\napp.revision=8\n")
        val failure = assertFailsWith<IllegalArgumentException> { ReleaseBundle.verifyDesktop(site, next.version, next.client, next.desktopRevision) }
        assertEquals("Desktop site lacks the current platform package: teamtalk-0.0.0-8-mac-amd64.zip", failure.message)
        desktopFixture(site, next)
        ReleaseBundle.verifyDesktop(site, next.version, next.client, next.desktopRevision)
    }

    @Test
    fun `sealed snapshot reuse still rejects an older Conveyor installation number`() = bundle { directory ->
        val snapshot = identity.copy(distributionKind = "snapshot", protocolContractSha256 = "a".repeat(64), desktopRevision = 8)
        sealFixture(directory, identity = snapshot, desktopIdentity = identity)
        val failure = assertFailsWith<IllegalArgumentException> { ReleaseBundle.verify(directory, snapshot, notes) }
        assertEquals("Stale Conveyor version metadata", failure.message)
    }

    @Test
    fun `Android snapshot preserves its root installation number but still requires the new APK source`() = bundle { directory ->
        val snapshot = identity.copy(sourceCommit = "abcdef0123456789", distributionKind = "snapshot",
            protocolContractSha256 = "a".repeat(64), desktopRevision = 8)
        androidFixture(directory, snapshot, versionCode = 8)
        val oldCode = assertFailsWith<IllegalArgumentException> { ReleaseBundle.requireAndroidApk(directory, snapshot) }
        assertEquals("Stale Android output metadata", oldCode.message)
        androidFixture(directory, snapshot, apkBuildIdentity = identity.buildIdentity)
        val oldApk = assertFailsWith<IllegalArgumentException> { ReleaseBundle.requireAndroidApk(directory, snapshot) }
        assertEquals("APK came from a different source revision", oldApk.message)
        androidFixture(directory, snapshot)
        assertEquals(1, snapshot.version.buildNumber + 1)
        assertEquals(File(directory, "client.apk"), ReleaseBundle.requireAndroidApk(directory, snapshot))
    }

    private fun sealFixture(
        directory: File,
        snapshot: String? = config.toCanonicalJson(),
        identity: BundleIdentity = this.identity,
        notes: String = this.notes,
        desktopIdentity: BundleIdentity = identity,
    ) {
        desktopFixture(File(directory, "desktop"), desktopIdentity)
        File(directory, "assets").mkdirs()
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
            if (identity.distributionKind == "snapshot") put("desktopRevision", identity.desktopRevision)
            put("deploymentSha256", identity.deploymentSha256)
            putJsonObject("client") {
                put("applicationId", identity.client.applicationId)
                put("androidApplicationId", identity.client.androidApplicationId)
                put("displayName", identity.client.displayName)
                put("desktopName", identity.client.desktopName)
            }
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

    private fun desktopFixture(site: File, identity: BundleIdentity) {
        site.mkdirs()
        val version = identity.version
        val name = identity.client.desktopFsName
        val prefix = "$name-${version.name}-${identity.desktopRevision}"
        File(site, "metadata.properties").writeText("app.version=${version.name}\napp.revision=${identity.desktopRevision}\n")
        listOf(
            "download.html", "$name.appinstaller", "$name.exe", "appcast-amd64.rss", "appcast-aarch64.rss",
            "$prefix-mac-amd64.zip", "$prefix-mac-aarch64.zip", "$prefix-windows-amd64.zip", "$prefix.x64.msix",
            "$prefix-linux-amd64.tar.gz", "${name}_${version.name}-${identity.desktopRevision}_amd64.deb",
        ).forEach { File(site, it).writeText("Fixture $it") }
    }

    private fun androidFixture(
        outputs: File,
        identity: BundleIdentity,
        versionCode: Int = identity.version.buildNumber + 1,
        apkBuildIdentity: String = identity.buildIdentity,
    ) {
        File(outputs, "output-metadata.json").writeText(buildJsonObject {
            put("applicationId", identity.client.androidApplicationId)
            putJsonArray("elements") {
                add(buildJsonObject {
                    put("versionName", identity.version.name)
                    put("versionCode", versionCode)
                    put("outputFile", "client.apk")
                })
            }
        }.toString())
        ZipOutputStream(File(outputs, "client.apk").outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("assets/teamtalk-build.properties"))
            zip.write("artifactType=android-apk\nversion=${identity.version.name}\nbuildIdentity=$apkBuildIdentity\n".toByteArray())
            zip.closeEntry()
        }
    }

    private fun rewriteManifest(directory: File, edit: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        val manifestFile = File(directory, ReleaseBundle.MANIFEST)
        val original = Json.parseToJsonElement(manifestFile.readText()).jsonObject
        manifestFile.writeText(buildJsonObject {
            original.forEach { (key, value) -> put(key, value) }
            edit()
        }.toString())
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
