package release

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MsixManifestChecksTest {
    @Test
    fun `final MSIX is parsed by namespace and validation never changes package bytes`() = withSite { site ->
        val manifest = msixManifestFixture(minVersion = "10.0.19045.0")
            .replace("desktop6", "d6")
        val msix = writeMsixFixture(site.resolve("client.x64.msix"), manifest)
        val before = sha256(msix)
        verifyConveyorMsixDataDirectoryPolicy(site)
        assertEquals(before, sha256(msix))
    }

    @Test
    fun `previous full trust manifest and incomplete fixes cannot pass final package verification`() = withSite { site ->
        val invalid = listOf(
            // The revision 34 package declared full trust but retained default MSIX virtualization.
            msixManifestFixture(fileSystemProperty = "", capabilities = listOf("runFullTrust"), minVersion = "10.0.17763.0"),
            msixManifestFixture(capabilities = listOf("runFullTrust")),
            msixManifestFixture(capabilities = listOf("unvirtualizedResources")),
            msixManifestFixture(minVersion = "10.0.17763.0"),
            msixManifestFixture().replace(">disabled<", ">enabled<"),
            msixManifestFixture().replace("/desktop/windows10/6", "/desktop/windows10/5"),
            msixManifestFixture().replace("/foundation/windows10/restrictedcapabilities", "/foundation/windows10"),
            msixManifestFixture(fileSystemProperty = """
                <desktop6:FileSystemWriteVirtualization>disabled</desktop6:FileSystemWriteVirtualization>
                <virtualization:FileSystemWriteVirtualization><virtualization:ExcludedDirectories>
                    <virtualization:ExcludedDirectory>${'$'}(KnownFolder:LocalAppData)\TeamTalk</virtualization:ExcludedDirectory>
                </virtualization:ExcludedDirectories></virtualization:FileSystemWriteVirtualization>
            """.trimIndent()),
        )
        invalid.forEachIndexed { index, manifest ->
            writeMsixFixture(site.resolve("client.x64.msix"), manifest)
            assertFailsWith<IllegalArgumentException>("invalid final MSIX fixture $index") {
                verifyConveyorMsixDataDirectoryPolicy(site)
            }
        }
    }

    @Test
    fun `Conveyor output without an MSIX cannot be published as a completed site`() = withSite { site ->
        site.resolve("download.html").writeText("Windows download")
        val failure = assertFailsWith<IllegalArgumentException> { verifyConveyorMsixDataDirectoryPolicy(site) }
        assertTrue(failure.message.orEmpty().contains("missing its Windows MSIX"))
    }

    private fun withSite(block: (File) -> Unit) {
        val site = Files.createTempDirectory("teamtalk-msix-manifest-").toFile()
        try {
            block(site)
        } finally {
            site.deleteRecursively()
        }
    }
}
