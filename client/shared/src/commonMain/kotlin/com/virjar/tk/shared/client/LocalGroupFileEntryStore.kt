package com.virjar.tk.shared.client

import com.virjar.tk.shared.database.AppDatabaseQueries
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.shared.database.Group_file_entry
import com.virjar.tk.protocol.model.GroupFileEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * 群共享文件的行级本地投影（CONTENT-01）。
 *
 * 数据来源两路：GROUP_FILE_CHANGED 事件 delta（服务端在变更事务内追加，成员流内有序）
 * 与目录页快照（list RPC 的权威页）。UPSERT 只在 revision 不低于已存值时应用，DELETE 落
 * 墓穴行阻挡迟到 UPSERT 复活——重复、乱序与离线重放都收敛到同一状态；墓穴行随目录快照
 * 替换与每 chat 容量上限回收。观察按 (chat, parent) 目录订阅，发布活动条目。
 */
internal class LocalGroupFileEntryStore(
    private val queries: AppDatabaseQueries,
    private val cacheUseGate: CacheUseGate,
    private val stateLock: Any,
    private val maxRowsPerChat: Int = MAX_ROWS_PER_CHAT,
) {
    private class DirectoryObserver(
        val parentKey: String,
        val flow: MutableStateFlow<List<GroupFileEntry>>,
    )

    private val observers = LinkedHashMap<String, MutableList<DirectoryObserver>>()
    private val snapshots = KeyedProjectionSnapshotGate("group file directory")

    private fun <T> locked(block: () -> T): T = cacheUseGate.use {
        synchronized(stateLock) { block() }
    }

    private fun directoryKey(chatId: String, parentId: String?) = "${chatId.length}:$chatId:${parentId.orEmpty()}"

    private fun invalidateChatLocked(chatId: String) {
        val prefix = "${chatId.length}:$chatId:"
        snapshots.invalidateMatching { it.startsWith(prefix) }
    }

    /** GROUP_FILE_CHANGED UPSERT delta；重复/迟到低 revision 是无操作。 */
    fun applyUpsert(entry: GroupFileEntry) {
        cacheUseGate.use {
            synchronized(stateLock) {
                invalidateChatLocked(entry.chatId)
                queries.transaction {
                    val existing = queries.selectGroupFileEntry(entry.chatId, entry.entryId)
                        .executeAsOneOrNull()
                    if (existing != null && existing.revision > entry.revision) return@transaction
                    if (existing != null && existing.deleted == 1L && existing.revision >= entry.revision) {
                        return@transaction
                    }
                    upsertRow(entry)
                    pruneLocked(entry.chatId)
                }
                publishLocked(entry.chatId)
            }
        }
    }

    /** GROUP_FILE_CHANGED DELETE delta：墓穴行让迟到低 revision UPSERT 无操作。 */
    fun applyDelete(chatId: String, entryId: String, tombstoneRevision: Long, updatedBy: String, updatedAt: Long) {
        cacheUseGate.use {
            synchronized(stateLock) {
                invalidateChatLocked(chatId)
                queries.transaction {
                    val existing = queries.selectGroupFileEntry(chatId, entryId).executeAsOneOrNull()
                    if (existing == null) {
                        queries.insertGroupFileTombstone(
                            chat_id = chatId,
                            entry_id = entryId,
                            revision = tombstoneRevision,
                            created_by = updatedBy,
                            created_at = createdAtForTombstone(updatedAt),
                            updated_by = updatedBy,
                            updated_at = updatedAt,
                        )
                    } else if (existing.deleted == 0L && existing.revision <= tombstoneRevision) {
                        queries.markGroupFileEntryDeleted(
                            revision = tombstoneRevision,
                            updated_by = updatedBy,
                            updated_at = updatedAt,
                            chat_id = chatId,
                            entry_id = entryId,
                            revision_ = tombstoneRevision,
                        )
                    }
                }
                publishLocked(chatId)
            }
        }
    }

    fun beginSnapshot(chatId: String, parentId: String?): ProjectionSnapshotLease = locked {
        snapshots.begin(directoryKey(chatId, parentId))
    }

    fun abandonSnapshot(lease: ProjectionSnapshotLease): Boolean = cacheUseGate.runIfOpen {
        synchronized(stateLock) { snapshots.abandon(lease) }
    }

    /** 只有请求期间没有 delta/reset 或更新请求的完整目录页可以清走墓穴和缺席条目。 */
    fun applySnapshot(
        lease: ProjectionSnapshotLease,
        chatId: String,
        parentId: String?,
        entries: List<GroupFileEntry>,
    ): Boolean = cacheUseGate.runIfOpen {
        require(entries.all { it.chatId == chatId && it.parentId == parentId }) { "群文件响应身份不匹配" }
        require(entries.map(GroupFileEntry::entryId).toSet().size == entries.size) { "群文件目录包含重复条目" }
        synchronized(stateLock) {
            if (!snapshots.consumeIfCurrent(lease, directoryKey(chatId, parentId))) return@synchronized false
            queries.transaction {
                queries.deleteGroupFileDirectory(chatId, parentId.orEmpty())
                entries.forEach(::upsertRow)
            }
            publishLocked(chatId)
            true
        }
    }

    /** 当前活动条目（确定性读取，供快照首帧与测试断言）。 */
    fun activeEntries(chatId: String, parentId: String?): List<GroupFileEntry> = locked {
        activeEntriesLocked(chatId, parentId.orEmpty())
    }

    private fun activeEntriesLocked(chatId: String, parentKey: String): List<GroupFileEntry> =
        queries.selectActiveGroupFileEntries(chatId, parentKey).executeAsList().map(::toEntry)

    /** 观察一个目录的活动条目；delta 与快照写入都实时发布。 */
    fun observe(chatId: String, parentId: String?): Flow<List<GroupFileEntry>> = flow {
        val observer = locked {
            DirectoryObserver(parentId.orEmpty(), MutableStateFlow(activeEntriesLocked(chatId, parentId.orEmpty())))
                .also { observers.getOrPut(chatId) { mutableListOf() }.add(it) }
        }
        try {
            emitAll(observer.flow)
        } finally {
            synchronized(stateLock) {
                observers[chatId]?.let { registered ->
                    registered.remove(observer)
                    if (registered.isEmpty()) observers.remove(chatId)
                }
            }
        }
    }

    /** 403/404 或 reset：原子删除该群投影；观察者收到空列表。 */
    fun purgeChat(chatId: String) {
        cacheUseGate.use {
            synchronized(stateLock) {
                invalidateChatLocked(chatId)
                queries.deleteAllGroupFileEntriesForChat(chatId)
                publishLocked(chatId)
            }
        }
    }

    /** 调用方在服务器投影 reset 期间持有 [stateLock]。 */
    fun clearAllLocked() {
        snapshots.reset()
        queries.deleteAllGroupFileEntries()
        val chats = observers.keys.toList()
        chats.forEach { chatId ->
            observers[chatId]?.toList()?.forEach { it.flow.value = emptyList() }
        }
    }

    /** 调用方在缓存退役期间持有 [stateLock]。 */
    fun closeObserversLocked() {
        snapshots.reset()
        observers.clear()
    }

    private fun upsertRow(entry: GroupFileEntry) {
        queries.upsertGroupFileEntry(
            chat_id = entry.chatId,
            entry_id = entry.entryId,
            parent_id = entry.parentId.orEmpty(),
            kind = entry.kind.toLong(),
            name = entry.name,
            attachment_path = entry.attachment?.path,
            attachment_name = entry.attachment?.name,
            attachment_content_type = entry.attachment?.contentType,
            attachment_size = entry.attachment?.size,
            revision = entry.revision,
            content_version = entry.contentVersion,
            created_by = entry.createdBy,
            created_at = entry.createdAt,
            updated_by = entry.updatedBy,
            updated_at = entry.updatedAt,
        )
    }

    private fun pruneLocked(chatId: String) {
        val count = queries.countGroupFileRows(chatId).executeAsOne()
        if (count > maxRowsPerChat) {
            queries.pruneOldestGroupFileRows(chatId, chatId, count - maxRowsPerChat)
        }
    }

    private fun publishLocked(chatId: String) {
        observers[chatId]?.toList()?.forEach { observer ->
            observer.flow.value = activeEntriesLocked(chatId, observer.parentKey)
        }
    }

    private fun createdAtForTombstone(updatedAt: Long): Long = updatedAt

    private fun toEntry(row: Group_file_entry): GroupFileEntry = GroupFileEntry(
        entryId = row.entry_id,
        chatId = row.chat_id,
        parentId = row.parent_id.takeIf { it.isNotEmpty() },
        kind = row.kind.toInt(),
        name = row.name,
        attachment = row.attachment_path?.let { path ->
            Attachment(
                path = path,
                name = row.attachment_name ?: "",
                contentType = row.attachment_content_type ?: "",
                size = row.attachment_size ?: 0L,
            )
        },
        revision = row.revision,
        contentVersion = row.content_version,
        createdBy = row.created_by,
        createdAt = row.created_at,
        updatedBy = row.updated_by,
        updatedAt = row.updated_at,
    )

    companion object {
        /** 服务端每群硬上限 10,000 活动条目；本地只保留可重拉的活动投影，收紧一个数量级。 */
        const val MAX_ROWS_PER_CHAT = 2_048
    }
}
