plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    kotlin("plugin.serialization")
}

import org.gradle.internal.os.OperatingSystem
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

val logbackVersion: String by rootProject.extra
val kotlinxSerializationVersion: String by rootProject.extra
val currentTargetFormats = when {
    OperatingSystem.current().isWindows -> listOf(TargetFormat.Msi)
    OperatingSystem.current().isLinux -> listOf(TargetFormat.Deb)
    OperatingSystem.current().isMacOsX -> listOf(TargetFormat.Dmg)
    else -> listOf(TargetFormat.Deb, TargetFormat.Msi, TargetFormat.Dmg)
}

configurations.all {
    exclude(group = "org.slf4j", module = "slf4j-simple")
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":app"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")
    implementation("io.github.kdroidfilter:nucleus.decorated-window-jni:1.6.3")
    implementation("io.github.kdroidfilter:nucleus.decorated-window-material3:1.6.3")
    implementation("io.github.kdroidfilter:nucleus.core-runtime:1.6.3")
    implementation("io.github.kdroidfilter:composenativetray:1.1.0")
    runtimeOnly("ch.qos.logback:logback-classic:$logbackVersion")
}

compose.desktop {
    application {
        mainClass = "com.virjar.tk.MainKt"

        // 开发模式 jvmArgs
        project.findProperty("SERVER_BASE_URL")?.let {
            jvmArgs.add("-Dteamtalk.server.url=$it")
        }
        project.findProperty("TCP_HOST")?.let {
            jvmArgs.add("-Dteamtalk.tcp.host=$it")
        }
        project.findProperty("TCP_PORT")?.let {
            jvmArgs.add("-Dteamtalk.tcp.port=$it")
        }
        project.findProperty("DATA_DIR")?.let {
            jvmArgs.add("-Dteamtalk.data.dir=$it")
        }

        nativeDistributions {
            targetFormats(*currentTargetFormats.toTypedArray())

            packageName = "TeamTalk"
            packageVersion = project.findProperty("packageVersion")?.toString() ?: "1.0.0"
            description = "TeamTalk - 即时通讯与办公协作"
            vendor = "TeamTalk"

            // 固化生产环境服务端地址（可通过 -D 参数运行时覆盖）
            val deployUrl = project.findProperty("deploy.url")?.toString()
            val deployTcpHost = project.findProperty("deploy.tcpHost")?.toString()
            if (deployUrl != null) jvmArgs.add("-Dteamtalk.server.url=$deployUrl")
            if (deployTcpHost != null) jvmArgs.add("-Dteamtalk.tcp.host=$deployTcpHost")

            jvmArgs.add("-Xms256m")
            jvmArgs.add("-Xmx512m")

            windows {
                menuGroup = "TeamTalk"
                upgradeUuid = "a3f5c8e1-7b2d-4e9a-b8f1-6c3d0e5a2b7f"
                dirChooser = true
                perUserInstall = true
                shortcut = true
            }

            linux {
                debMaintainer = "teamtalk@virjar.com"
                menuGroup = "Network;InstantMessaging;"
                rpmLicenseType = "Apache-2.0"
            }

            macOS {
                bundleID = "com.virjar.tk.teamtalk"
            }

            buildTypes {
                release {
                    proguard {
                        isEnabled = false  // ProGuard 7.7 与 Kotlin 2.x metadata 不兼容，暂时关闭
                    }
                }
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

