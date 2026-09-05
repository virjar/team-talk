package com.virjar.tk.server.domain.groupfile

/**
 * 一个群文件聚合的硬性活跃行与字节预算。
 *
 * PostgreSQL 适配器在持有所属 Chat 行锁时评估写入准入。已删除条目释放其条目、同级、版本
 * 与字节槽位，因为它们的不可变版本都不再是活跃下载引用。读取投影只取一行溢出探针并
 * 默认拒绝（fail closed），而不是悄悄截断本不可能通过写入边界的数据。
 */
data class GroupFileCapacityPolicy(
    val maxTotalVersionBytesPerChat: Long = DEFAULT_MAX_TOTAL_VERSION_BYTES_PER_CHAT,
    val maxActiveEntriesPerChat: Int = DEFAULT_MAX_ACTIVE_ENTRIES_PER_CHAT,
    val maxDirectChildrenPerParent: Int = DEFAULT_MAX_DIRECT_CHILDREN_PER_PARENT,
    val maxActiveVersionsPerFile: Int = DEFAULT_MAX_ACTIVE_VERSIONS_PER_FILE,
) {
    init {
        require(maxTotalVersionBytesPerChat >= 0) { "群文件字节配额不能为负数" }
        require(maxActiveEntriesPerChat > 0) { "群文件活动条目上限必须大于 0" }
        require(maxDirectChildrenPerParent > 0) { "群文件同级条目上限必须大于 0" }
        require(maxActiveVersionsPerFile > 0) { "群文件版本上限必须大于 0" }
        require(maxDirectChildrenPerParent < Int.MAX_VALUE) { "群文件同级条目上限过大" }
        require(maxActiveVersionsPerFile < Int.MAX_VALUE) { "群文件版本上限过大" }
    }

    val directChildrenOverflowProbeLimit: Int = maxDirectChildrenPerParent + 1
    val activeVersionsOverflowProbeLimit: Int = maxActiveVersionsPerFile + 1

    fun requireEntrySlot(activeEntryCount: Long) {
        require(activeEntryCount in 0L until maxActiveEntriesPerChat.toLong()) {
            "每个群最多只能包含 $maxActiveEntriesPerChat 个活动群文件条目"
        }
    }

    fun requireDirectChildSlot(activeDirectChildCount: Long) {
        require(activeDirectChildCount in 0L until maxDirectChildrenPerParent.toLong()) {
            "同一群文件目录最多只能包含 $maxDirectChildrenPerParent 个活动条目"
        }
    }

    fun requireVersionSlot(activeVersionCount: Long) {
        require(activeVersionCount in 0L until maxActiveVersionsPerFile.toLong()) {
            "每个活动群文件最多只能保留 $maxActiveVersionsPerFile 个版本"
        }
    }

    fun requireByteSlot(usedBytes: Long, incomingBytes: Long) {
        require(usedBytes >= 0 && incomingBytes >= 0) { "群文件容量计数异常" }
        require(
            usedBytes <= maxTotalVersionBytesPerChat &&
                incomingBytes <= maxTotalVersionBytesPerChat - usedBytes,
        ) {
            "群文件空间已超出配额（${maxTotalVersionBytesPerChat / 1024 / 1024} MiB）"
        }
    }

    fun requireDirectChildrenProjection(actualCount: Int) {
        require(actualCount <= maxDirectChildrenPerParent) {
            "同一群文件目录的活动条目数量超过容量上限 $maxDirectChildrenPerParent"
        }
    }

    fun requireVersionsProjection(actualCount: Int) {
        require(actualCount <= maxActiveVersionsPerFile) {
            "活动群文件的版本数量超过容量上限 $maxActiveVersionsPerFile"
        }
    }

    companion object {
        const val DEFAULT_MAX_TOTAL_VERSION_BYTES_PER_CHAT: Long = 1024L * 1024 * 1024
        const val DEFAULT_MAX_ACTIVE_ENTRIES_PER_CHAT: Int = 10_000
        const val DEFAULT_MAX_DIRECT_CHILDREN_PER_PARENT: Int = 512
        const val DEFAULT_MAX_ACTIVE_VERSIONS_PER_FILE: Int = 128
    }
}
