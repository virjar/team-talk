import java.net.DatagramSocket
import java.net.InetAddress

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.sqldelight")
}

val composeVersion: String by rootProject.extra
val kotlinxSerializationVersion: String by rootProject.extra
val kotlinxCoroutinesVersion: String by rootProject.extra
val sqldelightVersion: String by rootProject.extra

// --- Profile config (keys match gradle/profiles/*.properties) ---
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

// Android BuildConfig: auto-rewrite localhost to LAN IP for real-device dev
val androidServerUrl by lazy {
    if (serverUrl.contains("localhost") || serverUrl.contains("127.0.0.1")) {
        "http://${detectLocalIp()}:8080"
    } else {
        serverUrl
    }
}
val androidTcpHost by lazy {
    if (tcpHost == "localhost" || tcpHost == "127.0.0.1") {
        detectLocalIp()
    } else {
        tcpHost
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
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")
            implementation("io.ktor:ktor-client-core:3.4.3")
            implementation("io.ktor:ktor-client-content-negotiation:3.4.3")
            implementation("io.ktor:ktor-serialization-kotlinx-json:3.4.3")
            implementation("io.netty:netty-handler:4.1.119.Final")
            implementation("io.netty:netty-transport:4.1.119.Final")
            implementation("io.netty:netty-buffer:4.1.119.Final")
            implementation("io.github.kdroidfilter:composemediaplayer:0.8.7")
            implementation("app.cash.sqldelight:coroutines-extensions:$sqldelightVersion")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        val desktopTest by getting {
            dependencies {
                implementation("app.cash.sqldelight:sqlite-driver:$sqldelightVersion")
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.preview)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:$kotlinxCoroutinesVersion")
                implementation("io.ktor:ktor-client-java:3.4.3")
                implementation("app.cash.sqldelight:sqlite-driver:$sqldelightVersion")
            }
        }
        val androidMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-okhttp:3.4.3")
                implementation("app.cash.sqldelight:android-driver:$sqldelightVersion")
                implementation("androidx.activity:activity-compose:1.10.1")
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

    defaultConfig {
        buildConfigField("String", "SERVER_BASE_URL", "\"$androidServerUrl\"")
        buildConfigField("String", "TCP_HOST", "\"$androidTcpHost\"")
        buildConfigField("int", "TCP_PORT", tcpPort)
        buildConfigField("String", "BUILD_PROFILE", "\"$buildProfile\"")
        buildConfigField("boolean", "SHOW_ADVANCED_SETTINGS", showAdvancedSettings)
    }
}

sqldelight {
    databases {
        create("TeamTalkDatabase") {
            packageName.set("com.virjar.tk.database")
        }
    }
}
