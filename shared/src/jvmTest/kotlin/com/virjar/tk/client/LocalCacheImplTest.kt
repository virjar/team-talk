package com.virjar.tk.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.model.Chat
import com.virjar.tk.model.Contact
import com.virjar.tk.model.Conversation
import com.virjar.tk.model.Message
import com.virjar.tk.model.User
import com.virjar.tk.database.AppDatabase
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * LocalCacheImpl 内存治理与并发正确性（JVM 专属内存 SQLite 测试）。
 * 锁定 lessons C1（StateFlow 读改写竞态）/D4（水位线合并）/窗口 LRU 语义。
 */
class LocalCacheImplTest {

    private fun newCache(): LocalCacheImpl {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        return LocalCacheImpl(driver)
    }

    private fun user(i: Int) = User(uid = "u$i", username = "user$i", name = "User$i")
    private fun conv(chatId: String, readSeq: Long = 0, unread: Int = 0, peer: Long = 0, draft: String? = null, ts: Long = System.currentTimeMillis()) =
        Conversation(chatId = chatId, chatType = 1, readSeq = readSeq, unreadCount = unread, peerReadSeq = peer, draft = draft, lastMsgTimestamp = ts)

    @Test
    fun `并发 upsertUser 无丢失 - stateLock 语义`() {
        val cache = newCache()
        val threads = 8
        val perThread = 200
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        repeat(threads) { t ->
            pool.submit {
                ready.countDown()
                go.await()
                repeat(perThread) { i ->
                    cache.upsertUser(user(t * perThread + i))
                    cache.upsertContact(Contact(uid = "owner", friendUid = "f${t}_$i"))
                }
            }
        }
        ready.await(5, TimeUnit.SECONDS); go.countDown()
        pool.shutdown(); pool.awaitTermination(30, TimeUnit.SECONDS)
        assertEquals(threads * perThread, cache.getUser("u${threads * perThread - 1}")?.let { usersCount(cache) } ?: usersCount(cache))
        assertEquals(threads * perThread, cache.getContacts().size)
    }

    private fun usersCount(cache: LocalCacheImpl): Int {
        // 通过观察流当前值计数
        var n = 0
        var last: String? = null
        for (i in 0 until Int.MAX_VALUE) {
            val u = cache.getUser("u$i") ?: break
            n++
        }
        return n
    }

    @Test
    fun `mergeConversation - readSeq 与 peerReadSeq 取 max 不回退`() {
        val cache = newCache()
        cache.upsertConversation(conv("c1", readSeq = 100, peer = 50, unread = 0))
        // 服务端滞后通知（readSeq 更小）不得回退本地水位线
        cache.upsertConversation(conv("c1", readSeq = 80, peer = 60, unread = 5))
        val merged = cache.getConversations().first { it.chatId == "c1" }
        assertEquals(100L, merged.readSeq, "readSeq 水位线只增不减")
        assertEquals(60L, merged.peerReadSeq, "peerReadSeq 水位线只增不减")
    }

    @Test
    fun `mergeConversation - draft 本地优先不被服务端覆盖`() {
        val cache = newCache()
        cache.upsertConversation(conv("c1", draft = "本地草稿"))
        cache.upsertConversation(conv("c1", draft = "服务端镜像"))
        assertEquals("本地草稿", cache.getConversations().first { it.chatId == "c1" }.draft)
        // 本地已有草稿时，服务端 null 也不清除（草稿纯客户端状态）
        cache.upsertConversation(conv("c2", draft = "新草稿"))
        cache.upsertConversation(conv("c2", draft = null))
        assertEquals("新草稿", cache.getConversations().first { it.chatId == "c2" }.draft)
        // 本地为 null 时允许服务端值进入
        cache.upsertConversation(conv("c3", draft = null))
        cache.upsertConversation(conv("c3", draft = "服务端草稿"))
        assertEquals("服务端草稿", cache.getConversations().first { it.chatId == "c3" }.draft)
    }

    @Test
    fun `markConversationRead 即时清零未读`() {
        val cache = newCache()
        cache.upsertConversation(conv("c1", readSeq = 10, unread = 7))
        cache.markConversationRead("c1", 17)
        val c = cache.getConversations().first { it.chatId == "c1" }
        assertEquals(0, c.unreadCount, "标记已读必须即时清零（不等服务端回环）")
        assertEquals(17L, c.readSeq)
    }

    @Test
    fun `置顶排序 - pinned 优先于时间`() {
        val cache = newCache()
        cache.upsertConversation(conv("old", ts = 1000))
        cache.upsertConversation(conv("new", ts = 2000))
        cache.upsertConversation(conv("pinned", ts = 500))
        cache.toggleConversationPin("pinned", true)
        val ids = cache.getConversations().map { it.chatId }
        assertEquals(listOf("pinned", "new", "old"), ids)
    }

    @Test
    fun `消息窗口 LRU - 超过 MAX_ACTIVE_CHATS 淘汰最旧且可从DB重载`() {
        val cache = newCache()
        val total = LocalCache.MAX_ACTIVE_CHATS + 5
        // 按顺序触碰 total 个 chat（写入即触碰窗口）
        for (i in 0 until total) {
            cache.insertMessage(Message(chatId = "c$i", clientMsgId = "m$i", serverSeq = 1, senderUid = "u", messageType = 1, timestamp = i.toLong()))
        }
        // 最旧的 5 个窗口被 evict；重新 observe 从 DB 重载不丢数据
        val reloaded = cache.getMessages("c0", 10)
        assertTrue(reloaded.isNotEmpty(), "evicted 窗口应从 DB 重载")
        assertEquals("m0", reloaded.first().clientMsgId)
    }

    @Test
    fun `消息窗口翻页 - loadMore 向上加载`() = runBlocking {
        val cache = newCache()
        // 逆序插入 150 条（seq 递减写入保证最新在窗口）
        for (seq in 150 downTo 1) {
            cache.insertMessage(Message(chatId = "c1", clientMsgId = "m$seq", serverSeq = seq.toLong(), senderUid = "u", messageType = 1, timestamp = seq.toLong()))
        }
        val pager = cache.pager("c1", windowSize = 100)
        val first = cache.getMessages("c1", 100)
        assertEquals(100, first.size, "初始窗口 100 条")
        pager.loadMore()
        val after = cache.getMessages("c1", 200)
        assertTrue(after.size > 100, "loadMore 追加更旧消息（实际 ${after.size}）")
    }

    @Test
    fun `clientMsgId 幂等覆盖 - 服务端回环不产生重复`() {
        val cache = newCache()
        val msg = Message(chatId = "c1", clientMsgId = "same-id", serverSeq = 5, senderUid = "u", messageType = 1, timestamp = 1)
        cache.insertMessage(msg)
        cache.insertMessage(msg.copy(sendStatus = Message.SEND_STATUS_SENT))
        val list = cache.getMessages("c1", 10)
        assertEquals(1, list.size, "同一 clientMsgId 必须覆盖（服务端 MESSAGE_RECV 含发送者回环）")
    }

    @Test
    fun `草稿清除 - 远端事件回环挡不住本地直清`() {
        val cache = newCache()
        cache.upsertConversation(conv("c1", draft = "z"))

        // 回环事件（draft=null）：合并策略本地非空优先，不会清（设计如此，
        // 保护刚保存未同步的草稿不被旧事件覆盖）
        cache.upsertConversation(conv("c1", draft = null, unread = 3))
        assertEquals("z", cache.getConversations().first { it.chatId == "c1" }.draft, "远端 null 不清本地草稿（本地优先）")

        // 清除必须走本地直清（发送后 setDraft(null) 链路）
        cache.setConversationDraft("c1", null)
        assertEquals(null, cache.getConversations().first { it.chatId == "c1" }.draft, "本地直清后草稿为 null")
    }

    @Test
    fun `草稿清除落库 - setConversationDraft 持久化到 SQLite`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver)
        cache.upsertConversation(conv("c1", draft = "z"))
        cache.setConversationDraft("c1", null)
        // 同一 driver 上的新实例（模拟重启后重读 DB）：草稿不复活
        val reloaded = LocalCacheImpl(driver)
        assertEquals(null, reloaded.getConversations().first { it.chatId == "c1" }.draft)
    }
}
