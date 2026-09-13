package release

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import java.util.Locale
import deployment.ProcessSpec
import deployment.LONG_PROCESS_TIMEOUT_MILLIS
import deployment.runCheckedProcess
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream

/** launch4j：把 bootstrap jar 包裹成 Windows GUI exe（workdir 二进制来自 buildSrc classpath）。 */
object Launch4jRunner {

    fun build(
        exe: File,
        bootstrapJar: File,
        icon: File,
        displayName: String,
        version: String,
        jvmOptions: List<String>,
        logger: org.gradle.api.logging.Logger,
    ) {
        val config = net.sf.launch4j.config.Config().apply {
            headerType = net.sf.launch4j.config.Config.GUI_HEADER
            outfile = exe
            jar = bootstrapJar
            errTitle = displayName
            downloadUrl = "https://github.com/JetBrains/JetBrainsRuntime/releases"
            setStayAlive(false)
            this.icon = icon
            jre = net.sf.launch4j.config.Jre().apply {
                path = "runtime"
                minVersion = "21"
                requires64Bit = true
                // 部署参数（-Dteamtalk.server.url=… 等）烧进 exe。
                options = jvmOptions + "-Dteamtalk.shell.launcher=\"%EXEFILE%\""
            }
            val numeric = version.takeIf { Regex("\\d+\\.\\d+\\.\\d+").matches(it) } ?: "0.0.1"
            versionInfo = net.sf.launch4j.config.VersionInfo().apply {
                copyright = "Apache License 2.0"
                fileVersion = "$numeric.0"
                txtFileVersion = version
                productVersion = "$numeric.0"
                txtProductVersion = version
                fileDescription = "$displayName 桌面客户端"
                productName = displayName
                companyName = "TeamTalk"
                originalFilename = exe.name
                internalName = "teamtalk"
            }
        }
        ensureWorkdirBinaries(logger)
        val log = object : net.sf.launch4j.Log() {
            override fun clear() = Unit
            override fun append(message: String?) = logger.lifecycle("launch4j: {}", message)
        }
        // launch4j 3.50 经单例取配置；basedir 影响 jar/icon 等相对路径解析，传 exe 所在目录。
        net.sf.launch4j.config.ConfigPersister.getInstance().setAntConfig(config, exe.parentFile)
        val produced = net.sf.launch4j.Builder(log).build()
        check(produced.absolutePath == exe.absolutePath && exe.isFile) {
            "launch4j did not produce ${exe.absolutePath}"
        }
    }

    /**
     * launch4j 从自身 core jar 所在目录解析 bin 目录下的 windres、ld 与 head 目录下的目标文件；Maven Central 的
     * workdir 二进制在独立分类器 jar 里。这里把宿主平台 workdir 的 bin、head、w32api 解压到
     * core jar 旁（幂等），使 Builder 的默认目录约定成立。
     */
    private fun ensureWorkdirBinaries(logger: org.gradle.api.logging.Logger) {
        val coreJar = File(net.sf.launch4j.Util::class.java.protectionDomain.codeSource.location.toURI())
        val baseDir = coreJar.parentFile ?: error("cannot locate launch4j core jar directory")
        if (File(baseDir, "bin/windres").canExecute() && File(baseDir, "w32api/crt2.o").isFile) return
        val versionDir = baseDir.parentFile ?: error("cannot locate launch4j version directory")
        val classifier = when {
            System.getProperty("os.name").lowercase().contains("mac") -> "workdir-mac"
            System.getProperty("os.name").lowercase().contains("linux") -> "workdir-linux64"
            else -> "workdir-win32"
        }
        val expected = "launch4j-" + coreJar.name
            .removePrefix("launch4j-").removeSuffix("-core.jar") + "-" + classifier + ".jar"
        val workdirJar = versionDir.listFiles { file -> file.isDirectory }
            ?.flatMap { dir -> dir.listFiles { file -> file.isFile }.orEmpty().toList() }
            .orEmpty()
            .firstOrNull { it.name == expected }
            ?: error("launch4j $classifier jar not found beside $coreJar; check buildSrc dependencies")
        java.util.zip.ZipFile(workdirJar).use { zip ->
            zip.entries().asSequence()
                .filter { entry ->
                    !entry.isDirectory && ("/bin/" in entry.name || "/head/" in entry.name || "/w32api/" in entry.name)
                }
                .forEach { entry ->
                    // 条目带顶层 workdir-<os>/ 前缀，剥掉后落在 core jar 旁边。
                    val target = File(baseDir, entry.name.substringAfter('/'))
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    target.setExecutable(true)
                }
        }
        logger.lifecycle("launch4j workdir binaries extracted to {}", baseDir)
    }
}


/** 生成 TeamTalk.exe 到 Windows 壳 staging 目录（launch4j 包裹 bootstrap jar）。 */
abstract class BuildWindowsExeTask : DefaultTask() {
    @get:Input val displayName: Property<String> = project.objects.property(String::class.java)
    @get:Input val installationName: Property<String> = project.objects.property(String::class.java)
    @get:Input val version: Property<String> = project.objects.property(String::class.java)
    @get:Input val jvmOptions: org.gradle.api.provider.ListProperty<String> =
        project.objects.listProperty(String::class.java)

    @get:InputFiles val bootstrapJar: ConfigurableFileCollection = project.objects.fileCollection()

    @get:InputFile val iconFile: RegularFileProperty = project.objects.fileProperty()

    @get:OutputFile val exeFile: RegularFileProperty = project.objects.fileProperty()

    @TaskAction
    fun build() {
        exeFile.get().asFile.parentFile.mkdirs()
        Launch4jRunner.build(
            exe = exeFile.get().asFile,
            bootstrapJar = bootstrapJar.files.first { it.extension == "jar" && it.name.startsWith("desktop-bootstrap") },
            icon = iconFile.get().asFile,
            displayName = displayName.get(),
            version = version.get(),
            jvmOptions = jvmOptions.get(),
            logger = logger,
        )
    }
}

/**
 * NSIS Windows 传统安装器（setup.exe）。前置：makensis 在 PATH（macOS: brew install makensis；
 * CI ubuntu: apt-get install nsis）。安装到 Program Files，写开始菜单与卸载项；
 * 应用内更新永不重装，安装器只在首装/壳升级时使用一次。
 */
abstract class BuildWindowsInstallerTask : DefaultTask() {
    @get:Input val version: Property<String> = project.objects.property(String::class.java)
    @get:Input val displayName: Property<String> = project.objects.property(String::class.java)
    @get:Input val installationName: Property<String> = project.objects.property(String::class.java)

    /** 壳任务的 staging 产物目录（含 TeamTalk/ 便携根）。 */
    @get:InputDirectory val stagingRoot: DirectoryProperty = project.objects.directoryProperty()

    @get:OutputDirectory val outputDir: DirectoryProperty = project.objects.directoryProperty()

    @TaskAction
    fun build() {
        val makensis = findExecutableOnPath("makensis")
            ?: throw GradleException(
                "makensis not found on PATH. Install it first: macOS `brew install makensis`; " +
                    "Debian/Ubuntu `sudo apt-get install nsis`. " +
                    "(打包机一次性准备，见 doc/07-operations/desktop-cross-build.md)",
            )
        val appDir = File(stagingRoot.get().asFile, installationName.get())
        check(appDir.isDirectory) { "windows staging root missing: ${appDir.absolutePath}" }
        val out = outputDir.get().asFile.apply { mkdirs() }
        val script = File(out, "teamtalk-installer.nsi")
        script.writeText(nsiScript(appDir, out))
        val setupExe = File(out, "TeamTalk-${version.get()}-setup.exe")
        runCommand(out, listOf(makensis.absolutePath, "-WX", "-V2", "-NOCD", script.absolutePath))
        check(setupExe.isFile) { "makensis did not produce ${setupExe.absolutePath}" }
        logger.lifecycle("assembled {}", setupExe)
    }

    private fun nsiScript(appDir: File, out: File): String {
        val name = installationName.get()
        val label = nsisLiteral(displayName.get())
        return """
            !include "MUI2.nsh"
            Name "$label"
            OutFile "${nsisLiteral(File(out, "TeamTalk-${version.get()}-setup.exe").absolutePath)}"
            InstallDir "${'$'}PROGRAMFILES64\$name"
            RequestExecutionLevel admin
            Unicode true
            SetCompressor /SOLID lzma

            !define MUI_ABORTWARNING
            !define MUI_ICON "${nsisLiteral(File(appDir, "$name.ico").absolutePath)}"
            !insertmacro MUI_PAGE_DIRECTORY
            !insertmacro MUI_PAGE_INSTFILES
            !insertmacro MUI_UNPAGE_CONFIRM
            !insertmacro MUI_UNPAGE_INSTFILES
            !insertmacro MUI_LANGUAGE "SimpChinese"

            Function .onInit
              SetRegView 64
              ReadRegStr ${'$'}0 HKLM "Software\$name" "InstallDir"
              StrCmp ${'$'}0 "" +2
              StrCpy ${'$'}INSTDIR ${'$'}0
            FunctionEnd

            Section "Install"
              SetRegView 64
              SetShellVarContext all
              SetOutPath "${'$'}INSTDIR"
              File /r "${nsisLiteral(appDir.absolutePath)}\*.*"
              CreateDirectory "${'$'}SMPROGRAMS\$name"
              CreateShortcut "${'$'}SMPROGRAMS\$name\$name.lnk" "${'$'}INSTDIR\$name.exe"
              CreateShortcut "${'$'}DESKTOP\$name.lnk" "${'$'}INSTDIR\$name.exe"
              WriteUninstaller "${'$'}INSTDIR\Uninstall.exe"
              WriteRegStr HKLM "Software\$name" "InstallDir" "${'$'}INSTDIR"
              WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\$name" "DisplayName" "$label"
              WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\$name" "UninstallString" '"${'$'}INSTDIR\Uninstall.exe"'
              WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\$name" "DisplayVersion" "${version.get()}"
            SectionEnd

            Section "Uninstall"
              SetRegView 64
              SetShellVarContext all
              Delete "${'$'}SMPROGRAMS\$name\$name.lnk"
              RMDir "${'$'}SMPROGRAMS\$name"
              Delete "${'$'}DESKTOP\$name.lnk"
              RMDir /r "${'$'}INSTDIR"
              DeleteRegKey HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\$name"
              DeleteRegKey HKLM "Software\$name"
            SectionEnd
        """.trimIndent() + "\n"
    }

    private fun findExecutableOnPath(name: String): File? =
        System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .flatMap { listOf(File(it, name), File(it, "$name.exe")) }
            .firstOrNull { it.isFile && it.canExecute() }
}

/**
 * Linux deb 包：tar 生成内容归档、JVM 写 ar 容器，macOS/Linux 均可交叉打包。
 * data.tar.xz：/opt/teamtalk（壳本体）+ /usr/share（桌面项与图标来自壳内 share/）。
 */
abstract class BuildLinuxDebTask : DefaultTask() {
    @get:Input val version: Property<String> = project.objects.property(String::class.java)
    @get:Input val arch: Property<String> = project.objects.property(String::class.java)
    @get:Input val installationName: Property<String> = project.objects.property(String::class.java)
    @get:Input val buildNumber: Property<Long> = project.objects.property(Long::class.java)

    @get:InputDirectory val stagingRoot: DirectoryProperty = project.objects.directoryProperty()

    @get:OutputDirectory val outputDir: DirectoryProperty = project.objects.directoryProperty()

    @TaskAction
    fun build() {
        val appDir = File(stagingRoot.get().asFile, installationName.get())
        check(appDir.isDirectory) { "linux staging root missing: ${appDir.absolutePath}" }
        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val work = File(out, "deb").apply { mkdirs() }

        File(work, "debian-binary").writeText("2.0\n")
        val packageName = installationName.get().lowercase(Locale.ROOT)
        val controlDir = File(work, "control.d").apply { mkdirs() }
        File(controlDir, "control").writeText(
            """
            Package: $packageName
            Version: ${version.get()}-${buildNumber.get()}
            Architecture: ${arch.get()}
            Maintainer: TeamTalk <teamtalk@virjar.im>
            Depends: libc6, libstdc++6, libx11-6, libxext6, libxi6, libxrender1, libxtst6, libasound2, libfreetype6, fontconfig
            Section: net
            Priority: optional
            Description: TeamTalk desktop client
             TeamTalk 即时通讯与办公协作桌面客户端（JBR 运行时随包携带）。
            """.trimIndent() + "\n",
        )
        runTar(File(work, "control.tar.gz"), controlDir, format = "gnu")

        val dataDir = File(work, "data.d")
        val optDir = File(dataDir, "opt/$packageName").apply { mkdirs() }
        copyDirectory(appDir, optDir) { relative -> !relative.startsWith("share/") }
        val share = File(appDir, "share")
        if (share.isDirectory) copyDirectory(share, File(dataDir, "usr/share"))
        val command = File(dataDir, "usr/bin/$packageName").apply { parentFile.mkdirs() }
        command.writeText("#!/bin/sh\nexec /opt/$packageName/bin/$packageName \"${'$'}@\"\n")
        check(command.setExecutable(true, false)) { "Cannot make Debian command executable: $command" }
        runTar(File(work, "data.tar.xz"), dataDir, format = "gnu", xz = true)

        val deb = File(out, "TeamTalk-${version.get()}-linux-${arch.get()}.deb")
        writeDebArchive(
            deb,
            listOf(
                "debian-binary" to File(work, "debian-binary"),
                "control.tar.gz" to File(work, "control.tar.gz"),
                "data.tar.xz" to File(work, "data.tar.xz"),
            ),
        )
        work.deleteRecursively()
        logger.lifecycle("assembled {}", deb)
    }

    private fun runTar(target: File, source: File, format: String, xz: Boolean = false) {
        // 末尾显式 "."：macOS bsdtar 不像 GNU tar 那样默认打包当前目录。
        val command = buildList {
            add("tar")
            // macOS bsdtar 不识别 gnu 格式名；dpkg 对归档格式无要求，条目以 ./ 开头即可。
            if (System.getProperty("os.name").lowercase().contains("linux")) add("--format=$format")
            if (xz) add("--xz") else add("-z")
            add("-cf")
            add(target.absolutePath)
            add("-C")
            add(source.absolutePath)
            add(".")
        }
        runCommand(source, command)
    }
}

/** 带过滤的目录复制（relative 以 '/' 分隔，匹配 payload 布局语义）。 */
internal fun copyDirectory(source: File, target: File, filter: (String) -> Boolean = { true }) {
    Files.walk(source.toPath()).use { paths ->
        paths.forEach { path ->
            val relative = source.toPath().relativize(path).toString().replace(File.separatorChar, '/')
            if (relative.isEmpty() || !filter(relative)) return@forEach
            val to = target.toPath().resolve(relative)
            if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
                Files.createDirectories(to)
            } else {
                Files.createDirectories(to.parent)
                // NOFOLLOW_LINKS preserves signed JBR symlinks; copying their targets invalidates macOS signatures.
                Files.copy(path, to, NOFOLLOW_LINKS, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
            }
        }
    }
}

private fun nsisLiteral(value: String): String = value.replace("$", "$$").replace("\"", "${'$'}\\\"")

/** 便携目录归档，和 macOS .app 共享跨宿主 ZIP 写入器。 */
abstract class ZipDirectoryTask : DefaultTask() {
    @get:Input val archiveName: Property<String> = project.objects.property(String::class.java)

    @get:InputDirectory val contentRoot: DirectoryProperty = project.objects.directoryProperty()

    @get:Input val entryName: Property<String> = project.objects.property(String::class.java)

    @get:OutputFile val archiveFile: RegularFileProperty = project.objects.fileProperty()

    @TaskAction
    fun zip() {
        val out = archiveFile.get().asFile
        out.parentFile?.mkdirs()
        archiveDirectoryZip(contentRoot.get().asFile, out, listOf(entryName.get()))
        logger.lifecycle("assembled {}", out)
    }
}

/** 进程外命令（zip/tar/ar/makensis）：不参与增量缓存语义，输出文件本身是任务输出。 */
internal fun runCommand(workingDir: File, command: List<String>): Int = runCheckedProcess(
    ProcessSpec(command.first(), command, timeoutMillis = LONG_PROCESS_TIMEOUT_MILLIS, workingDirectory = workingDir),
).exitCode

/** 直接重建 ZIP，保留链接及执行位；跨宿主使用同一归档实现，重试不残留已删除的文件。 */
internal fun archiveDirectoryZip(source: File, destination: File, entries: List<String>) {
    destination.outputStream().buffered().use { raw ->
        ZipArchiveOutputStream(raw).use { output ->
            for (name in entries) {
                Files.walk(File(source, name).toPath()).use { paths ->
                    paths.sorted().forEach { path ->
                        val relative = source.toPath().relativize(path).toString().replace(File.separatorChar, '/')
                        val directory = Files.isDirectory(path, NOFOLLOW_LINKS)
                        val link = Files.isSymbolicLink(path)
                        val entry = ZipArchiveEntry(relative + if (directory) "/" else "").apply {
                            time = 0
                            unixMode = when {
                                link -> 0xA1FF // symlink 0777
                                directory -> 0x41ED // directory 0755
                                Files.isExecutable(path) -> 0x81ED // file 0755
                                else -> 0x81A4 // file 0644
                            }
                        }
                        output.putArchiveEntry(entry)
                        when {
                            link -> output.write(Files.readSymbolicLink(path).toString().toByteArray(Charsets.UTF_8))
                            !directory -> Files.newInputStream(path).use { it.copyTo(output) }
                        }
                        output.closeArchiveEntry()
                    }
                }
            }
        }
    }
}

/** 纯 JVM 写 Debian .ar 归档：macOS 的 ar 拒绝非 Mach-O 成员，GNU ar 在 mac 不可用。 */
internal fun writeDebArchive(deb: File, members: List<Pair<String, File>>) {
    deb.outputStream().buffered().use { output ->
        output.write("!<arch>\n".toByteArray(Charsets.US_ASCII))
        members.forEach { (name, file) ->
            val size = file.length()
            val header = buildString {
                append(name.padEnd(16))
                append("0".padEnd(12))
                append("0".padEnd(6)).append("0".padEnd(6))
                append("100644".padEnd(8))
                append(size.toString().padEnd(10))
                append("`\n")
            }
            output.write(header.toByteArray(Charsets.US_ASCII))
            file.inputStream().buffered().use { it.copyTo(output) }
            if (size % 2 == 1L) output.write(0)
        }
    }
}
