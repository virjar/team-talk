package com.virjar.tk

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.virjar.tk.api.*
import com.virjar.tk.db.DatabaseFactory
import com.virjar.tk.db.MessageStore
import com.virjar.tk.dto.ApiError
import java.io.File
import com.virjar.tk.env.ClassPreloader
import com.virjar.tk.env.Environment
import com.virjar.tk.service.*
import com.virjar.tk.store.*
import com.virjar.tk.tcp.ClientRegistry
import com.virjar.tk.tcp.IOExecutor
import com.virjar.tk.tcp.TcpServer
import com.virjar.tk.tcp.agent.MessageDispatcher
import com.virjar.tk.tcp.agent.SubscribeDispatcher
import com.virjar.tk.tcp.agent.TypingDispatcher
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

// 这个一定是第一行，保证Environment首先执行
val logDir: String = Environment.logsDir.absolutePath

fun main(args: Array<String>) {
    // 开发环境设置日志目录到 ~/.tk/logs
    System.setProperty("TK_LOG_DIR", logDir)
    // 开发模式下预加载所有类，防止 gradle clean 导致 NoClassDefFoundError
    ClassPreloader.preloadDevClasses()
    EngineMain.main(args)
}

fun Application.module() {
    val jdbcUrl = environment.config.property("database.jdbcUrl").getString()
    val dbUser = environment.config.property("database.user").getString()
    val dbPassword = environment.config.property("database.password").getString()
    val jwtSecret = environment.config.property("jwt.secret").getString()

    DatabaseFactory.init(jdbcUrl, dbUser, dbPassword)

    // 单例 Service 初始化
    TokenService.init(jwtSecret)

    // 创建 Store 并全量加载
    val userStore = UserStore()
    val channelStore = ChannelStore()
    val contactStore = ContactStore()
    val conversationStore = ConversationStore()
    val deviceStore = DeviceStore()
    val inviteLinkStore = InviteLinkStore()

    userStore.loadAll()
    channelStore.loadAll()
    contactStore.loadAll()
    conversationStore.loadAll()

    val userService = UserService(TokenService, userStore)
    val channelService = ChannelService(channelStore, conversationStore, inviteLinkStore)
    val friendService = FriendService(contactStore, userStore)
    val messageStore = MessageStore(Environment.rocksdbDir.absolutePath)
    messageStore.start()

    val searchIndex = SearchIndex(Environment.luceneIndexDir)
    searchIndex.start()

    val messageService = MessageService(messageStore, searchIndex, channelStore, userStore, conversationStore)
    MessageDeliveryService.init(channelStore)
    DeviceService.init(deviceStore)
    PresenceService.init(contactStore)

    // Dispatcher 初始化
    MessageDispatcher.init(messageService, channelStore)
    TypingDispatcher.init(channelStore)
    SubscribeDispatcher.init(messageService)

    // 注册下线回调
    ClientRegistry.onLastDeviceOffline = { uid ->
        PresenceService.postBroadcastOffline(uid)
    }

    val conversationService =
        ConversationService(messageStore, channelStore, userStore, conversationStore)

    val fileService = FileService(
        endpoint = environment.config.propertyOrNull("minio.endpoint")?.getString() ?: "http://127.0.0.1:9000",
        accessKey = environment.config.propertyOrNull("minio.accessKey")?.getString() ?: "minioadmin",
        secretKey = environment.config.propertyOrNull("minio.secretKey")?.getString() ?: "minioadmin",
        bucketName = environment.config.propertyOrNull("minio.bucket")?.getString() ?: "teamtalk",
    )

    install(ContentNegotiation) { json() }
    install(CallLogging)
    install(CORS) {
        anyHost()
        allowHeader("Content-Type")
        allowHeader("Authorization")
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
    }
    install(StatusPages) {
        exception<BusinessException> { call, cause ->
            call.respond(cause.httpStatus, ApiError(cause.errorCode, cause.message))
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ApiError(message = cause.message ?: "bad request"))
        }
        exception<Exception> { call, cause ->
            call.application.environment.log.error("Unhandled exception", cause)
            call.respond(HttpStatusCode.InternalServerError, ApiError(message = "internal server error"))
        }
    }

    val hmacAlgorithm = Algorithm.HMAC256(jwtSecret)
    val verifier = JWT.require(hmacAlgorithm).build()
    install(Authentication) {
        jwt("auth-jwt") {
            verifier(verifier)
            validate { credential ->
                if (credential.payload.subject != null) JWTPrincipal(credential.payload) else null
            }
        }
    }

    val tcpPort = environment.config.propertyOrNull("ktor.tcp.port")?.getString()?.toInt() ?: 5100
    val tcpServer = TcpServer(tcpPort)
    tcpServer.start()

    routing {
        // ===== 首页和下载路由 =====
        val staticDir = resolveStaticDir()
        val downloadsDir = File(staticDir, "downloads")

        get("/") {
            val indexFile = File(staticDir, "index.html")
            if (indexFile.exists()) {
                call.respondFile(indexFile)
            } else {
                call.respondText("TeamTalk Server is running", ContentType.Text.Plain)
            }
        }

        // 通用静态文件服务（从 static/ 目录提供 css/js/images/txt 等）
        staticFiles("/", staticDir) {
            exclude { it == File(staticDir, "index.html") }
        }

        get("/downloads/{filename}") {
            val filename = call.parameters["filename"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val file = File(downloadsDir, filename)
            if (file.exists()) {
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$filename\"")
                call.respondFile(file)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }

        // ===== API 路由 =====
        get("/ping") { call.respondText("pong", ContentType.Text.Plain) }
        get("/health") { call.respondText("ok", ContentType.Text.Plain) }
        get("/stats/online") {
            call.respondText(
                """{"users":${ClientRegistry.getOnlineUserCount()},"connections":${ClientRegistry.getOnlineConnectionCount()}}""",
                ContentType.Application.Json
            )
        }
        authRoutes(userService, TokenService)
        channelRoutes(channelService)
        contactRoutes(friendService)
        conversationRoutes(conversationService)
        messageRoutes(messageService, searchIndex, MessageDeliveryService)
        val maxFileSizeBytes =
            environment.config.propertyOrNull("file.max-size-bytes")?.getString()?.toLong() ?: (50 * 1024 * 1024)
        fileRoutes(fileService, maxFileSizeBytes)
        deviceRoutes(DeviceService)

        // Online status API
        route("/api/v1/users") {
            authenticate("auth-jwt") {
                post("/online-status") {
                    val body = call.receive<Map<String, List<String>>>()
                    val uids = body["uids"] ?: return@post call.respond(
                        HttpStatusCode.BadRequest, ApiError(message = "uids required")
                    )
                    val statusMap = uids.associateWith { ClientRegistry.isUserOnline(it) }
                    call.respond(mapOf("status" to statusMap))
                }
            }
        }
    }


    monitor.subscribe(ApplicationStopping) {
        tcpServer.stop()
        IOExecutor.shutdown()
        fileService.close()
        searchIndex.stop()
        messageStore.stop()
    }
}

private fun resolveStaticDir(): File {
    // 开发环境：resources/static/
    if (Environment.isDevelopment) {
        val devStaticDir = File(Environment.runtimeClassPathDir, "static")
        if (devStaticDir.isDirectory) return devStaticDir
    }
    // 生产环境：安装根目录/static/（与 conf/ lib/ data/ 同级）
    val prodStaticDir = File(Environment.runtimeClassPathDir.parent, "static")
    if (prodStaticDir.isDirectory) return prodStaticDir
    return Environment.dataRoot
}
