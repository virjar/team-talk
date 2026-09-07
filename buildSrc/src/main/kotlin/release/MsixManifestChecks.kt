package release

import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

private const val MSIX_FOUNDATION = "http://schemas.microsoft.com/appx/manifest/foundation/windows10"
private const val MSIX_DESKTOP6 = "http://schemas.microsoft.com/appx/manifest/desktop/windows10/6"
private const val MSIX_RESTRICTED = "http://schemas.microsoft.com/appx/manifest/foundation/windows10/restrictedcapabilities"
private const val MSIX_VIRTUALIZATION = "http://schemas.microsoft.com/appx/manifest/virtualization/windows10"

/** Check the generated package, before moving a new Conveyor site over its previous output. */
internal fun verifyConveyorMsixDataDirectoryPolicy(site: File) {
    val packages = site.walkTopDown().filter { it.isFile && it.extension == "msix" }.toList()
    require(packages.isNotEmpty()) { "Conveyor site is missing its Windows MSIX package" }
    packages.forEach(::verifyMsixDataDirectoryPolicy)
}

/** A full-trust MSIX must use the same real AppData directory as an unpackaged Desktop process. */
internal fun verifyMsixDataDirectoryPolicy(msix: File) {
    ZipFile(msix).use { zip ->
        val manifest = requireNotNull(zip.getEntry("AppxManifest.xml")) { "MSIX lacks AppxManifest.xml: ${msix.name}" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val root = zip.getInputStream(manifest).use { factory.newDocumentBuilder().parse(it).documentElement }
        require(root.namespaceURI == MSIX_FOUNDATION && root.localName == "Package") { "Invalid MSIX package manifest" }
        val properties = requireNotNull(root.children(MSIX_FOUNDATION, "Properties").singleOrNull()) {
            "MSIX lacks its package Properties: ${msix.name}"
        }
        val virtualization = properties.children(MSIX_DESKTOP6, "FileSystemWriteVirtualization")
        require(virtualization.singleOrNull()?.textContent?.trim() == "disabled") {
            "MSIX must disable AppData write virtualization: ${msix.name}"
        }
        // Newer Windows gives the per-directory syntax precedence over the global desktop6 declaration.
        require(properties.children(MSIX_VIRTUALIZATION, "FileSystemWriteVirtualization").isEmpty()) {
            "MSIX per-directory virtualization overrides the required global AppData policy: ${msix.name}"
        }
        val capabilities = root.children(MSIX_FOUNDATION, "Capabilities").singleOrNull()
            ?.children(MSIX_RESTRICTED, "Capability").orEmpty().map { it.getAttribute("Name") }.toSet()
        require(capabilities.containsAll(setOf("runFullTrust", "unvirtualizedResources"))) {
            "MSIX requires runFullTrust and unvirtualizedResources capabilities: ${msix.name}"
        }
        val minVersion = root.children(MSIX_FOUNDATION, "Dependencies").singleOrNull()
            ?.children(MSIX_FOUNDATION, "TargetDeviceFamily")?.singleOrNull { it.getAttribute("Name") == "Windows.Desktop" }
            ?.getAttribute("MinVersion").orEmpty()
        val numbers = minVersion.split('.').map { it.toIntOrNull() ?: -1 }
        val firstDifference = numbers.zip(listOf(10, 0, 18362, 0)).firstOrNull { (actual, required) -> actual != required }
        require(numbers.size == 4 && numbers.all { it >= 0 } &&
            (firstDifference == null || firstDifference.first > firstDifference.second)) {
            "MSIX AppData policy requires Windows 10 1903 (10.0.18362.0) or later: ${msix.name}"
        }
    }
}

private fun Element.children(namespace: String, name: String): List<Element> =
    (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()
        .filter { it.namespaceURI == namespace && it.localName == name }
