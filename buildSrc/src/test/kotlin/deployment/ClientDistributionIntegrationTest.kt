package deployment

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import release.ReleaseBundle
import release.ReleaseVersion
import release.writeSkikoPackageFixture

class ClientDistributionIntegrationTest {
    private fun config(
        client: ClientDistributionIdentity = ClientDistributionIdentity(),
        server: String = "https://private.example.com",
    ) = DeploymentConfig(
        serverUrl = server,
        tcpAddress = "private.example.com:5100",
        deployHost = "private.example.com",
        client = client,
    )

    @Test
    fun `existing deployment configs retain every public installation identity`() {
        val identity = config().client
        assertEquals("com.virjar.tk.android", identity.androidApplicationId)
        assertEquals("com.virjar.tk", identity.applicationId)
        assertEquals("TeamTalk", identity.desktopName)
        assertEquals("teamtalk", identity.desktopFsName)
        assertEquals("TeamTalk", identity.desktopDataDirectoryName)
        assertEquals("teamtalk", identity.linuxDataDirectoryName)
        assertEquals("d5e8f9a0-1b2c-3d4e-5f6a-7b8c9d0e1f2a", identity.windowsUpgradeUuid)
    }

    @Test
    fun `renaming a private client or moving its server retains its data and installer family`() {
        val first = config(ClientDistributionIdentity("com.example.internal", "内部版", "TeamTalkInternal")).client
        val renamed = config(
            client = first.copy(displayName = "同事协作"),
            server = "https://new.example.com",
        ).client
        assertEquals("com.example.internal.android", first.androidApplicationId)
        assertEquals("com.example.internal", first.desktopDataDirectoryName)
        assertEquals(first.desktopDataDirectoryName, renamed.desktopDataDirectoryName)
        assertEquals(first.windowsUpgradeUuid, renamed.windowsUpgradeUuid)
        assertNotEquals(config().client.windowsUpgradeUuid, first.windowsUpgradeUuid)
        assertNotEquals(config().client.desktopFsName, first.desktopFsName)
    }

    @Test
    fun `partial private identities and unsafe installation names fail at the config entry`() {
        val invalidIdentities: List<() -> ClientDistributionIdentity> = listOf(
            { ClientDistributionIdentity(applicationId = "com.example.internal") },
            { ClientDistributionIdentity(desktopName = "TeamTalkInternal") },
            { ClientDistributionIdentity("com.example.internal", "内部版", "../Internal") },
            { ClientDistributionIdentity("com.example.internal", "内部版", "CON") },
            { ClientDistributionIdentity("com.example.internal", "Internal\nApp", "TeamTalkInternal") },
        )
        invalidIdentities.forEach { invalid -> assertFailsWith<IllegalArgumentException> { config(invalid()) } }
    }

    @Test
    fun `release and snapshot consume private Conveyor outputs with their own installation revision`() {
        val site = Files.createTempDirectory("teamtalk-private-release-").toFile()
        try {
            val version = ReleaseVersion("0.0.0", 0, 0, 0, 0)
            val client = ClientDistributionIdentity("com.example.internal", "内部版", "TeamTalkInternal")
            listOf(1, 8).forEach { revision ->
                site.resolve("metadata.properties").writeText("app.version=0.0.0\napp.revision=$revision\n")
                listOf(
                    "download.html", "teamtalkinternal.appinstaller", "teamtalkinternal.exe",
                    "appcast-amd64.rss", "appcast-aarch64.rss", "teamtalkinternal-0.0.0-$revision-mac-amd64.zip",
                    "teamtalkinternal-0.0.0-$revision-mac-aarch64.zip", "teamtalkinternal-0.0.0-$revision-windows-amd64.zip",
                    "teamtalkinternal-0.0.0-$revision.x64.msix", "teamtalkinternal-0.0.0-$revision-linux-amd64.tar.gz",
                    "teamtalkinternal_0.0.0-${revision}_amd64.deb",
                ).forEach {
                    val file = site.resolve(it)
                    if (file.extension in setOf("zip", "msix", "deb") || it.endsWith(".tar.gz")) {
                        writeSkikoPackageFixture(file)
                    } else file.writeText("fixture")
                }
                ReleaseBundle.verifyDesktop(site, version, client, revision)
                if (revision == 1) ReleaseBundle.verifyDesktop(site, version, client)
                else assertFailsWith<IllegalArgumentException> { ReleaseBundle.verifyDesktop(site, version, client) }
                assertFailsWith<IllegalArgumentException> { ReleaseBundle.verifyDesktop(site, version, ClientDistributionIdentity(), revision) }
            }
        } finally {
            site.deleteRecursively()
        }
    }
}
