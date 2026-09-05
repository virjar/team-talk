package com.virjar.tk.server.infra

import com.virjar.tk.protocol.payload.SyncDatasetIdPolicy
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 所有非 PostgreSQL 持久化服务器数据的快速失败守卫。
 *
 * PostgreSQL、消息、索引与文件属于同一个数据集。存储版本独立于发布版本和网络协商，
 * 不能为了重新编号或提高最低客户端版本而修改现有标记。
 * 不匹配时保留原数据并拒绝启动，等待显式迁移或恢复；这里从不自动清空数据。
 */
object ServerDataEpoch {
    const val CURRENT_EPOCH = 1

    private const val EPOCH_MARKER_FILE = "data-epoch"
    private const val DATASET_MARKER_FILE = "dataset-id"
    private val durableRelativePaths = listOf(
        "rocksdb",
        "lucene-index",
        "client-telemetry-index",
        "connection-trace-index",
        "file-store/rocksdb",
        "file-store/files",
    )
    /** 已移除的存储必须由明确的迁移处理；新运行时不解释或删除这些字节。 */
    private val legacyRejectedPaths = listOf("tokenstore", "client-logs")

    fun initializeOrValidate(dataRoot: File) {
        dataRoot.mkdirs()
        val legacyData = legacyRejectedPaths
            .map { File(dataRoot, it) }
            .filter { it.containsEntries() }
            .map { it.relativeTo(dataRoot).path }
        if (legacyData.isNotEmpty()) {
            throw DataResetRequiredException(
                "Removed legacy storage is still present; preserve the data and complete an explicit " +
                    "migration before startup (found: ${legacyData.joinToString()})",
            )
        }
        val marker = File(dataRoot, EPOCH_MARKER_FILE)
        if (marker.exists()) {
            val actual = marker.readMarker("Server data epoch").toIntOrNull()
                ?: throw DataResetRequiredException("Server data epoch marker is invalid: ${marker.absolutePath}")
            if (actual != CURRENT_EPOCH) {
                throw DataResetRequiredException(
                    "Server data epoch $actual is incompatible with required epoch $CURRENT_EPOCH; " +
                        "preserve the data and use a compatible release or an explicit migration",
                )
            }
            return
        }

        val populated = durableRelativePaths
            .map { File(dataRoot, it) }
            .filter { it.containsEntries() }
            .map { it.relativeTo(dataRoot).path }
        if (populated.isNotEmpty()) {
            throw DataResetRequiredException(
                "Server data predates epoch $CURRENT_EPOCH; preserve the data and migrate its storage " +
                    "metadata before startup (found: ${populated.joinToString()})",
            )
        }

        writeMarkerAtomically(marker, CURRENT_EPOCH.toString())
    }

    /**
     * 在打开之前，将每个非 PostgreSQL 持久化存储绑定到确切的 PostgreSQL 数据集。
     *
     * 只有在每个受守卫的本地存储都为空时，缺失标记才是可恢复的。这覆盖了
     * 在全新 PostgreSQL schema 提交之后、标记发布之前发生崩溃的情况，
     * 而绝不会为现有的 RocksDB、Lucene 或附件字节猜测所有权。不匹配则要求
     * 显式地在服务器停止状态下恢复匹配的数据集；启动绝不能自行轮换身份。
     */
    fun bindOrValidateDataset(dataRoot: File, datasetId: String) {
        try {
            SyncDatasetIdPolicy.requireValid(datasetId)
        } catch (_: IllegalArgumentException) {
            throw DataResetRequiredException("PostgreSQL dataset identity is invalid")
        }
        val epochMarker = File(dataRoot, EPOCH_MARKER_FILE)
        val epoch = if (epochMarker.exists()) {
            epochMarker.readMarker("Server data epoch").toIntOrNull()
        } else {
            null
        }
        if (epoch != CURRENT_EPOCH) {
            throw DataResetRequiredException(
                "Server data epoch must be initialized before binding its dataset identity",
            )
        }

        val marker = File(dataRoot, DATASET_MARKER_FILE)
        if (marker.exists()) {
            val actual = marker.readMarker("Server dataset identity")
            try {
                SyncDatasetIdPolicy.requireValid(actual)
            } catch (_: IllegalArgumentException) {
                throw DataResetRequiredException("Server dataset identity marker is invalid")
            }
            if (actual != datasetId) {
                throw DataResetRequiredException(
                    "PostgreSQL and local durable data belong to different server datasets; " +
                        "preserve both sides and restore the matching dataset while the server is stopped",
                )
            }
            return
        }

        val populated = populatedDurablePaths(dataRoot)
        if (populated.isNotEmpty()) {
            throw DataResetRequiredException(
                "Server durable data has no dataset identity; preserve the data and recover its verified " +
                    "dataset metadata before startup (found: ${populated.joinToString()})",
            )
        }
        writeMarkerAtomically(marker, datasetId)
    }

    private fun populatedDurablePaths(dataRoot: File): List<String> = durableRelativePaths
        .map { File(dataRoot, it) }
        .filter { it.containsEntries() }
        .map { it.relativeTo(dataRoot).path }

    private fun File.readMarker(label: String): String {
        if (!isFile) throw DataResetRequiredException("$label marker is not a regular file: $absolutePath")
        return try {
            readText().trim()
        } catch (failure: java.io.IOException) {
            throw DataResetRequiredException("$label marker cannot be read: $absolutePath").also {
                it.initCause(failure)
            }
        }
    }

    private fun writeMarkerAtomically(marker: File, value: String) {
        val pending = File(marker.parentFile, "${marker.name}.tmp-${ProcessHandle.current().pid()}")
        try {
            pending.writeText("$value\n")
            try {
                Files.move(
                    pending.toPath(),
                    marker.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(pending.toPath(), marker.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            pending.delete()
        }
    }

    private fun File.containsEntries(): Boolean = isDirectory && !list().isNullOrEmpty()
}

class DataResetRequiredException(message: String) : IllegalStateException(message)
