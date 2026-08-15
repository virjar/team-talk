plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
    alias(libs.plugins.sqldelight)
}

/**
 * IM SDK 模块（= shared）。
 *
 * 完整 SDK 闭环：协议 + 模型 + ImClient/RpcClient（连接层）
 * + Repository + LocalCache + EventProcessor（数据层）+ 无头入口。
 * UI（:app）只消费本模块公开 API；无头客户端/AI bot 可直接依赖本模块运行。
 *
 * 分层（单向依赖）：shared(SDK) ← app(UI) ← android/desktop(shell)；server 依赖协议定义。
 */
kotlin {
    jvm()
    androidTarget()

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
        }
        commonMain.dependencies {
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
                implementation(libs.ktor.client.okhttp)
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

// RPC IDL 处理器：扫描 @RpcService interface 生成 Contract/Stub/Proxy。
// 只跑 metadata 编译（生成物为 common 源码），目录注册进 commonMain 供全部 target 编译。
dependencies {
    add("kspCommonMainMetadata", project(":rpc-processor"))
}

// 各 target 编译依赖 KSP 生成（srcDir 注册了生成目录，但 Gradle 不知道目录内容何时产生）
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn("kspCommonMainKotlinMetadata")
}

// ── ImBot CLI 分发包（headless）──
// application 插件与 android library 冲突，此处手写等价 installDist：
// jvmJar 带 Main-Class + runtimeClasspath 全量 lib + bin 启动脚本。
val jvmJar by tasks.existing(org.gradle.jvm.tasks.Jar::class) {
    manifest { attributes["Main-Class"] = "com.virjar.tk.bot.HeadlessMainKt" }
}
tasks.register<Copy>("headlessDist") {
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
        File(bin, "headless").apply {
            writeText(
                "#!/usr/bin/env bash\n" +
                "cd \"\$(dirname \"\$0\")/..\"\n" +
                "exec java -cp \"lib/*\" com.virjar.tk.bot.HeadlessMainKt \"\$@\"\n"
            )
            setExecutable(true)
        }
    }
}

tasks.configureEach {
    if (name == "jvmRun") enabled = false
}

// bot 集成测试开关透传：默认跳过，仅 -Dtk.botTest.host=... 时启用（见 ImBotIntegrationTest）。
// Gradle 默认不把命令行 -D 转发给测试 JVM，需显式桥接（与 server 的 tk.e2e.* 同模式）。
tasks.named<Test>("jvmTest") {
    listOf("tk.botTest.host", "tk.botTest.port").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}
