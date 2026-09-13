package com.virjar.tk.server.infra.storage

import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * 客户端发布物的内容寻址文件仓：`data/release-store/<sha前两位>/<sha>`。
 *
 * 与普通附件 FileStore 完全隔离——发布物没有配额与 TTL 回收，只能经管理端
 * 删除发布索引不会同步删除对象，避免与导入复用竞态；相同内容跨版本天然去重。
 * 写入路径：先落同目录临时文件再原子 rename，崩溃只残留 tmp 文件。
 */
internal class ReleaseStore(rootDir: File) {

    private val root: File = rootDir.absoluteFile.apply { mkdirs() }
    private val tmpDir: File = File(root, "tmp").apply { mkdirs() }

    class StoredObject(val sha256: String, val size: Long)

    /** 校验摘要并返回对象文件；不存在或参数非法返回 null。 */
    fun resolve(sha256: String): File? {
        if (!SHA256_HEX.matches(sha256)) return null
        val file = objectFile(sha256)
        return file.takeIf { it.isFile }
    }

    /** 流式写入临时文件、摘要校验后原子落位；同一内容重复写入是幂等的。 */
    @Synchronized
    fun store(input: java.io.InputStream): StoredObject {
        val tmp = File.createTempFile("release-", ".tmp", tmpDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            tmp.outputStream().buffered().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    size += read
                }
            }
            val sha = hex(digest.digest())
            val target = objectFile(sha)
            if (target.isFile) {
                // 已有同内容对象：保留原文件，丢弃临时副本。
                if (!tmp.delete()) tmp.deleteOnExit()
            } else {
                target.parentFile!!.mkdirs()
                java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            }
            return StoredObject(sha, size)
        } finally {
            tmp.delete()
        }
    }

    private fun objectFile(sha256: String): File = File(File(root, sha256.substring(0, 2)), sha256)

    private fun hex(bytes: ByteArray): String = buildString {
        for (b in bytes) {
            append(String.format(Locale.ROOT, "%02x", b))
        }
    }

    companion object {
        private val SHA256_HEX = Regex("[0-9a-f]{64}")

    }
}
