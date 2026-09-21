import deployment.DeploymentConfig
import java.util.Base64
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

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.buildconfig)
}

val deploymentConfig = rootProject.extra["deploymentConfig"] as DeploymentConfig
val identity = deploymentConfig.client

buildConfig {
    packageName("com.virjar.tk.ios")
    className("ClientBuildConfig")
    useKotlinOutput { internalVisibility = false }
    buildConfigField("SERVER_BASE_URL", deploymentConfig.serverUrl)
    buildConfigField("TCP_HOST", deploymentConfig.tcpHost)
    buildConfigField("TCP_PORT", deploymentConfig.tcpPort)
    buildConfigField("TCP_TLS_CERTIFICATE_BASE64", deploymentConfig.tcpTlsCertificatePem?.let {
        Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8))
    }.orEmpty())
    buildConfigField("IOS_BUNDLE_ID", identity.iosBundleId)
    buildConfigField("DISPLAY_NAME", identity.displayName)
    buildConfigField("APP_VERSION", rootProject.extra["releaseVersion"] as String)
    buildConfigField("BUILD_NUMBER", rootProject.extra["releaseBuildNumber"] as Int)
    buildConfigField("BUILD_IDENTITY", rootProject.extra["buildIdentity"] as String)
    buildConfigField("GIT_COMMIT_ID", rootProject.extra["gitCommitId"] as String)
    buildConfigField("BUILD_TIME", rootProject.extra["buildTime"] as String)
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "TeamTalk"
            isStatic = true
            binaryOption("bundleId", "${identity.iosBundleId}.framework")
            linkerOpts("-lz")
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(project(":client:app"))
            implementation(project(":client:shared"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.jetbrains.compose.runtime)
            implementation(libs.jetbrains.compose.foundation)
            implementation(libs.jetbrains.compose.material3)
            implementation(libs.jetbrains.compose.material.icons.extended)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

/** Xcode reads only public installation identity. Signing stays in the developer's local config. */
tasks.register("generateXcodeConfiguration") {
    group = "build setup"
    description = "Generate the iOS installation identity from the selected deployment configuration"
    val output = layout.buildDirectory.file("generated/TeamTalkDeployment.xcconfig")
    val infoPlist = layout.buildDirectory.file("generated/TeamTalk-Info.plist")
    val template = layout.projectDirectory.file("AppleApp/Info.plist")
    val iconSource = rootProject.layout.projectDirectory.file("doc/design/logo/svg/logo-main.svg")
    val assets = layout.buildDirectory.dir("generated/Assets.xcassets")
    inputs.file(template)
    inputs.file(iconSource)
    inputs.property("bundleId", identity.iosBundleId)
    inputs.property("serverUrl", deploymentConfig.serverUrl)
    inputs.property("displayName", identity.displayName)
    inputs.property("version", rootProject.extra["releaseVersion"] as String)
    inputs.property("build", rootProject.extra["releaseBuildNumber"] as Int)
    outputs.file(output)
    outputs.file(infoPlist)
    outputs.dir(assets)
    doLast {
        // xcconfig has line-oriented syntax. Put arbitrary display text in a generated plist instead.
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText("""
                // Generated; edit DeploymentConfig.client instead.
                PRODUCT_BUNDLE_IDENTIFIER = ${identity.iosBundleId}
                MARKETING_VERSION = ${rootProject.extra["releaseVersion"]}
                CURRENT_PROJECT_VERSION = ${(rootProject.extra["releaseBuildNumber"] as Int) + 1}
            """.trimIndent() + "\n")
        }
        val displayName = identity.displayName.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
        val transportSecurity = if (deploymentConfig.serverUri.scheme.equals("http", ignoreCase = true)) {
            val host = deploymentConfig.serverUri.host.removeSurrounding("[", "]").lowercase()
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
        infoPlist.get().asFile.writeText(template.asFile.readText()
            .replace("__TEAMTALK_DISPLAY_NAME__", displayName)
            .replace("<!-- __TEAMTALK_TRANSPORT_SECURITY__ -->", transportSecurity))
        // Render the existing brand's line/circle geometry. iOS supplies the corner mask itself.
        val document = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder().parse(iconSource.asFile)
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
        val catalog = assets.get().asFile.apply { mkdirs() }
        catalog.resolve("Contents.json").writeText("""{"info":{"author":"xcode","version":1}}""")
        val icon = catalog.resolve("AppIcon.appiconset").apply { mkdirs() }
        check(ImageIO.write(image, "png", icon.resolve("AppIcon.png")))
        icon.resolve("Contents.json").writeText("""
            {"images":[{"filename":"AppIcon.png","idiom":"universal","platform":"ios","size":"1024x1024"}],
             "info":{"author":"xcode","version":1}}
        """.trimIndent())
    }
}
