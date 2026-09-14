package release

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logger
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.jar.JarFile
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 单机交叉打包（替代 Conveyor）：JBR 运行时按 sha256 固定下载，负载按目标架构
 * 组装为“文件级清单 + zip”，壳 = JBR + 原生启动器 + bootstrap + 种子负载。
 *
 * 产物形态：macOS .app zip（双架构）、Windows exe（launch4j 包裹 bootstrap）+
 * 便携 zip + NSIS 安装器、Linux tar.gz + deb。更新不走这些包——应用内更新器只
 * 交换用户目录里的负载；壳升级才需要重新下载安装包。
 */
object JbrRuntimes {

    class Spec(val url: String, val sha256: String)

    fun load(propertiesFile: File): Map<DesktopTarget, Spec> {
        val props = Properties().apply {
            propertiesFile.inputStream().use { load(it) }
        }
        val base = props.getProperty("baseUrl").trimEnd('/')
        return DesktopTarget.values().mapNotNull { target ->
            val archive = props.getProperty("${target.key}.archive") ?: return@mapNotNull null
            target to Spec(
                url = "$base/$archive",
                sha256 = props.getProperty("${target.key}.sha256")
                    ?: throw GradleException("jbr.properties misses sha256 for ${target.key}"),
            )
        }.toMap()
    }

    fun toolRoot(): File = File(File(System.getProperty("user.home"), ".gradle"), "teamtalk-tools/jbr")

    /** 解压后的归档根（含 jbr/ 顶层目录）。 */
    fun extractedRoot(target: DesktopTarget, propertiesFile: File): File {
        val spec = load(propertiesFile).getValue(target)
        return File(toolRoot(), "extracted/${target.key}/${spec.sha256}")
    }

    fun homeDir(extractedRoot: File): File {
        val jbr = archiveRoot(extractedRoot)
        val macHome = File(jbr, "Contents/Home")
        return if (macHome.isDirectory) macHome else jbr
    }

    /** 归档顶层目录：zip 是 jbr/，mac tar.gz 是带版本的全名——统一解析为唯一顶层目录。 */
    fun archiveRoot(extractedRoot: File): File {
        val canonical = File(extractedRoot, "jbr")
        if (canonical.isDirectory) return canonical
        val children = extractedRoot.listFiles { file -> file.isDirectory } ?: emptyArray()
        check(children.size == 1) { "unexpected JBR extraction layout at ${extractedRoot.absolutePath}" }
        return children[0]
    }

    private val downloadLock = ReentrantLock()

    /**
     * 幂等确保目标运行时可用：优先 TEAMTALK_JBR_DIR 离线目录（<dir>/<target>/ 即归档根），
     * 否则下载校验 sha256 后解压。返回归档根。
     */
    fun ensure(target: DesktopTarget, propertiesFile: File, logger: Logger, offlineRoot: String? = null): File = downloadLock.withLock {
        offlineRoot?.takeIf(String::isNotBlank)?.let {
            val candidate = File(it, target.key)
            val java = File(homeDir(candidate), target.platform.javaExecutable)
            require(java.isFile) { "TEAMTALK_JBR_DIR is set but $candidate has no JBR java executable" }
            return candidate
        }
        val spec = load(propertiesFile)[target]
            ?: throw GradleException("jbr.properties has no entry for target ${target.key}")
        val archives = File(toolRoot(), "archives").apply { mkdirs() }
        val archiveFile = File(archives, spec.url.substringAfterLast('/'))
        if (!archiveFile.isFile || sha256Hex(archiveFile) != spec.sha256) {
            logger.lifecycle("Downloading JBR for {}: {}", target.key, spec.url)
            val tmp = File(archives, archiveFile.name + ".tmp")
            try {
                URI(spec.url).toURL().openConnection().apply {
                    connectTimeout = 30_000
                    readTimeout = 120_000
                }.getInputStream().use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
                val actual = sha256Hex(tmp)
                check(actual == spec.sha256) {
                    "JBR archive checksum mismatch for ${target.key}: expected ${spec.sha256}, got $actual"
                }
                Files.move(tmp.toPath(), archiveFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally {
                tmp.delete()
            }
        }
        val root = extractedRoot(target, propertiesFile)
        val marker = File(root, ".complete")
        if (!marker.isFile || marker.readText() != spec.sha256) {
            root.deleteRecursively()
            root.mkdirs()
            extract(archiveFile, root)
            marker.writeText(spec.sha256)
        }
        root
    }

    /** 纯 JVM 解压（commons-compress），保留符号链接与可执行位；拒绝路径穿越。 */
    private fun extract(archive: File, target: File) {
        if (archive.extension == "zip") {
            ZipFile.Builder().setFile(archive).get().use { zip ->
                val entries = zip.entries
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement() as ZipArchiveEntry
                    val out = safeTarget(target, entry.name)
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            out.outputStream().use { output -> input.copyTo(output) }
                        }
                        // unix mode 高位携带权限；0 表示未知，交给默认。
                        val mode = entry.unixMode and 0xFFF
                        if (mode != 0) {
                            out.setExecutable(mode and 0b001_000_000 != 0)
                            out.setReadable(mode and 0b100_000_000 != 0)
                        }
                    }
                }
            }
            return
        }
        // tar.gz 用系统 tar（macOS bsdtar / Linux GNU tar）：PAX 头、硬链接与
        // Mach-O 签名语义都有保证；手写 commons-compress 流曾出现字节级损坏。
        runCommand(target, listOf("tar", "-xzf", archive.absolutePath, "-C", target.absolutePath))
    }

    private fun safeTarget(root: File, entryName: String): File {
        require(!entryName.startsWith("/") && !entryName.contains("..") && !entryName.contains('\\')) {
            "unsafe archive entry: $entryName"
        }
        return File(root, entryName)
    }

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** 下载并解压某个目标架构的 JBR 运行时（幂等，sha256 固定）。 */
abstract class EnsureJbrTask : DefaultTask() {
    @get:Input val target: Property<DesktopTarget> = project.objects.property(DesktopTarget::class.java)

    @get:InputFile val runtimeProperties: RegularFileProperty = project.objects.fileProperty()
    @get:Input @get:Optional val offlineRoot: Property<String> = project.objects.property(String::class.java)
    @get:OutputDirectory val extractedRoot: DirectoryProperty = project.objects.directoryProperty()

    @TaskAction
    fun ensure() {
        val actual = JbrRuntimes.ensure(target.get(), runtimeProperties.get().asFile, logger, offlineRoot.orNull)
        check(actual.canonicalFile == extractedRoot.get().asFile.canonicalFile) { "JBR task output differs from selected runtime" }
    }
}

/**
 * 组装某目标架构的桌面负载：jar + native 覆盖文件 → payload 目录 + payload.properties
 * + 种子 zip。种子 zip 同时是发布注册中心的 bundle 制品。
 */
abstract class AssembleDesktopPayloadTask : DefaultTask() {
    @get:Input val targetKey: Property<String> = project.objects.property(String::class.java)
    @get:Input val version: Property<String> = project.objects.property(String::class.java)
    @get:Input val buildNumber: Property<Long> = project.objects.property(Long::class.java)
    @get:Input val buildIdentity: Property<String> = project.objects.property(String::class.java)
    @get:Input val channel: Property<String> = project.objects.property(String::class.java)
    @get:Input val minShellAbi: Property<Int> = project.objects.property(Int::class.java)

    /** 负载 jar（desktop 主 jar + 目标架构 runtimeClasspath）。 */
    @get:InputFiles val jarFiles: ConfigurableFileCollection = project.objects.fileCollection()

    /** 额外按资源路径平铺的文件（mac 视频库覆盖等）。 */
    @get:InputFiles val overlayFiles: ConfigurableFileCollection = project.objects.fileCollection()

    /** overlay 文件名 → 负载内相对路径。 */
    @get:Input val overlayPaths: MapProperty<String, String> = project.objects.mapProperty(String::class.java, String::class.java)

    @get:OutputDirectory val payloadDir: DirectoryProperty = project.objects.directoryProperty()

    @get:OutputFile val payloadZip: RegularFileProperty = project.objects.fileProperty()

    @TaskAction
    fun assemble() {
        val dir = payloadDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val lib = File(dir, "lib").apply { mkdirs() }

        val seen = HashMap<String, String>()
        val classOwners = HashMap<String, String>()
        for (jar in jarFiles) {
            require(jar.isFile) { "payload jar missing: $jar" }
            val previous = seen[jar.name]
            check(previous == null || previous == jar.path) {
                "payload jar name collision: ${jar.name} appears as both $previous and ${jar.path}"
            }
            seen[jar.name] = jar.path
            // URLClassLoader 按顺序取首个同名类；重复版本不能靠文件名排序决定实际运行代码。
            JarFile(jar).use { archive ->
                archive.entries().asSequence()
                    .filter { it.name.endsWith(".class") && !it.name.endsWith("module-info.class") }
                    .forEach { entry ->
                        val owner = classOwners.putIfAbsent(entry.name, jar.name)
                        check(owner == null || owner == jar.name) {
                            "Duplicate payload class ${entry.name} in $owner and ${jar.name}; align runtime dependency versions"
                        }
                    }
            }
            jar.copyTo(File(lib, jar.name), overwrite = true)
        }
        overlayFiles.forEach { file ->
            val relative = overlayPaths.get()[file.name]
                ?: throw GradleException("overlay file not declared in overlayPaths: ${file.name}")
            val target = File(dir, relative)
            target.parentFile?.mkdirs()
            file.copyTo(target, overwrite = true)
        }

        // 描述符覆盖目录内除自身外的全部文件；这是 bootstrap/更新器共同的磁盘契约。
        val files = dir.walkTopDown()
            .filter { it.isFile && it.relativeTo(dir).path != "payload.properties" }
            .map { it.relativeTo(dir).invariantSeparatorsPath to it }
            .toList()
            .sortedBy { it.first }
        val props = Properties().apply {
            setProperty("version", version.get())
            setProperty("build", buildNumber.get().toString())
            setProperty("buildIdentity", buildIdentity.get())
            setProperty("channel", channel.get())
            setProperty("minShellAbi", minShellAbi.get().toString())
            setProperty("files.count", files.size.toString())
            files.forEachIndexed { index, (path, file) ->
                setProperty("file.$index.path", path)
                setProperty("file.$index.sha256", JbrRuntimes.sha256Hex(file))
                setProperty("file.$index.size", file.length().toString())
            }
        }
        File(dir, "payload.properties").outputStream().use {
            props.store(it, "TeamTalk desktop payload descriptor (${targetKey.get()})")
        }

        val zip = payloadZip.get().asFile
        zip.parentFile?.mkdirs()
        zip.outputStream().buffered().use { raw ->
            ZipArchiveOutputStream(raw).use { out ->
                out.putArchiveEntry(ZipArchiveEntry("payload.properties"))
                File(dir, "payload.properties").inputStream().use { it.copyTo(out) }
                out.closeArchiveEntry()
                for ((path, file) in files) {
                    out.putArchiveEntry(ZipArchiveEntry(path))
                    file.inputStream().use { it.copyTo(out) }
                    out.closeArchiveEntry()
                }
            }
        }
    }
}

/**
 * 组装某目标平台的桌面壳并产出发行归档。
 *
 * 布局（三平台一致的“根”语义）：
 * - macOS：TeamTalk.app/Contents/{MacOS/TeamTalk(脚本), Info.plist, Resources/TeamTalk.icns,
 *   runtime/(JBR Contents), app/{bootstrap.jar, seed-payload.zip}} → zip
 * - Windows：TeamTalk/{TeamTalk.exe(launch4j 包裹 bootstrap), runtime/, app/seed-payload.zip,
 *   TeamTalk.ico} → 便携 zip（NSIS 安装器由独立任务产出）
 * - Linux：TeamTalk/{bin/teamtalk(脚本), runtime/, app/…, share/icons} → tar.gz（deb 独立任务）
 */
abstract class AssembleDesktopShellTask : DefaultTask() {
    @get:Input val target: Property<DesktopTarget> = project.objects.property(DesktopTarget::class.java)
    @get:Input val version: Property<String> = project.objects.property(String::class.java)
    @get:Input val displayName: Property<String> = project.objects.property(String::class.java)
    @get:Input val appId: Property<String> = project.objects.property(String::class.java)
    @get:Input val installationName: Property<String> = project.objects.property(String::class.java)
    @get:Input val buildNumber: Property<Long> = project.objects.property(Long::class.java)

    /** 固化进启动器的部署 JVM 参数（-Dteamtalk.server.url=… 等，来自部署配置）。 */
    @get:Input val serverJvmOptions: org.gradle.api.provider.ListProperty<String> =
        project.objects.listProperty(String::class.java)

    /** 解压后的 JBR 归档根（含 jbr/ 顶层）。 */
    @get:InputDirectory val jbrExtractedRoot: DirectoryProperty = project.objects.directoryProperty()

    /** 壳自身的 bootstrap jar（来自 :client:desktop-bootstrap 的产物）。 */
    @get:InputFiles val bootstrapJar: ConfigurableFileCollection = project.objects.fileCollection()

    @get:InputFile val seedPayloadZip: RegularFileProperty = project.objects.fileProperty()

    @get:InputFile val iconFile: RegularFileProperty = project.objects.fileProperty()

    @get:OutputDirectory val outputDir: DirectoryProperty = project.objects.directoryProperty()

    @TaskAction
    fun assemble() {
        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        // staging 保留为任务输出：NSIS/deb 安装器任务直接消费 staging/ 里的组装树。
        val staging = File(out, "staging")
        staging.mkdirs()
        when (target.get().platform) {
            DesktopPlatform.MACOS -> assembleMac(staging)
            DesktopPlatform.WINDOWS -> assembleWindows(staging)
            DesktopPlatform.LINUX -> assembleLinux(staging)
        }
    }

    private fun jbrRoot(): File = JbrRuntimes.archiveRoot(jbrExtractedRoot.get().asFile)

    /** detachedConfiguration 会连带 bootstrap 的隐式 stdlib；按产物名取真正的壳 jar。 */
    private fun bootstrapJarFile(): File =
        bootstrapJar.files.first { it.extension == "jar" && it.name.startsWith("desktop-bootstrap") }

    private fun copyAppPayload(appDir: File, includeBootstrapJar: Boolean) {
        appDir.mkdirs()
        seedPayloadZip.get().asFile.copyTo(File(appDir, "seed-payload.zip"), overwrite = true)
        if (includeBootstrapJar) {
            bootstrapJarFile().copyTo(File(appDir, "bootstrap.jar"), overwrite = true)
        }
    }

    private fun assembleMac(staging: File) {
        val name = installationName.get()
        val app = File(staging, "$name.app/Contents").apply { mkdirs() }
        File(app, "MacOS").mkdirs()
        File(app, "Resources").mkdirs()
        copyDirectory(jbrRoot(), File(app, "runtime"))
        copyAppPayload(File(app, "app"), includeBootstrapJar = true)
        iconFile.get().asFile.copyTo(File(app, "Resources/$name.icns"), overwrite = true)
        installMacLauncher(File(app, "MacOS/$name"))
        File(app, "Info.plist").writeText(macInfoPlist())
        signMacAppForLocalTrust(File(staging, "$name.app"))
        archiveZip(staging, target.get().portableArchiveName(version.get()), "$name.app")
    }

    private fun assembleWindows(staging: File) {
        val name = installationName.get()
        val root = File(staging, name).apply { mkdirs() }
        copyDirectory(jbrRoot(), File(root, "runtime"))
        val javaw = File(root, "runtime/bin/javaw.exe")
        val originalSha256 = JbrRuntimes.sha256Hex(javaw)
        if (WindowsRuntimeManifest.enableUtf8(javaw)) {
            // 原始下载缓存保持厂商字节；安装副本的修改必须明示，不能继续冒充原厂商签名。
            File(root, "runtime/TEAMTALK-MODIFICATIONS.txt").writeText(
                "TeamTalk sets javaw.exe activeCodePage to UTF-8 in its embedded process manifest.\n" +
                    "Any original vendor Authenticode certificate is removed; this copy is unsigned.\n" +
                    "Native code and all other resources are unchanged.\n" +
                    "Original javaw.exe SHA-256: $originalSha256\n" +
                    "Modified javaw.exe SHA-256: ${JbrRuntimes.sha256Hex(javaw)}\n",
            )
        }
        copyAppPayload(File(root, "app"), includeBootstrapJar = false)
        iconFile.get().asFile.copyTo(File(root, "$name.ico"), overwrite = true)
    }

    private fun assembleLinux(staging: File) {
        val name = installationName.get()
        val executable = name.lowercase(java.util.Locale.ROOT)
        val root = File(staging, name).apply { mkdirs() }
        copyDirectory(jbrRoot(), File(root, "runtime"))
        copyAppPayload(File(root, "app"), includeBootstrapJar = true)
        File(root, "bin").mkdirs()
        writePosixLauncher(File(root, "bin/$executable"), "../runtime/bin/java", "../app/bootstrap.jar", serverJvmOptions.get())
        val icons = File(root, "share/icons/hicolor/256x256/apps").apply { mkdirs() }
        iconFile.get().asFile.copyTo(File(icons, "$executable.png"), overwrite = true)
        File(root, "share/applications").mkdirs()
        File(root, "share/applications/$executable.desktop").writeText(
            """
            [Desktop Entry]
            Type=Application
            Name=${displayName.get().replace("\\", "\\\\")}
            Comment=TeamTalk 即时通讯与办公协作
            Exec=/opt/$executable/bin/$executable
            Icon=$executable
            Terminal=false
            Categories=Network;InstantMessaging;
            """.trimIndent() + "\n",
        )
        archiveTarGz(staging, target.get().portableArchiveName(version.get()), name)
    }

    private fun macInfoPlist(): String {
        val serverUrl = serverJvmOptions.get().single { it.startsWith("-Dteamtalk.server.url=") }.substringAfter('=')
        val jvmOptions = serverJvmOptions.get().joinToString("") { "            <string>${xmlText(it)}</string>\n" }
        return """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
        <dict>
          <key>CFBundleDevelopmentRegion</key><string>zh_CN</string>
          <key>CFBundleExecutable</key><string>${installationName.get()}</string>
          <key>CFBundleIconFile</key><string>${installationName.get()}</string>
          <key>CFBundleIdentifier</key><string>${appId.get()}</string>
          <key>CFBundleInfoDictionaryVersion</key><string>6.0</string>
          <key>CFBundleName</key><string>${xmlText(displayName.get())}</string>
          <key>CFBundleDisplayName</key><string>${xmlText(displayName.get())}</string>
          <key>CFBundlePackageType</key><string>APPL</string>
          <key>CFBundleShortVersionString</key><string>${version.get()}</string>
          <key>CFBundleVersion</key><string>${version.get()}.${buildNumber.get()}</string>
          <key>LSMinimumSystemVersion</key><string>14.0</string>
          <key>LSArchitecturePriority</key><array><string>${if (target.get() == DesktopTarget.MACOS_AARCH64) "arm64" else "x86_64"}</string></array>
          <key>NSHighResolutionCapable</key><true/>
          <key>NSSupportsAutomaticGraphicsSwitching</key><true/>
          <key>TeamTalkMainClass</key><string>com.virjar.tk.desktop.shell.BootstrapMain</string>
          <key>TeamTalkDownloadBaseURL</key><string>${xmlText(serverUrl)}</string>
          <key>TeamTalkJVMOptions</key>
          <array>
$jvmOptions          </array>
        </dict>
        </plist>
    """.trimIndent() + "\n"
    }

    /**
     * 安装 macOS 原生 JNI 启动器（universal x86_64 + arm64）。
     * macOS 二进制必须链接 Cocoa SDK，无法在 Windows/Linux 构建机上现场编译；
     * 单机交叉打包要求所有平台都能产出全部目标，因此预编译二进制随源码树分发
     * （源码 [macLauncherSource] 与 sha256 清单同目录），组装时校验后复制。
     * 更新源码后须在 macOS 上运行 [rebuildMacLauncherBinary] 重编并提交新二进制。
     */
    private fun installMacLauncher(output: File) {
        val name = "macos-launcher/TeamTalkLauncher"
        val binary = macLauncherResource(name)
        val expected = macLauncherResource("$name.sha256").readText().trim()
        val actual = JbrRuntimes.sha256Hex(binary)
        check(actual == expected) {
            "macOS launcher binary mismatch: expected $expected, got $actual; rebuild via rebuildMacLauncherBinary"
        }
        binary.copyTo(output, overwrite = true)
        check(output.setExecutable(true, false)) { "Cannot make launcher executable: $output" }
    }

    /**
     * macOS 的现代通知 API 要求应用至少有 ad-hoc 代码签名，否则授权请求被系统静默
     * 拒绝（不弹窗、不注册）。签名由纯 JVM 实现（[MacAppBundleSigner]）完成，任何
     * 构建平台行为一致，交叉打包不依赖 macOS 工具；等价 `codesign -f -s -`（不 deep），
     * runtime 保持厂商原签名。ad-hoc 不提供身份担保，正式分发仍需 Developer ID
     * 签名与公证（另行决策）。
     */
    private fun signMacAppForLocalTrust(app: File) {
        MacAppBundleSigner.sign(File(app, "Contents"), appId.get())
        logger.lifecycle("ad-hoc signed (JVM signer) {}", app)
    }

    private fun archiveZip(staging: File, name: String, vararg entries: String) {
        val zipFile = File(outputDir.get().asFile, name)
        archiveDirectoryZip(staging, zipFile, entries.toList())
        logger.lifecycle("assembled {}", zipFile)
    }

    private fun archiveTarGz(staging: File, name: String, vararg entries: String) {
        val tarFile = File(outputDir.get().asFile, name)
        // Linux 包不携带 macOS 宿主扩展属性或 AppleDouble；保留原文件、权限与符号链接。
        runCommand(staging, listOf("tar", "--no-xattrs", "--owner=0", "--group=0", "--numeric-owner",
            "--format=pax", "-czf", tarFile.absolutePath) + entries.toList(),
            mapOf("COPYFILE_DISABLE" to "1"))
        logger.lifecycle("assembled {}", tarFile)
    }

}

/**
 * Linux 启动脚本；参数作为 POSIX 字面量写入，安装位置运行时解析。
 * macOS 使用原生 JNI 启动器（[macLauncherSource]），不再生成脚本。
 */
internal fun writePosixLauncher(file: File, javaPath: String, bootstrapPath: String, options: List<String>) {
    val arguments = options.joinToString(" ") { posixLiteral(it) }
    file.writeText(
        """
        |#!/bin/sh
        |DIR=${'$'}(CDPATH= cd -- "${'$'}(dirname -- "${'$'}0")" && pwd) || exit 1
        |exec "${'$'}DIR/$javaPath" $arguments \
        |  "-Dteamtalk.shell.launcher=${'$'}DIR/${file.name}" \
        |  ${'$'}TEAMTALK_JAVA_OPTS -cp "${'$'}DIR/$bootstrapPath" \
        |  com.virjar.tk.desktop.shell.BootstrapMain "${'$'}@"
        """.trimMargin() + "\n",
    )
    check(file.setExecutable(true, false)) { "Cannot make launcher executable: $file" }
}

private fun posixLiteral(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
private fun xmlText(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** 从 buildSrc 资源提取 macOS 原生启动器文件；二进制与源码同入库，不提交构建期产物。 */
internal fun macLauncherResource(name: String): File {
    val resource = JbrRuntimes::class.java.classLoader.getResourceAsStream(name)
        ?: throw GradleException("Missing bundled launcher resource: $name")
    val extension = name.substringAfterLast('/', "").substringAfterLast('.', "")
    val target = File.createTempFile("teamtalk-mac-launcher", if (extension.isEmpty()) "" else ".$extension")
    target.deleteOnExit()
    resource.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
    return target
}

/** 兼容旧引用：启动器 Objective-C 源码。 */
internal fun macLauncherSource(): File = macLauncherResource("macos-launcher/TeamTalkLauncher.m")

/**
 * 在 macOS 上重编 universal 启动器并更新源码树里的二进制与 sha256 清单。
 * 仅限 mac 开发流程使用；普通组装（任何平台）走 [macLauncherResource] 的预编译产物。
 */
internal fun rebuildMacLauncherBinary(resourcesDir: File) {
    val source = File(resourcesDir, "macos-launcher/TeamTalkLauncher.m")
    val binary = File(resourcesDir, "macos-launcher/TeamTalkLauncher")
    val javaHome = File(System.getProperty("java.home"))
    val jniInclude = File(javaHome, "include")
    check(jniInclude.isDirectory) {
        "重编启动器需要 JDK 头文件，当前 Gradle JVM ($javaHome) 缺少 include/jni.h"
    }
    val code = runCommand(
        resourcesDir,
        listOf(
            "xcrun", "clang", "-x", "objective-c",
            "-arch", "x86_64", "-arch", "arm64",
            "-mmacosx-version-min=14.0",
            "-fobjc-arc",
            "-I", jniInclude.absolutePath,
            "-I", File(jniInclude, "darwin").absolutePath,
            "-framework", "Cocoa",
            "-framework", "UserNotifications",
            "-ldl",
            "-O2",
            "-o", binary.absolutePath,
            source.absolutePath,
        ),
        emptyMap(),
    )
    check(code == 0 && binary.isFile) { "clang failed with exit $code" }
    File(resourcesDir, "macos-launcher/TeamTalkLauncher.sha256").writeText(JbrRuntimes.sha256Hex(binary) + "\n")
    check(binary.setExecutable(true, false))
}
