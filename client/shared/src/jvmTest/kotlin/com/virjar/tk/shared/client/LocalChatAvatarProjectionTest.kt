package com.virjar.tk.shared.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.shared.database.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalChatAvatarProjectionTest {
    @Test
    fun `offline restart restores avatars before any RPC and clearing survives another restart`() = runBlocking {
        val directory = createTempDirectory("tk-chat-avatar-").toFile()
        val url = "jdbc:sqlite:${directory.resolve("cache.db").absolutePath}"
        try {
            withCache(JdbcSqliteDriver(url).also { AppDatabase.Schema.create(it) }) { cache ->
                // 没有 UI 订阅者时到达的权威事件仍须被下一位订阅者和下次启动观察到。
                cache.upsertChatAvatar("group", avatar(1))
                assertEquals(mapOf("group" to avatar(1)), cache.observeChatAvatars().first())
            }
            withCache(JdbcSqliteDriver(url)) { cache ->
                cache.observeChatAvatars().test {
                    assertEquals(mapOf("group" to avatar(1)), awaitItem())
                    assertFalse(cache.isChatAvatarResolved("group"), "旧投影仍应允许本次会话在线刷新")
                    cache.upsertChatAvatar("group", null)
                    assertEquals(emptyMap(), awaitItem())
                    assertTrue(cache.isChatAvatarResolved("group"), "权威空头像不能反复触发懒加载")
                    cache.close()
                    awaitComplete()
                }
            }
            withCache(JdbcSqliteDriver(url)) { cache ->
                assertEquals(emptyMap(), cache.observeChatAvatars().first())
            }
        } finally {
            check(directory.deleteRecursively())
        }
    }

    @Test
    fun `avatar bursts replay their full snapshot and chat deletion and dataset reset purge it`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        withCache(driver) { cache ->
            repeat(100) { index -> cache.upsertChatAvatar("group-$index", avatar(index)) }
            cache.observeChatAvatars().test {
                assertEquals((0 until 100).associate { "group-$it" to avatar(it) }, awaitItem())
                cache.deleteChat("group-0")
                assertEquals((1 until 100).associate { "group-$it" to avatar(it) }, awaitItem())
                assertFalse(cache.isChatAvatarResolved("group-0"))
                cache.resetServerProjection(UUID.randomUUID().toString())
                assertEquals(emptyMap(), awaitItem())
                assertFalse(cache.isChatAvatarResolved("group-1"))
                cache.close()
                awaitComplete()
            }
        }
    }

    private fun avatar(index: Int) = Attachment(
        path = "avatars/avatar-$index.png",
        name = "avatar-$index.png",
        contentType = "image/png",
        size = 128L,
    )

    private inline fun <T> withCache(driver: JdbcSqliteDriver, block: (LocalCacheImpl) -> T): T {
        val cache = LocalCacheImpl(driver)
        return try {
            block(cache)
        } finally {
            cache.close()
        }
    }
}
