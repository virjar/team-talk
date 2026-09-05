package com.virjar.tk.shared.testkit

import com.virjar.tk.protocol.model.GroupFileEntry
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
    private val tombstones = HashMap<Pair<String, String>, Long>()

    private class Observer(
        val chatId: String,
        val parentId: String?,
        val flow: MutableStateFlow<List<GroupFileEntry>>,
    )

    private val observers = ArrayList<Observer>()
    private val lock = Any()

    fun applyUpsert(entry: GroupFileEntry) {
        synchronized(lock) {
            val key = entry.chatId to entry.entryId
            val tombstone = tombstones[key]
            if (tombstone != null && tombstone >= entry.revision) return
            val rows = rowsByChat.getOrPut(entry.chatId) { LinkedHashMap() }
            val existing = rows[entry.entryId]
            if (existing != null && existing.revision > entry.revision) return
            rows[entry.entryId] = entry
            tombstones.remove(key)
        }
        publish()
    }

    fun applyDelete(chatId: String, entryId: String, tombstoneRevision: Long) {
        synchronized(lock) {
            val key = chatId to entryId
            val existing = rowsByChat[chatId]?.remove(entryId)
            val known = existing?.revision ?: tombstones[key] ?: 0L
            if (known <= tombstoneRevision) tombstones[key] = tombstoneRevision
        }
        publish()
    }

    fun replaceDirectory(chatId: String, parentId: String?, entries: List<GroupFileEntry>) {
        synchronized(lock) {
            val rows = rowsByChat.getOrPut(chatId) { LinkedHashMap() }
            rows.entries.removeAll {
                it.value.parentId == parentId && it.value.entryId !in entries.map { e -> e.entryId }
            }
            entries.forEach { rows[it.entryId] = it }
        }
        publish()
    }

    fun activeEntries(chatId: String, parentId: String?): List<GroupFileEntry> = synchronized(lock) {
        rowsByChat[chatId].orEmpty().values
            .filter { it.parentId == parentId }
            .sortedWith(compareBy({ it.kind }, { it.name }))
    }

    fun observe(chatId: String, parentId: String?): Flow<List<GroupFileEntry>> = flow {
        val state = MutableStateFlow(activeEntries(chatId, parentId))
        val observer = Observer(chatId, parentId, state)
        synchronized(lock) { observers.add(observer) }
        try {
            emitAll(state)
        } finally {
            synchronized(lock) { observers.remove(observer) }
        }
    }

    fun purge(chatId: String) {
        synchronized(lock) { rowsByChat.remove(chatId) }
        publish()
    }

    private fun publish() {
        val snapshot = synchronized(lock) { observers.toList() }
        snapshot.forEach { it.flow.value = activeEntries(it.chatId, it.parentId) }
    }
}
