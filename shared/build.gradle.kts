plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
    alias(libs.plugins.sqldelight)
}

/**
 * IM SDK 模块（= shared）。
 *
 * 完整客户端 SDK 闭环：ImClient/RpcClient（连接层）
 * + Repository + LocalCache + EventProcessor（数据层）+ 无头入口。
 * wire、模型和 RPC IDL 由独立 :protocol 模块提供。
 * UI（:app）只消费本模块公开 API；无头客户端/AI bot 可直接依赖本模块运行。
 *
 * 分层（单向依赖）：protocol ← shared(SDK) ← app(UI) ← android/desktop(shell)。
 */
kotlin {
    jvm()
    androidTarget()

    sourceSets {
        val commonMain by getting
        commonMain.dependencies {
            api(project(":protocol"))
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.netty.handler)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
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
            packageName.set("com.virjar.tk.database")
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
    manifest { attributes["Main-Class"] = "com.virjar.tk.agent.AgentMainKt" }
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
                appendLine("exec java -Djava.net.preferIPv4Stack=true -cp \"lib/*\" com.virjar.tk.agent.McpMainKt \"\$@\"")
            }
            writeText(script)
            setExecutable(true)
        }
        File(bin, "tt").apply {
            val script = buildString {
                appendLine("#!/usr/bin/env bash")
                appendLine("cd \"\$(dirname \"\$0\")/..\"")
                appendLine("exec java -cp \"lib/*\" com.virjar.tk.agent.CliMainKt \"\$@\"")
            }
            writeText(script)
            setExecutable(true)
        }
        File(bin, "tt-agent").apply {
            val script = buildString {
                appendLine("#!/usr/bin/env bash")
                appendLine("cd \"\$(dirname \"\$0\")/..\"")
                appendLine("exec java -cp \"lib/*\" com.virjar.tk.agent.AgentMainKt \"\$@\"")
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
