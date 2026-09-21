package deployment

import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathFactory
import kotlin.test.*

class GenerateIosXcodeConfigurationTest {
    private val repository = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { it.resolve("client/ios/AppleApp/Info.plist").isFile }

    @Test fun `typed task produces matching public identity escaped plist and opaque brand icon`() {
        val root = Files.createTempDirectory("ios-xcode-config").toFile()
        try {
            val project = ProjectBuilder.builder().withProjectDir(root).build()
            val task = project.tasks.create("generateXcodeConfiguration", GenerateIosXcodeConfiguration::class.java)
            task.bundleId.set("com.example.internal.ios")
            task.displayName.set("研发 & '协作' <内测> \"团队\"")
            task.serverUrl.set("https://private.example.com")
            task.releaseVersion.set("0.0.4")
            task.buildNumber.set(8)
            task.infoPlistTemplate.set(repository.resolve("client/ios/AppleApp/Info.plist"))
            task.iconSource.set(repository.resolve("doc/design/logo/svg/logo-main.svg"))
            task.outputDirectory.set(root.resolve("generated"))
            task.generate()
            val config = task.configurationFile.get().asFile.readText()
            assertTrue(config.contains("PRODUCT_BUNDLE_IDENTIFIER = com.example.internal.ios"))
            assertTrue(config.contains("MARKETING_VERSION = 0.0.4"))
            assertTrue(config.contains("CURRENT_PROJECT_VERSION = 9"))
            val info = task.infoPlistFile.get().asFile
            assertEquals(task.displayName.get(), plistValue(info, "CFBundleDisplayName"))
            assertEquals("$(PRODUCT_BUNDLE_IDENTIFIER)", plistValue(info, "CFBundleIdentifier"))
            assertFalse(info.readText().contains("NSAppTransportSecurity"))
            val icon = ImageIO.read(task.assetsDirectory.get().file("AppIcon.appiconset/AppIcon.png").asFile)
            assertEquals(1024, icon.width)
            assertEquals(1024, icon.height)
            assertFalse(icon.colorModel.hasAlpha())
            assertFalse(task.outputs.files.files.contains(root.resolve("generated")),
                "Xcode generation must not claim other plugins' generated sources")
        } finally { root.deleteRecursively() }
    }

    @Test fun `explicit HTTP adds only its precise host including literal IPs`() {
        val root = Files.createTempDirectory("ios-ats-config").toFile()
        try {
            for ((url, host) in listOf(
                "http://private.example.com:8080/api" to "private.example.com",
                "http://192.0.2.1:8080" to "192.0.2.1",
                "http://[2001:db8::1]:8080" to "2001:db8::1",
            )) {
                writeIosXcodeConfiguration(root, repository.resolve("client/ios/AppleApp/Info.plist"),
                    repository.resolve("doc/design/logo/svg/logo-main.svg"),
                    "com.example.internal.ios", "内部版", url, "0.0.4", 8)
                val xml = root.resolve("TeamTalk-Info.plist").readText()
                assertTrue(xml.contains("<key>$host</key>"))
                assertTrue(xml.contains("<key>NSIncludesSubdomains</key><false/>"))
                assertFalse(xml.contains("NSAllowsArbitraryLoads"))
                assertEquals("内部版", plistValue(root.resolve("TeamTalk-Info.plist"), "CFBundleDisplayName"))
            }
        } finally { root.deleteRecursively() }
    }

    private fun plistValue(file: File, key: String): String {
        val document = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }.newDocumentBuilder().parse(file)
        return XPathFactory.newInstance().newXPath().evaluate(
            "/plist/dict/key[.='$key']/following-sibling::*[1]", document,
        )
    }
}
