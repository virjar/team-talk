package com.virjar.tk.e2e

import com.virjar.tk.di.createServerModule
import com.virjar.tk.infra.db.DatabaseFactory
import com.virjar.tk.infra.search.SearchIndex
import com.virjar.tk.infra.storage.FileStore
import com.virjar.tk.infra.storage.MessageStore
import com.virjar.tk.infra.storage.TokenStore
import com.virjar.tk.infra.sync.ClientRegistry
import com.virjar.tk.protocol.TcpServer
import com.virjar.tk.protocol.codec.ImAgent
import org.koin.dsl.koinApplication
import java.io.File
import java.sql.DriverManager
import java.util.UUID

/**
 * E2E 协议测试环境：进程内 Koin + TcpServer + 真实 PostgreSQL。
 *
 * 数据库直接使用本地 PG 的 teamtalk 库（与 local profile 的 `:server:run` 同一环境，
 * 不另建测试库/不做环境编排），每个测试开始时 TRUNCATE 全部业务表清场。
 * 前置：本机 5432 运行 PostgreSQL（brew services start postgresql@16）。
 */
class TcpE2eEnvironment : AutoCloseable {
    private val testId = UUID.randomUUID().toString().replace("-", "").take(12)
    private val testRoot = File("/tmp/tk-e2e-${testId}")

    private val tokensDir = File(testRoot, "tokens")
    private val msgsDir = File(testRoot, "msgs")
    private val searchDir = File(testRoot, "search")
    private val fileStoreDir = File(testRoot, "file-store")

    private val pgUser = System.getenv("TK_E2E_PG_USER") ?: System.getProperty("user.name")
    private val pgJdbc = "jdbc:postgresql://localhost:5432/teamtalk?user=$pgUser"

    private val koinApp = koinApplication {
        modules(createServerModule(
            tokenStorePath = tokensDir.absolutePath,
            messageStorePath = msgsDir.absolutePath,
            searchIndexPath = searchDir,
            fileStoreDbPath = File(fileStoreDir, "rocksdb").absolutePath,
            fileStoreFsPath = File(fileStoreDir, "files").absolutePath,
        ))
    }
    private val koin = koinApp.koin

    private val tcpServer = TcpServer(port = 0)
    val tcpPort: Int

    init {
        testRoot.mkdirs()
        // 团队库存在性保证（与 local profile 共用；首次运行自动创建）
        DriverManager.getConnection("jdbc:postgresql://localhost:5432/postgres?user=$pgUser").use { conn ->
            val rs = conn.createStatement().executeQuery("SELECT 1 FROM pg_database WHERE datname='teamtalk'")
            if (!rs.next()) conn.createStatement().execute("CREATE DATABASE teamtalk")
        }
        // 清场：TRUNCATE 全部业务表（自增序列重置）
        DriverManager.getConnection(pgJdbc).use { conn ->
            val tables = conn.createStatement().executeQuery(
                "SELECT tablename FROM pg_tables WHERE schemaname='public'").let { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
            if (tables.isNotEmpty()) {
                conn.createStatement().execute("TRUNCATE ${tables.joinToString(", ")} RESTART IDENTITY CASCADE")
            }
        }
        DatabaseFactory.create(jdbcUrl = pgJdbc, user = pgUser, password = "", maxPoolSize = 4)
        koin.get<MessageStore>().init()
        koin.get<FileStore>().init()
        koin.get<SearchIndex>().start()

        tcpServer.start { channel, recorder, ioExecutor ->
            ImAgent(
                channel = channel,
                recorder = recorder,
                authService = koin.get(),
                clientRegistry = koin.get(),
                rpcDispatcher = koin.get(),
                messageService = koin.get(),
                chatStore = koin.get(),
                messageStore = koin.get(),
                syncEvents = koin.get(),
                events = koin.get(),
                presenceService = koin.get(),
                ioExecutor = ioExecutor,
            )
        }
        tcpPort = tcpServer.actualPort
    }

    /** 测试辅助：用户名 → uid。 */
    fun uidOf(username: String): String {
        return java.sql.DriverManager.getConnection(pgJdbc).use { conn ->
            conn.createStatement().executeQuery("SELECT uid FROM users WHERE username = '$username'").use {
                it.next(); it.getString(1)
            }
        }
    }

    /** 测试辅助：直连 FileStore 存文件，返回相对 path（媒体消息附件存在性校验用）。 */
    fun storeFile(bytes: ByteArray, fileName: String, contentType: String = "application/octet-stream"): com.virjar.tk.model.Attachment {
        val store = koin.get<FileStore>()
        val path = store.store("e2e-staging", fileName, contentType, bytes.inputStream())
        return requireNotNull(store.getAttachment(path))
    }

    /** 测试辅助：直接执行 SQL（造状态用）。 */
    fun jdbcExec(sql: String) {
        java.sql.DriverManager.getConnection(pgJdbc).use { conn ->
            conn.createStatement().execute(sql)
        }
    }

    override fun close() {
        tcpServer.stop()
        koin.get<SearchIndex>().stop()
        koin.get<MessageStore>().close()
        koin.get<FileStore>().close()
        koin.get<TokenStore>().close()
        koin.get<ClientRegistry>().stop()
        koinApp.close()
        testRoot.deleteRecursively()
    }
}
