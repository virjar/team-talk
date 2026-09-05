package com.virjar.tk.server.infra.storage

import kotlinx.serialization.Serializable

/** FileStore 协调器与两个存储层共享的持久元数据权威源。 */
@Serializable
data class FileMetadata(
    val path: String,
    val originalName: String,
    val contentType: String,
    val size: Long,
    val tier: StorageTier,
    val storageKey: String,
    val uploadedAt: Long,
    val uid: String,
    val lifecycle: FileMetadataLifecycle,
    /** 首次成功发布到业务对象；仅上传暂存阶段为 null。 */
    val businessBoundAt: Long? = null,
    /** 持久上传回执拥有者；直接存储调用与已过期回执为 null。 */
    val uploadTransactionKey: String? = null,
    /** 区分活跃上传尝试与复用同一 upload id 的过期句柄。 */
    val uploadAttemptToken: String? = null,
    /** [uploadAttemptToken] 内稳定的主文件/缩略图顺序。 */
    val uploadObjectIndex: Int? = null,
)

enum class StorageTier { ROCKSDB, FILESYSTEM }

@Serializable
enum class FileMetadataLifecycle { PENDING_CREATE, ACTIVE, PENDING_DELETE }

data class ReadRange(val start: Long, val end: Long) {
    init {
        require(start >= 0L) { "Read range start must not be negative" }
        require(end >= start) { "Read range end must not precede its start" }
        require(end < Long.MAX_VALUE) { "Read range end cannot be represented as an exclusive bound" }
    }
}

internal data class BoundedReadSlice(
    val offset: Long,
    val length: Long,
)

/** 把闭区间请求范围截断到一个不可变对象的 `[0, size)` 字节区间。 */
internal fun ReadRange?.boundedReadSlice(size: Long): BoundedReadSlice {
    require(size >= 0L) { "Stored object size must not be negative" }
    if (this == null) return BoundedReadSlice(offset = 0L, length = size)
    val boundedOffset = minOf(start, size)
    val exclusiveEnd = minOf(end + 1L, size)
    return BoundedReadSlice(
        offset = boundedOffset,
        length = maxOf(0L, exclusiveEnd - boundedOffset),
    )
}
