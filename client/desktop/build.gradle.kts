import java.util.Base64
import org.gradle.internal.os.OperatingSystem
import deployment.DeploymentConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Properties
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

// 交叉打包与开发运行共用根项目唯一发布版本。
val releaseVersion = rootProject.extra.get("releaseVersion") as String
version = releaseVersion

val deploymentConfig = rootProject.extra.get("deploymentConfig") as DeploymentConfig
val clientIdentity = deploymentConfig.client
val tcpTlsCertificateBase64 = deploymentConfig.tcpTlsCertificatePem
    ?.let { Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) }
    .orEmpty()

// ComposeMediaPlayer 0.9.0's macOS backend retains local video descriptors after dispose.
// TeamTalk ships a narrow, source-auditable local-file replacement at the exact resource
// paths its existing NativeLibraryLoader resolves. The checked-in binaries keep cross-builds
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
// Matches the shell's LSMinimumSystemVersion baseline and the upstream 0.9 dylibs.
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
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
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

// Compose 1.11 把跨目标坐标访问器标记为弃用（KTS 按编译错误呈现）；
// 显式抑制并继续使用同一来源，不在构建脚本里手工复制带版本的模块坐标。
val desktopDependenciesExtension =
    extensions.getByType(org.jetbrains.compose.ComposeExtension::class.java).dependencies.desktop

@Suppress("DEPRECATION")
fun composeDesktopNotation(property: String): String = with(desktopDependenciesExtension) {
    when (property) {
        "currentOs" -> currentOs
        "linux_x64" -> linux_x64
        "macos_x64" -> macos_x64
        "macos_arm64" -> macos_arm64
        "windows_x64" -> windows_x64
        else -> error("unknown compose desktop notation: $property")
    }
}

// currentOs 只负责本机运行；交叉负载必须分别解析每个交付目标的 Compose/Skiko native。
// 版本由同一 Compose 元数据决定，不能手工复制 DLL 或把宿主 runtime 当成跨平台 runtime。
// 这四个交叉目标配置原由 Conveyor 插件隐式创建；移除 Conveyor 后由本仓库显式声明。
listOf("linuxAmd64", "macAmd64", "macAarch64", "windowsAmd64").forEach { name ->
    configurations.maybeCreate(name)
}
dependencies {
    add("linuxAmd64", composeDesktopNotation("linux_x64"))
    add("macAmd64", composeDesktopNotation("macos_x64"))
    add("macAarch64", composeDesktopNotation("macos_arm64"))
    add("windowsAmd64", composeDesktopNotation("windows_x64"))
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

// BuildConfig：构建信息内嵌（产物可溯源）+ 测试 HTTP 服务开关（恒 true，打包 jar 排除）
val gitCommitId = rootProject.extra.get("gitCommitId") as String
val buildIdentity = rootProject.extra.get("buildIdentity") as String
val buildTime = rootProject.extra.get("buildTime") as String
val releaseBuildNumber = rootProject.extra.get("releaseBuildNumber") as Int
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

// ── 单机交叉打包（替代 Conveyor）：文件级清单负载 + 三平台壳 ──
// 壳 = JBR + 原生启动器 + bootstrap + 种子负载，极少更新；应用内更新器只交换
// 用户目录里的负载。产物与语义见 buildSrc release/DesktopShellTasks.kt。
// 负载构建号取 desktopRevision（正式=buildNumber+1，快照=提交历史推导），保证更新通道内单调；
// 该值由根 ReleaseTasks 在配置期写入 extra。
val desktopShellBuildNumber = (rootProject.extra.get("desktopRevision") as Number).toLong()
// 与 client/desktop-bootstrap 的 SHELL_ABI 常量保持一致（壳布局/启动协议变更时同步递增）。
val desktopShellAbi = 1
val desktopReleaseChannel = rootProject.extra.get("clientReleaseChannel") as String
val desktopIconDir = layout.projectDirectory.dir("packaging/icons")

// 宿主 currentOs 的 Compose/Skiko 工件只服务本机 dev 运行；交叉负载需换成目标架构工件。
val desktopHostOsConfiguration =
    configurations.detachedConfiguration(dependencies.create(composeDesktopNotation("currentOs")))

data class DesktopShellTarget(
    val key: String,
    val platform: String,
    val arch: String,
    val configurationName: String,
)

val desktopShellTargets = listOf(
    DesktopShellTarget("macos-aarch64", "macos", "aarch64", "macAarch64"),
    DesktopShellTarget("macos-amd64", "macos", "amd64", "macAmd64"),
    DesktopShellTarget("windows-amd64", "windows", "amd64", "windowsAmd64"),
    DesktopShellTarget("linux-amd64", "linux", "amd64", "linuxAmd64"),
)

// 以 detachedConfiguration 惰性引用 bootstrap 产物，避免跨项目任务过早解析。
val bootstrapJarConfiguration = configurations.detachedConfiguration(
    dependencies.create(project(":client:desktop-bootstrap")),
)

fun camelCase(text: String): String =
    text.split('-', '_', ' ').filter(String::isNotBlank)
        .joinToString("") { it.replaceFirstChar { char -> char.uppercase() } }

// 部署身份烧进首装包 JVM 参数（原 jpackage nativeDistributions.jvmArgs 的等价物；
// 运行时 ServerConfig.defaultServerConfig() 读这些系统属性）。
val desktopDeploymentJvmOptions = listOf(
    "-Dteamtalk.server.url=${deploymentConfig.serverUrl}",
    "-Dteamtalk.tcp.host=${deploymentConfig.tcpHost}",
    "-Dteamtalk.tcp.port=${deploymentConfig.tcpPort}",
)

// Material icons 裁剪：负载用“被引用闭包子集”替换 material-icons-extended 胖 jar。
val desktopPayloadBase: org.gradle.api.file.FileCollection =
    (configurations.getByName("desktopRuntimeClasspath") as org.gradle.api.file.FileCollection)
        .minus(desktopHostOsConfiguration)
val desktopIconsOriginal = desktopPayloadBase.filter { it.name.startsWith("material-icons-extended-desktop-") }
val pruneDesktopIcons = tasks.register<release.PruneDesktopMaterialIconsTask>("pruneDesktopMaterialIcons") {
    group = "distribution"
    description = "Rewrite material-icons-extended into the referenced-classes subset"
    payloadJars.from(tasks.named("desktopJar"), desktopPayloadBase)
    payloadJars.from(configurations.named("macAarch64"), configurations.named("macAmd64"),
        configurations.named("windowsAmd64"), configurations.named("linuxAmd64"))
    subsetDirectory.set(layout.buildDirectory.dir("desktop-payload/icons-subset"))
}

desktopShellTargets.forEach { target ->
    val suffix = camelCase(target.key)
    // lambda 接收者会遮蔽脚本级同名 val；先取局部别名避免任务属性自引用。
    val shellBuildIdentity = buildIdentity
    val ensureJbr = tasks.register<release.EnsureJbrTask>("ensureJbr$suffix") {
        group = "distribution"
        description = "Download and extract the pinned JBR runtime for ${target.key}"
        this.target.set(target.key)
        val jbrProperties = rootProject.layout.projectDirectory.file("gradle/jbr.properties")
        runtimeProperties.set(jbrProperties)
        offlineRoot.set(providers.environmentVariable("TEAMTALK_JBR_DIR").filter(String::isNotBlank))
        // 缓存解压根与离线根路径一致（离线时直接采用）；任务内校验最终一致。
        extractedRoot.set(
            project.layout.dir(
                offlineRoot.map { File(it, target.key) }.orElse(
                    providers.provider { release.JbrRuntimes.extractedRoot(target.key, jbrProperties.asFile) },
                ),
            ),
        )
    }

    val payloadTask = tasks.register<release.AssembleDesktopPayloadTask>("assembleDesktopPayload$suffix") {
        group = "distribution"
        description = "Assemble the file-level payload manifest and seed zip for ${target.key}"
        targetKey.set(target.key)
        version.set(releaseVersion)
        buildNumber.set(desktopShellBuildNumber)
        this.buildIdentity.set(shellBuildIdentity)
        channel.set(desktopReleaseChannel)
        minShellAbi.set(desktopShellAbi)
        jarFiles.from(tasks.named("desktopJar"))
        jarFiles.from(desktopPayloadBase.minus(desktopIconsOriginal))
        jarFiles.from(pruneDesktopIcons.flatMap { it.subsetDirectory.file("material-icons-extended-desktop.jar") })
        jarFiles.from(configurations.named(target.configurationName))
        if (target.platform == "macos") {
            val resource = if (target.arch == "aarch64") {
                "darwin-aarch64/libNativeVideoPlayer.dylib"
            } else {
                "darwin-x86-64/libNativeVideoPlayer.dylib"
            }
            val override = macVideoPlayerOverrideResourceDir.file(resource).asFile
            overlayFiles.from(override)
            overlayPaths.put(override.name, "composemediaplayer/native/$resource")
        }
        payloadDir.set(layout.buildDirectory.dir("desktop-payload/${target.key}/payload"))
        payloadZip.set(layout.buildDirectory.file("desktop-payload/${target.key}/payload.zip"))
        dependsOn(tasks.named("desktopJar"))
    }

    val shellTask = tasks.register<release.AssembleDesktopShellTask>("assembleDesktopShell$suffix") {
        group = "distribution"
        description = "Assemble the ${target.key} desktop shell (JBR + launcher + bootstrap + seed payload)"
        platform.set(target.platform)
        arch.set(target.arch)
        targetKey.set(target.key)
        version.set(releaseVersion)
        displayName.set(clientIdentity.displayName)
        installationName.set(clientIdentity.desktopName)
        buildNumber.set(desktopShellBuildNumber)
        appId.set(clientIdentity.applicationId)
        serverJvmOptions.set(desktopDeploymentJvmOptions)
        jbrExtractedRoot.set(ensureJbr.flatMap { it.extractedRoot })
        bootstrapJar.from(bootstrapJarConfiguration)
        seedPayloadZip.set(payloadTask.flatMap { it.payloadZip })
        iconFile.set(
            when (target.platform) {
                "macos" -> desktopIconDir.file("TeamTalk.icns")
                "windows" -> desktopIconDir.file("TeamTalk.ico")
                else -> desktopIconDir.file("TeamTalk-256.png")
            },
        )
        outputDir.set(layout.buildDirectory.dir("desktop-shell/${target.key}"))
        dependsOn(ensureJbr, payloadTask, bootstrapJarConfiguration)
    }

    if (target.platform == "windows") {
        // exe：launch4j 把 bootstrap jar 包裹进 GUI exe（jar 已带 Main-Class 清单）。
        val createExe = tasks.register<release.BuildWindowsExeTask>("wrapWindowsBootstrap$suffix") {
            group = "distribution"
            description = "Wrap the bootstrap jar into TeamTalk.exe for ${target.key}"
            dependsOn(shellTask, bootstrapJarConfiguration)
            bootstrapJar.from(bootstrapJarConfiguration)
            iconFile.set(desktopIconDir.file("TeamTalk.ico"))
            displayName.set(clientIdentity.displayName)
            installationName.set(clientIdentity.desktopName)
            version.set(releaseVersion)
            jvmOptions.set(desktopDeploymentJvmOptions)
            exeFile.set(shellTask.flatMap { it.outputDir.file("staging/${clientIdentity.desktopName}/${clientIdentity.desktopName}.exe") })
        }
        tasks.register<release.ZipDirectoryTask>("packageWindowsPortable$suffix") {
            group = "distribution"
            description = "Archive the ${target.key} portable directory into a zip"
            dependsOn(createExe)
            archiveName.set("TeamTalk-${releaseVersion}-windows-${target.arch}-portable.zip")
            contentRoot.set(shellTask.flatMap { it.outputDir.dir("staging") })
            entryName.set(clientIdentity.desktopName)
            archiveFile.set(layout.buildDirectory.file("desktop-shell/${target.key}/TeamTalk-${releaseVersion}-windows-${target.arch}-portable.zip"))
        }
        tasks.register<release.BuildWindowsInstallerTask>("buildWindowsInstaller$suffix") {
            group = "distribution"
            description = "Build the NSIS setup.exe for ${target.key} (requires makensis on PATH)"
            version.set(releaseVersion)
            displayName.set(clientIdentity.displayName)
            installationName.set(clientIdentity.desktopName)
            stagingRoot.set(shellTask.flatMap { it.outputDir.dir("staging") })
            outputDir.set(layout.buildDirectory.dir("desktop-shell/${target.key}/installer"))
            dependsOn(createExe)
        }
    }
    if (target.platform == "linux") {
        tasks.register<release.BuildLinuxDebTask>("buildLinuxDeb$suffix") {
            group = "distribution"
            description = "Build the .deb package for ${target.key}"
            version.set(releaseVersion)
            arch.set(target.arch)
            installationName.set(clientIdentity.desktopName)
            buildNumber.set(desktopShellBuildNumber)
            stagingRoot.set(shellTask.flatMap { it.outputDir.dir("staging") })
            outputDir.set(layout.buildDirectory.dir("desktop-shell/${target.key}/installer"))
            dependsOn(shellTask)
        }
    }
}

// 统一入口：三平台四目标全部产物（壳归档 + Windows exe/便携包/安装器 + Linux deb）。
// NSIS 安装器需要 makensis（macOS: brew install makensis；CI: apt-get install nsis）；
// 本机未安装且显式要求跳过时（-Pteamtalk.skipWindowsInstaller=true）只出便携包。
tasks.register("assembleDesktopShells") {
    group = "distribution"
    description = "Assemble every desktop shell artifact for all platform/arch targets"
    val skipInstaller = providers.gradleProperty("teamtalk.skipWindowsInstaller").map(String::toBoolean).orElse(false)
    dependsOn(
        "assembleDesktopShellMacosAarch64", "assembleDesktopShellMacosAmd64",
        "assembleDesktopShellWindowsAmd64", "assembleDesktopShellLinuxAmd64",
        "packageWindowsPortableWindowsAmd64", "buildLinuxDebLinuxAmd64",
        "assembleDesktopPayloadMacosAarch64", "assembleDesktopPayloadMacosAmd64",
        "assembleDesktopPayloadWindowsAmd64", "assembleDesktopPayloadLinuxAmd64",
    )
    if (!skipInstaller.get()) {
        dependsOn("buildWindowsInstallerWindowsAmd64")
    }
}
