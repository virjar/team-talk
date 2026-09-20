package com.virjar.tk.desktop.shell

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

/**
 * 壳端只依赖 JDK。文件字段和 directoryName 摘要顺序与 shared/update/PayloadLayout 相同。
 * 内容目录一旦发布就不再改写；指针中的 seedId 记录用户最后安装的种子，避免每次启动退回首装版本。
 */
/**
 * 壳端负载/指针的磁盘格式实现。public 仅供跨模块契约测试引用（client/shared jvmTest），
 * 运行时仍只有 bootstrap 消费；字段与摘要顺序必须与 shared/update/PayloadLayout 保持一致。
 */
object PayloadStore {
    class PayloadFile(val path: String, val sha256: String, val size: Long)
    class Descriptor(
        val version: String,
        val build: Long,
        val minShellAbi: Int?,
        val files: List<PayloadFile>,
        val buildIdentity: String? = null,
    )
    class Pointer(
        val version: String,
        val build: Long,
        val directory: String = build.toString(),
        val seedId: String? = null,
    )

    fun readPointer(root: File): Pointer? {
        val props = load(File(root, "current.properties")) ?: return null
        val version = props.getProperty("version") ?: return null
        val build = props.getProperty("build")?.toLongOrNull() ?: return null
        val directory = props.getProperty("directory") ?: build.toString()
        if (!Regex("[a-zA-Z0-9.-]+").matches(directory) || directory in setOf(".", "..")) return null
        return Pointer(version, build, directory, props.getProperty("seedId"))
    }

    fun writePointer(root: File, pointer: Pointer) {
        val props = Properties().apply {
            setProperty("version", pointer.version)
            setProperty("build", pointer.build.toString())
            setProperty("directory", pointer.directory)
            pointer.seedId?.let { setProperty("seedId", it) }
        }
        val tmp = Files.createTempFile(root.toPath(), ".pointer-", ".tmp")
        try {
            Files.newOutputStream(tmp).use { props.store(it, "TeamTalk desktop payload") }
            Files.move(tmp, File(root, "current.properties").toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    fun readDescriptor(directory: File): Descriptor? = load(File(directory, "payload.properties"))?.let(::descriptorFrom)

    /** 启动期核对已验证负载的清单与大小；完整哈希在种子提取或更新落位前完成。 */
    fun verifyLight(directory: File, descriptor: Descriptor) {
        descriptor.files.forEach {
            val target = File(directory, it.path)
            require(target.isFile && target.length() == it.size) { "payload file missing or damaged: ${it.path}" }
        }
    }

    fun directoryName(descriptor: Descriptor): String {
        val identity = buildString {
            appendLine(descriptor.version)
            appendLine(descriptor.build)
            appendLine(descriptor.minShellAbi ?: 0)
            appendLine(descriptor.buildIdentity.orEmpty())
            descriptor.files.sortedBy { it.path }.forEach {
                appendLine("${it.path}\t${it.sha256}\t${it.size}")
            }
        }
        return hex(MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8)))
    }

    /** 换装新安装包时采用其种子；同一个种子后续启动继续尊重应用内更新的指针。 */
    fun selectPayload(installDirs: List<File>, root: File): Pointer? {
        root.mkdirs()
        FileChannel.open(File(root, ".update.lock").toPath(), CREATE, WRITE).use { channel ->
            val lock = channel.tryLock() ?: error("另一个进程正在更新，请稍后重试")
            lock.use {
                val current = readPointer(root)
                val seed = installDirs.firstNotNullOfOrNull { dir -> seedDescriptor(dir)?.let { dir to it } }
                if (seed != null && (current == null || current.seedId != directoryName(seed.second))) {
                    return seedFromInstall(seed.first, root, seed.second)
                }
                if (current != null) {
                    val descriptor = readDescriptor(File(root, current.directory))
                    val valid = descriptor != null && descriptor.version == current.version && descriptor.build == current.build &&
                        runCatching { verifyLight(File(root, current.directory), descriptor) }.isSuccess
                    if (!valid && seed != null) return seedFromInstall(seed.first, root, seed.second)
                }
                return current
            }
        }
    }

    private fun seedDescriptor(install: File): Descriptor? {
        val directory = File(install, "seed-payload")
        if (directory.isDirectory) return readDescriptor(directory)
        val zip = File(install, "seed-payload.zip")
        if (!zip.isFile) return null
        return ZipFile(zip).use { archive ->
            val entry = archive.getEntry("payload.properties") ?: return@use null
            require(entry.size in 1..4L * 1024 * 1024) { "invalid seed descriptor size" }
            archive.getInputStream(entry).use { input -> descriptorFrom(Properties().apply { load(input) }) }
        }
    }

    private fun seedFromInstall(install: File, root: File, descriptor: Descriptor): Pointer {
        require((descriptor.minShellAbi ?: 0) <= SHELL_ABI) { "安装种子要求更新的壳，请重新下载安装包" }
        val seedId = directoryName(descriptor)
        var directory = seedId
        var finalDir = File(root, directory)
        val staging = Files.createTempDirectory(root.toPath(), ".seed-").toFile()
        try {
            val source = File(install, "seed-payload")
            if (source.isDirectory) {
                (descriptor.files.map { it.path } + "payload.properties").forEach { name ->
                    File(staging, name).also { it.parentFile.mkdirs() }.let { File(source, name).copyTo(it) }
                }
            } else {
                ZipFile(File(install, "seed-payload.zip")).use { archive ->
                    (descriptor.files.map { it.path } + "payload.properties").forEach { name ->
                        val entry = archive.getEntry(name) ?: error("seed file missing: $name")
                        val target = File(staging, name).also { it.parentFile.mkdirs() }
                        archive.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                    }
                }
            }
            verifyFull(staging, descriptor)
            if (finalDir.exists() && runCatching { verifyFull(finalDir, descriptor) }.isFailure) {
                directory += "-" + java.util.UUID.randomUUID()
                finalDir = File(root, directory)
            }
            if (!finalDir.exists()) Files.move(staging.toPath(), finalDir.toPath(), ATOMIC_MOVE)
            return Pointer(descriptor.version, descriptor.build, directory, seedId).also { writePointer(root, it) }
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun verifyFull(directory: File, descriptor: Descriptor) {
        verifyLight(directory, descriptor)
        descriptor.files.forEach { file ->
            val digest = MessageDigest.getInstance("SHA-256")
            File(directory, file.path).inputStream().buffered().use { input ->
                val bytes = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(bytes)
                    if (count < 0) break
                    digest.update(bytes, 0, count)
                }
            }
            require(hex(digest.digest()) == file.sha256) { "seed checksum mismatch: ${file.path}" }
        }
    }

    private fun descriptorFrom(props: Properties): Descriptor? {
        val version = props.getProperty("version") ?: return null
        val build = props.getProperty("build")?.toLongOrNull() ?: return null
        val count = props.getProperty("files.count")?.toIntOrNull()?.takeIf { it in 1..5000 } ?: return null
        val files = (0 until count).map { index ->
            val path = props.getProperty("file.$index.path") ?: return null
            val sha = props.getProperty("file.$index.sha256") ?: return null
            val size = props.getProperty("file.$index.size")?.toLongOrNull() ?: return null
            require(path.isNotBlank() && path != "payload.properties" && !path.startsWith('/') &&
                !path.contains('\\') && !path.contains(':') && !path.contains('\u0000') &&
                path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "unsafe payload path: $path" }
            require(size >= 0 && Regex("[0-9a-f]{64}").matches(sha)) { "invalid payload size or checksum" }
            PayloadFile(path, sha, size)
        }
        require(files.map { it.path }.distinct().size == count) { "duplicate payload path" }
        return Descriptor(version, build, props.getProperty("minShellAbi")?.toIntOrNull(), files, props.getProperty("buildIdentity"))
    }

    private fun load(file: File): Properties? = try {
        file.inputStream().use { input -> Properties().apply { load(input) } }
    } catch (_: IOException) {
        null
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
