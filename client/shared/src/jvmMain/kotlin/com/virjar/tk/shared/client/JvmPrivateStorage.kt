package com.virjar.tk.shared.client

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * 一个 JVM 数据根，其叶子与已认证子目录对一个操作系统 owner 私有。
 *
 * 现有路径只被检查；其 owner 或权限绝不被修复。新路径用精确 POSIX mode 或精确 owner-only
 * Windows ACL 创建。这让 Desktop 凭据、SQLite 与崩溃状态处于一个失败关闭路径策略之后，而不是
 * 几个微妙不同的 `mkdirs`/`FileOutputStream` 实现之后。
 */
class JvmPrivateDataDirectory private constructor(
    val root: Path,
    private val security: JvmPrivatePathSecurity,
) {
    /** 在一个可信、已存在的 owner 锚点之下创建或校验私有叶子。 */
    companion object {
        fun openOrCreate(dataDir: File, ownerAnchor: File): JvmPrivateDataDirectory =
            open(dataDir, ownerAnchor, requireNew = false)

        /** 原子认领一个新私有叶子；现有路径绝不被采纳。 */
        fun createNew(dataDir: File, ownerAnchor: File): JvmPrivateDataDirectory =
            open(dataDir, ownerAnchor, requireNew = true)

        private fun open(
            dataDir: File,
            ownerAnchor: File,
            requireNew: Boolean,
        ): JvmPrivateDataDirectory {
            val root = dataDir.toPath().toAbsolutePath().normalize()
            require(root.parent != null) { "Private data directory cannot be a filesystem root" }
            val anchor = ownerAnchor.toPath().toAbsolutePath().normalize()
            val anchorAttributes = basicAttributes(anchor)
            requireRealDirectory(anchorAttributes, "Private data owner anchor")
            val expectedOwner = Files.getOwner(anchor, LinkOption.NOFOLLOW_LINKS)

            val parent = requireNotNull(root.parent)
            val parentAttributes = basicAttributes(parent)
            requireRealDirectory(parentAttributes, "Private data directory parent")
            requireSameOwner(parent, expectedOwner, "Private data directory parent")
            requireSafeOwnerChain(anchor, parent, expectedOwner)
            val security = JvmPrivatePathSecurity.forPath(parent, expectedOwner)

            var created = false
            if (requireNew || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                security.createDirectory(root)
                created = true
                security.forceDirectory(parent)
            }
            return try {
                security.requirePrivateDirectory(root)
                JvmPrivateDataDirectory(root.toRealPath(LinkOption.NOFOLLOW_LINKS), security)
            } catch (failure: Throwable) {
                var terminalFailure = failure
                if (created) {
                    try {
                        Files.deleteIfExists(root)
                    } catch (cleanupFailure: Throwable) {
                        terminalFailure = mergeSessionLifecycleFailures(terminalFailure, cleanupFailure)
                    }
                }
                throw terminalFailure
            }
        }

        /**
         * 打开一个调用方选定的、已经私有的根。其 owner 成为每个子目录的不可变 owner。Desktop 启动
         * 在发布路径之前使用上面的带锚点重载；该重载也支持 SDK 测试与单独校验的无头根。
         */
        fun openExisting(dataDir: File): JvmPrivateDataDirectory {
            val root = dataDir.toPath().toAbsolutePath().normalize()
            val attributes = basicAttributes(root)
            requireRealDirectory(attributes, "Private data directory")
            val expectedOwner = Files.getOwner(root, LinkOption.NOFOLLOW_LINKS)
            val security = JvmPrivatePathSecurity.forPath(root, expectedOwner)
            security.requirePrivateDirectory(root)
            return JvmPrivateDataDirectory(root.toRealPath(LinkOption.NOFOLLOW_LINKS), security)
        }

        /** 针对单独的当前用户锚点校验现有根。 */
        fun openExisting(dataDir: File, ownerAnchor: File): JvmPrivateDataDirectory {
            val root = dataDir.toPath().toAbsolutePath().normalize()
            val anchor = ownerAnchor.toPath().toAbsolutePath().normalize()
            val anchorAttributes = basicAttributes(anchor)
            requireRealDirectory(anchorAttributes, "Private data owner anchor")
            val expectedOwner = Files.getOwner(anchor, LinkOption.NOFOLLOW_LINKS)
            root.parent?.takeIf { it.startsWith(anchor) }?.let { parent ->
                requireSafeOwnerChain(anchor, parent, expectedOwner)
            }
            val security = JvmPrivatePathSecurity.forPath(root, expectedOwner)
            security.requirePrivateDirectory(root)
            return JvmPrivateDataDirectory(root.toRealPath(LinkOption.NOFOLLOW_LINKS), security)
        }
    }

    fun ensureDirectory(vararg components: String): File =
        ensureDirectory(components.asList()).toFile()

    fun atomicTextFile(
        privateDirectories: List<String> = emptyList(),
        fileName: String,
        replacementTemporaryFileName: String? = null,
    ): JvmPrivateAtomicTextFile {
        requireSafeComponent(fileName)
        return JvmPrivateAtomicTextFile(
            this,
            privateDirectories,
            fileName,
            replacementTemporaryFileName,
        )
    }

    /**
     * 在把文件交给 SQLite 之类的库之前，预创建 0600/owner-only 常规文件。
     * 因此库绝没有机会选择一个依赖进程 umask 的 mode。
     */
    fun preparePrivateFile(privateDirectories: List<String>, fileName: String): File {
        requireSafeComponent(fileName)
        val directory = ensureDirectory(privateDirectories)
        val target = directory.resolve(fileName)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            try {
                security.createEmptyFile(target)
                security.forceDirectory(directory)
            } catch (_: FileAlreadyExistsException) {
                // 竞争路径在下方被校验，绝不被静默采纳。
            }
        }
        security.requirePrivateFile(target)
        return target.toFile()
    }

    fun requirePrivateFile(privateDirectories: List<String>, fileName: String): File {
        requireSafeComponent(fileName)
        val directory = ensureDirectory(privateDirectories, create = false)
            ?: error("Private namespace does not exist")
        val target = directory.resolve(fileName)
        security.requirePrivateFile(target)
        return target.toFile()
    }

    /**
     * 列出恰好一个私有命名空间，而不跟随链接或接受嵌套目录。
     *
     * 内容寻址存储在原子发布新清单之后用它收集过时的不可变记录。只返回已校验的常规文件，让清理
     * 与读取和原子替换处于同一 owner/链接边界。
     */
    fun listPrivateFileNames(privateDirectories: List<String>): Set<String> {
        val directory = ensureDirectory(privateDirectories, create = false) ?: return emptySet()
        return useResourcePreservingFatalFailure(Files.newDirectoryStream(directory)) { children ->
            children.mapTo(linkedSetOf()) { child ->
                val name = child.fileName.toString()
                requireSafeComponent(name)
                security.requirePrivateFile(child)
                name
            }
        }
    }

    /** 在外部发布之前递归重新校验一棵完整的私有树。 */
    fun validatePrivateTree() {
        security.requirePrivateDirectory(root)
        useResourcePreservingFatalFailure(Files.newDirectoryStream(root)) { children ->
            children.forEach(::validatePrivateNode)
        }
    }

    internal fun ensureDirectory(components: List<String>): Path =
        checkNotNull(ensureDirectory(components, create = true))

    internal fun security(): JvmPrivatePathSecurity = security

    private fun ensureDirectory(components: List<String>, create: Boolean): Path? {
        components.forEach(::requireSafeComponent)
        security.requirePrivateDirectory(root)
        var directory = root
        components.forEach { component ->
            val child = directory.resolve(component)
            if (!Files.exists(child, LinkOption.NOFOLLOW_LINKS)) {
                if (!create) return null
                try {
                    security.createDirectory(child)
                    security.forceDirectory(directory)
                } catch (_: FileAlreadyExistsException) {
                    // 在下方校验；一个竞争产生的不安全路径绝不会被采纳。
                }
            }
            security.requirePrivateDirectory(child)
            directory = child
        }
        return directory
    }

    private fun validatePrivateNode(path: Path) {
        val attributes = basicAttributes(path)
        when {
            attributes.isDirectory && !attributes.isSymbolicLink && !attributes.isOther -> {
                security.requirePrivateDirectory(path)
                useResourcePreservingFatalFailure(Files.newDirectoryStream(path)) { children ->
                    children.forEach(::validatePrivateNode)
                }
            }
            attributes.isRegularFile && !attributes.isSymbolicLink && !attributes.isOther ->
                security.requirePrivateFile(path)
            else -> error("Private tree contains a link or unsupported path: $path")
        }
    }

}

/** [JvmPrivateDataDirectory] 中的一个原子、有界文本载荷。 */
class JvmPrivateAtomicTextFile internal constructor(
    private val dataDirectory: JvmPrivateDataDirectory,
    private val privateDirectories: List<String>,
    private val fileName: String,
    private val replacementTemporaryFileName: String?,
) {
    init {
        privateDirectories.forEach(::requireSafeComponent)
        requireSafeComponent(fileName)
        replacementTemporaryFileName?.let { temporary ->
            requireSafeComponent(temporary)
            require(temporary != fileName) { "Replacement temporary file must differ from its target" }
        }
    }

    fun existsNonEmpty(): Boolean {
        val directory = openNamespace(create = false) ?: return false
        val target = directory.resolve(fileName)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return false
        return dataDirectory.security().requirePrivateFile(target).size() > 0L
    }

    fun readText(maxBytes: Long = DEFAULT_MAX_TEXT_BYTES): String? {
        require(maxBytes in 1L..Int.MAX_VALUE.toLong()) { "Invalid private text size limit" }
        val directory = openNamespace(create = false) ?: return null
        val target = directory.resolve(fileName)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return null
        val expected = dataDirectory.security().requirePrivateFile(target)
        require(expected.size() <= maxBytes) { "Private text file is too large" }
        val bytes = useResourcePreservingFatalFailure(FileChannel.open(target, PRIVATE_READ_OPTIONS)) { channel ->
            val size = channel.size()
            require(size == expected.size() && size <= maxBytes) {
                "Private text file changed before read"
            }
            val buffer = ByteBuffer.allocate(size.toInt())
            while (buffer.hasRemaining()) {
                require(channel.read(buffer) >= 0) { "Private text file changed during read" }
            }
            buffer.array()
        }
        val after = dataDirectory.security().requirePrivateFile(target)
        require(
            expected.fileKey() != null && expected.fileKey() == after.fileKey() &&
                expected.size() == after.size() && expected.lastModifiedTime() == after.lastModifiedTime(),
        ) { "Private text file changed during read" }
        return bytes.decodeToString()
    }

    fun replaceText(content: String, maxBytes: Long = DEFAULT_MAX_TEXT_BYTES) {
        val bytes = content.encodeToByteArray()
        require(bytes.size.toLong() <= maxBytes) { "Private text content is too large" }
        val directory = checkNotNull(openNamespace(create = true))
        val target = directory.resolve(fileName)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            dataDirectory.security().requirePrivateFile(target)
        }
        val temporary = replacementTemporaryFileName?.let { temporaryFileName ->
            cleanupPendingReplacement()
            directory.resolve(temporaryFileName).also { pending ->
                dataDirectory.security().createEmptyFile(pending)
                dataDirectory.security().forceDirectory(directory)
            }
        } ?: dataDirectory.security().createTempFile(directory, ".$fileName-", ".tmp")
        var installed = false
        var operationFailure: Throwable? = null
        try {
            useResourcePreservingFatalFailure(FileChannel.open(temporary, PRIVATE_WRITE_OPTIONS)) { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            dataDirectory.security().requirePrivateFile(temporary)
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                dataDirectory.security().requirePrivateFile(target)
            }
            moveAtomically(temporary, target, replaceExisting = true)
            dataDirectory.security().requirePrivateFile(target)
            dataDirectory.security().forceDirectory(directory)
            installed = true
        } catch (failure: Throwable) {
            operationFailure = failure
            throw failure
        } finally {
            try {
                if (!installed || Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(temporary)
                }
            } catch (cleanupFailure: Throwable) {
                val terminal = mergeSessionLifecycleFailures(operationFailure, cleanupFailure)
                if (operationFailure == null || terminal !== operationFailure) throw terminal
            }
        }
    }

    fun cleanupPendingReplacement(): Boolean {
        val temporaryFileName = replacementTemporaryFileName ?: return false
        val directory = openNamespace(create = false) ?: return false
        val pending = directory.resolve(temporaryFileName)
        if (!Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) return false
        dataDirectory.security().requirePrivateFile(pending)
        Files.delete(pending)
        dataDirectory.security().forceDirectory(directory)
        return true
    }

    fun delete(): Boolean {
        val directory = openNamespace(create = false) ?: return false
        val target = directory.resolve(fileName)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return false
        dataDirectory.security().requirePrivateFile(target)
        Files.delete(target)
        dataDirectory.security().forceDirectory(directory)
        return true
    }

    private fun openNamespace(create: Boolean): Path? = if (create) {
        dataDirectory.ensureDirectory(privateDirectories)
    } else {
        var directory = dataDirectory.root
        dataDirectory.security().requirePrivateDirectory(directory)
        for (component in privateDirectories) {
            val child = directory.resolve(component)
            if (!Files.exists(child, LinkOption.NOFOLLOW_LINKS)) return null
            dataDirectory.security().requirePrivateDirectory(child)
            directory = child
        }
        directory
    }
}

private fun requireSafeComponent(component: String) {
    require(
        component.isNotBlank() && component != "." && component != ".." &&
            component.none { it == '/' || it == '\\' || it.isISOControl() },
    ) { "Unsafe private path component" }
}

private fun moveAtomically(source: Path, target: Path, replaceExisting: Boolean) {
    val options = buildList {
        add(StandardCopyOption.ATOMIC_MOVE)
        if (replaceExisting) add(StandardCopyOption.REPLACE_EXISTING)
    }.toTypedArray()
    try {
        Files.move(source, target, *options)
    } catch (unsupported: AtomicMoveNotSupportedException) {
        throw IllegalStateException("Private storage requires atomic replacement", unsupported)
    }
}

private val PRIVATE_READ_OPTIONS: Set<OpenOption> = setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
private val PRIVATE_WRITE_OPTIONS: Set<OpenOption> = setOf(
    StandardOpenOption.WRITE,
    StandardOpenOption.TRUNCATE_EXISTING,
    LinkOption.NOFOLLOW_LINKS,
)
private const val DEFAULT_MAX_TEXT_BYTES = 1024L * 1024L
