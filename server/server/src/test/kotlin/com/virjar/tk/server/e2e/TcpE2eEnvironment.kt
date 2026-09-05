package com.virjar.tk.server.e2e

import com.virjar.tk.server.di.createServerModule
import com.virjar.tk.server.application.admin.AdminService
import com.virjar.tk.server.application.admin.ClientTelemetryAdminService
import com.virjar.tk.server.domain.auth.AccessTokenValidator
import com.virjar.tk.server.domain.auth.AuthenticationAttemptGuard
import com.virjar.tk.server.domain.auth.AuthenticationAttemptGuardConfig
import com.virjar.tk.server.domain.auth.AuthenticationOperation
import com.virjar.tk.server.domain.auth.AuthenticationOperationLimits
import com.virjar.tk.server.domain.telemetry.ConnectionTraceEventStore
import com.virjar.tk.server.domain.telemetry.ClientTelemetryControlRepository
import com.virjar.tk.server.domain.telemetry.ClientTelemetryEventStore
import com.virjar.tk.server.domain.telemetry.ClientTelemetryPolicy
import com.virjar.tk.server.domain.telemetry.ConnectionTraceStoreSnapshot
import com.virjar.tk.server.domain.telemetry.ConnectionTraceContext as StoredConnectionTraceContext
import com.virjar.tk.server.domain.telemetry.TelemetryBatchDraft
import com.virjar.tk.server.domain.telemetry.TelemetryEventDraft
import com.virjar.tk.server.domain.telemetry.TelemetryRuntimeSnapshot
import com.virjar.tk.server.domain.telemetry.TelemetrySearchQuery
import com.virjar.tk.server.infra.db.DatabaseFactory
import com.virjar.tk.server.infra.db.PostgresDatabase
import com.virjar.tk.server.infra.health.TcpHealthProbeConfiguration
import com.virjar.tk.server.infra.search.SearchIndex
import com.virjar.tk.server.infra.storage.FileStore
import com.virjar.tk.server.infra.storage.MessageStore
import com.virjar.tk.server.infra.sync.ClientRegistry
import com.virjar.tk.server.infra.sync.SyncEventDispatcher
import com.virjar.tk.server.protocol.TcpServer
import com.virjar.tk.server.protocol.TcpServerConfiguration
import com.virjar.tk.protocol.ProtocolVersions
import com.virjar.tk.server.protocol.connection.ImAgent
import com.virjar.tk.protocol.telemetry.TelemetryEventKind
import com.virjar.tk.protocol.telemetry.ConnectionTraceContext as WireConnectionTraceContext
import com.virjar.tk.server.testing.PostgresSchemaLease
import kotlinx.coroutines.runBlocking
import org.koin.core.Koin
import org.koin.core.KoinApplication
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
class TcpE2eEnvironment(
    private val protocolConfiguration: com.virjar.tk.server.protocol.ServerProtocolConfiguration =
        com.virjar.tk.server.protocol.ServerProtocolConfiguration(),
) : AutoCloseable {
    private val testId = UUID.randomUUID().toString().replace("-", "").take(12)
    private val testRoot = File("/tmp/tk-e2e-${testId}")

    private val msgsDir = File(testRoot, "msgs")
    private val searchDir = File(testRoot, "search")
    private val fileStoreDir = File(testRoot, "file-store")

    private val tcpServerConfiguration = TcpServerConfiguration.plaintext(port = 0)
    private var tcpServer: TcpServer? = null
    private val postgres = PostgresSchemaLease.open()
    private val closed = AtomicBoolean(false)
    // close() 中的部分初始化检查使这里有意使用 lateinit。
    @Suppress("UNNECESSARY_LATEINIT")
    private lateinit var postgresDatabase: PostgresDatabase
    @Suppress("UNNECESSARY_LATEINIT")
    private lateinit var koinApp: KoinApplication
    @Suppress("UNNECESSARY_LATEINIT")
    private lateinit var koin: Koin
    private var clientRegistry: ClientRegistry? = null
    private var syncEventDispatcher: SyncEventDispatcher? = null

    val tcpPort: Int
    val syncDatasetId: String get() = postgresDatabase.datasetId
    val adminService: AdminService get() = koin.get()
    val clientTelemetryAdminService: ClientTelemetryAdminService get() = koin.get()
    val accessTokenValidator: AccessTokenValidator get() = koin.get()

    suspend fun enableConnectionTrace(uid: String): ClientTelemetryPolicy {
        val now = System.currentTimeMillis()
        val policy = koin.get<ClientTelemetryControlRepository>().enableDiagnosticPolicy(
            uid = uid,
            deviceId = null,
            reason = "e2e connection trace",
            expiresAt = now + 60_000L,
            actor = "e2e-admin",
            now = now,
        )
        checkNotNull(clientRegistry).refreshConnectionTracePolicy(uid)
        return policy
    }

    suspend fun disableConnectionTrace(policyId: String): ClientTelemetryPolicy {
        val policy = checkNotNull(
            koin.get<ClientTelemetryControlRepository>().disablePolicy(
                policyId = policyId,
                actor = "e2e-admin",
                now = System.currentTimeMillis(),
            ),
        )
        checkNotNull(clientRegistry).refreshConnectionTracePolicy(policy.uid, policy.deviceId)
        return policy
    }

    fun connectionTraceSnapshot(): ConnectionTraceStoreSnapshot =
        koin.get<ConnectionTraceEventStore>().snapshot()

    /**
     * 将一条客户端发起的诊断事件持久化到生产遥测索引中，
     * 并返回其精确的内部记录 id，供管理员关联场景使用。
     */
    suspend fun ingestClientTelemetryEvent(
        uid: String,
        deviceId: String,
        context: WireConnectionTraceContext,
        eventName: String,
    ): Long {
        val now = System.currentTimeMillis().coerceAtLeast(1L)
        val eventId = UUID.randomUUID().toString()
        val store = koin.get<ClientTelemetryEventStore>()
        store.ingest(
            uid = uid,
            deviceId = deviceId,
            batch = TelemetryBatchDraft(
                batchId = UUID.randomUUID().toString(),
                payloadSha256 = UUID.randomUUID().toString().replace("-", "").repeat(2),
                createdAt = now,
                runtime = TelemetryRuntimeSnapshot(
                    platform = "desktop",
                    osName = "E2E",
                    osVersion = "1",
                    architecture = "test",
                    deviceModel = "TcpE2EClient",
                    appVersion = "1",
                    buildNumber = "1",
                    gitCommit = "e2e",
                    buildIdentity = "connection-trace-e2e",
                    buildTime = "2026-01-01T00:00:00Z",
                    protocolVersion = ProtocolVersions.CURRENT_ID,
                    distribution = "test",
                ),
                events = listOf(
                    TelemetryEventDraft(
                        eventId = eventId,
                        runId = UUID.randomUUID().toString(),
                        sequence = 0L,
                        occurredAt = now,
                        category = TelemetryEventKind.ACTION.name,
                        eventName = eventName,
                        message = "$eventName succeeded",
                        searchText = "$eventName succeeded",
                        connectionTraceContext = StoredConnectionTraceContext(
                            correlationId = context.correlationId,
                            traceId = context.traceId,
                            sessionId = context.sessionId,
                            connectionGeneration = context.connectionGeneration,
                            policyRevision = context.policyRevision,
                        ),
                    ),
                ),
            ),
            receivedAt = now,
            sourceBytes = 1_024,
        )
        return store.search(
            TelemetrySearchQuery(
                uid = uid,
                deviceId = deviceId,
                eventName = eventName,
                receivedAtFrom = now,
                receivedAtUntil = now,
            ),
            offset = 0,
            limit = 10,
        ).hits.single { it.event.event.eventId == eventId }.event.id
    }

    init {
        try {
            testRoot.mkdirs()
            postgresDatabase = DatabaseFactory.create(
                jdbcUrl = postgres.jdbcUrl,
                user = postgres.user,
                password = postgres.password,
                maxPoolSize = 4,
            )
            koinApp = koinApplication {
                modules(createServerModule(
                    database = postgresDatabase.database,
                    syncDatasetId = postgresDatabase.datasetId,
                    tcpServerConfiguration = tcpServerConfiguration,
                    // 该容器会在下方启动进程持有的随机端口服务器，并且从不解析 HealthChecker。
                    // 端口 1 是显式的未使用 DI 占位符，而不是断言随机监听器运行在生产环境的 5100 上。
                    tcpHealthProbeConfiguration =
                        TcpHealthProbeConfiguration.plaintext(port = 1),
                    messageStorePath = msgsDir.absolutePath,
                    searchIndexPath = searchDir,
                    clientTelemetryIndexPath = File(testRoot, "telemetry-search"),
                    connectionTraceIndexPath = File(testRoot, "connection-traces"),
                    fileStoreDbPath = File(fileStoreDir, "rocksdb").absolutePath,
                    fileStoreFsPath = File(fileStoreDir, "files").absolutePath,
                    authenticationAttemptGuardFactory = { trustedE2eAuthenticationAttemptGuard() },
                ))
            }
            koin = koinApp.koin
            koin.get<MessageStore>().init()
            koin.get<FileStore>().init()
            koin.get<SearchIndex>().start()
            check(koin.get<ClientTelemetryEventStore>().start())
            check(koin.get<ConnectionTraceEventStore>().start())

            // 匹配生产环境的运行时顺序：注册表负责在线投递，
            // 而持久化分发器必须在 TCP 接受任何客户端之前完成恢复扫描。
            clientRegistry = koin.get()
            syncEventDispatcher = koin.get<SyncEventDispatcher>().also { dispatcher ->
                dispatcher.start()
                runBlocking { dispatcher.awaitStartupScan() }
            }

            // 解析生产环境使用的同一个 connection-trace sink；否则 E2E 会在不知不觉中
            // 使用 TcpServer 可丢弃的 no-op 默认实现，而不是真正持久化。
            val startedTcpServer = TcpServer(tcpServerConfiguration, traceEventSink = koin.get())
            tcpServer = startedTcpServer
            startedTcpServer.start { channel, recorder, ioExecutor ->
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
                    authenticationAttempts = koin.get(),
                    protocolConfiguration = protocolConfiguration,
                )
            }
            tcpPort = startedTcpServer.actualPort
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
    ): com.virjar.tk.protocol.model.Attachment {
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
        tcpServer?.let { server -> cleanUp { server.stop() } }
        cleanUp { syncEventDispatcher?.close() }
        if (::koin.isInitialized) {
            cleanUp { koin.get<SearchIndex>().stop() }
            cleanUp { koin.get<ConnectionTraceEventStore>().close() }
            cleanUp { koin.get<ClientTelemetryEventStore>().close() }
            cleanUp { koin.get<MessageStore>().close() }
            cleanUp { koin.get<FileStore>().close() }
        }
        cleanUp { clientRegistry?.stop() }
        if (::koinApp.isInitialized) cleanUp { koinApp.close() }
        if (::postgresDatabase.isInitialized) cleanUp { postgresDatabase.close() }
        cleanUp { postgres.close() }
        cleanUp { testRoot.deleteRecursively() }
        failure?.let { throw it }
    }
}

/**
 * 仅面向 loopback、进程持有的 E2E 服务器的准入策略。
 *
 * ProtocolE2eTest 有意在整个套件中复用一个服务器，因此生产的来源配额会让测试顺序和
 * 墙钟冷却时间变成可观测因素。把这个覆盖保留在组合边界：
 * 生产环境仍然从环境解析其有界默认值。
 */
internal fun trustedE2eAuthenticationAttemptGuard(
    monotonicNanos: () -> Long = System::nanoTime,
): AuthenticationAttemptGuard {
    val trustedTrafficLimits = AuthenticationOperation.entries.associateWith {
        AuthenticationOperationLimits(
            operationAttempts = TRUSTED_E2E_ATTEMPT_BUDGET,
            sourceAttempts = TRUSTED_E2E_ATTEMPT_BUDGET,
            accountAttempts = TRUSTED_E2E_ATTEMPT_BUDGET,
        )
    }
    return AuthenticationAttemptGuard(
        AuthenticationAttemptGuardConfig(
            globalAttempts = TRUSTED_E2E_ATTEMPT_BUDGET,
            limits = trustedTrafficLimits,
        ),
        monotonicNanos,
    )
}

private const val TRUSTED_E2E_ATTEMPT_BUDGET = 100_000
