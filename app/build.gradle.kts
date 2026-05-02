import java.net.DatagramSocket
import java.net.InetAddress

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.sqldelight)
}

// --- Profile 全量数据（多渠道构建） ---
val allProfiles: Map<String, Map<String, String>> by rootProject.extra
val activeProfileName: String by rootProject.extra

// 活跃 profile 属性（向后兼容）
val serverUrl: String by rootProject.extra
val tcpHost: String by rootProject.extra
val tcpPort: String by rootProject.extra
val buildProfile: String by rootProject.extra
val showAdvancedSettings: String by rootProject.extra

/**
 * Detect local LAN IP via UDP connect trick.
 * Used when profile has localhost — Android devices need the actual LAN IP to reach the host.
 * Throws on failure so the build fails loud instead of silently using a wrong address.
 */
fun detectLocalIp(): String {
    val socket = DatagramSocket()
    return try {
        socket.connect(InetAddress.getByName("8.8.8.8"), 10002)
        socket.localAddress.hostAddress
            ?: throw GradleException("Failed to detect local LAN IP: hostAddress is null")
    } catch (e: Exception) {
        throw GradleException(
            "Failed to detect local LAN IP for Android build. " +
                "Profile uses localhost/127.0.0.1, which cannot be reached from Android devices. " +
                "Please set serverUrl and tcpHost to your actual LAN IP in the profile, " +
                "or ensure network connectivity. Original error: ${e.message}"
        )
    } finally {
        socket.close()
    }
}

kotlin {
    jvm("desktop")
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.bundles.netty)
            implementation(libs.compose.media.player)
            implementation(libs.sqldelight.coroutines)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.preview)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.ktor.client.java)
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.ktor.client.okhttp)
                implementation(libs.sqldelight.android.driver)
                implementation(libs.activity.compose)
            }
        }
    }
}

android {
    namespace = "com.virjar.tk.app"
    compileSdk = 36
    defaultConfig { minSdk = 26 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions += "profile"

    productFlavors {
        allProfiles.forEach { (profileName, props) ->
            create(profileName) {
                dimension = "profile"
                val pServerUrl = props["serverUrl"]!!
                val pTcpHost = props["tcpHost"]!!
                val pTcpPort = props["tcpPort"]!!
                val pShowAdvanced = props["showAdvancedSettings"] ?: "false"

                // Android 自动替换 localhost 为局域网 IP
                val resolvedUrl = if (pServerUrl.contains("localhost") || pServerUrl.contains("127.0.0.1"))
                    "http://${detectLocalIp()}:8080" else pServerUrl
                val resolvedHost = if (pTcpHost == "localhost" || pTcpHost == "127.0.0.1")
                    detectLocalIp() else pTcpHost

                buildConfigField("String", "SERVER_BASE_URL", "\"$resolvedUrl\"")
                buildConfigField("String", "TCP_HOST", "\"$resolvedHost\"")
                buildConfigField("int", "TCP_PORT", pTcpPort)
                buildConfigField("String", "BUILD_PROFILE", "\"$profileName\"")
                buildConfigField("boolean", "SHOW_ADVANCED_SETTINGS", pShowAdvanced)
            }
        }
    }

    variantFilter {
        val names = flavors.map { it.name }
        if (names.contains("production") && buildType.name == "debug") {
            ignore = true
        }
    }
}

sqldelight {
    databases {
        create("TeamTalkDatabase") {
            packageName.set("com.virjar.tk.database")
        }
    }
}

// 禁用 KMP 为库模块自动生成的虚假 run 任务
tasks.configureEach {
    if (name == "desktopRun") enabled = false
}
