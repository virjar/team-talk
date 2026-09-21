import deployment.DeploymentConfig
import java.util.Base64

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

tasks.register<deployment.GenerateIosXcodeConfiguration>("generateXcodeConfiguration") {
    group = "build setup"
    description = "Generate the iOS installation identity from the selected deployment configuration"
    bundleId.set(identity.iosBundleId)
    displayName.set(identity.displayName)
    serverUrl.set(deploymentConfig.serverUrl)
    releaseVersion.set(rootProject.extra["releaseVersion"] as String)
    buildNumber.set(rootProject.extra["releaseBuildNumber"] as Int)
    infoPlistTemplate.set(layout.projectDirectory.file("AppleApp/Info.plist"))
    iconSource.set(rootProject.layout.projectDirectory.file("doc/design/logo/svg/logo-main.svg"))
    outputDirectory.set(layout.buildDirectory.dir("generated"))
}
