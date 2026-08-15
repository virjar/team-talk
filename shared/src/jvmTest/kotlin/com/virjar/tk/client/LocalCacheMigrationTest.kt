package com.virjar.tk.client

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.database.AppDatabase
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 本地库 schema 迁移：v1（无 peer_read_seq）→ v2 自动迁移，数据保留。
 */
class LocalCacheMigrationTest {

    private fun columnsOf(driver: SqlDriver): List<String> =
        driver.executeQuery(null, "PRAGMA table_info(conversation)", { c: SqlCursor ->
            QueryResult.Value(buildList { while (c.next().value) add(c.getString(1)!!) })
        }, 0).value

    @Test
    fun `v1 旧库自动迁移到 v2 且保留数据`() {
        val dataDir = File("/tmp/tk-mig-${System.nanoTime()}")
        val file = File(dataDir, "users/u1/cache.db").also { it.parentFile.mkdirs() }
        // 构造逼真 v1 库：v2 全表结构建好后 DROP 掉 v2 新列（SQLite 3.35+），版本写 1
        JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { legacy ->
            com.virjar.tk.database.AppDatabase.Schema.create(legacy)
            java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
                conn.createStatement().execute("ALTER TABLE conversation DROP COLUMN peer_read_seq")
                conn.createStatement().execute("INSERT INTO conversation(chat_id, chat_type) VALUES('c1', 1)")
                conn.createStatement().execute("PRAGMA user_version = 1")
            }
        }

        // 新工厂打开（命中同一文件）：应触发 Schema.migrate（加列）而非报错/清库
        createDesktopLocalCache("u1", dataDir)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        val cols = columnsOf(driver)
        assertTrue("peer_read_seq" in cols, "v1→v2 迁移应添加 peer_read_seq，实际列: $cols")
        // 旧数据保留 + 新列默认值生效
        val rows = driver.executeQuery(null, "SELECT chat_id, peer_read_seq FROM conversation", { c: SqlCursor ->
            QueryResult.Value(buildList { while (c.next().value) add(c.getString(0)!! to (c.getLong(1) ?: 0L)) })
        }, 0).value
        assertEquals(listOf("c1" to 0L), rows, "迁移后旧数据必须保留")
        driver.close()
        dataDir.deleteRecursively()
    }

    @Test
    fun `v1 遗库 user_version=0（旧 create 不写版本）也能迁移`() {
        val dataDir = File("/tmp/tk-mig-legacy0-${System.nanoTime()}")
        val file = File(dataDir, "users/u1/cache.db").also { it.parentFile.mkdirs() }
        // 模拟真实 v1 遗库：全表结构（DROP 新列）+ 数据，user_version 保持 0（旧 create 不写）
        JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { legacy ->
            com.virjar.tk.database.AppDatabase.Schema.create(legacy)
            java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
                conn.createStatement().execute("ALTER TABLE conversation DROP COLUMN peer_read_seq")
                conn.createStatement().execute("INSERT INTO conversation(chat_id, chat_type) VALUES('legacy', 2)")
            }
        }
        createDesktopLocalCache("u1", dataDir)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        assertTrue("peer_read_seq" in columnsOf(driver), "遗库（版本0）必须识别为旧库并迁移")
        driver.close()
        dataDir.deleteRecursively()
    }

    @Test
    fun `全新库直接建 v2 且版本号正确`() {
        val dir = File("/tmp/tk-mig-new-${System.nanoTime()}")
        dir.mkdirs()
        createDesktopLocalCache("u1", dir)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dir.resolve("users/u1/cache.db").absolutePath}")
        val cols = columnsOf(driver)
        assertTrue("peer_read_seq" in cols)
        val v = driver.executeQuery(null, "PRAGMA user_version", { c: SqlCursor ->
            QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0L else 0L)
        }, 0).value
        assertEquals(AppDatabase.Schema.version, v, "user_version 应推进到 schema 版本")
        driver.close()
        dir.deleteRecursively()
    }
}
