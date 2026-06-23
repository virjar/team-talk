package com.virjar.tk

import com.virjar.tk.api.clientLogRoutes
import com.virjar.tk.api.fileRoutes
import com.virjar.tk.di.serverModule
import com.virjar.tk.log.Slf4jTkLogger
import com.virjar.tk.log.TkLoggerFactory
import com.virjar.tk.domain.auth.AuthService
import com.virjar.tk.domain.chat.ChatStore
import com.virjar.tk.domain.health.HealthChecker
import com.virjar.tk.domain.message.MessageService
import com.virjar.tk.domain.presence.PresenceService
import com.virjar.tk.infra.db.DatabaseFactory
import com.virjar.tk.infra.search.SearchIndex
import com.virjar.tk.infra.storage.FileStore
import com.virjar.tk.infra.storage.MessageStore
import com.virjar.tk.infra.sync.ClientRegistry
import com.virjar.tk.infra.sync.SyncEventService
import com.virjar.tk.protocol.TcpServer
import com.virjar.tk.protocol.codec.ImAgent
import com.virjar.tk.protocol.dispatcher.RpcDispatcher
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.koin.ktor.plugin.Koin
import org.slf4j.LoggerFactory
import java.io.File
import java.security.KeyStore

fun main() {
    // 0. Environment 必须先于 logback 初始化，确保 LOG_DIR 系统属性已设置
    val env = com.virjar.tk.env.Environment
    System.setProperty("LOG_DIR", env.logsDir.absolutePath)
    val logger = LoggerFactory.getLogger("Application")

    // 0.5 注入 TkLogger 实现：shared 模块的日志通过 SLF4J 输出
    TkLoggerFactory.install { name -> Slf4jTkLogger(LoggerFactory.getLogger(name)) }

    logger.info("TeamTalk Server starting...")
    // 显式记录关键路径解析结果，便于排查「日志丢失/数据目录错误」类问题
    logger.info("Environment resolved: isDevelopment={}, dataRoot={}, logsDir={}, classPathDir={}",
        env.isDevelopment, env.dataRoot.absolutePath, env.logsDir.absolutePath, env.runtimeClassPathDir.absolutePath)
    startServer()
}

/**
 * 启动 Ktor Netty 引擎，同时绑定 HTTP 和 HTTPS。
 *
 * 显式配置 connectors（而非依赖 EngineMain 解析 application.conf）：
 * V2 重构曾用 `embeddedServer(Netty, module=...)` 单参重载，它不读 conf、不支持 SSL，
 * 导致 HTTPS 443 永远起不来。这里通过环境变量配置（与部署 env.sh 中的变量名一致）：
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

    // 2. JSON
    install(ContentNegotiation) {
        json(Json { prettyPrint = false })
    }

    // 3. Database
    DatabaseFactory.create()

    // 4. Storage
    val koin = org.koin.java.KoinJavaComponent.getKoin()
    val messageStore = koin.get<MessageStore>()
    messageStore.init()
    val fileStore = koin.get<FileStore>()
    val clientLogStore = koin.get<com.virjar.tk.infra.storage.ClientLogStore>()
    fileStore.init()

    // 5. Search Index (Lucene + IK)
    val searchIndex = koin.get<SearchIndex>()
    searchIndex.start()

    // 6. TCP Server
    val tcpServer = koin.get<TcpServer>()
    val authService = koin.get<AuthService>()
    val clientRegistry = koin.get<ClientRegistry>()
    val rpcDispatcher = koin.get<RpcDispatcher>()
    val msgService = koin.get<MessageService>()
    val chatStore = koin.get<ChatStore>()
    val syncEventService = koin.get<SyncEventService>()
    val presenceService = koin.get<PresenceService>()
    tcpServer.start { channel, recorder, ioExecutor ->
        ImAgent(channel, recorder, authService, clientRegistry, rpcDispatcher, msgService, chatStore, messageStore, syncEventService, presenceService, ioExecutor)
    }

    // 7. Health Checker
    val healthChecker = koin.get<HealthChecker>()

    // 8. HTTP Routes
    routing {
        get("/health") {
            val health = healthChecker.check()
            val status = if (health.status == "UP") HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
            call.respond(status, health)
        }
        fileRoutes(fileStore)
        clientLogRoutes(clientLogStore)

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
    }

    // 9. Graceful shutdown
    environment.monitor.subscribe(ApplicationStopping) {
        clientRegistry.stop()
        searchIndex.stop()
        tcpServer.stop()
        messageStore.close()
        logger.info("TeamTalk Server stopped")
    }

    logger.info("TeamTalk Server initialized")
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
