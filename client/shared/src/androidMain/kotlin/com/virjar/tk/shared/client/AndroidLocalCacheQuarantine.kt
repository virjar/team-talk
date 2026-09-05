package com.virjar.tk.shared.client

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files

private val ANDROID_SQLITE_SIDECAR_SUFFIXES = listOf("-wal", "-shm", "-journal")
private const val ANDROID_SQLITE_CORRUPTION_MARKER_SUFFIX = ".corruption-reported"
private const val ANDROID_SQLITE_INTEGRITY_MARKER_SUFFIX = ".integrity-checked"
private const val ANDROID_SQLITE_OPEN_MARKER_SUFFIX = ".open"
private const val ANDROID_LOCAL_CACHE_QUICK_CHECK_INTERVAL_MILLIS = 7L * 24L * 60L * 60L * 1_000L

internal data class AndroidLocalCacheLifecycleMarkers(
    val corruption: File,
    val integrity: File,
    val open: File,
)

internal data class AndroidLocalCacheQuarantine(
    val quarantinedMainFile: File,
    val quarantinedFiles: List<File>,
)

internal fun androidLocalCacheLifecycleMarkers(databaseFile: File) = AndroidLocalCacheLifecycleMarkers(
    corruption = File(databaseFile.path + ANDROID_SQLITE_CORRUPTION_MARKER_SUFFIX),
    integrity = File(databaseFile.path + ANDROID_SQLITE_INTEGRITY_MARKER_SUFFIX),
    open = File(databaseFile.path + ANDROID_SQLITE_OPEN_MARKER_SUFFIX),
)

internal fun shouldQuickCheckAndroidLocalCache(
    markers: AndroidLocalCacheLifecycleMarkers,
    nowEpochMs: Long,
): Boolean {
    if (markers.corruption.exists() || markers.open.exists() || !markers.integrity.isFile) return true
    val checkedAt = markers.integrity.lastModified()
    val age = nowEpochMs - checkedAt
    return checkedAt <= 0L || age < 0L || age >= ANDROID_LOCAL_CACHE_QUICK_CHECK_INTERVAL_MILLIS
}

internal fun recordSuccessfulAndroidLocalCacheQuickCheck(marker: File, checkedAtEpochMs: Long) {
    if (!marker.createNewFile() && !marker.isFile) {
        throw IOException("Android local-cache integrity marker is not a regular file")
    }
    // 刷新时间戳失败是无害的：它只会导致下次再做一次完整检查。
    marker.setLastModified(checkedAtEpochMs)
}

/**
 * 只移动一个已经关闭的账号数据库族。副文件先移动，主文件作为提交点，这样提交前的失败可以回滚，而不会让
 * 后续打开者看到一个缺失的主路径。在数据库目录内重命名可以保持字节内容与文件权限不变。
 */
internal fun quarantineAndroidLocalCacheDatabase(
    databaseFile: File,
    quarantineId: String = System.currentTimeMillis().toString(),
    moveFile: (source: File, target: File) -> Unit = ::moveAndroidLocalCacheFile,
): AndroidLocalCacheQuarantine {
    if (!databaseFile.isFile) {
        throw FileNotFoundException("Corrupt Android local-cache main file is missing")
    }
    requireNoRetainedAndroidLocalCacheQuarantine(databaseFile)
    val quarantinedMain = nextAndroidLocalCacheQuarantineMain(databaseFile, quarantineId)
    val markers = androidLocalCacheLifecycleMarkers(databaseFile)
    val moves = buildList {
        ANDROID_SQLITE_SIDECAR_SUFFIXES.forEach { suffix ->
            val source = File(databaseFile.path + suffix)
            if (source.exists()) add(source to File(quarantinedMain.path + suffix))
        }
        addMarkerMove(markers.corruption, quarantinedMain, ANDROID_SQLITE_CORRUPTION_MARKER_SUFFIX)
        addMarkerMove(markers.integrity, quarantinedMain, ANDROID_SQLITE_INTEGRITY_MARKER_SUFFIX)
        addMarkerMove(markers.open, quarantinedMain, ANDROID_SQLITE_OPEN_MARKER_SUFFIX)
        add(databaseFile to quarantinedMain)
    }
    val completed = mutableListOf<Pair<File, File>>()
    try {
        moves.forEach { (source, target) ->
            moveFile(source, target)
            completed += source to target
        }
    } catch (failure: Throwable) {
        completed.asReversed().forEach { (source, target) ->
            try {
                moveFile(target, source)
            } catch (rollbackFailure: Throwable) {
                if (rollbackFailure !== failure) failure.addSuppressed(rollbackFailure)
            }
        }
        throw failure
    }
    return AndroidLocalCacheQuarantine(
        quarantinedMainFile = quarantinedMain,
        quarantinedFiles = moves.map { it.second },
    )
}

private fun MutableList<Pair<File, File>>.addMarkerMove(
    marker: File,
    quarantinedMain: File,
    suffix: String,
) {
    if (marker.exists()) add(marker to File(quarantinedMain.path + suffix))
}

private fun requireNoRetainedAndroidLocalCacheQuarantine(databaseFile: File) {
    val parent = databaseFile.parentFile
        ?: throw IOException("Android local-cache database has no parent directory")
    val prefix = "${databaseFile.name}.corrupt-"
    val entries = parent.listFiles()
        ?: throw IOException("Cannot inspect Android local-cache quarantine directory")
    if (entries.any { it.name.startsWith(prefix) }) {
        throw IOException(
            "An unprocessed Android local-cache quarantine already exists; refusing another copy",
        )
    }
}

private fun nextAndroidLocalCacheQuarantineMain(databaseFile: File, quarantineId: String): File {
    require(quarantineId.isNotBlank() && quarantineId.all { it.isLetterOrDigit() || it == '-' }) {
        "Android local-cache quarantine id is invalid"
    }
    val candidate = File(
        databaseFile.parentFile,
        "${databaseFile.name}.corrupt-$quarantineId",
    )
    if (androidLocalCacheQuarantineFamily(candidate).any(File::exists)) {
        throw IOException("Android local-cache quarantine target already exists")
    }
    return candidate
}

private fun androidLocalCacheQuarantineFamily(mainFile: File): List<File> = buildList {
    add(mainFile)
    ANDROID_SQLITE_SIDECAR_SUFFIXES.forEach { suffix -> add(File(mainFile.path + suffix)) }
    add(File(mainFile.path + ANDROID_SQLITE_CORRUPTION_MARKER_SUFFIX))
    add(File(mainFile.path + ANDROID_SQLITE_INTEGRITY_MARKER_SUFFIX))
    add(File(mainFile.path + ANDROID_SQLITE_OPEN_MARKER_SUFFIX))
}

private fun moveAndroidLocalCacheFile(source: File, target: File) {
    Files.move(source.toPath(), target.toPath())
}
