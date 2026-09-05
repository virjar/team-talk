@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
    alias(libs.plugins.sqldelight)
}

// SDK 与图形端/服务端共享人类可读构建身份；不参与二进制版本比较。
val sdkBuildSourceDirectory = layout.buildDirectory.dir("generated/teamtalk-build/commonMain")
val sdkReleaseVersion = rootProject.extra.get("releaseVersion") as String
val sdkBuildIdentity = rootProject.extra.get("buildIdentity") as String
val sdkReleaseBuildNumber = rootProject.extra.get("releaseBuildNumber") as Int
val generateTeamTalkBuild by tasks.registering {
    inputs.property("releaseVersion", sdkReleaseVersion)
    inputs.property("buildIdentity", sdkBuildIdentity)
    inputs.property("releaseBuildNumber", sdkReleaseBuildNumber)
    outputs.dir(sdkBuildSourceDirectory)
    doLast {
        sdkBuildSourceDirectory.get().file("com/virjar/tk/shared/TeamTalkBuild.kt").asFile.apply {
            parentFile.mkdirs()
            writeText("""
                package com.virjar.tk.shared

                /** 同一源码构建的展示身份；协议兼容只比较 ProtocolVersion。 */
                object TeamTalkBuild {
                    const val RELEASE_VERSION: String = "$sdkReleaseVersion"
                    const val BUILD_IDENTITY: String = "$sdkBuildIdentity"
                    const val RELEASE_BUILD_NUMBER: Int = $sdkReleaseBuildNumber
                }
            """.trimIndent() + "\n")
        }
    }
}

/**
 * IM SDK 模块（= shared）。
 *
 * 完整客户端 SDK 闭环：ImClient/RpcClient（连接层）
 * + Repository + LocalCache + EventProcessor（数据层）+ 无头入口。
 * wire、模型和 RPC IDL 由独立 :protocol:protocol 模块提供。
 * UI（:client:app）只消费本模块公开 API；无头客户端/AI bot 可直接依赖本模块运行。
 *
 * 分层（单向依赖）：protocol ← protocol-netty ← shared(SDK) ← app(UI) ← android/desktop(shell)。
 */
kotlin {
    jvm()
    androidTarget()
    applyHierarchyTemplate {
        sourceSetTrees(
            org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree.main,
            org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree.test,
        )
        common {
            group("jvmAndAndroid") {
                withJvm()
                withAndroidTarget()
            }
        }
    }

    sourceSets {
        val commonMain by getting { kotlin.srcDir(sdkBuildSourceDirectory) }
        commonMain.dependencies {
            api(project(":protocol:protocol"))
            implementation(project(":protocol:protocol-netty"))
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.netty.handler)
        }
        val commonTest by getting
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
            implementation(project(":client:shared-testkit"))
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.sqldelight.android.driver)
            }
        }
    }
}

sqldelight {
    databases {
        create("AppDatabase") {
            packageName.set("com.virjar.tk.shared.database")
        }
    }


}

android {
    namespace = "com.virjar.tk.shared"
    compileSdk = 36
    defaultConfig { minSdk = 26 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// ── tt-agent / CLI / MCP 分发包（headless）──
// application 插件与 android library 冲突，此处手写等价 installDist：
// jvmJar 带 Main-Class + runtimeClasspath 全量 lib + bin 启动脚本。
val jvmJar by tasks.existing(org.gradle.jvm.tasks.Jar::class) {
    manifest { attributes["Main-Class"] = "com.virjar.tk.shared.agent.AgentMainKt" }
}
tasks.register<org.gradle.api.tasks.Sync>("headlessDist") {
    group = "distribution"
    description = "ImBot 无头 CLI 分发（build/headless/）"
    into(layout.buildDirectory.dir("headless"))
    into("lib") {
        from(jvmJar)
        from(configurations.getByName("jvmRuntimeClasspath"))
    }
    doLast {
        val bin = layout.buildDirectory.dir("headless/bin").get().asFile
        bin.mkdirs()
        // 同一 jar 提供守护进程、CLI 与 MCP 三个明确入口。
        File(bin, "tt-mcp").apply {
            val script = buildString {
                appendLine("#!/usr/bin/env bash")
                appendLine("cd \"\$(dirname \"\$0\")/..\"")
                appendLine("exec java -Djava.net.preferIPv4Stack=true -cp \"lib/*\" com.virjar.tk.shared.agent.McpMainKt \"\$@\"")
            }
            writeText(script)
            setExecutable(true)
        }
        File(bin, "tt").apply {
            val script = buildString {
                appendLine("#!/usr/bin/env bash")
                appendLine("cd \"\$(dirname \"\$0\")/..\"")
                appendLine("exec java -cp \"lib/*\" com.virjar.tk.shared.agent.CliMainKt \"\$@\"")
            }
            writeText(script)
            setExecutable(true)
        }
        File(bin, "tt-agent").apply {
            val script = buildString {
                appendLine("#!/usr/bin/env bash")
                appendLine("cd \"\$(dirname \"\$0\")/..\"")
                appendLine("exec java -cp \"lib/*\" com.virjar.tk.shared.agent.AgentMainKt \"\$@\"")
            }
            writeText(script)
            setExecutable(true)
        }
    }
}

// bot 集成测试开关透传：默认跳过，仅 -Dtk.botTest.host=... 时启用（见 ImBotIntegrationTest）。
// Gradle 默认不把命令行 -D 转发给测试 JVM，需显式桥接（与 server 的 tk.e2e.* 同模式）。
tasks.named<Test>("jvmTest") {
    listOf("tk.botTest.host", "tk.botTest.port").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}

// 编译与 sources jar 都必须消费当前构建身份，避免遗留生成物混入另一个版本。
tasks.matching { it.name.startsWith("compile") || it.name.endsWith("SourcesJar") }.configureEach {
    dependsOn(generateTeamTalkBuild)
}
