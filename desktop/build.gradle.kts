import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.internal.os.OperatingSystem
import profiles.BuildProfile
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

@Suppress("UNCHECKED_CAST")
val allProfiles = rootProject.extra.get("allProfiles") as Map<String, BuildProfile>
// 活跃 profile：由根 build.gradle.kts 从 -Pprofile 参数解析。
// 打包时把它的服务端地址固化进产物 JVM 启动参数（对齐 V1 机制）。
val activeProfile = rootProject.extra.get("activeProfile") as BuildProfile
val activeProfileName = rootProject.extra.get("activeProfileName") as String

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
    // 生成 BuildConfig 编译期常量
    alias(libs.plugins.buildconfig)
}

kotlin {
    jvm("desktop")

    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation(project(":shared"))
                implementation(project(":app"))
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.compose.media.player)
            }
        }
    }
}

// BuildConfig：构建信息内嵌（产物可溯源）+ 测试 HTTP 服务开关（恒 true，打包 jar 排除）
val gitCommitId = rootProject.extra.get("gitCommitId") as String
val buildTime = rootProject.extra.get("buildTime") as String
buildConfig {
    packageName("com.virjar.tk")
    // 构建溯源：每个产物可回答「我是谁、用什么 commit 构建的」
    buildConfigField("GIT_COMMIT_ID", gitCommitId)
    buildConfigField("BUILD_TIME", buildTime)
    // 测试 HTTP 服务：恒 true（dev/demo 运行含），打包 jar exclude 物理删除 TestHttpServer
    buildConfigField("TEST_HTTP_SERVER", true)
}

compose.desktop {
    application {
        mainClass = "com.virjar.tk.MainKt"

        // ProGuard：仅压缩，不混淆。删除依赖 jar 中未引用的类，
        // 保留堆栈可读性，规避反射/序列化风险。
        // 由 packageRelease* 任务触发（packageDmg 等不触发）。
        buildTypes {
            release {
                proguard {
                    isEnabled.set(true)
                    obfuscate.set(false)
                    optimize.set(false)
                    // 不合并 jar：保留分 jar 结构，便于体积归因和增量缓存
                    joinOutputJars.set(false)
                    // 不设 maxHeapSize：Compose 1.10.3 的 proguard 任务会把它拼成
                    // 非法的 -Xmx:{value}（多冒号），用 ProGuard 默认堆更稳。
                    configurationFiles.from("desktop-proguard.pro")
                }
            }
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "TeamTalk"
            packageVersion = rootProject.extra.get("packageVersion") as String

            // ── Profile 固化：把 activeProfile 的服务端地址编译进产物 JVM 启动参数 ──
            // 对齐 V1 机制。由 -Pprofile 参数选定 profile（默认 dev）。
            // 运行时 ServerConfig.defaultServerConfig() 读取这些 JVM 属性拿到正确服务端地址，
            // 否则打包产物会 fallback 到 localhost:5100（连不上任何真实服务器）。
            // 注意：是 nativeDistributions 层（打包专用），不影响 run<Profile> 开发运行任务。
            jvmArgs.add("-Dteamtalk.server.url=${activeProfile.serverUrl}")
            jvmArgs.add("-Dteamtalk.tcp.host=${activeProfile.tcpHost}")
            jvmArgs.add("-Dteamtalk.tcp.port=${activeProfile.tcpPort}")
            jvmArgs.add("-Dteamtalk.allow.custom.server=${activeProfile.allowCustomServer}")

            // 打包前从编译产物移除测试 HTTP 服务相关 class（生产构建不含测试代码）。
            // compileKotlinDesktop 产出后在 runtimeClasspath 里，打包时排除 test 包。
            modules("java.desktop")
            modules("java.sql")  // JdbcSqliteDriver 需要 java.sql.DriverManager
            // 精确限制 jlink 模块，排除 jpackage 自动检测的 java.xml/java.logging 等不必要模块
            // jdeps 实测只需: java.base, java.desktop, java.sql, jdk.unsupported
            // java.base 和 jdk.unsupported 由 jlink 自动包含，不需声明
            modules("jdk.unsupported")

            // 应用图标：使用 TeamTalk 自有图标素材（design/logo/desktop/）
            macOS {
                // macOS: 预生成的 .icns（iconutil 从 iconset 转换，含 16–512px）
                iconFile.set(rootProject.file("design/logo/desktop/TeamTalk.icns"))
            }
            windows {
                menuGroup = "TeamTalk"
                upgradeUuid = "d5e8f9a0-1b2c-3d4e-5f6a-7b8c9d0e1f2a"
                // Windows: jpackage 从 PNG 自动转 .ico
                iconFile.set(rootProject.file("design/logo/desktop/icon-256.png"))
            }
            linux {
                // Linux: 直接使用 PNG
                iconFile.set(rootProject.file("design/logo/desktop/icon-256.png"))
            }
        }
    }
}

// 生产打包安全性：打包用的 jar 排除测试 HTTP 服务相关 class。
// LoginWindow 通过 TestServiceBridge 反射调用 TestHttpServer（无编译期硬依赖），
// 打包删除 test 包后反射 ClassNotFound 被静默 catch，不会 NoClassDefFoundError。
// 开发运行（runXxx）直接用 classes 目录，不受影响。
tasks.matching { it.name == "desktopJar" || it.name == "distJar" || it.name == "shadowJar" }.configureEach {
    if (this is Jar) {
        exclude("com/virjar/tk/test/**")
    }
}

// 排除 kotlinx-coroutines-test（KMP commonTest 依赖泄漏到 desktopRuntimeClasspath）。
// coroutines-core 的 ServiceLoader 引用 test 的 ExceptionCollectorAsService，
// 打进生产产物会导致 ServiceConfigurationError。
configurations.matching { it.name.startsWith("desktop") && it.name.contains("Runtime") }.configureEach {
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-test")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-test-jvm")
}

// ── 产物瘦身：按当前 OS/架构裁剪 sqlite-jdbc 的 native 库 ──
// sqlite-jdbc 是「胖 jar」（14M），内含全平台/全架构 24 个 native 库，
// 运行时 OSInfo 只加载当前平台那一个（如 Mac/x86_64/libsqlitejdbc.dylib，~1.2M）。
// 在 ProGuard 输出后、打包前重写 sqlite jar，剔除非当前平台的 native，14M→~1.5M。
// 跨平台构建时（CI 各 OS runner）自动按当前 OS 选取保留哪个。
val sqliteNativeOsDir: String = when {
    OperatingSystem.current().isWindows -> "Windows"
    OperatingSystem.current().isMacOsX -> "Mac"
    else -> "Linux" // Linux 及未知 OS 兜底
}
val sqliteNativeArchDir: String = when (System.getProperty("os.arch")) {
    "aarch64", "arm64" -> "aarch64"
    else -> "x86_64" // x86_64/amd64 统一；ppc64/riscv 等罕见架构走默认
}
val sqliteKeepNativePath = "org/sqlite/native/$sqliteNativeOsDir/$sqliteNativeArchDir/"

tasks.register("stripSqliteNativeForRelease") {
    group = "compose desktop"
    description = "按当前 OS/架构裁剪 proguard 输出的 sqlite-jdbc jar 中的 native 库"
    // 在 proguard 输出后执行，createReleaseDistributable 会读处理后的 jar
    mustRunAfter("proguardReleaseJars")

    val proguardOut = layout.buildDirectory.dir("compose/tmp/main-release/proguard")
    inputs.dir(proguardOut)
    outputs.upToDateWhen { false } // 每次都检查，避免读到脏 jar

    doLast {
        val outDir = proguardOut.get().asFile
        if (!outDir.isDirectory) {
            logger.lifecycle("[stripSqliteNative] proguard 输出目录不存在，跳过: $outDir")
            return@doLast
        }
        val sqliteJars = outDir.listFiles { f -> f.name.startsWith("sqlite-jdbc") && f.name.endsWith(".jar") }
        if (sqliteJars.isNullOrEmpty()) {
            logger.lifecycle("[stripSqliteNative] 未找到 sqlite-jdbc jar，跳过")
            return@doLast
        }
        sqliteJars.forEach { jar ->
            var kept = 0
            var removed = 0
            val keptNames = mutableListOf<String>()
            val tmpJar = File(jar.parentFile, "${jar.name}.stripped")
            JarOutputStream(tmpJar.outputStream()).use { dst ->
                JarFile(jar).use { src ->
                    src.entries().asSequence().forEach { entry ->
                        val name = entry.name
                        val isNative = name.startsWith("org/sqlite/native/") &&
                            (name.endsWith(".so") || name.endsWith(".dll") || name.endsWith(".dylib"))
                        if (isNative && !name.startsWith(sqliteKeepNativePath)) {
                            removed++
                            // 跳过非当前平台的 native
                        } else {
                            if (isNative) {
                                kept++
                                keptNames.add(name.removePrefix("org/sqlite/native/"))
                            }
                            dst.putNextEntry(ZipEntry(name))
                            src.getInputStream(entry).use { input -> input.copyTo(dst) }
                            dst.closeEntry()
                        }
                    }
                }
            }
            jar.delete()
            tmpJar.renameTo(jar)
            logger.lifecycle(
                "[stripSqliteNative] ${jar.name}: 平台=$sqliteKeepNativePath 保留 native=$kept 移除=$removed"
            )
            logger.lifecycle("[stripSqliteNative]   保留: ${keptNames.joinToString()}")
        }
    }
}

// 让 release 打包链依赖裁剪任务（createReleaseDistributable 读 proguard 输出打包）
tasks.matching { it.name == "createReleaseDistributable" }.configureEach {
    dependsOn("stripSqliteNativeForRelease")
    finalizedBy("stripRuntimeFonts")
}

// ── JVM runtime 压缩：重新 jlink 带 --compress=2 ──
// Compose 的 createRuntimeImage 默认开了 --strip-debug 但没开 --compress。
// 实测 --compress=2 让 modules 从 44M 降到 20M（-24M），runtime 总 69M→56M。
// Compose 1.10.3 的 compressionLevel 是 internal 属性未暴露 public DSL，且 Gradle
// 装饰器代理不包装 internal 方法，反射注入不可行。改为后置任务：createRuntimeImage
// 完成后用 jlink 重新生成带 --compress=2 的 runtime，替换到 Compose 期望的位置。
val jlinkBinary = file("${System.getProperty("java.home")}/bin/jlink")

tasks.register("compressRuntimeImage") {
    group = "compose desktop"
    description = "重新 jlink 生成带 --compress=2 的 runtime，替换 createRuntimeImage 输出"
    mustRunAfter("createRuntimeImage")
    outputs.upToDateWhen { false }

    doLast {
        // 压缩 createRuntimeImage 的源头输出（compose/tmp/main/runtime），
        // createReleaseDistributable 会从这里复制到各产物目录。
        // 不能压缩复制后的副本（会被 createReleaseDistributable 覆盖）。
        val sourceRuntime = layout.buildDirectory.dir("compose/tmp/main/runtime").get().asFile
        if (sourceRuntime.isDirectory) {
            compressRuntime(sourceRuntime, jlinkBinary)
        } else {
            logger.lifecycle("[compressRuntime] 源 runtime 不存在，跳过: $sourceRuntime")
        }
    }
}

fun compressRuntime(rtDir: java.io.File, jlinkBin: java.io.File) {
    // 兼容两种 runtime 目录结构：
    //  - macOS .app: Contents/Home/lib/modules
    //  - jlink 源输出: lib/modules（无 Contents/Home 前缀）
    val homeDir = rtDir.resolve("Contents/Home").takeIf { it.isDirectory } ?: rtDir
    if (!homeDir.resolve("lib/modules").isFile) {
        logger.lifecycle("[compressRuntime] runtime 目录无 modules 文件，跳过: $homeDir")
        return
    }
    // 从 release 文件读取当前包含的模块列表
    val releaseFile = homeDir.resolve("release")
    if (!releaseFile.isFile) {
        logger.lifecycle("[compressRuntime] 无 release 文件，跳过: $homeDir")
        return
    }
    val modules = releaseFile.readText()
        .lineSequence()
        .firstOrNull { it.startsWith("MODULES=") }
        ?.substringAfter("MODULES=")
        ?.trim()
        ?.trim('"')
        ?.split(" ")
        ?: run {
            logger.lifecycle("[compressRuntime] 无法读取模块列表，跳过")
            return
        }
    logger.lifecycle("[compressRuntime] 重新 jlink 压缩: modules=$modules")

    val jmodsDir = file("${System.getProperty("java.home")}/jmods")
    val tmpOut = file("${rtDir.parentFile}/runtime.compressed")
    tmpOut.deleteRecursively()

    val cmd = listOf(
        jlinkBin.absolutePath,
        "--module-path", jmodsDir.absolutePath,
        "--add-modules", modules.joinToString(","),
        "--strip-debug",
        "--no-header-files",
        "--no-man-pages",
        "--strip-native-commands",
            "--compress=1",
        "--output", tmpOut.absolutePath,
    )
    val process = ProcessBuilder(cmd)
        .redirectErrorStream(true)
        .start()
    val exitCode = process.waitFor()
    if (exitCode != 0) {
        throw GradleException(
            "[compressRuntime] jlink 失败 (exit=$exitCode):\n${process.inputStream.bufferedReader().readText()}"
        )
    }

    // 只替换 modules 文件（jimage），保留 Compose/jpackage 的其他 runtime 结构
    val oldModules = homeDir.resolve("lib/modules")
    val newModules = tmpOut.resolve("lib/modules")
    if (newModules.exists()) {
        val beforeSize = oldModules.length()
        oldModules.delete()
        newModules.copyTo(oldModules)
        val afterSize = oldModules.length()
        logger.lifecycle(
            "[compressRuntime] modules 压缩: ${beforeSize / 1024 / 1024}M -> ${afterSize / 1024 / 1024}M"
        )
    }
    tmpOut.deleteRecursively()
}

// 让打包链在 createRuntimeImage 之后、createReleaseDistributable 之前压缩
tasks.matching { it.name == "createReleaseDistributable" || it.name == "createDistributable" }.configureEach {
    dependsOn("compressRuntimeImage")
}

// ── 产物瘦身：移除捆绑 runtime 里的编程字体 ──
// JBR（JetBrains Runtime）自带 43 个字体文件（9.2M，FiraCode/JetBrainsMono/Inter/DroidSans 等），
// 这些是 IDE 用的，IM 客户端用系统字体渲染即可。
// jpackage 把它们打进 runtime/lib/fonts，打包后清理。
// macOS/Linux/Windows 系统都有完整字体支持，不依赖这些捆绑字体。
tasks.register("stripRuntimeFonts") {
    group = "compose desktop"
    description = "删除打包产物中捆绑 runtime 的字体文件（IM 客户端用系统字体）"

    val appRoot = layout.buildDirectory.dir("compose/binaries/main-release/app/TeamTalk.app/Contents")
    inputs.dir(appRoot)
    outputs.upToDateWhen { false }

    doLast {
        val fontsDir = appRoot.get().asFile.resolve("runtime/Contents/Home/lib/fonts")
        if (!fontsDir.isDirectory) {
            logger.lifecycle("[stripRuntimeFonts] runtime 字体目录不存在，跳过: $fontsDir")
            return@doLast
        }
        val before = fontsDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        fontsDir.deleteRecursively()
        val mb = before / 1024 / 1024
        logger.lifecycle("[stripRuntimeFonts] 删除 ${fontsDir.absolutePath} ($mb MB)")
    }
}

// 为每个 profile 动态注册 run<Profile> 任务
allProfiles.forEach { (pn, profile) ->
    val cap = pn.replaceFirstChar { it.uppercase() }
    tasks.register<JavaExec>("run$cap") {
        group = "application"
        description = "Run desktop client with profile: $pn (dev/demo 含测试 HTTP 服务)"
        mainClass.set("com.virjar.tk.MainKt")
        classpath = configurations["desktopRuntimeClasspath"] + kotlin.targets["desktop"].compilations["main"].output.allOutputs
        jvmArgs = listOf(
            "-Dteamtalk.server.url=${profile.serverUrl}",
            "-Dteamtalk.tcp.host=${profile.tcpHost}",
            "-Dteamtalk.tcp.port=${profile.tcpPort}",
            "-Dteamtalk.allow.custom.server=${profile.allowCustomServer}",
            "-Dteamtalk.data.dir=${rootProject.file("data/desktop").absolutePath}",
            "-Dteamtalk.is.dev=true",
        )
    }
}
