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
 * 在原路径旁原子移出精确的已关闭 `users/<uid>` 命名空间，为重建腾出规范路径。
 *
 * 服务器是唯一可靠信息源：损坏旧库在替换库验证健康后即被删除，不保留待处置副本。
 * 隔离名包含一个点，因此不会与合法 uid 冲突；上一次恢复中途崩溃残留的旧隔离族
 * 在移出前先被尽力清扫，不会阻塞本次重建。
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
    val parent = checkNotNull(userDirectory.parentFile)
    sweepJvmLocalCacheQuarantines(parent)
    val target = File(parent, "${userDirectory.name}.corrupt-$quarantineId")
    if (target.exists()) throw IOException("JVM local-cache quarantine target already exists")
    Files.move(userDirectory.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    return JvmLocalCacheQuarantine(target)
}

/** 替换库验证健康后删除隔离目录；失败只记录，残留由下一次恢复清扫。 */
internal fun deleteJvmLocalCacheQuarantine(quarantine: JvmLocalCacheQuarantine): Exception? = try {
    quarantine.quarantinedUserDirectory.deleteRecursively()
    null
} catch (failure: Exception) {
    failure
}

/** 尽力删除历史恢复残留的隔离族；它们是不再被任何打开路径引用的弃用数据。 */
internal fun sweepJvmLocalCacheQuarantines(parent: File): List<Exception> {
    val failures = mutableListOf<Exception>()
    parent.listFiles()?.forEach { entry ->
        if (!entry.name.contains(".corrupt-")) return@forEach
        try {
            if (!entry.deleteRecursively()) {
                failures += IOException("JVM local-cache quarantine leftover could not be deleted: ${entry.name}")
            }
        } catch (failure: Exception) {
            failures += failure
        }
    }
    return failures
}
