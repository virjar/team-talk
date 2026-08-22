package com.virjar.tk

import com.virjar.tk.api.clientLogRoutes
import com.virjar.tk.api.adminRoutes
import com.virjar.tk.api.fileRoutes
import com.virjar.tk.api.botRoutes
import com.virjar.tk.application.presence.PresenceCoordinator
import com.virjar.tk.di.serverModule
import com.virjar.tk.domain.auth.AuthService
import com.virjar.tk.domain.auth.AccessTokenValidator
import com.virjar.tk.domain.bot.BotService
import com.virjar.tk.domain.chat.ChatAccess
import com.virjar.tk.infra.health.HealthChecker
import com.virjar.tk.infra.ServerDataEpoch
import com.virjar.tk.domain.message.MessageService
import com.virjar.tk.domain.organization.OrganizationService
import com.virjar.tk.infra.db.DatabaseFactory
import com.virjar.tk.infra.search.SearchIndex
import com.virjar.tk.infra.storage.FileStore
import com.virjar.tk.infra.storage.MessageStore
import com.virjar.tk.infra.sync.ClientRegistry
import com.virjar.tk.infra.sync.SyncEventService
import com.virjar.tk.protocol.TcpServer
import com.virjar.tk.protocol.codec.ImAgent
import com.virjar.tk.protocol.dispatcher.RpcDispatcher
import com.virjar.tk.runtime.ServerResourceOwner
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.http.content.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.ktor.plugin.Koin
import org.slf4j.LoggerFactory
import java.io.File
import java.security.KeyStore

fun main() {
    // 0. Environment 必须先于 logback 初始化，确保 LOG_DIR 系统属性已设置
    val env = com.virjar.tk.env.Environment
    System.setProperty("LOG_DIR", env.logsDir.absolutePath)
    val logger = LoggerFactory.getLogger("Application")

    logger.info("TeamTalk Server starting...")
    // 显式记录关键路径解析结果，便于排查「日志丢失/数据目录错误」类问题
    logger.info("Environment resolved: isDevelopment={}, dataRoot={}, logsDir={}, classPathDir={}",
        env.isDevelopment, env.dataRoot.absolutePath, env.logsDir.absolutePath, env.runtimeClassPathDir.absolutePath)
    startServer()
}

/**
 * 启动 Ktor Netty 引擎，同时绑定 HTTP 和 HTTPS。
 *
 * 显式配置 connectors（而非依赖 EngineMain 解析 application.conf），保证 HTTP 与 HTTPS
 * 都由同一组部署环境变量控制：
 *   - KTOR_PORT / KTOR_SSL_PORT / SSL_KEYSTORE / SSL_KEYSTORE_PASSWORD / SSL_PRIVATE_KEY_PASSWORD
 *
 * 仅当 SSL_KEYSTORE 配置时才启用 HTTPS；否则只起 HTTP（开发模式典型场景）。
 */
private fun startServer() {
    val logger = LoggerFactory.getLogger("Application")
    val httpPort = (System.getenv("KTOR_PORT") ?: "8080").toInt()
    val sslPort = System.getenv("KTOR_SSL_PORT")?.toIntOrNull()
    val keystorePath = System.getenv("SSL_KEYSTORE")
    val keystorePassword = System.getenv("SSL_KEYSTORE_PASSWORD")
    val privateKeyPassword = System.getenv("SSL_PRIVATE_KEY_PASSWORD")

    // 预构造 HTTPS connector（若配置），避免在 configure lambda 内做带日志的复杂逻辑
    val sslConnectorConfig: EngineSSLConnectorBuilder? = if (sslPort != null && !keystorePath.isNullOrBlank()) {
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            File(keystorePath).inputStream().use { load(it, keystorePassword?.toCharArray()) }
        }
        val alias = keyStore.aliases().nextElement()
        logger.info("HTTPS enabled on port $sslPort (keystore=$keystorePath, alias=$alias)")
        EngineSSLConnectorBuilder(
            keyStore,
            alias,
            { keystorePassword?.toCharArray() ?: CharArray(0) },
            { privateKeyPassword?.toCharArray() ?: CharArray(0) },
        ).apply { port = sslPort }
    } else {
        logger.warn("HTTPS disabled: SSL_KEYSTORE or KTOR_SSL_PORT not configured")
        null
    }

    embeddedServer(
        factory = Netty,
        environment = applicationEnvironment { log = logger },
        configure = {
            connector { port = httpPort }
            sslConnectorConfig?.let { connectors.add(it) }
        },
    ) {
        module()
    }.start(wait = true)
}

fun Application.module() {
    val logger = LoggerFactory.getLogger("Application")

    // 1. DI
    install(Koin) {
        modules(serverModule)
    }

    // 1.5 CORS：管理后台 SPA 开发态（vite :5173）跨域；生产同源不受影响
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

    // 2. JSON
    install(ContentNegotiation) {
        json(Json { prettyPrint = false })
    }

    val resources = ServerResourceOwner { name, error ->
        logger.warn("Failed to close server resource {}", name, error)
    }
    try {
        // PostgreSQL and every local durable store advance as one disposable pre-release epoch.
        // Validate local data before opening a JDBC pool so an epoch failure leaves no live resources.
        ServerDataEpoch.initializeOrValidate(com.virjar.tk.env.Environment.dataRoot)

        // 3. Database
        DatabaseFactory.create()
        resources.own("PostgreSQL pool") { DatabaseFactory.close() }

        // 4. Storage. Register each instance before init so partial native initialization is unwindable.
        val koin = org.koin.java.KoinJavaComponent.getKoin()
        val messageStore = resources.own("message store", koin.get<MessageStore>()) { it.close() }
        messageStore.init()
        val accessTokens = koin.get<AccessTokenValidator>()
        val fileStore = resources.own("file store", koin.get<FileStore>()) { it.close() }
        fileStore.init()
        val clientLogStore = koin.get<com.virjar.tk.infra.storage.ClientLogStore>()

        // 5. Search Index (Lucene + IK)
        val searchIndex = resources.own("search index", koin.get<SearchIndex>()) { it.stop() }
        searchIndex.start()

        // ClientRegistry starts its looper in the constructor. Own it before resolving services that use it.
        val clientRegistry = resources.own("client registry", koin.get<ClientRegistry>()) { it.stop() }
        val syncEventDispatcher = resources.own(
            "sync event dispatcher",
            koin.get<com.virjar.tk.infra.sync.SyncEventDispatcher>(),
        ) { it.close() }
        syncEventDispatcher.start()
        val authService = koin.get<AuthService>()
        val rpcDispatcher = koin.get<RpcDispatcher>()
        val msgService = koin.get<MessageService>()
        val chatAccess = koin.get<ChatAccess>()
        val syncEventService = koin.get<SyncEventService>()
        val organizationService = koin.get<OrganizationService>()
        val botService = koin.get<BotService>()
        val recoveredProjections = runBlocking(Dispatchers.IO) {
            recoverStartupProjections(organizationService, botService, msgService)
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
                chatAccess, syncEventService, syncEventService, ioExecutor,
            )
        }

        // 7. Health Checker
        val healthChecker = koin.get<HealthChecker>()

        // 7.5 保留 sync_events 维护挂点。当前 cleanup 是显式 no-op。SYNC_RESET 能处理
        // 非法游标，但没有服务端全量快照时，裁掉合法历史仍不足以重建一个空客户端投影。
        val maintenanceJob = SupervisorJob()
        resources.own("sync-event maintenance") {
            runBlocking { maintenanceJob.cancelAndJoin() }
        }
        val maintenanceScope = CoroutineScope(maintenanceJob + Dispatchers.IO)
        maintenanceScope.launch {
            while (isActive) {
                runCatching { syncEventService.cleanupExpiredEvents() }
                    .onFailure { logger.warn("sync_events cleanup failed", it) }
                delay(24 * 60 * 60 * 1000L)
            }
        }
        maintenanceScope.launch {
            while (isActive) {
                delay(5_000L)
                try {
                    val failures = organizationService.reconcileDueManagedGroups()
                    if (failures.isNotEmpty()) {
                        logger.warn("Managed department group runtime drain is still pending for units={}", failures)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    logger.warn("Managed department group runtime drain failed", failure)
                }
            }
        }

        // 8. HTTP Routes
        routing {
            // 管理后台 SPA（/admin）：静态资源 + 前端路由 fallback 到 index.html
            staticResources("/admin", "static/admin") {
                default("index.html")
            }

            get("/health") {
                val health = healthChecker.check()
                val status = if (health.status == "UP") HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
                call.respond(status, health)
            }
            fileRoutes(fileStore, accessTokens, koin.get())
            botRoutes(koin.get(), accessTokens)
            adminRoutes(koin.get())
            clientLogRoutes(clientLogStore, accessTokens)

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
                val file = java.io.File(downloadsDir, filename)
                if (file.exists()) call.respondFile(file) else call.respond(HttpStatusCode.NotFound)
            }
            head("/downloads/{filename}") {
                val filename = call.parameters["filename"] ?: return@head call.respond(HttpStatusCode.BadRequest)
                val file = java.io.File(downloadsDir, filename)
                if (file.exists()) call.respond(HttpStatusCode.OK) else call.respond(HttpStatusCode.NotFound)
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
        // presence -> registry -> durable stores -> PostgreSQL, and is safe if startup also unwinds.
        environment.monitor.subscribe(ApplicationStopping) {
            resources.close()
            logger.info("TeamTalk Server stopped")
        }

        logger.info("TeamTalk Server initialized")
    } catch (error: Throwable) {
        resources.close()
        throw error
    }
}

/**
 * Converge every PostgreSQL membership authority before replaying the external message outbox.
 * Organization runs first because Bot recovery enters its managed-authority fence; Bot then repairs
 * grant/member/Conversation projections so message replay cannot deliver to an orphan or skip a
 * still-authorized service member.
 */
internal suspend fun recoverStartupProjections(
    organizationService: OrganizationService,
    botService: BotService,
    messageService: MessageService,
): Int {
    val organizationFailures = organizationService.reconcileAllManagedGroups()
    check(organizationFailures.isEmpty()) {
        "Managed department group startup drain did not converge: $organizationFailures"
    }
    val botGrantFailures = botService.recoverGrantMemberships()
    check(botGrantFailures.isEmpty()) {
        "Bot grant startup reconciliation did not converge: $botGrantFailures"
    }
    return messageService.recoverPendingProjections()
}

private fun resolveStaticDir(): java.io.File {
    val env = com.virjar.tk.env.Environment
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
