package com.virjar.tk.shared.agent

import com.virjar.tk.shared.bot.ImBot
import com.virjar.tk.shared.bot.ImBotAuthenticationRejectedException
import com.virjar.tk.shared.bot.ImBotAuthenticationTerminal
import com.virjar.tk.shared.bot.ImBotAuthenticationTransportException
import com.virjar.tk.shared.bot.ImBotMessageInbox
import com.virjar.tk.shared.bot.PersistentImBotCacheOwner
import com.virjar.tk.shared.client.PendingBotMessage
import com.virjar.tk.shared.client.OutgoingMessage
import com.virjar.tk.shared.client.DeploymentIdentity
import com.virjar.tk.shared.log.PlatformOnlyTkLogger
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ExecutorService
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
        // 不把被拒绝的 refresh 或服务端给出的原因打印进日志。
        System.err.println("[tt-agent] stored authentication requires operator intervention; follow the recovery runbook")
        exitProcess(AGENT_REAUTH_REQUIRED_EXIT_CODE)
    } catch (_: ImBotAuthenticationRejectedException) {
        // 瞬时/前台的认证失败同样保持脱敏。systemd 服务只在
        // 显式可重试的服务端状态下才会走到这个分支，并且受 unit 限流。
        System.err.println("[tt-agent] authentication was not accepted")
        exitProcess(1)
    } catch (_: ImBotAuthenticationTransportException) {
        // 显式的密码/注册尝试是一次性的。何时重试由服务管理器/操作员决定，
        // 并且端点/错误细节不会进入日志。
        System.err.println("[tt-agent] authentication transport is unavailable")
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
    val configuredServerUrl = opts["server-url"] ?: env["TK_SERVER_URL"]
    val deploymentIdentity = configuredServerUrl?.let { serverUrl ->
        DeploymentIdentity.from(host, port, serverUrl)
    } ?: DeploymentIdentity.fromTcpWithDefaultHttp(host, port)
    val serverUrl = deploymentIdentity.httpBaseUrl
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
    com.virjar.tk.shared.client.JvmClientDataLease.acquire(dataDir).use {
        com.virjar.tk.shared.client.prepareJvmClientDataVersion(dataDir)
        val cacheOwner = PersistentImBotCacheOwner(dataDir)

        val credentials = if (explicitRegistration) {
            AgentRegistration.beginOrResume(dataDir, deploymentIdentity, opts["prefix"] ?: "agent")
        } else {
            AgentCredentials.load(dataDir, deploymentIdentity)
        }
        val reauthentication = resolveAgentReauthentication(
            requested = explicitReauthentication,
            credentials = credentials,
            suppliedUsername = suppliedUser,
            suppliedPassword = suppliedPassword,
        )
        val identity = credentials?.let { AgentRuntimeIdentity(it.apiToken, it.deviceId) }
            ?: AgentCredentials.ensureIdentity(dataDir, deploymentIdentity)
        // 在任何 IM 网络连接之前，先验证/创建两个上传专属的子目录。
        val fileAccessPolicy = AgentFileAccessPolicy(dataDir)

        fun refreshRecorder(
            expectedUsername: String,
            expectedDeviceId: String,
        ): (String, String, String) -> Unit = { uid, authenticatedUsername, refreshToken ->
            AgentCredentials.recordAuthentication(
                dataDir = dataDir,
                deploymentIdentity = deploymentIdentity,
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
                    deploymentIdentity = deploymentIdentity,
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

        val runtime = AgentRuntime(
            host = host,
            port = port,
            dataDir = dataDir,
            serverUrl = serverUrl,
            messageInbox = connected.inbox,
            fileAccessPolicy = fileAccessPolicy,
        )
        runAgentRuntimeUntilAuthenticationTerminal(
            runtime = runtime,
            start = { agent ->
                agent.attach(connected.bot)
                agent.startHttp(apiBind.display)
                println(
                    "[tt-agent] ready uid=${connected.bot.uid} " +
                        "user=${connected.bot.username} api=http://${apiBind.display}",
                )
            },
            awaitTerminal = { runBlocking { connected.bot.awaitAuthenticationTerminal() } },
        )
    }
}

/** 永久 ACTIVE 拒绝：在操作员运行 --reauth 之前，systemd 必须停止重启。 */
internal class AgentReauthenticationRequiredException private constructor(
    val terminal: ImBotAuthenticationTerminal?,
    cause: Throwable?,
) : IllegalStateException("stored authentication requires explicit reauthentication", cause) {
    constructor(cause: Throwable) : this(terminal = null, cause = cause)
    constructor(terminal: ImBotAuthenticationTerminal) : this(terminal = terminal, cause = null)
}

/** 运行时关闭发生在类型化终局逃逸到 [main] 并映射为退出码 78 之前。 */
internal fun <T : AutoCloseable> runAgentRuntimeUntilAuthenticationTerminal(
    runtime: T,
    start: (T) -> Unit,
    awaitTerminal: (T) -> ImBotAuthenticationTerminal,
): Nothing {
    val terminal = runtime.use { active ->
        start(active)
        awaitTerminal(active)
    }
    // 把终局保留在 `use` 之外：取消/非 Exception 的关闭缺陷必须保持为
    // 主失败，而不是被抑制到普通的 reauthentication 信号上。
    throw AgentReauthenticationRequiredException(terminal)
}

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

private data class AgentMessageWaiter(
    val afterEventId: Long,
    val chatId: String?,
    val channel: Channel<PendingBotMessage>,
)

data class AgentWaitResult(
    val delivery: PendingBotMessage?,
    /** 存在 delivery 时为它的 eventId；否则为本次调用快照到的有效基线。 */
    val nextEventId: Long,
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

/** agent 运行时：bot 会话 + SQLite eventId delivery 事实源 + 长轮询唤醒 + REST。 */
class AgentRuntime(
    private val host: String,
    private val port: Int,
    private val dataDir: File,
    val serverUrl: String,
    val messageInbox: ImBotMessageInbox = ImBotMessageInbox(),
    val fileAccessPolicy: AgentFileAccessPolicy = AgentFileAccessPolicy(dataDir),
) : AutoCloseable {

    private val deploymentIdentity = DeploymentIdentity.from(host, port, serverUrl)
    private val identity = AgentCredentials.ensureIdentity(dataDir, deploymentIdentity)
    lateinit var bot: ImBot
    val apiToken: String = identity.apiToken
    val deviceId: String = identity.deviceId
    private val waiters = ConcurrentLinkedDeque<AgentMessageWaiter>()
    private val waiterLifecycle = Any()
    @Volatile
    private var closed = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fileSendCoordinator = AgentFileSendCoordinator(
        findReceipt = { chatId, clientMsgId, fingerprint ->
            if (fingerprint == null) {
                bot.outgoingReceipt(chatId, clientMsgId)
            } else {
                bot.outgoingReceipt(chatId, clientMsgId, fingerprint)
            }
        },
        stage = fileAccessPolicy::stageUpload,
        upload = { prepared, contentType ->
            bot.uploadFile(prepared.source, prepared.originalFileName, contentType)
        },
        enqueue = { chatId, clientMsgId, attachment, fingerprint ->
            bot.enqueueFile(chatId, clientMsgId, attachment, fingerprint)
        },
    )
    private var server: HttpServer? = null
    private var serverExecutor: ExecutorService? = null

    init {
        // 认证与回放已经发布到这个由磁盘支撑的 inbox 中。之后
        // 再启动消费者是安全的：待处理行桥接回放、SYNC_READY 与 attach 之间的缺口。
        scope.launch {
            while (messageInbox.consumePendingForAgent(::notifyMessageAvailable)) {
                // 一次按 chat 键的原子消费已完成；继续处理下一个全局 eventId。
            }
        }
    }

    fun attach(bot: ImBot) {
        this.bot = bot
    }

    suspend fun enqueueFile(chatId: String, clientMsgId: String, rawPath: String): OutgoingMessage =
        fileSendCoordinator.enqueueFile(chatId, clientMsgId, rawPath)

    fun outgoingReceipt(chatId: String, clientMsgId: String): OutgoingMessage? =
        bot.outgoingReceipt(chatId, clientMsgId)

    /** 在 ImBotMessageInbox 仍然持有此 delivery 的每会话门禁期间同步调用。 */
    private fun notifyMessageAvailable(delivery: PendingBotMessage) {
        waiters.forEach { waiter ->
            if (
                delivery.eventId > waiter.afterEventId &&
                (waiter.chatId == null || delivery.message.chatId == waiter.chatId)
            ) {
                waiter.channel.trySend(delivery)
            }
        }
    }

    fun startHttp(bind: String) {
        check(server == null) { "HTTP server is already started" }
        val endpoint = AgentBindPolicy.parse(bind)
        val s = HttpServer.create(endpoint.socketAddress(), 0)
        val executor = createAgentHttpExecutor()
        try {
            s.executor = executor
            val api = AgentApi(this)
            for (path in listOf(
                "/v1/status", "/v1/messages", "/v1/recv-wait", "/v1/send-text", "/v1/send-rich",
                "/v1/send-file", "/v1/outgoing", "/v1/upload", "/v1/history", "/v1/revoke", "/v1/forward",
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
    fun waitMessage(afterEventId: Long?, chatId: String?, timeoutSec: Int): AgentWaitResult {
        require(afterEventId == null || afterEventId >= 0L) { "afterEventId must be non-negative" }
        require(timeoutSec in 1..MAX_WAIT_SECONDS) { "timeout must be between 1 and $MAX_WAIT_SECONDS" }
        val cursor = afterEventId ?: messageInbox.maxEventId()
        fun nextPersisted(): PendingBotMessage? =
            messageInbox.deliveries(cursor, chatId, limit = 1).firstOrNull()
        if (afterEventId != null) nextPersisted()?.let { return AgentWaitResult(it, it.eventId) }
        val ch = Channel<PendingBotMessage>(1)
        val waiter = AgentMessageWaiter(cursor, chatId, ch)
        synchronized(waiterLifecycle) {
            if (closed) return AgentWaitResult(null, cursor)
            waiters.add(waiter)
        }
        val delivery = try {
            // 用第二次持久读取来封住 query→waiter 注册之间的竞争。
            nextPersisted()?.let { return AgentWaitResult(it, it.eventId) }
            try {
                runBlocking {
                    kotlinx.coroutines.withTimeout(timeoutSec * 1000L) { ch.receive() }
                }
            } catch (_: TimeoutCancellationException) {
                // 超时可能与其内存通知之前的持久插入竞争。这次
                // 最终读取要么返回那一行，要么把空响应串行化到 [cursor]。
                nextPersisted()
            } catch (_: ClosedReceiveChannelException) {
                // 运行时关闭负责这个 channel 的关闭。如果 inbox 仍然可用，保留
                // 刚刚持久化的行；否则关闭基线就是唯一有意义的结果。
                if (closed) null else nextPersisted()
            }
        } finally {
            waiters.remove(waiter)
        }
        return AgentWaitResult(delivery, delivery?.eventId ?: cursor)
    }

    val connectionState get() = bot.connectionState.value
    val bufferedCount get() = messageInbox.deliveries(0L, null, MAX_RECENT_MESSAGES).size

    fun bufferedMessages(chatId: String?, limit: Int, afterEventId: Long): List<PendingBotMessage> {
        require(afterEventId >= 0L) { "afterEventId must be non-negative" }
        require(limit in 1..MAX_RECENT_MESSAGES) { "limit must be between 1 and $MAX_RECENT_MESSAGES" }
        return messageInbox.deliveries(afterEventId, chatId, limit)
    }

    override fun close() {
        val ownedWaiters = synchronized(waiterLifecycle) {
            if (closed) return
            closed = true
            waiters.toList().also {
                waiters.clear()
            }
        }
        val ownedServer = server.also { server = null }
        val ownedExecutor = serverExecutor.also { serverExecutor = null }
        val drain = AgentLifecycleDrain()
        ownedWaiters.forEach { waiter ->
            drain.release("message waiter") { waiter.channel.close() }
        }
        drain.release("HTTP server") {
            ownedServer?.stop(0)
        }
        drain.release("HTTP executor") {
            ownedExecutor?.let(::shutdownExecutor)
        }
        if (::bot.isInitialized) {
            drain.release("bot session") { bot.shutdown() }
        } else {
            drain.release("message inbox") { messageInbox.close() }
        }
        drain.release("runtime scope") { scope.cancel() }
        drain.finish { failures ->
            val owners = failures.joinToString { (owner, failure) ->
                "$owner=${failure::class.simpleName}"
            }
            logger.fault(
                "Agent runtime shutdown completed with ${failures.size} ordinary failure(s): $owners",
                failures.first().second,
            )
        }
    }

    private companion object {
        const val MAX_RECENT_MESSAGES = 1000
        const val MAX_WAIT_SECONDS = 60
        val logger = PlatformOnlyTkLogger("AgentRuntime")

        fun shutdownExecutor(executor: ExecutorService) {
            executor.shutdownNow()
            try {
                check(executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    "Agent HTTP executor did not terminate within the shutdown budget"
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            }
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
