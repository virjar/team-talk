package com.virjar.tk.shared.update

import java.io.File
import java.util.Properties
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

/**
 * 壳/负载分离的磁盘契约。
 *
 * 布局（由桌面壳 bootstrap 与本更新器共同遵守，格式刻意用 java.util.Properties，
 * 保证零依赖的 bootstrap 能读写同一种文件）：
 *
 * ```
 * <payloadRoot>/                          # 默认 ~/.teamtalk-client/<appId>/versions
 *   current.properties                    # 指针：version / build
 *   <payload-sha256>/                              # 一个版本一个目录，原子切换
 *     payload.properties                  # 描述符：version/build/files.count/file.N.*
 *     lib 目录（*.jar）、native 等         # 负载文件，路径即 classpath 资源路径
 *   .staging-<build>-<millis>/            # 更新暂存（仅清理本次创建的暂存）
 * ```
 *
 * current.properties 的替换是“临时文件 + 原子 rename”，崩溃时要么旧版要么新版。
 */
object PayloadLayout {
    const val CURRENT_POINTER_FILE = "current.properties"
    const val DESCRIPTOR_FILE = "payload.properties"
    const val STAGING_PREFIX = ".staging-"

    const val PROP_VERSION = "version"
    const val PROP_BUILD = "build"
    const val PROP_FILES_COUNT = "files.count"
    const val PROP_MIN_SHELL_ABI = "minShellAbi"
    private const val PROP_FILE_PATH = "file.%d.path"
    private const val PROP_FILE_SHA = "file.%d.sha256"
    private const val PROP_FILE_SIZE = "file.%d.size"

    data class PayloadFile(val path: String, val sha256: String, val size: Long)

    data class PayloadDescriptor(
        val version: String,
        val build: Long,
        val minShellAbi: Int?,
        val files: List<PayloadFile>,
        val buildIdentity: String? = null,
        val channel: String = "stable",
    )

    data class CurrentPointer(
        val version: String,
        val build: Long,
        val directory: String = build.toString(),
        val seedId: String? = null,
    )

    fun readDescriptor(versionDir: File): PayloadDescriptor? {
        val descriptorFile = File(versionDir, DESCRIPTOR_FILE)
        if (!descriptorFile.isFile) return null
        val props = descriptorFile.inputStream().use { Properties().apply { load(it) } } ?: return null
        val version = props.getProperty(PROP_VERSION) ?: return null
        val build = props.getProperty(PROP_BUILD)?.toLongOrNull() ?: return null
        val count = props.getProperty(PROP_FILES_COUNT)?.toIntOrNull()?.takeIf { it in 1..5000 } ?: return null
        val files = (0 until count).mapNotNull { index ->
            val path = props.getProperty(PROP_FILE_PATH.format(index)) ?: return@mapNotNull null
            val sha = props.getProperty(PROP_FILE_SHA.format(index)) ?: return@mapNotNull null
            val size = props.getProperty(PROP_FILE_SIZE.format(index))?.toLongOrNull() ?: return@mapNotNull null
            PayloadFile(path, sha, size)
        }
        if (files.size != count) return null
        return PayloadDescriptor(
            version = version,
            build = build,
            minShellAbi = props.getProperty(PROP_MIN_SHELL_ABI)?.toIntOrNull(),
            files = files,
            buildIdentity = props.getProperty("buildIdentity"),
            channel = props.getProperty("channel") ?: "stable",
        )
    }

    fun writeDescriptor(versionDir: File, descriptor: PayloadDescriptor) {
        val props = Properties().apply {
            descriptor.buildIdentity?.let { setProperty("buildIdentity", it) }
            setProperty("channel", descriptor.channel)
            setProperty(PROP_VERSION, descriptor.version)
            setProperty(PROP_BUILD, descriptor.build.toString())
            descriptor.minShellAbi?.let { setProperty(PROP_MIN_SHELL_ABI, it.toString()) }
            setProperty(PROP_FILES_COUNT, descriptor.files.size.toString())
            descriptor.files.forEachIndexed { index, file ->
                setProperty(PROP_FILE_PATH.format(index), file.path)
                setProperty(PROP_FILE_SHA.format(index), file.sha256)
                setProperty(PROP_FILE_SIZE.format(index), file.size.toString())
            }
        }
        writeProperties(File(versionDir, DESCRIPTOR_FILE), props)
    }

    fun readCurrentPointer(versionsRoot: File): CurrentPointer? {
        val pointerFile = File(versionsRoot, CURRENT_POINTER_FILE)
        if (!pointerFile.isFile) return null
        val props = pointerFile.inputStream().use { Properties().apply { load(it) } }
        val version = props.getProperty(PROP_VERSION) ?: return null
        val build = props.getProperty(PROP_BUILD)?.toLongOrNull() ?: return null
        val directory = props.getProperty("directory") ?: build.toString()
        if (!Regex("[a-zA-Z0-9.-]+").matches(directory) || directory in setOf(".", "..")) return null
        return CurrentPointer(version, build, directory, props.getProperty("seedId"))
    }

    /** 原子指针切换：先写临时文件再 rename，读方永远看到完整旧版或完整新版。 */
    fun writeCurrentPointer(versionsRoot: File, pointer: CurrentPointer) {
        val props = Properties().apply {
            setProperty("directory", pointer.directory)
            pointer.seedId?.let { setProperty("seedId", it) }
            setProperty(PROP_VERSION, pointer.version)
            setProperty(PROP_BUILD, pointer.build.toString())
        }
        writeProperties(File(versionsRoot, CURRENT_POINTER_FILE), props)
    }

    private fun writeProperties(target: File, props: Properties) {
        val tmp = Files.createTempFile(target.parentFile.toPath(), ".pointer-", ".tmp")
        try {
            Files.newOutputStream(tmp).use { props.store(it, "TeamTalk desktop payload") }
            Files.move(tmp, target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /** 同一 version/build 的不同负载仍用不同目录，发布后不再改写。与壳端格式一致。 */
    fun directoryName(descriptor: PayloadDescriptor): String {
        val identity = buildString {
            appendLine(descriptor.version)
            appendLine(descriptor.build)
            appendLine(descriptor.minShellAbi ?: 0)
            appendLine(descriptor.buildIdentity.orEmpty())
            descriptor.files.sortedBy { it.path }.forEach {
                appendLine("${it.path}\t${it.sha256}\t${it.size}")
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
/**
 * 当前进程的壳上下文。bootstrap 启动负载时写入系统属性；dev/裸 JVM 运行时不存在，
 * 此时自更新不可用（[fromSystem] 返回 null），UI 应隐藏更新入口。
 */
class DesktopUpdateContext(
    val versionsRoot: File,
    val currentDir: File,
    val descriptor: PayloadLayout.PayloadDescriptor,
    val launcherCommand: String?,
    val shellAbi: Int,
) {
    val version: String get() = descriptor.version
    val build: Long get() = descriptor.build

    companion object {
        const val PROP_PAYLOAD_DIR = "teamtalk.payload.dir"
        const val PROP_PAYLOAD_VERSION = "teamtalk.payload.version"
        const val PROP_PAYLOAD_BUILD = "teamtalk.payload.build"
        const val PROP_SHELL_LAUNCHER = "teamtalk.shell.launcher"
        const val PROP_SHELL_ABI = "teamtalk.shell.abi"

        fun fromSystem(): DesktopUpdateContext? {
            val dirPath = System.getProperty(PROP_PAYLOAD_DIR) ?: return null
            val dir = File(dirPath)
            val descriptor = PayloadLayout.readDescriptor(dir) ?: return null
            return DesktopUpdateContext(
                versionsRoot = dir.parentFile ?: dir,
                currentDir = dir,
                descriptor = descriptor,
                launcherCommand = System.getProperty(PROP_SHELL_LAUNCHER),
                shellAbi = System.getProperty(PROP_SHELL_ABI)?.toIntOrNull() ?: 0,
            )
        }
    }
}

/** 桌面平台词汇，与服务端 ClientUpdateContracts 对齐。 */
object DesktopPlatform {
    fun platform(): String = when {
        osName.startsWith("Windows", ignoreCase = true) -> "windows"
        osName.startsWith("Mac", ignoreCase = true) || osName.contains("Darwin", ignoreCase = true) -> "macos"
        else -> "linux"
    }

    fun arch(): String = when (System.getProperty("os.arch")?.lowercase()) {
        "aarch64", "arm64" -> "aarch64"
        else -> "amd64"
    }

    private val osName: String get() = System.getProperty("os.name").orEmpty()
}
