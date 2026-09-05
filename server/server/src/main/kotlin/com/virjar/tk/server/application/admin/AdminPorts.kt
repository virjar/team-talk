package com.virjar.tk.server.application.admin

import com.virjar.tk.server.domain.message.MAX_MESSAGE_SEARCH_COLLECTION_WINDOW
import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.User
import kotlinx.serialization.Serializable

@Serializable
data class AdminPage<T>(val total: Long, val items: List<T>)

/** 经过校验的管理员分页参数，供 HTTP、应用用例和持久化适配器共用。 */
data class AdminPageRequest(
    val page: Int,
    val size: Int,
) {
    init {
        require(page >= 1) { "page must be at least 1" }
        require(size in 1..MAX_SIZE) { "size must be between 1 and $MAX_SIZE" }
    }

    /** 数据库偏移量取 Long；乘法做饱和处理，即使将来输入类型变宽也不会溢出。 */
    val offset: Long = saturatedMultiply(page.toLong() - 1L, size.toLong())

    /**
     * Lucene 在切片之前会先收集 `offset + size` 条命中。把这份分配
     * 约束在真实的资源预算内，而不是把 Int.MAX_VALUE 当作可用的分页边界。
     */
    fun searchOffset(): Int {
        require(offset + size.toLong() <= MAX_SEARCH_WINDOW.toLong()) {
            "page exceeds the $MAX_SEARCH_WINDOW-hit search window"
        }
        return offset.toInt()
    }

    companion object {
        const val MAX_SIZE = 100
        const val MAX_SEARCH_WINDOW = MAX_MESSAGE_SEARCH_COLLECTION_WINDOW

        private fun saturatedMultiply(left: Long, right: Long): Long =
            if (left == 0L || right == 0L) 0L
            else if (left > Long.MAX_VALUE / right) Long.MAX_VALUE
            else left * right
    }
}

/** 管理员用户列表刻意与终端用户的发现语义分开。 */
interface AdminUserDirectory {
    fun listUsers(query: String?, pagination: AdminPageRequest): AdminPage<User>
    fun countUsers(): Long
}

/** 仅限管理员的全局群列表与计数器，与聊天聚合仓库分离。 */
interface AdminChatDirectory {
    fun listGroups(query: String?, pagination: AdminPageRequest): AdminPage<Chat>
    fun findGroup(chatId: String): Chat?
    fun countGroups(): Long
    fun countEventsSince(sinceMillis: Long): Long
}

/** 概览用例使用的窄运行时计数器。 */
interface AdminOverviewCounters {
    suspend fun onlineCount(): Int
    fun groupCount(): Long
    fun eventCountSince(sinceMillis: Long): Long
}

@Serializable
data class AdminLogFileInfo(
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
)

data class AdminStorageUsage(
    val rocksdbBytes: Long,
    val fileStoreBytes: Long,
    /** True 表示至少有一个目录达到了遍历预算，因此字节值是下界。 */
    val truncated: Boolean,
)

/** 文件系统诊断停留在这一受限、路径中性的应用端口之后。 */
interface AdminDiagnostics {
    fun storageUsage(): AdminStorageUsage
    fun listServerLogs(): List<AdminLogFileInfo>
    fun readServerLog(name: String, lines: Int): List<String>
}

@Serializable
data class AdminOverview(
    val onlineCount: Int,
    val userCount: Long,
    val groupCount: Long,
    val todayEvents: Long,
    val storageRocksdbBytes: Long,
    val storageFileStoreBytes: Long,
    /** 为 true 时，两个字节字段都是有效的下界，而非精确总数。 */
    val storageScanTruncated: Boolean = false,
)
