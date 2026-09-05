package com.virjar.tk.server

import com.virjar.tk.server.api.clientTelemetryRoutes
import com.virjar.tk.server.api.adminRoutes
import com.virjar.tk.server.api.AttachmentUploadAdmission
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
import org.koin.ktor.plugin.Koin
import org.koin.ktor.ext.getKoin
import org.slf4j.LoggerFactory
import java.io.File
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
 * Ktor configures an Application before Netty binds its connectors. A bind failure therefore
 * happens after TeamTalk has already opened TCP, database, native stores, and background workers.
 * Ktor's start failure path does not destroy that successfully configured Application, so this
 * outer owner must stop the engine and explicitly replay the application's resource terminal.
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
    // ApplicationStopping listeners are deliberately isolated by Ktor. Closing again is how the
    // process observes and replays the exact terminal failure retained by ServerResourceOwner.
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
        // Storage compatibility is independent of network negotiation. Preserve existing data on
        // a mismatch and fail before opening a JDBC pool or starting dependent resources.
        val dataRoot = com.virjar.tk.server.env.Environment.dataRoot
        ServerDataEpoch.initializeOrValidate(dataRoot)

        // 1. Database ownership precedes DI: this Application binds exactly one Exposed Database,
        // and the resource owner closes only the matching pool after all dependent services stop.
        val postgres = resources.own("PostgreSQL pool", DatabaseFactory.create()) { it.close() }
        // The relational schema and every local durable byte belong to one cursor namespace.
        // Validate that pairing before Koin can construct/open RocksDB, Lucene, or FileStore.
        ServerDataEpoch.bindOrValidateDataset(dataRoot, postgres.datasetId)

        // 2. DI
        install(Koin) {
            modules(
                createServerModule(
                    database = postgres.database,
                    syncDatasetId = postgres.datasetId,
                    tcpServerConfiguration = tcpTransport.server,
                    tcpHealthProbeConfiguration = tcpTransport.health,
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

        // 4. Storage. Register each instance before init so partial native initialization is unwindable.
        // Resolve from this Application's Ktor-owned container. Process-global Koin lookup makes
        // two embedded server instances steal each other's resources and breaks parallel tests.
        val koin = getKoin()
        resources.own(
            "password hashing executor",
            koin.get<com.virjar.tk.server.infra.security.BCryptPasswordHasher>(),
        ) { it.close() }
        val messageStore = resources.own("message store", koin.get<MessageStore>()) { it.close() }
        messageStore.init()
        val accessTokens = koin.get<AccessTokenValidator>()
        val fileStore = resources.own("file store", koin.get<FileStore>()) { it.close() }
        fileStore.init()

        // 5. Search Index (Lucene + IK). Its production binding owns the MessageStore archive
        // reader, so start performs the full bounded audit/rebuild before publishing a writer.
        val searchIndex = resources.own("search index", koin.get<SearchIndex>()) { it.stop() }
        searchIndex.start()
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

        // ClientRegistry starts its looper in the constructor. Own it before resolving services that use it.
        val clientRegistry = resources.own("client registry", koin.get<ClientRegistry>()) { it.stop() }
        val syncEventDispatcher = resources.own(
            "sync event dispatcher",
            koin.get<com.virjar.tk.server.infra.sync.SyncEventDispatcher>(),
        ) { it.close() }
        syncEventDispatcher.start()
        // PostgreSQL commit may precede the process-local wake. Do not recover projections, open
        // TCP, or install health routes until the mandatory durable fallback scan has succeeded.
        runBlocking(Dispatchers.IO) { syncEventDispatcher.awaitStartupScan() }
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

        val presenceCoordinator = resources.own(
            "presence coordinator",
            koin.get<PresenceCoordinator>(),
        ) { it.close() }
        presenceCoordinator.start()

        // TCP is acquired after the registry so reverse-order shutdown always closes all channels first.
        val tcpServer = resources.own("TCP server", koin.get<TcpServer>()) { it.stop() }
        tcpServer.start { channel, recorder, ioExecutor ->
            ImAgent(
                channel, recorder, authService, clientRegistry, rpcDispatcher, msgService,
                chatAccess, syncEventService, syncEventService, ioExecutor, koin.get(),
                protocolConfiguration = protocolConfiguration,
            )
        }

        // 7. Health Checker
        val healthChecker = koin.get<HealthChecker>()
        val attachmentRetention = koin.get<AttachmentRetentionService>()
        val reliableCommandReceiptMaintenance = ReliableCommandReceiptMaintenance(koin.get())
        var reliableCommandReceiptBacklogReported = false

        // 7.5 Bounded retention maintenance. Durable sync events are removed only after checkpoint
        // bootstrap can rebuild their current projections. Disposable client telemetry events use
        // an independent receivedAt-based 168-hour Lucene authority; PostgreSQL owns only control facts.
        val maintenance = resources.ownDependencyBarrier(
            name = "server maintenance",
            resource = MaintenanceRuntime(),
            close = MaintenanceRuntime::close,
            dependenciesMayClose = MaintenanceRuntime::workersTerminated,
        )
        maintenance.start(
            listOf(
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
                            // Keep retry ownership outside request traffic. While the poison operation is
                            // still failing, readiness remains DOWN and this worker retries on the next
                            // bounded interval without spawning another job or retaining per-key state.
                            logger.warn("Message projection runtime drain failed", failure)
                        }
                    }
                },
            ),
        )

        // 8. HTTP Routes. This resource is acquired last so reverse shutdown closes HTTP
        // admission before the stores and services used by already-drained calls.
        val httpBlockingExecutor = resources.ownDependencyBarrier(
            "HTTP blocking executor",
            HttpBlockingExecutor(),
            close = HttpBlockingExecutor::close,
            dependenciesMayClose = HttpBlockingExecutor::workersTerminated,
        )
        installHttpBlockingBoundary(httpBlockingExecutor)
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
            adminRoutes(koin.get(), koin.get(), koin.get())
            clientTelemetryRoutes(
                control = koin.get(),
                events = clientTelemetryEvents,
                accessTokens = accessTokens,
            )

            // 首页
            val staticDir = resolveStaticDir()
            val downloadsDir = java.io.File(staticDir, "downloads")
            get("/") {
                val indexFile = java.io.File(staticDir, "index.html")
                if (indexFile.exists()) {
                    call.respondFile(indexFile)
                } else {
                    call.respondText("TeamTalk Server", ContentType.Text.Plain)
                }
            }

            // 客户端下载
            get("/downloads/{filename}") {
                val filename = call.parameters["filename"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val file = resolveDirectDownload(downloadsDir, filename)
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respondFile(file)
            }
            head("/downloads/{filename}") {
                val filename = call.parameters["filename"] ?: return@head call.respond(HttpStatusCode.BadRequest)
                val file = resolveDirectDownload(downloadsDir, filename)
                    ?: return@head call.respond(HttpStatusCode.NotFound)
                call.respond(HttpStatusCode.OK)
            }

            // Conveyor 更新站点（进程内静态目录，单进程约束不破）：安装包 + appcast/
            // appinstaller 更新元数据 + apt 仓库索引 + 下载页。Sparkle/MSIX/apt 直接消费。
            // 不设 default 兜底：更新元数据（appcast/appinstaller/Packages）命中不了必须
            // 诚实 404，返回 HTML 会毒死更新客户端；下载页用显式根路由。
            val desktopSiteDir = java.io.File(downloadsDir, "desktop")
            get("/downloads/desktop") { call.respondFile(java.io.File(desktopSiteDir, "download.html")) }
            get("/downloads/desktop/") { call.respondFile(java.io.File(desktopSiteDir, "download.html")) }
            staticFiles("/downloads/desktop", desktopSiteDir) {
                enableAutoHeadResponse()
            }
        }

        // 9. Graceful shutdown. ResourceOwner enforces maintenance -> TCP/connections ->
        // presence -> dispatcher -> registry -> durable stores -> PostgreSQL. If maintenance does
        // not actually quiesce, its dependency barrier fails the process close before that chain.
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
 * Keep every unexpected HTTP failure detail on the server side. Route-local handlers still own
 * their documented 4xx/5xx contracts; this is the final privacy boundary for unknown failures.
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
            // Throwable.message is intentionally excluded: adapter failures can carry request
            // material. The error id, exception type, and bounded code-only stack retain enough
            // context to correlate the fixed public response with server diagnostics.
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
 * Converge every PostgreSQL membership authority before replaying the external message outbox.
 * Organization runs first because Bot recovery enters its managed-authority fence; Bot then repairs
 * grant/member/Conversation projections so message replay cannot deliver to an orphan or skip a
 * still-authorized service member.
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
 * Runtime liveness edge for the external message projection outbox.
 *
 * A load balancer is expected to stop sending traffic while readiness is DOWN. This explicit
 * maintenance owner therefore checks the readiness fact and drains independently of a later
 * message command; a healthy system performs no RocksDB scan on each poll.
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

/** Only direct regular package files under the trusted downloads root are publicly exposed. */
internal fun resolveDirectDownload(downloadsDir: File, filename: String): File? {
    if (
        filename.length !in 1..255 ||
        filename == "." ||
        filename == ".." ||
        filename.any { it == '/' || it == '\\' || it == '\u0000' }
    ) {
        return null
    }
    return try {
        val canonicalRoot = downloadsDir.canonicalFile
        val candidate = File(canonicalRoot, filename).canonicalFile
        candidate.takeIf { it.isFile && it.parentFile == canonicalRoot }
    } catch (_: java.io.IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}
