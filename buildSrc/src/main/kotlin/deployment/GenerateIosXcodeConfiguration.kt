package deployment

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.URI
import java.awt.BasicStroke
import java.awt.Color
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Generates public Xcode inputs independently of Kotlin/Native or an installed Apple toolchain. */
abstract class GenerateIosXcodeConfiguration : DefaultTask() {
    @get:Input abstract val bundleId: Property<String>
    @get:Input abstract val displayName: Property<String>
    @get:Input abstract val serverUrl: Property<String>
    @get:Input abstract val releaseVersion: Property<String>
    @get:Input abstract val buildNumber: Property<Int>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val infoPlistTemplate: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val iconSource: RegularFileProperty
    @get:Internal abstract val outputDirectory: DirectoryProperty
    // Other plugins also generate sources below build/generated; own only these Xcode artifacts.
    @get:OutputFile val configurationFile get() = outputDirectory.file("TeamTalkDeployment.xcconfig")
    @get:OutputFile val infoPlistFile get() = outputDirectory.file("TeamTalk-Info.plist")
    @get:OutputDirectory val assetsDirectory get() = outputDirectory.dir("Assets.xcassets")

    @TaskAction fun generate() = writeIosXcodeConfiguration(
        outputDirectory.get().asFile, infoPlistTemplate.get().asFile, iconSource.get().asFile,
        bundleId.get(), displayName.get(), serverUrl.get(), releaseVersion.get(), buildNumber.get(),
    )
}

internal fun writeIosXcodeConfiguration(
    outputDirectory: File,
    infoPlistTemplate: File,
    iconSource: File,
    bundleId: String,
    displayName: String,
    serverUrl: String,
    releaseVersion: String,
    buildNumber: Int,
) {
    val serverUri = URI(serverUrl)
    // xcconfig has line-oriented syntax. Put arbitrary display text in a generated plist instead.
    outputDirectory.resolve("TeamTalkDeployment.xcconfig").apply {
        parentFile.mkdirs()
        writeText("""
            // Generated; edit DeploymentConfig.client instead.
            PRODUCT_BUNDLE_IDENTIFIER = ${bundleId}
            MARKETING_VERSION = ${releaseVersion}
            CURRENT_PROJECT_VERSION = ${buildNumber + 1}
        """.trimIndent() + "\n")
    }
    val escapedDisplayName = displayName.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    val transportSecurity = if (serverUri.scheme.equals("http", ignoreCase = true)) {
        val host = serverUri.host.removeSurrounding("[", "]").lowercase()
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        """
            <key>NSAppTransportSecurity</key><dict>
                <key>NSExceptionDomains</key><dict>
                    <key>$host</key><dict>
                        <key>NSExceptionAllowsInsecureHTTPLoads</key><true/>
                        <key>NSIncludesSubdomains</key><false/>
                    </dict>
                </dict>
            </dict>
        """.trimIndent()
    } else ""
    outputDirectory.resolve("TeamTalk-Info.plist").writeText(infoPlistTemplate.readText()
        .replace("__TEAMTALK_DISPLAY_NAME__", escapedDisplayName)
        .replace("<!-- __TEAMTALK_TRANSPORT_SECURITY__ -->", transportSecurity))
    // Render the existing brand's line/circle geometry. iOS supplies the corner mask itself.
    val document = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(iconSource)
    val stops = document.getElementsByTagName("stop")
    fun stop(index: Int) = Color.decode((stops.item(index) as Element).getAttribute("stop-color"))
    val image = BufferedImage(1024, 1024, BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    try {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.paint = GradientPaint(0f, 0f, stop(0), 1024f, 1024f, stop(1))
        graphics.fillRect(0, 0, 1024, 1024)
        val lines = document.getElementsByTagName("line")
        for (index in 0 until lines.length) {
            val line = lines.item(index) as Element
            val parent = line.parentNode as Element
            graphics.color = Color.decode(parent.getAttribute("stroke"))
            graphics.stroke = BasicStroke(parent.getAttribute("stroke-width").toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            graphics.draw(Line2D.Double(line.getAttribute("x1").toDouble(), line.getAttribute("y1").toDouble(),
                line.getAttribute("x2").toDouble(), line.getAttribute("y2").toDouble()))
        }
        val circles = document.getElementsByTagName("circle")
        for (index in 0 until circles.length) {
            val circle = circles.item(index) as Element
            val fill = circle.getAttribute("fill").ifEmpty { (circle.parentNode as Element).getAttribute("fill") }
            graphics.color = Color.decode(fill)
            val radius = circle.getAttribute("r").toDouble()
            graphics.fill(Ellipse2D.Double(circle.getAttribute("cx").toDouble() - radius,
                circle.getAttribute("cy").toDouble() - radius, radius * 2, radius * 2))
        }
    } finally {
        graphics.dispose()
    }
    val catalog = outputDirectory.resolve("Assets.xcassets").apply { mkdirs() }
    catalog.resolve("Contents.json").writeText("""{"info":{"author":"xcode","version":1}}""")
    val icon = catalog.resolve("AppIcon.appiconset").apply { mkdirs() }
    check(ImageIO.write(image, "png", icon.resolve("AppIcon.png")))
    icon.resolve("Contents.json").writeText("""
        {"images":[{"filename":"AppIcon.png","idiom":"universal","platform":"ios","size":"1024x1024"}],
         "info":{"author":"xcode","version":1}}
    """.trimIndent())
}
