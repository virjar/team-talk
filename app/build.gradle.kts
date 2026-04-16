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

// --- Server config injection for Android BuildConfig ---

fun readLocalProperty(key: String, default: String): String {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        val lines = localPropsFile.readLines()
        for (line in lines) {
            val parts = line.split("=", limit = 2)
            if (parts.size == 2 && parts[0].trim() == key) {
                return parts[1].trim()
            }
        }
    }
    return default
}

fun detectLocalIp(): String {
    return try {
        val inetAddressClass = Class.forName("java.net.InetAddress")
        val datagramSocketClass = Class.forName("java.net.DatagramSocket")
        val getAddress = inetAddressClass.getMethod("getByName", String::class.java)
        val address = getAddress.invoke(null, "8.8.8.8")
        val socket = datagramSocketClass.getConstructor().newInstance()
        val connect = datagramSocketClass.getMethod("connect", inetAddressClass, Int::class.javaPrimitiveType)
        connect.invoke(socket, address, 10002)
        val getLocalAddress = datagramSocketClass.getMethod("getLocalAddress")
        val localAddr = getLocalAddress.invoke(socket)
        val getHostAddress = inetAddressClass.getMethod("getHostAddress")
        val ip = getHostAddress.invoke(localAddr) as? String ?: "10.0.2.2"
        val close = datagramSocketClass.getMethod("close")
        close.invoke(socket)
        ip
    } catch (_: Exception) {
        "10.0.2.2"
    }
}

val serverBaseUrl: String by lazy {
    project.findProperty("SERVER_BASE_URL")?.toString()
        ?: readLocalProperty("server.baseUrl", "http://${detectLocalIp()}:8080")
}
val tcpHost: String by lazy {
    project.findProperty("TCP_HOST")?.toString()
        ?: readLocalProperty("server.tcpHost", detectLocalIp())
}
val tcpPort: Int by lazy {
    project.findProperty("TCP_PORT")?.toString()?.toIntOrNull()
        ?: readLocalProperty("server.tcpPort", "5100").toInt()
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
            implementation("io.ktor:ktor-client-core:3.1.3")
            implementation("io.ktor:ktor-client-content-negotiation:3.1.3")
            implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.3")
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
                implementation("io.ktor:ktor-client-java:3.1.3")
                implementation("app.cash.sqldelight:sqlite-driver:$sqldelightVersion")
            }
        }
        val androidMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-okhttp:3.1.3")
                implementation("app.cash.sqldelight:android-driver:$sqldelightVersion")
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
        buildConfigField("String", "SERVER_BASE_URL", "\"$serverBaseUrl\"")
        buildConfigField("String", "TCP_HOST", "\"$tcpHost\"")
        buildConfigField("int", "TCP_PORT", tcpPort.toString())
    }
}

sqldelight {
    databases {
        create("TeamTalkDatabase") {
            packageName.set("com.virjar.tk.database")
        }
    }
}
