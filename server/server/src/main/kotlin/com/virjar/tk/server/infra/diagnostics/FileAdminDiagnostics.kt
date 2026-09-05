package com.virjar.tk.server.infra.diagnostics

import com.virjar.tk.server.application.admin.AdminDiagnostics
import com.virjar.tk.server.application.admin.AdminLogFileInfo
import com.virjar.tk.server.application.admin.AdminStorageUsage
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.ArrayDeque
import java.util.TreeSet

internal data class AdminDiagnosticsLimits(
    val serverLogFiles: Int = 256,
    val tailLines: Int = 2_000,
    val tailBytes: Int = 2 * 1024 * 1024,
    val tailChunkBytes: Int = 16 * 1024,
    val storageEntriesPerRoot: Int = 100_000,
) {
    init {
        require(serverLogFiles > 0)
        require(tailLines > 0)
        require(tailBytes > 0)
        require(tailChunkBytes in 1..tailBytes)
        require(storageEntriesPerRoot > 0)
    }
}

/**
 * 管理员诊断 API 的有界文件系统适配器。
 *
 * 目录列表在扫描时只保留其配置的 top K 条目，tail 读取绝不超过
 * [AdminDiagnosticsLimits.tailBytes]，存储大小在固定条目预算后停止。
 */
internal class FileAdminDiagnostics(
    logsRoot: Path,
    rocksDbRoots: List<Path>,
    fileStoreRoots: List<Path>,
    private val limits: AdminDiagnosticsLimits = AdminDiagnosticsLimits(),
) : AdminDiagnostics {
    private val logsRoot = logsRoot.toAbsolutePath().normalize()
    private val rocksDbRoots = rocksDbRoots.map { it.toAbsolutePath().normalize() }
    private val fileStoreRoots = fileStoreRoots.map { it.toAbsolutePath().normalize() }

    override fun storageUsage(): AdminStorageUsage {
        val rocks = measureRoots(rocksDbRoots, limits.storageEntriesPerRoot)
        val files = measureRoots(fileStoreRoots, limits.storageEntriesPerRoot)
        return AdminStorageUsage(
            rocksdbBytes = rocks.bytes,
            fileStoreBytes = files.bytes,
            truncated = rocks.truncated || files.truncated,
        )
    }

    override fun listServerLogs(): List<AdminLogFileInfo> {
        val order = compareByDescending<AdminLogFileInfo> { it.lastModified }.thenBy { it.name }
        val selected = TreeSet(order)
        collectLogFiles(logsRoot, prefix = "", selected)
        collectLogFiles(logsRoot.resolve("traces"), prefix = "traces/", selected)
        return selected.toList()
    }

    override fun readServerLog(name: String, lines: Int): List<String> {
        val relative = parseServerLogName(name)
        val file = resolveExistingFile(logsRoot, relative, "非法日志路径")
        return readTail(file, lines.coerceIn(1, limits.tailLines))
    }

    private fun collectLogFiles(
        directory: Path,
        prefix: String,
        selected: TreeSet<AdminLogFileInfo>,
    ) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return
        Files.newDirectoryStream(directory).use { entries ->
            entries.forEach { entry ->
                val attributes = readAttributesOrNull(entry) ?: return@forEach
                if (!attributes.isRegularFile) return@forEach
                selected += AdminLogFileInfo(
                    name = prefix + entry.fileName.toString(),
                    sizeBytes = attributes.size(),
                    lastModified = attributes.lastModifiedTime().toMillis(),
                )
                if (selected.size > limits.serverLogFiles) selected.pollLast()
            }
        }
    }

    private fun readAttributesOrNull(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: IOException) {
        // 日志可能在目录枚举与元数据查找之间发生轮转。
        null
    }

    private fun parseServerLogName(name: String): Path {
        val relative = try {
            Path.of(name)
        } catch (_: InvalidPathException) {
            throw IllegalArgumentException("非法日志路径")
        }
        require(!relative.isAbsolute) { "非法日志路径" }
        val allowed = relative.nameCount == 1 ||
            (relative.nameCount == 2 && relative.getName(0).toString() == "traces")
        require(allowed) { "非法日志路径" }
        return relative
    }

    private fun resolveExistingFile(root: Path, relative: Path, invalidMessage: String): Path {
        try {
            val rootReal = root.toRealPath()
            val candidate = root.resolve(relative).normalize()
            require(candidate.startsWith(root)) { invalidMessage }
            val real = candidate.toRealPath()
            require(real != rootReal && real.startsWith(rootReal)) { invalidMessage }
            require(Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) { invalidMessage }
            return real
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (_: IOException) {
            throw IllegalArgumentException("日志不存在")
        } catch (_: SecurityException) {
            throw IllegalArgumentException(invalidMessage)
        }
    }

    private fun readTail(file: Path, requestedLines: Int): List<String> {
        val chunks = ArrayDeque<ByteArray>()
        var startsAtFileBeginning = true
        FileChannel.open(file, StandardOpenOption.READ).use { channel ->
            var position = channel.size()
            var remainingBytes = limits.tailBytes
            var newlineCount = 0
            while (position > 0L && remainingBytes > 0 && newlineCount <= requestedLines) {
                val chunkSize = minOf(limits.tailChunkBytes.toLong(), position, remainingBytes.toLong()).toInt()
                position -= chunkSize
                val bytes = ByteArray(chunkSize)
                val buffer = ByteBuffer.wrap(bytes)
                var offset = position
                while (buffer.hasRemaining()) {
                    val read = channel.read(buffer, offset)
                    if (read <= 0) break
                    offset += read
                }
                val actual = if (buffer.position() == bytes.size) bytes else bytes.copyOf(buffer.position())
                chunks.addFirst(actual)
                newlineCount += actual.count { it == NEWLINE }
                remainingBytes -= actual.size
            }
            startsAtFileBeginning = position == 0L
        }
        if (chunks.isEmpty()) return emptyList()
        val combined = ByteArray(chunks.sumOf { it.size })
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(combined, destinationOffset = offset)
            offset += chunk.size
        }
        val visibleBytes = if (startsAtFileBeginning) {
            combined
        } else {
            val firstNewline = combined.indexOf(NEWLINE)
            if (firstNewline < 0) return emptyList()
            combined.copyOfRange(firstNewline + 1, combined.size)
        }
        if (visibleBytes.isEmpty()) return emptyList()
        val text = visibleBytes.decodeToString()
        val split = text.split('\n')
        val completeLines = if (text.endsWith('\n')) split.dropLast(1) else split
        return completeLines.takeLast(requestedLines).map { it.removeSuffix("\r") }
    }

    private companion object {
        const val NEWLINE: Byte = 0x0A
    }
}

internal data class BoundedDirectorySize(
    val bytes: Long,
    val visitedEntries: Int,
    val truncated: Boolean,
)

internal fun measureDirectorySize(root: Path, entryBudget: Int): BoundedDirectorySize {
    require(entryBudget > 0) { "Directory entry budget must be positive" }
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return BoundedDirectorySize(0L, 0, false)
    var bytes = 0L
    var visited = 0
    var truncated = false

    fun admitEntry(): Boolean {
        if (visited >= entryBudget) {
            truncated = true
            return false
        }
        visited += 1
        return true
    }

    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (dir == root) return FileVisitResult.CONTINUE
            return if (admitEntry()) FileVisitResult.CONTINUE else FileVisitResult.TERMINATE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (!admitEntry()) return FileVisitResult.TERMINATE
            if (attrs.isRegularFile) {
                bytes = if (Long.MAX_VALUE - bytes < attrs.size()) Long.MAX_VALUE else bytes + attrs.size()
            }
            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, error: IOException): FileVisitResult {
            if (!admitEntry()) return FileVisitResult.TERMINATE
            truncated = true
            return FileVisitResult.CONTINUE
        }
    })
    return BoundedDirectorySize(bytes, visited, truncated)
}

private fun measureRoots(roots: List<Path>, entryBudget: Int): BoundedDirectorySize {
    var bytes = 0L
    var visited = 0
    var truncated = false
    roots.forEach { root ->
        val measurement = measureDirectorySize(root, entryBudget)
        bytes = if (Long.MAX_VALUE - bytes < measurement.bytes) Long.MAX_VALUE else bytes + measurement.bytes
        visited = if (Int.MAX_VALUE - visited < measurement.visitedEntries) {
            Int.MAX_VALUE
        } else {
            visited + measurement.visitedEntries
        }
        truncated = truncated || measurement.truncated
    }
    return BoundedDirectorySize(bytes, visited, truncated)
}
