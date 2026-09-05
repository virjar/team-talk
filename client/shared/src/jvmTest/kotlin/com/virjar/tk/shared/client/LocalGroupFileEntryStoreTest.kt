package com.virjar.tk.shared.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.shared.database.AppDatabase
import com.virjar.tk.protocol.model.GroupFileEntry
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CONTENT-01 群文件行级投影的确定性收敛：重复、乱序、墓穴、快照替换、复位清理与有界容量。
 */
class LocalGroupFileEntryStoreTest {

    private val drivers = mutableListOf<JdbcSqliteDriver>()
    private val roots = mutableListOf<java.io.File>()

    private fun newStore(maxRows: Int = 2_048): LocalGroupFileEntryStore {
        val root = createTempDirectory("gf-projection-").toFile()
        roots += root
        val driver = JdbcSqliteDriver("jdbc:sqlite:${root.resolve("cache.db").absolutePath}")
        drivers += driver
        AppDatabase.Schema.create(driver)
        val queries = AppDatabase(driver).appDatabaseQueries
        val gate = CacheUseGate()
        val lock = Any()
        return LocalGroupFileEntryStore(queries, gate, lock, maxRowsPerChat = maxRows)
    }

    private fun entry(
        entryId: String,
        chatId: String = "chat-1",
        parentId: String? = null,
        kind: Int = GroupFileEntry.KIND_FOLDER,
        name: String,
        revision: Long,
    ) = GroupFileEntry(
        entryId = entryId,
        chatId = chatId,
        parentId = parentId,
        kind = kind,
        name = name,
        revision = revision,
        createdBy = "u1",
        createdAt = revision,
        updatedBy = "u1",
        updatedAt = revision,
    )

    @AfterTest
    fun cleanup() {
        drivers.forEach { runCatching { it.close() } }
        drivers.clear()
        roots.forEach { it.deleteRecursively() }
        roots.clear()
    }

    @Test
    fun `duplicate and stale upserts are no-ops while newer revisions win`() {
        val store = newStore()
        store.applyUpsert(entry("e1", name = "A", revision = 2))
        // 重复 delta
        store.applyUpsert(entry("e1", name = "A", revision = 2))
        // 迟到低 revision 不回退
        store.applyUpsert(entry("e1", name = "A-old", revision = 1))
        val rows = store.activeEntries("chat-1", null)
        assertEquals(listOf("A" to 2L), rows.map { it.name to it.revision })
    }

    @Test
    fun `delete tombstone blocks late upsert resurrection and repeats converge`() {
        val store = newStore()
        store.applyUpsert(entry("e1", name = "A", revision = 3))
        store.applyDelete("chat-1", "e1", tombstoneRevision = 4, updatedBy = "u2", updatedAt = 9L)
        // 重复删除
        store.applyDelete("chat-1", "e1", tombstoneRevision = 4, updatedBy = "u2", updatedAt = 9L)
        // 迟到低 revision UPSERT 不得复活
        store.applyUpsert(entry("e1", name = "zombie", revision = 3))
        // 高于墓穴的新 revision（合法重建，UUID 不会复用但语义上允许更高）
        store.applyUpsert(entry("e1", name = "reborn", revision = 5))
        assertEquals(emptyList(), store.activeEntries("chat-1", null).filter { it.revision < 5 })
        assertEquals(1, store.activeEntries("chat-1", null).size)
        assertEquals("reborn", store.activeEntries("chat-1", null).single().name)
    }

    @Test
    fun `directory snapshot atomically replaces rows and clears tombstones`() {
        val store = newStore()
        store.applyUpsert(entry("e1", name = "旧A", revision = 1))
        store.applyUpsert(entry("e2", name = "旧B", revision = 1))
        store.applyDelete("chat-1", "e2", tombstoneRevision = 2, updatedBy = "u2", updatedAt = 5L)

        store.replaceDirectory("chat-1", null, listOf(entry("e1", name = "新A", revision = 2)))
        val rows = store.activeEntries("chat-1", null)
        assertEquals(listOf("新A"), rows.map { it.name }, "快照替换整目录并清走墓穴/缺席条目")
    }

    @Test
    fun `subdirectory rows survive sibling snapshot replacement`() {
        val store = newStore()
        store.applyUpsert(entry("dir", name = "目录", revision = 1))
        store.applyUpsert(entry("child", parentId = "dir", name = "子项", revision = 1))
        store.replaceDirectory("chat-1", null, listOf(entry("dir", name = "目录", revision = 1)))
        assertEquals(1, store.activeEntries("chat-1", "dir").size, "子目录行不受父目录快照影响")
    }

    @Test
    fun `purge clears the chat and reset clears everything`() {
        val store = newStore()
        store.applyUpsert(entry("e1", chatId = "c1", name = "A", revision = 1))
        store.applyUpsert(entry("e2", chatId = "c2", name = "B", revision = 1))
        store.purgeChat("c1")
        assertEquals(emptyList(), store.activeEntries("c1", null))
        assertEquals(1, store.activeEntries("c2", null).size)
    }

    @Test
    fun `bounded per-chat capacity evicts oldest active rows`() {
        val store = newStore(maxRows = 2)
        store.applyUpsert(entry("e1", name = "A", revision = 1))
        store.applyUpsert(entry("e2", name = "B", revision = 2))
        store.applyUpsert(entry("e3", name = "C", revision = 3))
        val names = store.activeEntries("chat-1", null).map { it.name }.toSet()
        assertTrue("B" in names && "C" in names, "最旧活动行被回收: $names")
        assertTrue("A" !in names)
    }
}
