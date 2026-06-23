package com.virjar.tk.e2e

import com.virjar.tk.domain.auth.TokenStore
import com.virjar.tk.di.createServerModule
import com.virjar.tk.infra.db.DatabaseFactory
import com.virjar.tk.infra.search.SearchIndex
import com.virjar.tk.infra.storage.FileStore
import com.virjar.tk.infra.storage.MessageStore
import com.virjar.tk.infra.sync.ClientRegistry
import com.virjar.tk.protocol.TcpServer
import com.virjar.tk.protocol.codec.ImAgent
import com.virjar.tk.protocol.dispatcher.RpcDispatcher
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.koin.dsl.koinApplication
import java.io.File
import java.util.UUID

/**
 * E2E 协议测试环境。
 * 启动完整的 PG + Koin + TcpServer，客户端通过真实 TCP 连接测试。
 */
class TcpE2eEnvironment : AutoCloseable {
    private val testId = UUID.randomUUID().toString()
    private val testRoot = File("/tmp/tk-e2e-${testId}")

    private val tokensDir = File(testRoot, "tokens")
    private val msgsDir = File(testRoot, "msgs")
    private val searchDir = File(testRoot, "search")
    private val fileStoreDir = File(testRoot, "file-store")

    private val embeddedPg = EmbeddedPostgres.builder().start()

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
        DatabaseFactory.create(
            jdbcUrl = embeddedPg.getJdbcUrl("postgres", "postgres"),
            user = "postgres",
            password = "postgres",
        )
        koin.get<MessageStore>().init()
        koin.get<FileStore>().init()
        koin.get<SearchIndex>().start()

        tcpServer.start { channel, recorder, ioExecutor ->
            ImAgent(
                channel,
                recorder,
                koin.get(),
                koin.get(),
                koin.get(),
                koin.get(),
                koin.get(),
                koin.get(),
                koin.get(),
                koin.get(),
                ioExecutor,
            )
        }
        tcpPort = tcpServer.actualPort
    }

    override fun close() {
        tcpServer.stop()
        koin.get<SearchIndex>().stop()
        koin.get<MessageStore>().close()
        koin.get<TokenStore>().close()
        koin.get<ClientRegistry>().stop()
        koinApp.close()
        embeddedPg.close()
        testRoot.deleteRecursively()
    }
}
