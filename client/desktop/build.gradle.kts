import java.util.Base64
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import org.gradle.internal.os.OperatingSystem
import deployment.DeploymentConfig
import hydraulic.conveyor.gradle.WriteConveyorConfigTask
import release.PrepareConveyorTask
import release.PrepareDesktopIcons
import release.defaultConveyorConfigDirectory
import release.buildConveyorSite
import release.requireConveyorSigningConfiguration
import release.desktopRuntimeFontsDirectory
import release.verifyDesktopMediaNativeBridges
import release.verifyDesktopWindowsIdentityBindings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Properties
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

// Conveyor 与 Compose native distribution 共用根项目唯一发布版本。
val releaseVersion = rootProject.extra.get("releaseVersion") as String
version = releaseVersion

val deploymentConfig = rootProject.extra.get("deploymentConfig") as DeploymentConfig
val clientIdentity = deploymentConfig.client
val tcpTlsCertificateBase64 = deploymentConfig.tcpTlsCertificatePem
    ?.let { Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) }
    .orEmpty()

// ComposeMediaPlayer 0.9.0's macOS backend retains local video descriptors after dispose.
// TeamTalk ships a narrow, source-auditable local-file replacement at the exact resource
// paths its existing NativeLibraryLoader resolves. The checked-in binaries keep Conveyor
// cross-builds independent of Xcode; rebuildMacVideoPlayerOverride is an explicit macOS task.
val macVideoPlayerOverrideSourceDir =
    layout.projectDirectory.dir("src/desktopMain/native/macos/teamtalk-player")
val macVideoPlayerOverrideResourceDir =
    layout.projectDirectory.dir("src/desktopMain/resources/composemediaplayer/native")
val macVideoPlayerOverrideLibraries = mapOf(
    "darwin-aarch64" to macVideoPlayerOverrideResourceDir.file("darwin-aarch64/libNativeVideoPlayer.dylib"),
    "darwin-x86-64" to macVideoPlayerOverrideResourceDir.file("darwin-x86-64/libNativeVideoPlayer.dylib"),
)
val macVideoPlayerOverrideManifest =
    macVideoPlayerOverrideResourceDir.file("teamtalk-local-player.properties")
val macVideoPlayerOverrideLicense =
    layout.projectDirectory.file("src/desktopMain/resources/META-INF/licenses/composemediaplayer-local-macos-MIT.txt")
// Matches desktop/conveyor.conf LSMinimumSystemVersion and the upstream 0.9 dylibs.
val macVideoPlayerOverrideMinimumMacOs = "14.0"
val macVideoPlayerOverrideUpstreamVersion = "0.9.0"

fun java.io.File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun JarFile.entrySha256(entryPath: String): String {
    val entry = checkNotNull(getJarEntry(entryPath)) { "Jar is missing $entryPath" }
    val digest = MessageDigest.getInstance("SHA-256")
    getInputStream(entry).buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun java.io.File.machOMinimumMacOs(): String? {
    val bytes = readBytes()
    if (bytes.size < 32) return null
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    if (buffer.int != 0xfeedfacf.toInt()) return null
    buffer.position(16)
    val commandCount = buffer.int
    var offset = 32
    repeat(commandCount) {
        if (offset > bytes.size - 8) return null
        val command = buffer.getInt(offset)
        val commandSize = buffer.getInt(offset + 4)
        if (commandSize < 8 || offset > bytes.size - commandSize) return null
        if (command == 0x32 && commandSize >= 24) { // LC_BUILD_VERSION
            val encoded = buffer.getInt(offset + 12)
            val major = encoded ushr 16
            val minor = (encoded ushr 8) and 0xff
            val patch = encoded and 0xff
            return if (patch == 0) "$major.$minor" else "$major.$minor.$patch"
        }
        offset += commandSize
    }
    return null
}

fun java.io.File.hasCompleteMachOCodeSignature(): Boolean {
    val bytes = readBytes()
    if (bytes.size < 32) return false
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    if (buffer.int != 0xfeedfacf.toInt()) return false
    buffer.position(16)
    val commandCount = buffer.int
    var offset = 32
    repeat(commandCount) {
        if (offset > bytes.size - 8) return false
        val command = buffer.getInt(offset)
        val commandSize = buffer.getInt(offset + 4)
        if (commandSize < 8 || offset > bytes.size - commandSize) return false
        if (command == 0x1d && commandSize >= 16) { // LC_CODE_SIGNATURE
            val dataOffset = Integer.toUnsignedLong(buffer.getInt(offset + 8))
            val dataSize = Integer.toUnsignedLong(buffer.getInt(offset + 12))
            return dataSize > 0 && dataOffset + dataSize == bytes.size.toLong()
        }
        offset += commandSize
    }
    return false
}

val rebuildMacVideoPlayerOverride = tasks.register<Exec>("rebuildMacVideoPlayerOverride") {
    group = "build"
    description = "Rebuild the TeamTalk local-file macOS video JNI backend for arm64 and x86_64"
    inputs.files(
        macVideoPlayerOverrideSourceDir.file("NativeVideoPlayer.swift"),
        macVideoPlayerOverrideSourceDir.file("jni_bridge.c"),
        macVideoPlayerOverrideSourceDir.file("build.sh"),
    )
    outputs.files(macVideoPlayerOverrideLibraries.values)
    outputs.file(macVideoPlayerOverrideManifest)
    doFirst {
        check(OperatingSystem.current().isMacOsX) {
            "rebuildMacVideoPlayerOverride requires macOS with Xcode and JDK 21"
        }
    }
    commandLine(macVideoPlayerOverrideSourceDir.file("build.sh").asFile.absolutePath)
}

val verifyMacVideoPlayerOverride = tasks.register("verifyMacVideoPlayerOverride") {
    group = "verification"
    description = "Verify source hashes, architecture and JNI resources for the macOS video override"
    inputs.files(
        macVideoPlayerOverrideSourceDir.file("NativeVideoPlayer.swift"),
        macVideoPlayerOverrideSourceDir.file("jni_bridge.c"),
        macVideoPlayerOverrideSourceDir.file("build.sh"),
        macVideoPlayerOverrideManifest,
        macVideoPlayerOverrideLicense,
        *macVideoPlayerOverrideLibraries.values.toTypedArray(),
    )
    doLast {
        val manifestFile = macVideoPlayerOverrideManifest.asFile
        check(manifestFile.isFile) {
            "Missing macOS video override manifest; run :client:desktop:rebuildMacVideoPlayerOverride on macOS"
        }
        val properties = Properties().apply {
            manifestFile.inputStream().use { load(it) }
        }
        check(properties.getProperty("format") == "2") {
            "Unsupported macOS video override manifest format"
        }
        check(properties.getProperty("macos.minimum") == macVideoPlayerOverrideMinimumMacOs) {
            "macOS video override minimum version drifted from the Desktop release baseline"
        }

        val expectedSourceHashes = mapOf(
            "swift.sha256" to macVideoPlayerOverrideSourceDir.file("NativeVideoPlayer.swift").asFile,
            "jni.sha256" to macVideoPlayerOverrideSourceDir.file("jni_bridge.c").asFile,
            "build.sha256" to macVideoPlayerOverrideSourceDir.file("build.sh").asFile,
        )
        expectedSourceHashes.forEach { (key, source) ->
            check(source.sha256() == properties.getProperty(key)) {
                "Stale macOS video override: $key does not match; run :client:desktop:rebuildMacVideoPlayerOverride"
            }
        }
        val expectedSourceIdentity = MessageDigest.getInstance("SHA-256")
            .digest(
                buildString {
                    expectedSourceHashes.values.forEach { source -> appendLine(source.sha256()) }
                }.toByteArray(),
            )
            .joinToString("") { "%02x".format(it) }
        check(properties.getProperty("source.sha256") == expectedSourceIdentity) {
            "macOS native source identity does not match its source set"
        }
        check(libs.compose.media.player.get().versionConstraint.requiredVersion == macVideoPlayerOverrideUpstreamVersion) {
            "ComposeMediaPlayer changed; audit the macOS JNI override before upgrading"
        }
        check(macVideoPlayerOverrideLicense.asFile.isFile) {
            "Missing bundled ComposeMediaPlayer MIT notice"
        }

        val expectedCpuHeader = mapOf(
            "darwin-aarch64" to byteArrayOf(0xcf.toByte(), 0xfa.toByte(), 0xed.toByte(), 0xfe.toByte(), 0x0c, 0, 0, 1),
            "darwin-x86-64" to byteArrayOf(0xcf.toByte(), 0xfa.toByte(), 0xed.toByte(), 0xfe.toByte(), 0x07, 0, 0, 1),
        )
        macVideoPlayerOverrideLibraries.forEach { (platform, resource) ->
            val library = resource.asFile
            check(library.isFile && library.length() > 4096) {
                "Missing $platform macOS video override; run :client:desktop:rebuildMacVideoPlayerOverride on macOS"
            }
            check(library.inputStream().use { it.readNBytes(8) }.contentEquals(expectedCpuHeader.getValue(platform))) {
                "macOS video override has the wrong Mach-O architecture: $platform"
            }
            check(library.machOMinimumMacOs() == macVideoPlayerOverrideMinimumMacOs) {
                "macOS video override minimum version is not $macVideoPlayerOverrideMinimumMacOs: $platform"
            }
            check(library.hasCompleteMachOCodeSignature()) {
                "macOS video override is unsigned or has bytes outside its code signature: $platform"
            }
            check(library.sha256() == properties.getProperty("$platform.sha256")) {
                "macOS video override binary hash does not match its manifest: $platform"
            }
            if (OperatingSystem.current().isMacOsX) {
                val strictCheck = ProcessBuilder("/usr/bin/codesign", "--verify", "--strict", library.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                val output = strictCheck.inputStream.bufferedReader().use { it.readText() }
                check(strictCheck.waitFor() == 0) {
                    "macOS video override failed strict signature verification: $platform\n$output"
                }
            }
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
    // 插件负责提取依赖/入口，prepareConveyor 负责管理 CLI，Gradle 统一驱动完整构建。
    id("dev.hydraulic.conveyor") version "2.0"
    // 生成 BuildConfig 编译期常量
    alias(libs.plugins.buildconfig)
}

kotlin {
    // T011：Gradle 运行 JDK 21，Desktop 产物字节码显式钉 21。
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation(project(":client:shared"))
                implementation(project(":client:app"))
                implementation(compose.desktop.currentOs)
                implementation(libs.jetbrains.compose.material3)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.compose.media.player)
                // Desktop 直接调用 Windows 包身份和 KnownFolder API，不能依赖 SDK 的 implementation 泄漏。
                implementation(libs.jna.platform)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

// currentOs 只负责本机运行；Conveyor 必须分别解析每个交付目标的 Compose/Skiko native。
// 版本由同一 Compose 元数据决定，不能手工复制 DLL 或把宿主 runtime 当成跨平台 runtime。
dependencies {
    add("linuxAmd64", compose.desktop.linux_x64)
    add("macAmd64", compose.desktop.macos_x64)
    add("macAarch64", compose.desktop.macos_arm64)
    add("windowsAmd64", compose.desktop.windows_x64)
}

// Keep ordinary Desktop builds and cross-platform packaging on the audited resource set.
// Rebuilding remains explicit so non-macOS release hosts can consume the checked-in pair.
tasks.matching { it.name == "desktopProcessResources" }.configureEach {
    dependsOn(verifyMacVideoPlayerOverride)
}

tasks.named<Jar>("desktopJar").configure {
    doLast {
        JarFile(archiveFile.get().asFile).use { jar ->
            macVideoPlayerOverrideLibraries.forEach { (platform, resource) ->
                val entryPath = "composemediaplayer/native/$platform/libNativeVideoPlayer.dylib"
                val entry = checkNotNull(jar.getJarEntry(entryPath)) {
                    "Desktop jar is missing the macOS video override resource: $entryPath"
                }
                val packagedHash = jar.entrySha256(entry.name)
                check(packagedHash == resource.asFile.sha256()) {
                    "Desktop jar contains a stale macOS video override: $platform"
                }
            }
            val packagedLicenseHash =
                jar.entrySha256("META-INF/licenses/composemediaplayer-local-macos-MIT.txt")
            check(packagedLicenseHash == macVideoPlayerOverrideLicense.asFile.sha256()) {
                "Desktop jar contains a stale ComposeMediaPlayer MIT notice"
            }
        }
    }
}

// jpackage rewrites nested Mach-O resources by removing their ad-hoc signature. Verify that the
// first runtime classpath jar still carries TeamTalk's exact signature-stripped payload and license,
// rather than the same-path ComposeMediaPlayer dependency resource.
tasks.matching { it.name == "createDistributable" || it.name == "createReleaseDistributable" }.configureEach {
    doLast {
        // Only macOS jpackage strips the nested Mach-O signature. Other target hosts retain the
        // signed resource already verified by desktopJar and must not be compared with this hash.
        if (!OperatingSystem.current().isMacOsX) return@doLast

        val buildKind = if (name == "createReleaseDistributable") "main-release" else "main"
        val appRoot = layout.buildDirectory.dir("compose/binaries/$buildKind/app").get().asFile
        val launcherConfig = checkNotNull(
            appRoot.walkTopDown().firstOrNull { it.isFile && it.name == "${clientIdentity.desktopName}.cfg" },
        ) { "Cannot locate the packaged TeamTalk launcher config" }
        val firstClasspath = checkNotNull(
            launcherConfig.readLines().firstOrNull { it.startsWith("app.classpath=") },
        ) { "Packaged TeamTalk launcher has no classpath" }
        val jarName = firstClasspath.substringAfter("\$APPDIR/")
        check(jarName.startsWith("desktop-desktop-")) {
            "TeamTalk application jar is not first on the packaged classpath: $jarName"
        }
        val applicationJar = launcherConfig.parentFile.resolve(jarName)
        val platform = when (System.getProperty("os.arch").lowercase()) {
            "aarch64", "arm64" -> "darwin-aarch64"
            "amd64", "x86_64" -> "darwin-x86-64"
            else -> error("Unsupported macOS package architecture")
        }
        val properties = Properties().apply {
            macVideoPlayerOverrideManifest.asFile.inputStream().use { load(it) }
        }
        JarFile(applicationJar).use { jar ->
            val nativeEntry = "composemediaplayer/native/$platform/libNativeVideoPlayer.dylib"
            check(jar.entrySha256(nativeEntry) == properties.getProperty("$platform.stripped.sha256")) {
                "Packaged TeamTalk application contains the wrong macOS media backend"
            }
            check(
                jar.entrySha256("META-INF/licenses/composemediaplayer-local-macos-MIT.txt") ==
                    macVideoPlayerOverrideLicense.asFile.sha256(),
            ) { "Packaged TeamTalk application is missing the current native media license" }
        }
    }
}

// BuildConfig：构建信息内嵌（产物可溯源）+ 测试 HTTP 服务开关（恒 true，打包 jar 排除）
val gitCommitId = rootProject.extra.get("gitCommitId") as String
val buildIdentity = rootProject.extra.get("buildIdentity") as String
val buildTime = rootProject.extra.get("buildTime") as String
val releaseBuildNumber = rootProject.extra.get("releaseBuildNumber") as Int
val desktopRevision = rootProject.extra.get("desktopRevision") as Int
val snapshotDistribution = providers.gradleProperty("releaseMode").orNull == "snapshot"
buildConfig {
    packageName("com.virjar.tk.desktop")
    // 构建溯源：每个产物可回答「我是谁、用什么 commit 构建的」
    buildConfigField("GIT_COMMIT_ID", gitCommitId)
    buildConfigField("BUILD_IDENTITY", buildIdentity)
    buildConfigField("BUILD_TIME", buildTime)
    buildConfigField("APP_VERSION", releaseVersion)
    // 零起点构建计数；Android 的正数 versionCode 在这个计数上加一。
    buildConfigField("BUILD_NUMBER", releaseBuildNumber)
    // 测试 HTTP 服务：开发运行时启用，打包 jar exclude 物理删除 TestHttpServer
    buildConfigField("TEST_HTTP_SERVER", true)
    // 登录页自定义服务器入口（选中的 buildSrc Kotlin 配置 驱动，编译期定死；生产部署 false）
    buildConfigField("ALLOW_CUSTOM_SERVER", deploymentConfig.allowCustomServer)
    // 公共证书直接编译进客户端，避免将长证书内容放入打包后的 JVM 启动参数。
    buildConfigField("TCP_TLS_CERTIFICATE_BASE64", tcpTlsCertificateBase64)
}

compose.desktop {
    application {
        mainClass = "com.virjar.tk.desktop.TeamTalkMain"

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
            packageName = clientIdentity.desktopName
            packageVersion = releaseVersion

            // 部署地址固化进安装包 JVM 启动参数。
            // 运行时 ServerConfig.defaultServerConfig() 读取这些 JVM 属性拿到正确服务端地址，
            // 注意：是 nativeDistributions 层（打包专用），不影响 run 开发运行任务。
            jvmArgs.add("-Dteamtalk.server.url=${deploymentConfig.serverUrl}")
            jvmArgs.add("-Dteamtalk.tcp.host=${deploymentConfig.tcpHost}")
            jvmArgs.add("-Dteamtalk.tcp.port=${deploymentConfig.tcpPort}")

            // 打包前从编译产物移除测试 HTTP 服务相关 class（生产构建不含测试代码）。
            // compileKotlinDesktop 产出后在 runtimeClasspath 里，打包时排除 test 包。
            modules("java.desktop")
            modules("java.sql")  // JdbcSqliteDriver 需要 java.sql.DriverManager
            // 精确限制 jlink 模块，排除 jpackage 自动检测的 java.xml/java.logging 等不必要模块
            // jdeps 实测只需: java.base, java.desktop, java.sql, jdk.unsupported
            // java.base 和 jdk.unsupported 由 jlink 自动包含，不需声明
            modules("jdk.unsupported")

            // 应用图标：使用 TeamTalk 自有图标素材（doc/design/logo/desktop/）
            macOS {
                // 旧 Compose DMG 的默认 bundle ID 不同于 Conveyor，普通升级保留它。
                bundleID = if (clientIdentity.applicationId == "com.virjar.tk") {
                    "com.virjar.tk.desktop"
                } else {
                    clientIdentity.applicationId
                }
                dockName = clientIdentity.displayName
                infoPlist {
                    val displayNameXml = clientIdentity.displayName
                        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    extraKeysRawXml = "<key>CFBundleDisplayName</key><string>$displayNameXml</string>"
                }
                // JDK jpackage 拒绝首段为 0 的 app-version。仅系统安装元数据使用正数映射，
                // 应用内 APP_VERSION、Conveyor 与发行清单仍使用统一的 releaseVersion。
                packageVersion = "${releaseBuildNumber / 1_000_000 + 1}.${releaseBuildNumber / 1_000 % 1_000}.${releaseBuildNumber % 1_000}"
                // jpackage 默认 10.13，必须与实际 native 依赖和 Conveyor 的 14.0 基线一致。
                minimumSystemVersion = macVideoPlayerOverrideMinimumMacOs
                // macOS: 预生成的 .icns（iconutil 从 iconset 转换，含 16–512px）
                iconFile.set(rootProject.file("doc/design/logo/desktop/TeamTalk.icns"))
            }
            windows {
                menuGroup = clientIdentity.displayName
                upgradeUuid = clientIdentity.windowsUpgradeUuid
                // Windows: jpackage 从 PNG 自动转 .ico
                iconFile.set(rootProject.file("doc/design/logo/desktop/icon-256.png"))
            }
            linux {
                packageName = clientIdentity.desktopFsName
                // Linux: 直接使用 PNG
                iconFile.set(rootProject.file("doc/design/logo/desktop/icon-256.png"))
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
        exclude("com/virjar/tk/desktop/test/**")
        manifest.attributes("TeamTalk-Build-Identity" to buildIdentity)
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

// 配置是 Gradle 任务的产物。Conveyor 只读普通 include，不反向启动另一个 Gradle 进程。
val extractedConveyorConfig = layout.buildDirectory.file("conveyor/extracted.conveyor.conf")
val generatedConveyorConfig = layout.buildDirectory.file("conveyor/generated.conveyor.conf")
val generatedSiteConfig = layout.buildDirectory.file("conveyor/site.conveyor.conf")
val conveyorExecutableDescriptor = layout.buildDirectory.file("conveyor/tool.properties")

tasks.named<WriteConveyorConfigTask>("writeConveyorConfig") {
    destination.set(extractedConveyorConfig)
    dependsOn("desktopJar")
    doFirst { destination.get().asFile.parentFile.mkdirs() }
}

// Keep the Gradle-resolved platform graph; replace only the pure vector icon JAR with its used
// class closure. Neither host-native pruning nor Compose's whole-app ProGuard enters this path.
val prepareDesktopIcons by tasks.registering(PrepareDesktopIcons::class) {
    group = "distribution"
    description = "Keep referenced Material icon classes in the actual cross-platform Conveyor inputs"
    dependsOn("writeConveyorConfig")
    runtimeClasspath.from(configurations.named("desktopRuntimeClasspath"), tasks.named<Jar>("desktopJar").flatMap { it.archiveFile })
    originalConfig.set(extractedConveyorConfig)
    destinationConfig.set(generatedConveyorConfig)
    subsetDirectory.set(layout.buildDirectory.dir("conveyor/icons"))
}

val writeConveyorSiteConfig by tasks.registering {
    group = "distribution"
    description = "Write the update URL and resolved installation revision for Conveyor"
    inputs.property("serverUrl", deploymentConfig.serverUrl)
    inputs.property("desktopRevision", desktopRevision)
    inputs.property("snapshotDistribution", snapshotDistribution)
    inputs.property("clientApplicationId", clientIdentity.applicationId)
    inputs.property("clientDisplayName", clientIdentity.displayName)
    inputs.property("clientDesktopName", clientIdentity.desktopName)
    outputs.file(generatedSiteConfig)
    doLast {
        // 正式发行从根构建号映射；内测由提交历史计算，不写回根配置。
        check(desktopRevision in 1..65535) {
            "Conveyor MSIX revision must fit 1..65535; revise the installation-version mapping before increasing the build number further"
        }
        val siteUrl = "${deploymentConfig.serverUrl.trimEnd('/')}/downloads/desktop"
        fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        generatedSiteConfig.get().asFile.apply {
            parentFile.mkdirs()
            // display-name also controls the physical .app directory and Windows executable.
            // Keep those English names stable; visible UI labels may change independently.
            writeText(
                """
                app.site.base-url = ${quoted(siteUrl)}
                app.revision = $desktopRevision
                ${if (snapshotDistribution) "app.mac.deltas = 0" else ""}
                app.fsname = ${quoted(clientIdentity.desktopFsName)}
                app.display-name = ${quoted(clientIdentity.desktopName)}
                app.rdns-name = ${quoted(clientIdentity.applicationId)}
                app.mac.info-plist.CFBundleName = ${quoted(clientIdentity.displayName)}
                app.mac.info-plist.CFBundleDisplayName = ${quoted(clientIdentity.displayName)}
                app.windows.manifests.msix.display-name = ${quoted(clientIdentity.displayName)}
                app.linux.desktop-file."Desktop Entry".Name = ${quoted(clientIdentity.displayName)}
                """.trimIndent() + "\n",
            )
        }
    }
}

val prepareConveyorConfig by tasks.registering {
    group = "distribution"
    description = "Generate the complete Gradle-owned Conveyor configuration inputs"
    dependsOn(prepareDesktopIcons, writeConveyorSiteConfig)
}

val prepareConveyor by tasks.registering(PrepareConveyorTask::class) {
    distributionCatalog.set(rootProject.layout.projectDirectory.file("gradle/conveyor-tools.properties"))
    cacheDirectory.set(rootProject.layout.dir(provider { gradle.gradleUserHomeDir.resolve("teamtalk-tools/conveyor") }))
    executableOverride.set(providers.gradleProperty("conveyorExecutable").orElse(providers.environmentVariable("TEAMTALK_CONVEYOR_EXECUTABLE")))
    downloadBaseUrl.set(providers.gradleProperty("conveyorDownloadBaseUrl")
        .orElse(providers.environmentVariable("TEAMTALK_CONVEYOR_DOWNLOAD_BASE_URL"))
        .orElse("https://downloads.hydraulic.dev/conveyor"))
    executableDescriptor.set(conveyorExecutableDescriptor)
}

tasks.register("buildConveyorSite") {
    group = "distribution"
    description = "Build all Desktop installers and the update site with the Gradle-managed Conveyor CLI"
    dependsOn(prepareConveyor, prepareConveyorConfig)
    inputs.files("conveyor.conf", "conveyor/extract-native-libraries.conf", generatedConveyorConfig, generatedSiteConfig)
    inputs.property("buildIdentity", buildIdentity)
    outputs.dir(layout.projectDirectory.dir("output"))
    // Conveyor owns its content-addressed cache; always let it verify all platforms and signing inputs.
    outputs.upToDateWhen { false }
    doLast {
        val configDirectory = providers.gradleProperty("conveyorConfigDir")
            .orElse(providers.environmentVariable("TEAMTALK_CONVEYOR_CONFIG_DIR"))
            .orNull?.let { rootProject.file(it) } ?: defaultConveyorConfigDirectory()
        requireConveyorSigningConfiguration(configDirectory)
        buildConveyorSite(
            conveyorExecutableDescriptor.get().asFile,
            projectDir,
            configDirectory,
            releaseVersion,
            buildIdentity,
        )
    }
}

// ── Conveyor 打包输入集（跨平台交叉打包的标准产物）──
// 主 jar（已物理排除 test 包）+ 全量依赖 jar。sqlite-jdbc 多平台 native 保留
// （单机出三平台包，各平台 native 都要用——与 jpackage 链的单平台裁剪相反）。
tasks.register<Copy>("conveyorInputs") {
    group = "distribution"
    description = "收集 Conveyor 打包输入：主 jar + 全部运行时依赖（含多平台 native）"
    from(tasks.named("desktopJar"))
    from(configurations.getByName("desktopRuntimeClasspath"))
    into(layout.buildDirectory.dir("conveyor/lib"))
}

// Native libraries resolve methods that ProGuard cannot see as JVM call sites. Check all three
// bridges after real shrinking, even on a host that cannot load the other two platforms' libraries.
tasks.matching { it.name == "proguardReleaseJars" }.configureEach {
    doLast {
        val packagedJars = fileTree(layout.buildDirectory.dir("compose/tmp/main-release/proguard")) { include("*.jar") }.files
        verifyDesktopMediaNativeBridges(
            configurations.getByName("desktopRuntimeClasspath").files,
            packagedJars,
        )
        verifyDesktopWindowsIdentityBindings(packagedJars)
    }
}

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
// 沿用本机打包的可选字体清理，只删除 runtime 内的 fonts 目录；系统字体、字体映射与 AWT 库保持原样。
// jpackage 的字体位置因平台不同而变化，不能用 macOS bundle 路径作为 Windows/Linux 的输入目录。
// 中文渲染仍须在目标系统验证；这里不参与 Conveyor 的 JBR 组装。
tasks.register("stripRuntimeFonts") {
    group = "compose desktop"
    description = "删除打包产物中捆绑 runtime 的字体文件（IM 客户端用系统字体）"

    val appImage = tasks.named<AbstractJPackageTask>("createReleaseDistributable")
    val destination = appImage.flatMap { it.destinationDir }
    val packageName = appImage.flatMap { it.packageName }
    val platform = when {
        OperatingSystem.current().isMacOsX -> "macos"
        OperatingSystem.current().isWindows -> "windows"
        else -> "linux"
    }
    inputs.dir(destination)
    inputs.property("packageName", packageName)
    inputs.property("platform", platform)
    outputs.upToDateWhen { false }

    doLast {
        val fontsDir = desktopRuntimeFontsDirectory(destination.get().asFile, packageName.get(), platform)
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

tasks.withType<JavaExec>().configureEach {
    if (name == "run") {
        description = "Run Desktop with an isolated checkout data directory and the test HTTP service"
        // Compose Desktop 1.10 的 run 默认从 desktopJar 启动，而生产 jar 会物理排除 test 包。
        // Compose 插件会在配置后期重写 classpath 和 JVM 参数，因此必须在执行前最后注入。
        doFirst {
            // Main classes and resources must precede dependency jars. Besides exposing the
            // development TestHttpServer, this makes the TeamTalk native resource win over
            // ComposeMediaPlayer's same-path macOS payload deterministically during `run`.
            classpath =
                files(
                    layout.buildDirectory.dir("classes/kotlin/desktop/main"),
                    layout.projectDirectory.dir("src/desktopMain/resources"),
                ) + classpath

            if (args?.firstOrNull() in setOf(
                    "list-document-draft-rescue", "preview-document-draft-rescue", "import-document-draft-rescue",
                    "list-document-create-rescue", "preview-document-create-rescue", "import-document-create-rescue",
                    "list-document-space-create-rescue", "preview-document-space-create-rescue", "import-document-space-create-rescue",
                    "list-document-tree-create-rescue", "preview-document-tree-create-rescue", "import-document-tree-create-rescue",
                )) {
                // Offline maintenance supplies its own existing root. Do not create even the
                // development data parent or provision a UI test instance for these commands.
                jvmArgs = listOf("-Djava.awt.headless=true")
                return@doFirst
            }

            val developmentDataDirectory = System.getProperty("teamtalk.data.dir") ?: run {
                // Development must not lock or write an installed client's account directory.
                // Keep checkout data outside build/ so clean and rebuilds preserve local drafts.
                val checkout = MessageDigest.getInstance("SHA-256")
                    .digest(rootProject.rootDir.canonicalPath.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }.take(24)
                val parent = File(
                    System.getProperty("user.home"),
                    ".teamtalk/desktop-development/${clientIdentity.applicationId}",
                )
                check(parent.isDirectory || parent.mkdirs()) { "Cannot create Desktop development data parent: $parent" }
                // The application owns final-root admission, permissions, markers and locking.
                File(parent, checkout).absolutePath
            }
            val dataDirectoryArgs = listOf("-Dteamtalk.data.dir=$developmentDataDirectory")
            logger.lifecycle("Desktop development data directory: $developmentDataDirectory")
            val themeArgs = System.getProperty("teamtalk.theme")
                ?.let { listOf("-Dteamtalk.theme=$it") }
                .orEmpty()
            // 验收实例令牌：-Ptk.desktop.instanceToken=xxx 显式指定；缺省随机生成（App 内兜底）。
            // /ping 与 X-Instance-Token 响应头回显该值，用于区分刚启动的实例与占用端口的僵尸实例。
            val instanceTokenArg = (findProperty("tk.desktop.instanceToken") as String?)
                ?.takeIf(String::isNotBlank)
                ?.let { listOf("-Dtk.desktop.instance.token=$it") }
                .orEmpty()
            val testPortArg = System.getProperty("tk.desktop.test.port")
                ?.let { listOf("-Dtk.desktop.test.port=$it") }
                .orEmpty()
            jvmArgs = listOf(
                "-Dteamtalk.server.url=${deploymentConfig.serverUrl}",
                "-Dteamtalk.tcp.host=${deploymentConfig.tcpHost}",
                "-Dteamtalk.tcp.port=${deploymentConfig.tcpPort}",
            ) + dataDirectoryArgs + themeArgs + instanceTokenArg + testPortArg
        }
    }
}
