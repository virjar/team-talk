package com.virjar.tk.e2e

import com.virjar.tk.di.createServerModule
import com.virjar.tk.application.admin.AdminService
import com.virjar.tk.domain.auth.AccessTokenValidator
import com.virjar.tk.infra.db.DatabaseFactory
import com.virjar.tk.infra.search.SearchIndex
import com.virjar.tk.infra.storage.FileStore
import com.virjar.tk.infra.storage.MessageStore
import com.virjar.tk.infra.sync.ClientRegistry
import com.virjar.tk.infra.sync.SyncEventDispatcher
import com.virjar.tk.protocol.TcpServer
import com.virjar.tk.protocol.codec.ImAgent
import com.virjar.tk.testing.PostgresSchemaLease
import kotlinx.coroutines.runBlocking
import org.koin.dsl.koinApplication
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * E2E 协议测试环境：进程内 Koin + TcpServer + 真实 PostgreSQL。
 *
 * 每个环境租用独立的 `tt_test_*` schema；关闭或启动失败时均以 `DROP SCHEMA ... CASCADE` 回收，
 * 不读取或修改开发环境的 public schema。连接由 TK_TEST_PG_JDBC/USER/PASSWORD 配置。
 */
class TcpE2eEnvironment : AutoCloseable {
    private val testId = UUID.randomUUID().toString().replace("-", "").take(12)
    private val testRoot = File("/tmp/tk-e2e-${testId}")

    private val msgsDir = File(testRoot, "msgs")
    private val searchDir = File(testRoot, "search")
    private val fileStoreDir = File(testRoot, "file-store")

    private val koinApp = koinApplication {
        modules(createServerModule(
            messageStorePath = msgsDir.absolutePath,
            searchIndexPath = searchDir,
            fileStoreDbPath = File(fileStoreDir, "rocksdb").absolutePath,
            fileStoreFsPath = File(fileStoreDir, "files").absolutePath,
        ))
    }
    private val koin = koinApp.koin
    private val tcpServer = TcpServer(port = 0)
    private val postgres = PostgresSchemaLease.open()
    private val closed = AtomicBoolean(false)
    private var clientRegistry: ClientRegistry? = null
    private var syncEventDispatcher: SyncEventDispatcher? = null

    val tcpPort: Int
    val adminService: AdminService get() = koin.get()
    val accessTokenValidator: AccessTokenValidator get() = koin.get()

    init {
        try {
            testRoot.mkdirs()
            DatabaseFactory.create(
                jdbcUrl = postgres.jdbcUrl,
                user = postgres.user,
                password = postgres.password,
                maxPoolSize = 4,
            )
            koin.get<MessageStore>().init()
            koin.get<FileStore>().init()
            koin.get<SearchIndex>().start()

            // Match the production runtime ordering: the registry owns online delivery, while the
            // durable dispatcher must finish its recovery scan before TCP accepts any client.
            clientRegistry = koin.get()
            syncEventDispatcher = koin.get<SyncEventDispatcher>().also { dispatcher ->
                dispatcher.start()
                runBlocking { dispatcher.awaitStartupScan() }
            }

            tcpServer.start { channel, recorder, ioExecutor ->
                ImAgent(
                    channel = channel,
                    recorder = recorder,
                    authService = koin.get(),
                    clientRegistry = koin.get(),
                    rpcDispatcher = koin.get(),
                    messageService = koin.get(),
                    chatAccess = koin.get(),
                    syncEvents = koin.get(),
                    events = koin.get(),
                    ioExecutor = ioExecutor,
                )
            }
            tcpPort = tcpServer.actualPort
        } catch (error: Throwable) {
            runCatching { close() }.onFailure(error::addSuppressed)
            throw error
        }
    }

    /** 测试辅助：用户名 → uid。 */
    fun uidOf(username: String): String {
        return postgres.openConnection().use { connection ->
            connection.prepareStatement("SELECT uid FROM users WHERE username = ?").use { statement ->
                statement.setString(1, username)
                statement.executeQuery().use {
                    it.next()
                    it.getString(1)
                }
            }
        }
    }

    /** 测试辅助：直连 FileStore 存文件，返回相对 path（媒体消息附件存在性校验用）。 */
    fun storeFile(
        ownerUid: String,
        bytes: ByteArray,
        fileName: String,
        contentType: String = "application/octet-stream",
    ): com.virjar.tk.model.Attachment {
        val store = koin.get<FileStore>()
        val path = store.store(ownerUid, fileName, contentType, bytes.inputStream())
        return requireNotNull(store.getAttachment(path))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var failure: Throwable? = null
        fun cleanUp(action: () -> Unit) {
            runCatching(action).onFailure { error ->
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        cleanUp { tcpServer.stop() }
        cleanUp { syncEventDispatcher?.close() }
        cleanUp { koin.get<SearchIndex>().stop() }
        cleanUp { koin.get<MessageStore>().close() }
        cleanUp { koin.get<FileStore>().close() }
        cleanUp { clientRegistry?.stop() }
        cleanUp { DatabaseFactory.close() }
        cleanUp { koinApp.close() }
        cleanUp { postgres.close() }
        cleanUp { testRoot.deleteRecursively() }
        failure?.let { throw it }
    }
}
