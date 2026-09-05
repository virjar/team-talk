package com.virjar.tk.shared.client

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal data class JvmLocalCacheQuarantine(
    val quarantinedUserDirectory: File,
)

/**
 * 在原路径旁原子保留精确的已关闭 `users/<uid>` 命名空间。
 *
 * Desktop 的 uid 目录只归本地缓存工厂所有，因此一次目录重命名让主 DB、WAL/SHM/journal、生命周期
 * 标记与更旧 epoch 保持在一起。隔离名包含一个点，因此不会与合法 uid 冲突。在显式恢复或丢弃工具
 * 处理第一个之前，拒绝保留第二份副本。
 */
internal fun quarantineJvmLocalCacheUserDirectory(
    userDirectory: File,
    quarantineId: String = System.currentTimeMillis().toString(),
): JvmLocalCacheQuarantine {
    if (!userDirectory.isDirectory) {
        throw FileNotFoundException("Corrupt JVM local-cache user directory is missing")
    }
    require(quarantineId.isNotBlank() && quarantineId.all { it.isLetterOrDigit() || it == '-' }) {
        "JVM local-cache quarantine id is invalid"
    }
    val parent = userDirectory.parentFile
        ?: throw IOException("JVM local-cache user directory has no parent")
    val prefix = "${userDirectory.name}.corrupt-"
    val entries = parent.listFiles()
        ?: throw IOException("Cannot inspect JVM local-cache users directory")
    if (entries.any { it.name.startsWith(prefix) }) {
        throw IOException("An unprocessed JVM local-cache quarantine already exists")
    }
    val target = File(parent, prefix + quarantineId)
    if (target.exists()) throw IOException("JVM local-cache quarantine target already exists")
    Files.move(userDirectory.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    return JvmLocalCacheQuarantine(target)
}
