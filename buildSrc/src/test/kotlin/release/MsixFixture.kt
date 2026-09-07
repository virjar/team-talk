package release

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Unsigned ZIP fixtures exercise final-manifest checks without pretending to be installable packages. */
internal fun writeMsixFixture(file: File, manifest: String = msixManifestFixture()): File {
    ZipOutputStream(file.outputStream()).use { zip ->
        zip.putNextEntry(ZipEntry("AppxManifest.xml"))
        zip.write(manifest.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
    return file
}

internal fun msixManifestFixture(
    fileSystemProperty: String = "<desktop6:FileSystemWriteVirtualization>disabled</desktop6:FileSystemWriteVirtualization>",
    capabilities: List<String> = listOf("runFullTrust", "unvirtualizedResources"),
    minVersion: String = "10.0.18362.0",
): String = """
    <Package xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10"
        xmlns:desktop6="http://schemas.microsoft.com/appx/manifest/desktop/windows10/6"
        xmlns:rescap="http://schemas.microsoft.com/appx/manifest/foundation/windows10/restrictedcapabilities"
        xmlns:virtualization="http://schemas.microsoft.com/appx/manifest/virtualization/windows10"
        IgnorableNamespaces="desktop6 rescap virtualization">
        <Properties>$fileSystemProperty</Properties>
        <Capabilities>${capabilities.joinToString("") { "<rescap:Capability Name=\"$it\"/>" }}</Capabilities>
        <Dependencies><TargetDeviceFamily Name="Windows.Desktop" MinVersion="$minVersion"/></Dependencies>
    </Package>
""".trimIndent()
