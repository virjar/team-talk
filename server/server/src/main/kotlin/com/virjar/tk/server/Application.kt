package com.virjar.tk.server

import com.virjar.tk.server.api.clientTelemetryRoutes
import com.virjar.tk.server.api.clientDownloadRoutes
import com.virjar.tk.server.api.publicSiteRoutes
import com.virjar.tk.server.api.inviteLandingRoutes
import com.virjar.tk.server.api.clientUpdateRoutes
import com.virjar.tk.server.api.adminRoutes
import com.virjar.tk.server.api.AttachmentUploadAdmission
import com.virjar.tk.server.api.documentExportRoutes
import com.virjar.tk.server.api.fileRoutes
import com.virjar.tk.server.api.botRoutes
import com.virjar.tk.server.application.PresenceCoordinator
import com.virjar.tk.server.di.createServerModule
import com.virjar.tk.server.domain.auth.AuthService
import com.virjar.tk.server.domain.auth.AccessTokenValidator
import com.virjar.tk.server.domain.attachment.AttachmentRetentionService
import com.virjar.tk.server.domain.bot.BotService
import com.virjar.tk.server.domain.chat.ChatAccess
import com.virjar.tk.server.infra.health.HealthChecker
import com.virjar.tk.server.infra.ServerDataEpoch
import com.virjar.tk.server.domain.message.MessageService
import com.virjar.tk.server.domain.message.MessageProjector
import com.virjar.tk.server.domain.organization.OrganizationService
import com.virjar.tk.server.domain.telemetry.ClientTelemetryControlRepository
import com.virjar.tk.server.domain.telemetry.ClientTelemetryEventStore
import com.virjar.tk.server.domain.telemetry.ConnectionTraceEventStore
import com.virjar.tk.server.domain.telemetry.ConnectionTraceStoragePolicy
import com.virjar.tk.server.domain.telemetry.TelemetryStoragePolicy
import com.virjar.tk.server.infra.db.DatabaseFactory
import com.virjar.tk.server.infra.db.ReliableCommandReceiptMaintenance
import com.virjar.tk.server.infra.search.SearchIndex
import com.virjar.tk.server.infra.storage.FileStore
import com.virjar.tk.server.infra.storage.MessageStore
import com.virjar.tk.server.infra.sync.ClientRegistry
import com.virjar.tk.server.infra.sync.SyncEventService
import com.virjar.tk.server.protocol.TcpServer
import com.virjar.tk.server.protocol.connection.ImAgent
import com.virjar.tk.server.protocol.dispatcher.RpcDispatcher
import com.virjar.tk.server.runtime.HttpBlockingExecutor
import com.virjar.tk.server.runtime.HEALTH_CHECK_PATH
import com.virjar.tk.server.runtime.MaintenanceRuntime
import com.virjar.tk.server.runtime.SystemCommandRouter
import com.virjar.tk.server.runtime.ServiceBroadcastRuntime
import com.virjar.tk.server.domain.message.ServiceAccountMessages
import com.virjar.tk.server.runtime.MaintenanceWorker
import com.virjar.tk.server.runtime.RuntimeFailureCollector
import com.virjar.tk.server.runtime.ServerResourceOwner
import com.virjar.tk.server.runtime.configureProtectedHttpEventLoops
import com.virjar.tk.server.runtime.installHttpBlockingBoundary
import com.virjar.tk.server.runtime.mergeRuntimeFailure
import com.virjar.tk.server.runtime.runClientTelemetryRetentionStep
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.response.*
import io.ktor.server.http.content.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koin.ktor.plugin.Koin
import org.koin.ktor.ext.getKoin
import org.slf4j.LoggerFactory
import java.util.UUID

private const val ATTACHMENT_RETENTION_INTERVAL_MILLIS = 60L * 60L * 1_000L
private const val RELIABLE_COMMAND_RECEIPT_RETENTION_INTERVAL_MILLIS = 60L * 60L * 1_000L
private const val RELIABLE_COMMAND_RECEIPT_CATCH_UP_INTERVAL_MILLIS = 5_000L
private const val RELIABLE_COMMAND_RECEIPT_FAILURE_RETRY_INTERVAL_MILLIS = 60_000L
private const val SYNC_EVENT_RETENTION_INTERVAL_MILLIS = 60L * 60L * 1_000L
private const val SYNC_EVENT_RETENTION_CATCH_UP_MILLIS = 5_000L
private const val SYNC_EVENT_RETENTION_RETRY_MILLIS = 60_000L
private const val CLIENT_TELEMETRY_RETENTION_INTERVAL_MILLIS = 60L * 60L * 1_000L
private const val CLIENT_TELEMETRY_RETENTION_CATCH_UP_MILLIS = 5_000L
private const val CONNECTION_TRACE_RETENTION_INTERVAL_MILLIS = 60L * 60L * 1_000L
private const val CONNECTION_TRACE_RETENTION_RETRY_MILLIS = 60_000L
private const val CALL_SERVICE_SHUTDOWN_TIMEOUT_MILLIS = 5_000L

fun main() {
    // 0. Environment 必须先于 logback 初始化，确保 LOG_DIR 系统属性已设置
    val env = com.virjar.tk.server.env.Environment
    System.setProperty("LOG_DIR", env.logsDir.absolutePath)
        val logger = LoggerFactory.getLogger("Application")

    logger.info("TeamTalk Server starting...")
    // 显式记录关键路径解析结果，便于排查「日志丢失/数据目录错误」类问题
    logger.info("Environment resolved: isDevelopment={}, dataRoot={}, logsDir={}, classPathDir={}",
        env.isDevelopment, env.dataRoot.absolutePath, env.logsDir.absolutePath, env.runtimeClassPathDir.absolutePath)
    startServer()
}

/**
 * 启动 Ktor Netty 引擎。
 *
 * 运行时保留无需证书的 HTTP 入口，供低成本部署使用。是否启用 TCP TLS 由
 * serverTcpTransportConfiguration 根据 keystore 配置决定，不由 HTTP 监听地址推断。
 * SDK 地址校验和部署工具仍有独立约束，三者的实际支持范围见 doc/07-operations/configuration.md。
 *
 * 环境变量：
 *   - KTOR_PORT：HTTP 监听端口（默认 8080）
 *   - KTOR_SSL_PORT / SSL_KEYSTORE / SSL_KEYSTORE_PASSWORD / SSL_PRIVATE_KEY_PASSWORD
 *
 * 行为：
 *   - 未启用 HTTPS connector → 只启动 HTTP（绑定 0.0.0.0）。
 *   - 配置了 HTTPS 端口与可加载 keystore → 关闭 HTTP，只启动 HTTPS。
 */
private fun startServer() {
    val logger = LoggerFactory.getLogger("Application")
    val processEnvironment = System.getenv()
    val httpPort = (processEnvironment["KTOR_PORT"] ?: "8080").toInt()
    val sslPort = processEnvironment["KTOR_SSL_PORT"]?.toIntOrNull()
    val keystorePath = processEnvironment["SSL_KEYSTORE"]
    val tlsMaterial = loadServerTlsMaterial(processEnvironment)
    val tcpTransport = serverTcpTransportConfiguration(processEnvironment, tlsMaterial)

    // 预构造 HTTPS connector（若配置），避免在 configure lambda 内做带日志的复杂逻辑
    val sslEnabled = sslPort != null && tlsMaterial != null
    val sslConnectorConfig: EngineSSLConnectorBuilder? = if (sslEnabled) {
        logger.info(
            "HTTPS enabled on port {} (keystore={}, alias={}); HTTP disabled",
            sslPort, keystorePath, tlsMaterial.keyAlias,
        )
        EngineSSLConnectorBuilder(
            tlsMaterial.keyStore,
            tlsMaterial.keyAlias,
            tlsMaterial::keyStorePasswordCopy,
            tlsMaterial::privateKeyPasswordCopy,
        ).apply { port = sslPort }
    } else {
        logger.info(
            "HTTPS connector disabled; plaintext HTTP enabled on 0.0.0.0:{}",
            httpPort,
        )
        null
    }

    val resources = applicationResources(logger)
    val server = embeddedServer(
        factory = Netty,
        environment = applicationEnvironment { log = logger },
        configure = {
            configureProtectedHttpEventLoops()
            if (!sslEnabled) {
                // 明文模式：HTTP 绑定所有接口，远程客户端和管理界面均可通过 HTTP 访问。
                connector {
                    host = "0.0.0.0"
                    port = httpPort
                }
            }
            // SSL 模式：只暴露 HTTPS，主动关闭 HTTP（生产安全能力）。
            sslConnectorConfig?.let { connectors.add(it) }
        },
    ) {
        module(resources, tcpTransport)
    }
    startAndWaitForManagedServer(server, resources)
}

fun Application.module() {
    val logger = LoggerFactory.getLogger("Application")
    val processEnvironment = System.getenv()
    val tlsMaterial = loadServerTlsMaterial(processEnvironment)
    module(
        applicationResources(logger),
        serverTcpTransportConfiguration(processEnvironment, tlsMaterial),
    )
}

private fun applicationResources(logger: org.slf4j.Logger): ServerResourceOwner =
    ServerResourceOwner { name, error ->
        logger.warn("Failed to close server resource {}", name, error)
    }

/**
 * Ktor 在 Netty 绑定 connector 之前先完成 Application 的配置，因此绑定失败发生时，
 * TeamTalk 已经打开了 TCP、数据库、本地存储和后台 worker。Ktor 的启动失败路径不会
 * 销毁这个配置成功的 Application，所以外层 owner 必须停止引擎，并显式重放该
 * Application 的资源终结动作。
 */
internal fun <TEngine : ApplicationEngine, TConfiguration : ApplicationEngine.Configuration>
startAndWaitForManagedServer(
    server: EmbeddedServer<TEngine, TConfiguration>,
    resources: ServerResourceOwner,
) {
    var terminalFailure: Throwable? = null
    try {
        server.start(wait = true)
    } catch (failure: Throwable) {
        terminalFailure = failure
    }

    val cleanupFailures = RuntimeFailureCollector()
    cleanupFailures.capture { server.stop(gracePeriodMillis = 0, timeoutMillis = 30_000) }
    // ApplicationStopping 监听器被 Ktor 刻意隔离；再次 close 才能让进程观察并重放
    // ServerResourceOwner 保留的确切终结失败。
    cleanupFailures.capture { resources.close() }
    cleanupFailures.failureOrNull()?.let { cleanupFailure ->
        terminalFailure = mergeRuntimeFailure(terminalFailure, cleanupFailure)
    }
    terminalFailure?.let { throw it }
}

internal fun Application.module(
    resources: ServerResourceOwner,
    tcpTransport: ServerTcpTransportConfiguration,
) {
    val logger = LoggerFactory.getLogger("Application")
    try {
        val protocolConfiguration = com.virjar.tk.server.protocol.ServerProtocolConfiguration
            .fromEnvironment(System.getenv())
        logger.info("Protocol compatibility window: {}", protocolConfiguration.supported)
        // 存储兼容性与网络协商无关。不匹配时保留既有数据，并在打开 JDBC 连接池或
        // 启动依赖资源之前先失败退出。
        val dataRoot = com.virjar.tk.server.env.Environment.dataRoot
        ServerDataEpoch.initializeOrValidate(dataRoot)

        // 1. 数据库所有权先于 DI：本 Application 只绑定一个 Exposed Database，
        // 资源 owner 在所有依赖服务停止后只关闭与之匹配的连接池。
        val postgres = resources.own("PostgreSQL pool", DatabaseFactory.create()) { it.close() }
        // 关系库 schema 与本地所有持久字节属于同一个 cursor 命名空间。
        // 必须在 Koin 构建/打开 RocksDB、Lucene 或 FileStore 之前先校验这组配对。
        ServerDataEpoch.bindOrValidateDataset(dataRoot, postgres.datasetId)

        // 内嵌 TURN/STUN（协议 minor 0.5 通话 NAT 兜底）：未配置 TURN_ENABLED 时不启动
        val turnServer = com.virjar.tk.server.infra.turn.TurnRuntime.fromEnvironment()?.let { options ->
            resources.own("embedded TURN", com.virjar.tk.server.infra.turn.TurnRuntime.start(options)) { it.close() }
        }

        // 2. DI
        install(Koin) {
            modules(
                createServerModule(
                    database = postgres.database,
                    syncDatasetId = postgres.datasetId,
                    tcpServerConfiguration = tcpTransport.server,
                    tcpHealthProbeConfiguration = tcpTransport.health,
                    turnServer = turnServer,
                ),
            )
        }

        // 2.5 CORS：管理后台 SPA 开发态（vite :5173）跨域；生产同源不受影响
        install(io.ktor.server.plugins.cors.routing.CORS) {
            allowHeader(io.ktor.http.HttpHeaders.Authorization)
            allowHeader(io.ktor.http.HttpHeaders.ContentType)
            allowMethod(io.ktor.http.HttpMethod.Post)
            allowMethod(io.ktor.http.HttpMethod.Put)
            allowMethod(io.ktor.http.HttpMethod.Delete)
            allowMethod(io.ktor.http.HttpMethod.Get)
            allowMethod(io.ktor.http.HttpMethod.Options)
            hosts.addAll(listOf("localhost:5173", "127.0.0.1:5173"))
        }

        // 3. JSON
        install(ContentNegotiation) {
            json(Json { prettyPrint = false })
        }
        installSafeErrorHandling()

        // 4. 存储。先注册各实例再 init，使部分完成的原生初始化可以回退清理。
        // 从本 Application 关联的 Ktor 容器解析。进程级全局 Koin 查找会让两个
        // 内嵌 server 实例互相抢占资源，破坏并行测试。
        val koin = getKoin()
        // 在解析 dispatcher 之前注册，因为解析 dispatcher 时也会连带解析 OEM 通知。
        // 逆序关闭时，维护任务先于其外发连接池与 token 持有者停止。
        resources.own("OEM push sender", koin.get<com.virjar.tk.server.infra.push.OemPushSender>()) { it.close() }
        resources.own(
            "password hashing executor",
            koin.get<com.virjar.tk.server.infra.security.BCryptPasswordHasher>(),
        ) { it.close() }
        val messageStore = resources.own("message store", koin.get<MessageStore>()) { it.close() }
        messageStore.init()
        val accessTokens = koin.get<AccessTokenValidator>()
        val fileStore = resources.own("file store", koin.get<FileStore>()) { it.close() }
        fileStore.init()

        // 5. 搜索索引（Lucene + IK）。其生产绑定持有 MessageStore 归档 reader，
        // 因此 start 会在发布 writer 之前先完成完整的受控审计/重建。
        val searchIndex = resources.own("search index", koin.get<SearchIndex>()) { it.stop() }
        searchIndex.start()
        val contentSearch = resources.own("content asset index", koin.get<com.virjar.tk.server.infra.search.ContentAssetSearchIndex>()) { it.close() }
        contentSearch.start()
        contentSearch.recoverBeforeServing()
        val clientTelemetryEvents = resources.own(
            "client telemetry event store",
            koin.get<ClientTelemetryEventStore>(),
        ) { it.close() }
        if (!clientTelemetryEvents.start()) {
            logger.warn(
                "Client telemetry event store failed to start; core messaging remains available and maintenance will retry",
            )
        }
        val connectionTraceEvents = resources.own(
            "connection trace event store",
            koin.get<ConnectionTraceEventStore>(),
        ) { it.close() }
        if (!connectionTraceEvents.start()) {
            logger.warn("Connection trace diagnostics failed to start; core messaging remains available")
        }

        // ClientRegistry 在构造函数中就会启动自身 looper；须在解析使用它的服务之前先接管所有权。
        val clientRegistry = resources.own("client registry", koin.get<ClientRegistry>()) { it.stop() }
        val syncEventDispatcher = resources.own(
            "sync event dispatcher",
            koin.get<com.virjar.tk.server.infra.sync.SyncEventDispatcher>(),
        ) { it.close() }
        syncEventDispatcher.start()
        // PostgreSQL 提交可能早于进程内唤醒。在强制的持久兜底扫描成功之前，不得恢复
        // 投影、打开 TCP 或注册健康检查路由。
        runBlocking(Dispatchers.IO) { syncEventDispatcher.awaitStartupScan() }
        val systemCommandRouter = koin.get<SystemCommandRouter>()
        // 回复晚于原消息提交，必须在 TCP 关闭后、同步/存储关闭前实际排空。
        resources.ownDependencyBarrier(
            name = "system command replies",
            resource = systemCommandRouter,
            close = SystemCommandRouter::close,
            dependenciesMayClose = SystemCommandRouter::workersTerminated,
        )
        // 服务号广播同理：执行中断的尾部留待启动续跑，已写记录由回复恢复排空。
        resources.ownDependencyBarrier(
            name = "service broadcasts",
            resource = koin.get<ServiceBroadcastRuntime>(),
            close = ServiceBroadcastRuntime::close,
            dependenciesMayClose = ServiceBroadcastRuntime::workersTerminated,
        )
        val authService = koin.get<AuthService>()
        val rpcDispatcher = koin.get<RpcDispatcher>()
        val msgService = koin.get<MessageService>()
        val messageProjector = koin.get<MessageProjector>()
        val chatAccess = koin.get<ChatAccess>()
        val syncEventService = koin.get<SyncEventService>()
        val organizationService = koin.get<OrganizationService>()
        val botService = koin.get<BotService>()
        val recoveredProjections = runBlocking(Dispatchers.IO) {
            recoverStartupProjections(organizationService, botService, messageProjector)
        }
        if (recoveredProjections > 0) {
            logger.info("Recovered {} pending message projections", recoveredProjections)
        }
        // 服务号回复发件箱在投影恢复之后、TCP 之前同步排空：原消息已提交而回复
        // 未完成的记录在这里按冻结身份补发；暂态失败留待下一次启动。
        val recoveredServiceReplies = runBlocking(Dispatchers.IO) {
            systemCommandRouter.recoverPendingServiceReplies()
        }
        if (recoveredServiceReplies > 0) {
            logger.info("Attempted {} pending service command replies", recoveredServiceReplies)
        }
        // 上次进程关闭时未完成的广播重新执行；已送达用户按稳定身份幂等跳过。
        val serviceBroadcastRuntime = koin.get<ServiceBroadcastRuntime>()
        val resumedBroadcasts = koin.get<ServiceAccountMessages>()
            .resumeUnfinishedBroadcasts(serviceBroadcastRuntime::launch)
        if (resumedBroadcasts.isNotEmpty()) {
            logger.info("Resumed {} unfinished service broadcasts", resumedBroadcasts.size)
        }

        // CallService 必须比 coordinator 的 observer 租约存活更久，但要先于其依赖项关闭。
        val calls = resources.ownDependencyBarrier(
            name = "call service",
            resource = koin.get<com.virjar.tk.server.domain.call.CallService>(),
            close = { service ->
                runBlocking(Dispatchers.IO) {
                    withTimeout(CALL_SERVICE_SHUTDOWN_TIMEOUT_MILLIS) { service.closeAndJoin() }
                }
            },
            dependenciesMayClose = { it.workersTerminated },
        )
        val presenceCoordinator = resources.own(
            "presence coordinator",
            koin.get<PresenceCoordinator>(),
        ) { it.close() }
        presenceCoordinator.start()

        // TCP 在 registry 之后接管，保证逆序关闭时总是先关闭所有 channel。
        val tcpServer = resources.own("TCP server", koin.get<TcpServer>()) { it.stop() }
        tcpServer.start { channel, recorder, ioExecutor ->
            ImAgent(
                channel, recorder, authService, clientRegistry, rpcDispatcher, msgService,
                chatAccess, syncEventService, syncEventService, ioExecutor, koin.get(),
                protocolConfiguration = protocolConfiguration,
            )
        }

        // 7. 健康检查器（HealthChecker）
        val healthChecker = koin.get<HealthChecker>()
        val attachmentRetention = koin.get<AttachmentRetentionService>()
        val tasks = koin.get<com.virjar.tk.server.domain.task.TaskService>()
        val oemPush = koin.get<com.virjar.tk.server.infra.push.OemPushNotifications>()
        val reliableCommandReceiptMaintenance = ReliableCommandReceiptMaintenance(koin.get())
        var reliableCommandReceiptBacklogReported = false

        // 7.5 有界保留维护。持久 sync 事件只有在 checkpoint 引导能重建其当前投影之后
        // 才会删除。一次性的客户端遥测事件使用独立的、基于 receivedAt 的 168 小时
        // Lucene 权威存储；PostgreSQL 只持有控制事实。
        val maintenance = resources.ownDependencyBarrier(
            name = "server maintenance",
            resource = koin.get<MaintenanceRuntime>(),
            close = MaintenanceRuntime::close,
            dependenciesMayClose = MaintenanceRuntime::workersTerminated,
        )
        maintenance.start(
            listOf(
                MaintenanceWorker("call-timeout-sweep") {
                    while (isActive) {
                        try {
                            calls.sweepTimeouts()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            logger.warn("Call timeout sweep remains pending")
                        }
                        delay(5_000L)
                    }
                },
                MaintenanceWorker("oem-push") {
                    while (isActive) {
                        try {
                            oemPush.drainDue()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // Provider 响应与注册凭据绝不写入日志。
                            logger.warn("OEM push maintenance remains pending")
                        }
                        delay(1_000L)
                    }
                },
                MaintenanceWorker("scheduled-message-delivery") {
                    while (isActive) {
                        try {
                            val processed = koin.get<com.virjar.tk.server.domain.message.ScheduledMessageService>()
                                .deliverDue(System.currentTimeMillis())
                            // 有到期积压时快速续扫，稳态低频心跳。
                            if (processed >= 1) delay(500L) else delay(10_000L)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            logger.warn("Scheduled message delivery remains pending", failure)
                            delay(10_000L)
                        }
                    }
                },
                MaintenanceWorker("task-due-reminders") {
                    while (isActive) {
                        val delayMillis = try {
                            val generated = tasks.generateRecurring()
                            val started = tasks.remindStarted()
                            val reminded = tasks.remindDue()
                            tasks.cleanupReceipts()
                            if (generated == 100 || started == 100 || reminded == 100) 1_000L else 30_000L
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            logger.warn("Task reminder maintenance remains pending", failure)
                            30_000L
                        }
                        delay(delayMillis)
                    }
                },
                MaintenanceWorker("content-search-projection") {
                    while (isActive) {
                        try {
                            contentSearch.catchUp()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            logger.warn("Content search projection remains pending", failure)
                        }
                        delay(1_000L)
                    }
                },
                MaintenanceWorker("sync-event-retention") {
                    while (isActive) {
                        val nextDelayMillis = try {
                            val result = syncEventService.cleanupExpiredEvents()
                            if (result.backlogMayRemain) {
                                SYNC_EVENT_RETENTION_CATCH_UP_MILLIS
                            } else {
                                SYNC_EVENT_RETENTION_INTERVAL_MILLIS
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            logger.warn("sync_events cleanup failed", failure)
                            SYNC_EVENT_RETENTION_RETRY_MILLIS
                        }
                        delay(nextDelayMillis)
                    }
                },
                MaintenanceWorker("client-telemetry-retention") {
                    val telemetryControl = koin.get<ClientTelemetryControlRepository>()
                    while (isActive) {
                        val needsCatchUp = runClientTelemetryRetentionStep(
                            now = System.currentTimeMillis(),
                            expirePolicies = telemetryControl::expirePolicies,
                            ensureEventStoreStarted = {
                                clientTelemetryEvents.isAvailable() || clientTelemetryEvents.start()
                            },
                            deleteEventsBefore = clientTelemetryEvents::deleteBefore,
                            warn = { operation, failure ->
                                logger.warn("Client telemetry {} failed", operation, failure)
                            },
                        )
                        val nextDelay = if (needsCatchUp) {
                            CLIENT_TELEMETRY_RETENTION_CATCH_UP_MILLIS
                        } else {
                            CLIENT_TELEMETRY_RETENTION_INTERVAL_MILLIS
                        }
                        delay(nextDelay)
                    }
                },
                MaintenanceWorker("connection-trace-retention") {
                    while (isActive) {
                        val now = System.currentTimeMillis()
                        val available = connectionTraceEvents.isAvailable() || connectionTraceEvents.start()
                        val retained = available && connectionTraceEvents.deleteBefore(
                            (now - ConnectionTraceStoragePolicy.RETENTION_MILLIS).coerceAtLeast(0L),
                        )
                        if (!retained) logger.warn("Connection trace retention failed; diagnostics remain bypassable")
                        delay(
                            if (retained) CONNECTION_TRACE_RETENTION_INTERVAL_MILLIS
                            else CONNECTION_TRACE_RETENTION_RETRY_MILLIS,
                        )
                    }
                },
                MaintenanceWorker("attachment-retention") {
                    while (isActive) {
                        try {
                            val retired = attachmentRetention.cleanupExpiredUnreferenced()
                            if (retired > 0) logger.info("Retired {} unreferenced attachments", retired)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            logger.warn("Unreferenced attachment retention failed", failure)
                        }
                        delay(ATTACHMENT_RETENTION_INTERVAL_MILLIS)
                    }
                },
                MaintenanceWorker("reliable-command-receipt-retention") {
                    while (isActive) {
                        var nextDelayMillis = RELIABLE_COMMAND_RECEIPT_RETENTION_INTERVAL_MILLIS
                        try {
                            val cleaned = reliableCommandReceiptMaintenance.cleanupExpiredReceipts()
                            val total = cleaned.contactReceiptsDeleted + cleaned.inviteReceiptsDeleted +
                                cleaned.documentPolicyReceiptsDeleted + cleaned.documentNodeMoveReceiptsDeleted
                            if (total > 0) {
                                logger.info(
                                    "Retired {} expired reliable-command receipts " +
                                        "(contact={}, invite={}, documentPolicy={}, documentNodeMove={})",
                                    total,
                                    cleaned.contactReceiptsDeleted,
                                    cleaned.inviteReceiptsDeleted,
                                    cleaned.documentPolicyReceiptsDeleted,
                                    cleaned.documentNodeMoveReceiptsDeleted,
                                )
                            }
                            if (cleaned.backlogMayRemain) {
                                nextDelayMillis = RELIABLE_COMMAND_RECEIPT_CATCH_UP_INTERVAL_MILLIS
                                if (!reliableCommandReceiptBacklogReported) {
                                    logger.warn(
                                        "Reliable-command receipt retention entered bounded catch-up mode " +
                                        "(contactBacklog={}, inviteBacklog={}, documentPolicyBacklog={}, " +
                                            "documentNodeMoveBacklog={})",
                                        cleaned.contactBacklogMayRemain,
                                        cleaned.inviteBacklogMayRemain,
                                        cleaned.documentPolicyBacklogMayRemain,
                                        cleaned.documentNodeMoveBacklogMayRemain,
                                    )
                                }
                            } else if (reliableCommandReceiptBacklogReported) {
                                logger.info("Reliable-command receipt retention backlog converged")
                            }
                            reliableCommandReceiptBacklogReported = cleaned.backlogMayRemain
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            logger.warn("Reliable-command receipt retention failed", failure)
                            nextDelayMillis = RELIABLE_COMMAND_RECEIPT_FAILURE_RETRY_INTERVAL_MILLIS
                        }
                        delay(nextDelayMillis)
                    }
                },
                MaintenanceWorker("managed-group-reconciliation") {
                    while (isActive) {
                        delay(5_000L)
                        try {
                            val failures = organizationService.reconcileDueManagedGroups()
                            if (failures.isNotEmpty()) {
                                logger.warn(
                                    "Managed department group runtime drain is still pending for units={}",
                                    failures,
                                )
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            logger.warn("Managed department group runtime drain failed", failure)
                        }
                    }
                },
                MaintenanceWorker("message-projection-recovery") {
                    while (isActive) {
                        delay(5_000L)
                        try {
                            recoverRuntimeMessageProjections(messageProjector).takeIf { it > 0 }?.let { recovered ->
                                logger.info("Recovered {} pending message projections at runtime", recovered)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            // 重试所有权保持在请求流量之外。当毒化操作仍在失败期间，readiness 保持
                            // DOWN，本 worker 按下一个有界间隔重试，不额外派生任务，也不保留
                            // 按 key 的状态。
                            logger.warn("Message projection runtime drain failed", failure)
                        }
                    }
                },
            ),
        )

        // 8. HTTP 路由。该资源最后接管，使逆序关闭时先关闭 HTTP 准入，再关闭已排空
        // 调用所使用的存储与服务。
        val httpBlockingExecutor = resources.ownDependencyBarrier(
            "HTTP blocking executor",
            HttpBlockingExecutor(),
            close = HttpBlockingExecutor::close,
            dependenciesMayClose = HttpBlockingExecutor::workersTerminated,
        )
        installHttpBlockingBoundary(httpBlockingExecutor)
        runBlocking(Dispatchers.IO) { koin.get<com.virjar.tk.server.application.admin.AdminSecurityService>().initialize() }
        // 固定系统账号幂等引导（内测反馈 T058）：sys_assistant / sys_service。
        runBlocking(Dispatchers.IO) { koin.get<com.virjar.tk.server.domain.user.UserService>().ensureSystemAccounts() }
        val attachmentUploadAdmission = AttachmentUploadAdmission()
        routing {
            // 管理后台 SPA（/admin）：静态资源 + 前端路由 fallback 到 index.html
            staticResources("/admin", "static/admin") {
                default("index.html")
            }

            get(HEALTH_CHECK_PATH) {
                val health = healthChecker.check()
                val status = if (health.status == "UP") HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
                call.respond(status, health)
            }
            fileRoutes(
                fileStore,
                accessTokens,
                koin.get(),
                uploadAdmission = attachmentUploadAdmission,
            )
            botRoutes(koin.get(), accessTokens)
            documentExportRoutes(
                exportService = koin.get(),
                exportPolicy = koin.get(),
                accessTokens = accessTokens,
            )
            adminRoutes(
                adminService = koin.get(),
                auth = koin.get(),
                organization = koin.get(),
                bots = koin.get(),
                documentCustody = koin.get(),
                clientTelemetry = koin.get(),
                documentExport = koin.get(),
                documentExportPolicy = koin.get(),
                clientReleases = koin.get(),
                serviceAccount = koin.get(),
                serviceBroadcastRuntime = koin.get(),
                groupFiles = koin.get(),
            )
            clientTelemetryRoutes(
                control = koin.get(),
                events = clientTelemetryEvents,
                accessTokens = accessTokens,
            )

            val staticDir = resolveStaticDir()
            val downloadsDir = java.io.File(staticDir, "downloads")
            publicSiteRoutes(staticDir)
            clientDownloadRoutes(downloadsDir, koin.get())
            inviteLandingRoutes()
            clientUpdateRoutes(
                service = koin.get(),
                adminAuth = koin.get(),
                stagingDir = java.io.File(com.virjar.tk.server.env.Environment.dataRoot, "tmp"),
            )
        }

        // 9. 优雅停机。ResourceOwner 保证关闭顺序：maintenance -> TCP/connections ->
        // presence -> dispatcher -> registry -> 持久存储 -> PostgreSQL。若 maintenance 并未
        // 真正静默，其依赖屏障会在该链条之前使进程关闭失败。
        monitor.subscribe(ApplicationStopping) {
            resources.close()
            logger.info("TeamTalk Server stopped")
        }

        logger.info("TeamTalk Server initialized")
    } catch (error: Throwable) {
        var terminalFailure = error
        try {
            resources.close()
        } catch (closeFailure: Throwable) {
            terminalFailure = mergeRuntimeFailure(terminalFailure, closeFailure)
        }
        throw terminalFailure
    }
}

/**
 * 将所有未预期的 HTTP 失败细节保留在服务端。路由内处理器仍各自负责其文档约定的
 * 4xx/5xx 契约；这里是未知失败的最后一道隐私边界。
 */
internal fun Application.installSafeErrorHandling() {
    install(StatusPages) {
        exception<CancellationException> { _, cancelled -> throw cancelled }
        exception<Exception> { call, failure ->
            val errorId = UUID.randomUUID().toString()
            val safeFrames = failure.stackTrace
                .take(MAX_PUBLIC_ERROR_LOG_FRAMES)
                .joinToString(" <- ") { frame ->
                    "${frame.className}.${frame.methodName}:${frame.lineNumber}"
                }
            // 刻意排除 Throwable.message：适配器失败可能携带请求内容。error id、异常
            // 类型与有界的纯代码调用栈保留了足够上下文，可将固定的公开响应与服务端
            // 诊断关联起来。
            call.application.log.error(
                "Unhandled HTTP request failure errorId={} type={} frames={}",
                errorId,
                failure::class.qualifiedName ?: "unknown",
                safeFrames,
            )
            if (!call.response.isCommitted) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "internal server error", "errorId" to errorId),
                )
            }
        }
    }
}

private const val MAX_PUBLIC_ERROR_LOG_FRAMES = 12

/**
 * 在重放外部消息发件箱之前，先收敛所有 PostgreSQL 成员权威。
 * Organization 先行，因为 Bot 恢复要进入其受管权威围栏；随后 Bot 修复
 * grant/member/Conversation 投影，确保消息重放既不会向孤儿目标投递，
 * 也不会漏掉仍被授权的服务号成员。
 */
internal suspend fun recoverStartupProjections(
    organizationService: OrganizationService,
    botService: BotService,
    messageProjector: MessageProjector,
): Int {
    val organizationFailures = organizationService.reconcileAllManagedGroups()
    check(organizationFailures.isEmpty()) {
        "Managed department group startup drain did not converge: $organizationFailures"
    }
    val botGrantFailures = botService.recoverGrantMemberships()
    check(botGrantFailures.isEmpty()) {
        "Bot grant startup reconciliation did not converge: $botGrantFailures"
    }
    return messageProjector.recoverPendingProjections()
}

/**
 * 外部消息投影发件箱的运行时存活兜底。
 *
 * 预期负载均衡器在 readiness 为 DOWN 时停止发送流量。因此这个显式维护 owner 会检查
 * readiness 事实并独立排空，不依赖后续消息命令；健康系统在每次轮询时不会触发
 * RocksDB 扫描。
 */
internal suspend fun recoverRuntimeMessageProjections(messageProjector: MessageProjector): Int =
    if (messageProjector.hasBlockedProjection()) messageProjector.recoverPendingProjections() else 0

private fun resolveStaticDir(): java.io.File {
    val env = com.virjar.tk.server.env.Environment
    // 开发环境：resources/static/
    if (env.isDevelopment) {
        val devStaticDir = java.io.File(env.runtimeClassPathDir, "static")
        if (devStaticDir.isDirectory) return devStaticDir
    }
    // 生产环境：安装根目录/static/（与 conf/ lib/ data/ 同级）
    val prodStaticDir = java.io.File(env.runtimeClassPathDir.parent, "static")
    if (prodStaticDir.isDirectory) return prodStaticDir
    return env.dataRoot
}
