package com.virjar.tk.agent

import com.virjar.tk.bot.ImBot
import com.virjar.tk.bot.ImBotAuthenticationRejectedException
import com.virjar.tk.bot.ImBotMessageInbox
import com.virjar.tk.bot.PersistentImBotCacheOwner
import com.virjar.tk.model.Message
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

internal const val AGENT_REAUTH_REQUIRED_EXIT_CODE = 78

/**
 * tt-agent：无头客户端守护进程（doc/05-clients/headless.md）。
 *
 * 常驻 ImBot 会话（自动重连/凭据持久化），本地 REST（127.0.0.1）供 tt-cli / MCP 调用。
 * 设计文档是实施事实源；本文件是 agent 核心 + HTTP 分发。
 *
 * 用法：
 * ```
 * # TK_USER/TK_PASS 由受控前台环境输入提供
 * tt-agent --host im.virjar.com --port 5100 --api 127.0.0.1:8600
 * tt-agent --register --prefix my-bot            # 无账号时注册新号
 * tt-agent --reauth                              # ACTIVE refresh 失效后一次性恢复
 * ```
 */
fun main(args: Array<String>) {
    try {
        runAgent(args)
    } catch (_: AgentReauthenticationRequiredException) {
        // Do not print the rejected refresh or a server-supplied reason into the journal.
        System.err.println("[tt-agent] stored authentication requires operator intervention; follow the recovery runbook")
        exitProcess(AGENT_REAUTH_REQUIRED_EXIT_CODE)
    } catch (_: ImBotAuthenticationRejectedException) {
        // Transient/foreground auth failures also remain redacted. A systemd service only reaches
        // this branch for explicitly retryable server states and is rate-limited by the unit.
        System.err.println("[tt-agent] authentication was not accepted")
        exitProcess(1)
    }
}

private fun runAgent(args: Array<String>) {
    // 子命令：install（systemd 服务化，Linux）/ uninstall
    if (args.firstOrNull() == "install") {
        AgentService.install(args.drop(1))
        return
    }
    if (args.firstOrNull() == "uninstall") {
        AgentService.uninstall()
        return
    }
    if (args.firstOrNull() == "prepare-service-data") {
        AgentService.prepareData(args.drop(1))
        return
    }
    val opts = AgentCli.parse(args)
    validateAgentRuntimeOptions(opts)
    val env = System.getenv()
    val host = opts["host"] ?: env["TK_HOST"] ?: "im.virjar.com"
    val port = (opts["port"] ?: env["TK_PORT"] ?: "5100").toInt()
    val apiBind = AgentBindPolicy.parse(opts["api"] ?: "127.0.0.1:8600")
    val dataDir = File(opts["data-dir"] ?: env["TK_AGENT_DIR"] ?: "${System.getProperty("user.home")}/.tt-agent")
    // 上传/下载走 HTTP 文件服务的 serverUrl（与 TCP host 区分——HTTPS 域名），
    // 不设则 defaultServerConfig 回退 localhost（曾致 upload 挂死无超时）
    val serverUrl = opts["server-url"] ?: env["TK_SERVER_URL"] ?: "https://$host"
    val explicitRegistration = opts.containsKey("register")
    val explicitReauthentication = opts.containsKey("reauth")
    require(!(explicitRegistration && explicitReauthentication)) {
        "--register and --reauth are mutually exclusive"
    }
    val suppliedUser = opts["user"] ?: env["TK_USER"]
    val suppliedPassword = env["TK_PASS"]
    if (explicitRegistration) {
        require(suppliedUser == null && suppliedPassword == null) {
            "Registration cannot be combined with login bootstrap credentials"
        }
    }
    AgentDataDirectoryPolicy.openRuntime(dataDir)
    val cacheOwner = PersistentImBotCacheOwner(dataDir)

    val credentials = if (explicitRegistration) {
        AgentRegistration.beginOrResume(dataDir, opts["prefix"] ?: "agent")
    } else {
        AgentCredentials.load(dataDir)
    }
    val reauthentication = resolveAgentReauthentication(
        requested = explicitReauthentication,
        credentials = credentials,
        suppliedUsername = suppliedUser,
        suppliedPassword = suppliedPassword,
    )
    val identity = credentials?.let { AgentRuntimeIdentity(it.apiToken, it.deviceId) }
        ?: AgentCredentials.ensureIdentity(dataDir)
    // Validate/create both upload-owned children before any IM network connection.
    val fileAccessPolicy = AgentFileAccessPolicy(dataDir)

    fun refreshRecorder(
        expectedUsername: String,
        expectedDeviceId: String,
    ): (String, String, String) -> Unit = { uid, authenticatedUsername, refreshToken ->
        AgentCredentials.recordAuthentication(
            dataDir = dataDir,
            expectedUsername = expectedUsername,
            expectedDeviceId = expectedDeviceId,
            uid = uid,
            authenticatedUsername = authenticatedUsername,
            refreshToken = refreshToken,
        )
    }

    val connected = when {
        reauthentication != null -> {
            runBlocking {
                connectLogin(
                    host, port, serverUrl, cacheOwner,
                    reauthentication.username, reauthentication.password, reauthentication.deviceId,
                    refreshRecorder(reauthentication.username, reauthentication.deviceId),
                )
            }
        }
        credentials?.state == AgentCredentialState.REGISTER_PENDING -> runBlocking {
            AgentRegistration.recover(
                dataDir = dataDir,
                pending = credentials,
                login = {
                    connectLogin(
                        host, port, serverUrl, cacheOwner,
                        requireNotNull(it.username), requireNotNull(it.password), it.deviceId,
                        refreshRecorder(requireNotNull(it.username), it.deviceId),
                    )
                },
                registerExact = {
                    connectRegistration(
                        host, port, serverUrl, cacheOwner, it,
                        refreshRecorder(requireNotNull(it.username), it.deviceId),
                    )
                },
                discard = { it.bot.shutdown() },
            )
        }
        credentials?.state == AgentCredentialState.ACTIVE -> {
            val active = credentials.requireActiveRefresh()
            try {
                runBlocking {
                    connectRefresh(
                        host, port, serverUrl, cacheOwner, active,
                        refreshRecorder(active.username, active.deviceId),
                    )
                }
            } catch (rejected: ImBotAuthenticationRejectedException) {
                if (rejected.requiresOperatorIntervention) {
                    throw AgentReauthenticationRequiredException(rejected)
                }
                throw rejected
            }
        }
        suppliedUser != null && suppliedPassword != null -> {
            runBlocking {
                connectLogin(
                    host, port, serverUrl, cacheOwner,
                    suppliedUser, suppliedPassword, identity.deviceId,
                    refreshRecorder(suppliedUser, identity.deviceId),
                )
            }
        }
        else -> {
            System.err.println("[tt-agent] 缺少登录凭据；请先完成安全 bootstrap 或前台一次性注册")
            return
        }
    }

    if (reauthentication != null) {
        connected.bot.shutdown()
        println("[tt-agent] reauthentication complete user=${reauthentication.username}")
        return
    }

    AgentRuntime(
        host = host,
        port = port,
        dataDir = dataDir,
        serverUrl = serverUrl,
        messageInbox = connected.inbox,
        fileAccessPolicy = fileAccessPolicy,
    ).use { agent ->
        agent.attach(connected.bot)
        agent.startHttp(apiBind.display)
        println(
            "[tt-agent] ready uid=${connected.bot.uid} " +
                "user=${connected.bot.username} api=http://${apiBind.display}",
        )
        // 常驻：主线程阻塞（HttpServer 线程池 + bot 协程自治）
        Thread.currentThread().join()
    }
}

/** Permanent ACTIVE rejection: systemd must stop restarting until an operator runs --reauth. */
internal class AgentReauthenticationRequiredException(cause: Throwable) :
    IllegalStateException("stored authentication requires explicit reauthentication", cause)

internal fun validateAgentRuntimeOptions(options: Map<String, String>) {
    require("pass" !in options) {
        "--pass is forbidden; use TK_PASS from controlled foreground environment input"
    }
    val unknown = options.keys - AGENT_RUNTIME_OPTIONS
    require(unknown.isEmpty()) { "Unknown tt-agent options: ${unknown.sorted().joinToString()}" }
}

private val AGENT_RUNTIME_OPTIONS = setOf(
    "host",
    "port",
    "api",
    "data-dir",
    "server-url",
    "user",
    "register",
    "prefix",
    "reauth",
)

internal data class AgentReauthentication(
    val username: String,
    val password: String,
    val deviceId: String,
) {
    override fun toString(): String =
        "AgentReauthentication(username=$username, password=<redacted>, deviceId=$deviceId)"
}

internal fun resolveAgentReauthentication(
    requested: Boolean,
    credentials: AgentCredentialRecord?,
    suppliedUsername: String?,
    suppliedPassword: String?,
): AgentReauthentication? {
    if (!requested) {
        if (credentials?.state != null) {
            require(suppliedUsername == null && suppliedPassword == null) {
                "A stateful agent dataDir does not accept bootstrap username/password without --reauth"
            }
        }
        return null
    }
    val active = requireNotNull(credentials).requireActiveRefresh()
    require(!suppliedPassword.isNullOrBlank()) {
        "--reauth requires TK_PASS from controlled input"
    }
    require(suppliedUsername == null || suppliedUsername == active.username) {
        "--reauth cannot change the ACTIVE account"
    }
    return AgentReauthentication(active.username, suppliedPassword, active.deviceId)
}

private data class ConnectedAgent(
    val bot: ImBot,
    val inbox: ImBotMessageInbox,
)

private suspend fun connectLogin(
    host: String,
    port: Int,
    serverUrl: String,
    cacheOwner: PersistentImBotCacheOwner,
    username: String,
    password: String,
    deviceId: String,
    onRefreshCredentials: (String, String, String) -> Unit,
): ConnectedAgent {
    val inbox = ImBotMessageInbox()
    val bot = ImBot.login(
        host = host,
        port = port,
        username = username,
        password = password,
        deviceId = deviceId,
        cacheOwner = cacheOwner,
        messageInbox = inbox,
        fileServerUrl = serverUrl,
        onRefreshCredentials = onRefreshCredentials,
    )
    return ConnectedAgent(bot, inbox)
}

private suspend fun connectRegistration(
    host: String,
    port: Int,
    serverUrl: String,
    cacheOwner: PersistentImBotCacheOwner,
    credentials: AgentCredentialRecord,
    onRefreshCredentials: (String, String, String) -> Unit,
): ConnectedAgent {
    val inbox = ImBotMessageInbox()
    val bot = ImBot.registerExact(
        host = host,
        port = port,
        username = requireNotNull(credentials.username),
        password = requireNotNull(credentials.password),
        deviceId = credentials.deviceId,
        cacheOwner = cacheOwner,
        messageInbox = inbox,
        fileServerUrl = serverUrl,
        onRefreshCredentials = onRefreshCredentials,
    )
    return ConnectedAgent(bot, inbox)
}

private suspend fun connectRefresh(
    host: String,
    port: Int,
    serverUrl: String,
    cacheOwner: PersistentImBotCacheOwner,
    credentials: AgentActiveRefresh,
    onRefreshCredentials: (String, String, String) -> Unit,
): ConnectedAgent {
    val inbox = ImBotMessageInbox()
    val bot = ImBot.authenticate(
        host = host,
        port = port,
        uid = credentials.uid,
        refreshToken = credentials.refreshToken,
        deviceId = credentials.deviceId,
        cacheOwner = cacheOwner,
        messageInbox = inbox,
        fileServerUrl = serverUrl,
        onRefreshCredentials = onRefreshCredentials,
    )
    return ConnectedAgent(bot, inbox)
}

/** agent 运行时：bot 会话 + SQLite recent 事实源 + 长轮询唤醒 + REST。 */
class AgentRuntime(
    private val host: String,
    private val port: Int,
    private val dataDir: File,
    val serverUrl: String,
    val messageInbox: ImBotMessageInbox = ImBotMessageInbox(),
    val fileAccessPolicy: AgentFileAccessPolicy = AgentFileAccessPolicy(dataDir),
) : AutoCloseable {

    private val identity = AgentCredentials.ensureIdentity(dataDir)
    lateinit var bot: ImBot
    val apiToken: String = identity.apiToken
    val deviceId: String = identity.deviceId
    private val waiters = ConcurrentLinkedDeque<Channel<Message>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var server: HttpServer? = null
    private var serverExecutor: ExecutorService? = null

    init {
        // Authentication and replay already published into this same disk-backed inbox. Starting
        // the consumer afterwards is safe: pending rows bridge replay, SYNC_READY and attach gaps.
        scope.launch {
            while (true) {
                val pending = messageInbox.receivePendingOrNull() ?: break
                // MESSAGE_RECV was persisted to the normal message projection before inbox insert.
                // That SQLite projection is the REST history source, so ack cannot make it vanish.
                messageInbox.ack(pending.eventId)
                notifyMessageAvailable(pending.message)
            }
        }
    }

    fun attach(bot: ImBot) {
        this.bot = bot
    }

    private fun notifyMessageAvailable(message: Message) {
        waiters.forEach { it.trySend(message) }
    }

    fun startHttp(bind: String) {
        check(server == null) { "HTTP server is already started" }
        val endpoint = AgentBindPolicy.parse(bind)
        val s = HttpServer.create(endpoint.socketAddress(), 0)
        val executor = Executors.newCachedThreadPool()
        try {
            s.executor = executor
            val api = AgentApi(this)
            for (path in listOf(
                "/v1/status", "/v1/messages", "/v1/recv-wait", "/v1/send-text", "/v1/send-rich",
                "/v1/send-file", "/v1/upload", "/v1/history", "/v1/revoke", "/v1/forward",
                "/v1/mark-read", "/v1/conversations", "/v1/friends", "/v1/friend-apply",
                "/v1/friend-accept", "/v1/friend-pending", "/v1/users-search", "/v1/group-create",
                "/v1/group-members", "/v1/group-invite", "/v1/chat-personal",
            )) {
                s.createContext(path) { ex -> api.handle(ex, path) }
            }
            s.start()
            server = s
            serverExecutor = executor
        } catch (failure: Throwable) {
            s.stop(0)
            shutdownExecutor(executor)
            throw failure
        }
    }

    /** 长轮询注册（AgentApi 调）。 */
    fun waitMessage(chatId: String?, timeoutSec: Int): Message? {
        val ch = Channel<Message>(1)
        fun latestPersisted(): Message? =
            messageInbox.recentMessages(chatId, afterSeq = 0L, limit = 1).lastOrNull()
        latestPersisted()?.let { return it }
        waiters.add(ch)
        return try {
            // Close the query->waiter registration race with a second persistent read.
            latestPersisted()?.let { return it }
            runBlocking {
                kotlinx.coroutines.withTimeout(timeoutSec * 1000L) {
                    while (true) {
                        val message = ch.receive()
                        if (chatId == null || message.chatId == chatId) return@withTimeout message
                    }
                    @Suppress("UNREACHABLE_CODE")
                    error("unreachable")
                }
            }
        } catch (_: Exception) {
            null
        } finally {
            waiters.remove(ch)
        }
    }

    val connectionState get() = bot.connectionState.value
    val bufferedCount get() = messageInbox.recentMessages(null, 0L, MAX_RECENT_MESSAGES).size

    fun bufferedMessages(chatId: String?, limit: Int, afterSeq: Long): List<Message> =
        messageInbox.recentMessages(chatId, afterSeq, limit.coerceIn(1, MAX_RECENT_MESSAGES))

    override fun close() {
        server?.stop(0)
        server = null
        serverExecutor?.let { executor ->
            shutdownExecutor(executor)
        }
        serverExecutor = null
        if (::bot.isInitialized) {
            runCatching { bot.shutdown() }
        } else {
            messageInbox.close()
        }
        scope.cancel()
    }

    private companion object {
        const val MAX_RECENT_MESSAGES = 1000

        fun shutdownExecutor(executor: ExecutorService) {
            executor.shutdownNow()
            runCatching { executor.awaitTermination(2, TimeUnit.SECONDS) }
        }
    }
}

/** CLI 参数解析（--k v / --k=v / 裸 flag）。 */
object AgentCli {
    fun parse(args: Array<String>): Map<String, String> = buildMap {
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (!a.startsWith("--")) { i++; continue }
            val option = a.removePrefix("--")
            val key = option.substringBefore('=')
            if ('=' in option) put(key, option.substringAfter('='))
            else if (i + 1 < args.size && !args[i + 1].startsWith("--")) { put(key, args[i + 1]); i++ }
            else put(key, "")
            i++
        }
    }
}
