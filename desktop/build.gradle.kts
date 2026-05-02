import org.gradle.internal.os.OperatingSystem
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    kotlin("plugin.serialization")
}



val logbackVersion: String by rootProject.extra
val kotlinxSerializationVersion: String by rootProject.extra
val jnaVersion: String by rootProject.extra
val packageVersion: String by rootProject.extra

// --- Profile 全量数据（多渠道构建） ---
val allProfiles: Map<String, Map<String, String>> by rootProject.extra
val activeProfileName: String by rootProject.extra

// 活跃 profile 属性（向后兼容，compose.desktop 块使用）
val serverUrl: String by rootProject.extra
val tcpHost: String by rootProject.extra
val tcpPort: String by rootProject.extra
val buildProfile: String by rootProject.extra
val showAdvancedSettings: String by rootProject.extra

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
    implementation("net.java.dev.jna:jna:$jnaVersion")
    runtimeOnly("ch.qos.logback:logback-classic:$logbackVersion")
}

compose.desktop {
    application {
        mainClass = "com.virjar.tk.MainKt"

        // 开发模式 jvmArgs — 从 profile 注入
        jvmArgs.add("-Dteamtalk.server.url=$serverUrl")
        jvmArgs.add("-Dteamtalk.tcp.host=$tcpHost")
        jvmArgs.add("-Dteamtalk.tcp.port=$tcpPort")
        jvmArgs.add("-Dteamtalk.build.profile=$buildProfile")
        jvmArgs.add("-Dteamtalk.show.advanced.settings=$showAdvancedSettings")
        project.findProperty("DATA_DIR")?.let {
            jvmArgs.add("-Dteamtalk.data.dir=$it")
        }

        nativeDistributions {
            targetFormats(*currentTargetFormats.toTypedArray())

            packageName = "TeamTalk"
            packageVersion = packageVersion
            description = if (OperatingSystem.current().isWindows) {
                "TeamTalk - Instant messaging and collaboration"
            } else {
                "TeamTalk - 即时通讯与办公协作"
            }
            vendor = "TeamTalk"

            // 固化生产环境服务端地址（从 profile 注入）
            jvmArgs.add("-Dteamtalk.server.url=$serverUrl")
            jvmArgs.add("-Dteamtalk.tcp.host=$tcpHost")
            jvmArgs.add("-Dteamtalk.tcp.port=$tcpPort")
            jvmArgs.add("-Dteamtalk.build.profile=$buildProfile")
            jvmArgs.add("-Dteamtalk.show.advanced.settings=$showAdvancedSettings")

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

            // 打包精简 JRE 缺少以下模块，需要显式声明：
            // - java.naming: logback 配置需要 javax.naming
            // - java.net.http: Ktor Java HTTP engine 需要 java.net.http.HttpClient
            modules("java.naming", "java.net.http", "java.sql")

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

// ── 按 Profile 的 Run/Package 任务 ──

fun capitalize(s: String) = s.replaceFirstChar { it.uppercase() }

allProfiles.forEach { (profileName, props) ->
    val cap = capitalize(profileName)

    if (profileName == activeProfileName) {
        // 活跃 profile：注册别名指向原 run 任务
        tasks.register("run$cap") {
            group = "run"
            description = "Run desktop app with profile: $profileName"
            dependsOn("run")
        }
    } else {
        // 非活跃 profile：注册独立 JavaExec 任务
        tasks.register<JavaExec>("run$cap") {
            group = "run"
            description = "Run desktop app with profile: $profileName"
            mainClass.set("com.virjar.tk.MainKt")
            classpath = sourceSets["main"].runtimeClasspath
            jvmArgs = listOf(
                "-Dteamtalk.server.url=${props["serverUrl"]}",
                "-Dteamtalk.tcp.host=${props["tcpHost"]}",
                "-Dteamtalk.tcp.port=${props["tcpPort"]}",
                "-Dteamtalk.build.profile=$profileName",
                "-Dteamtalk.show.advanced.settings=${props["showAdvancedSettings"]}",
                "-Xms256m", "-Xmx512m"
            )
            project.findProperty("DATA_DIR")?.let {
                jvmArgs = jvmArgs!!.plus("-Dteamtalk.data.dir=$it")
            }
            dependsOn(tasks.named("prepareAppResources"))
        }
    }

    // 按 profile 的打包验证任务
    tasks.register("package${cap}DistributionForCurrentOS") {
        group = "distribution"
        description = "Package desktop app with profile: $profileName"
        dependsOn(":desktop:packageDistributionForCurrentOS")
        doFirst {
            if (activeProfileName != profileName) {
                throw GradleException(
                    "package${cap}DistributionForCurrentOS requires -PbuildProfile=$profileName.\n" +
                            "Use: ./gradlew :desktop:package${cap}DistributionForCurrentOS -PbuildProfile=$profileName"
                )
            }
        }
    }
}
