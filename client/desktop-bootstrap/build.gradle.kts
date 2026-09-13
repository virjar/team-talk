// 桌面壳 bootstrap：零运行时依赖的纯 JDK 模块。
//
// 打包产物里它与 JBR 运行时、原生启动器、种子 payload 一起构成“壳”；
// 壳极少更新，应用 jar 组成的负载在用户目录按版本原子切换（见
// client/shared 的 com.virjar.tk.shared.update 包与本模块的磁盘契约注释）。
plugins {
    kotlin("jvm")
}

dependencies {
    testImplementation(kotlin("test"))
}

// 与主工程 JVM 目标保持一致（JBR 21）。
java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

// appId 参与负载根目录（~/.teamtalk-client/<appId>/versions），公版/私有版不同身份不共用负载。
val shellAppId = (rootProject.extra["deploymentConfig"] as deployment.DeploymentConfig).client.applicationId

val generatedDir = layout.buildDirectory.dir("generated/shellConfig")

val generateShellConfig by tasks.registering {
    val appId = shellAppId
    val output = generatedDir
    inputs.property("applicationId", appId)
    outputs.dir(output)
    doLast {
        val target = output.get().dir("com/virjar/tk/desktop/shell").apply { asFile.mkdirs() }
        target.file("ShellConfig.kt").asFile.writeText(
            """
            package com.virjar.tk.desktop.shell

            /** 壳 ABI：负载 payload.properties 的 minShellAbi 高于它时要求换新首装包。 */
            const val SHELL_ABI = 1

            /** 与最终部署配置一致的安装身份。 */
            const val SHELL_APP_ID = "$appId"

            /** 负载真实主类（由 URLClassLoader 加载后反射调用）。 */
            const val PAYLOAD_MAIN_CLASS = "com.virjar.tk.desktop.TeamTalkMain"

            """.trimIndent(),
        )
    }
}

kotlin {
    sourceSets {
        main {
            kotlin.srcDir(generatedDir)
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateShellConfig)
}

tasks.named<Jar>("jar") {
    manifest {
        attributes("Main-Class" to "com.virjar.tk.desktop.shell.BootstrapMain")
    }
    // 壳 jar 必须自带 kotlin-stdlib（本模块唯一运行时依赖）：脚本启动器只挂这一个 jar。
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
