package com.virjar.tk.shared.testkit

import com.virjar.tk.shared.platform.*
import com.virjar.tk.protocol.model.GroupFileEntry
import com.virjar.tk.shared.client.KeyedProjectionSnapshotGate
import com.virjar.tk.shared.client.ProjectionSnapshotLease
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * [FakeLocalCache] 的群共享文件行级投影（CONTENT-01 测试替身）：
 * 与真实 SQLite store 相同的 revision 守卫、墓穴与目录快照语义。
 */
internal class FakeGroupFileProjection {
    private val rowsByChat = LinkedHashMap<String, LinkedHashMap<String, GroupFileEntry>>()
    private data class Tombstone(val parentId: String?, val revision: Long)
    private val tombstones = HashMap<Pair<String, String>, Tombstone>()
    private val snapshots = KeyedProjectionSnapshotGate("fake group file directory")

    private class Observer(
        val chatId: String,
        val parentId: String?,
        val flow: MutableStateFlow<List<GroupFileEntry>>,
    )

    private val observers = ArrayList<Observer>()
    private val lock = PlatformLock()

    private fun directoryKey(chatId: String, parentId: String?) = "${chatId.length}:$chatId:${parentId.orEmpty()}"

    private fun invalidateChatLocked(chatId: String) {
        snapshots.invalidateMatching { it.startsWith("${chatId.length}:$chatId:") }
    }

    fun applyUpsert(entry: GroupFileEntry) {
        synchronized(lock) {
            invalidateChatLocked(entry.chatId)
            val key = entry.chatId to entry.entryId
            val tombstone = tombstones[key]
            if (tombstone != null && tombstone.revision >= entry.revision) return
            val rows = rowsByChat.getOrPut(entry.chatId) { LinkedHashMap() }
            val existing = rows[entry.entryId]
            if (existing != null && existing.revision > entry.revision) return
            rows[entry.entryId] = entry
            tombstones.remove(key)
            publishLocked()
        }
    }

    fun applyDelete(chatId: String, entryId: String, tombstoneRevision: Long) {
        synchronized(lock) {
            invalidateChatLocked(chatId)
            val key = chatId to entryId
            val existing = rowsByChat[chatId]?.get(entryId)
            val known = existing?.revision ?: tombstones[key]?.revision ?: 0L
            if (known <= tombstoneRevision) {
                rowsByChat[chatId]?.remove(entryId)
                tombstones[key] = Tombstone(existing?.parentId ?: tombstones[key]?.parentId, tombstoneRevision)
            }
            publishLocked()
        }
    }

    fun beginSnapshot(chatId: String, parentId: String?): ProjectionSnapshotLease = synchronized(lock) {
        snapshots.begin(directoryKey(chatId, parentId))
    }

    fun abandonSnapshot(lease: ProjectionSnapshotLease): Boolean = synchronized(lock) { snapshots.abandon(lease) }

    fun applySnapshot(
        lease: ProjectionSnapshotLease,
        chatId: String,
        parentId: String?,
        entries: List<GroupFileEntry>,
    ): Boolean = synchronized(lock) {
        require(entries.all { it.chatId == chatId && it.parentId == parentId })
        require(entries.map(GroupFileEntry::entryId).toSet().size == entries.size)
        if (!snapshots.consumeIfCurrent(lease, directoryKey(chatId, parentId))) return@synchronized false
        val rows = rowsByChat.getOrPut(chatId) { LinkedHashMap() }
        rows.entries.removeAll { it.value.parentId == parentId }
        tombstones.entries.removeAll { it.key.first == chatId && it.value.parentId == parentId }
        entries.forEach { rows[it.entryId] = it }
        publishLocked()
        true
    }

    fun activeEntries(chatId: String, parentId: String?): List<GroupFileEntry> = synchronized(lock) {
        rowsByChat[chatId].orEmpty().values
            .filter { it.parentId == parentId }
            .sortedWith(compareBy({ it.kind }, { it.name }))
    }

    fun observe(chatId: String, parentId: String?): Flow<List<GroupFileEntry>> = flow {
        val observer = synchronized(lock) {
            Observer(chatId, parentId, MutableStateFlow(activeEntries(chatId, parentId))).also(observers::add)
        }
        try {
            emitAll(observer.flow)
        } finally {
            synchronized(lock) { observers.remove(observer) }
        }
    }

    fun purge(chatId: String) {
        synchronized(lock) {
            invalidateChatLocked(chatId)
            rowsByChat.remove(chatId)
            tombstones.keys.removeAll { it.first == chatId }
            publishLocked()
        }
    }

    fun reset() = synchronized(lock) {
        snapshots.reset()
        rowsByChat.clear()
        tombstones.clear()
        publishLocked()
    }

    fun close() = synchronized(lock) {
        snapshots.reset()
        observers.clear()
    }

    private fun publishLocked() {
        observers.toList().forEach { it.flow.value = activeEntries(it.chatId, it.parentId) }
    }
}
