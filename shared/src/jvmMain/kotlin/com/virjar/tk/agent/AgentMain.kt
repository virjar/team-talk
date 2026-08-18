package com.virjar.tk.agent

import com.virjar.tk.bot.ImBot
import com.virjar.tk.model.Message
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * tt-agent：无头客户端守护进程（doc/05-clients/headless.md）。
 *
 * 常驻 ImBot 会话（自动重连/凭据持久化），本地 REST（127.0.0.1）供 tt-cli / MCP 调用。
 * 设计文档是实施事实源；本文件是 agent 核心 + HTTP 分发。
 *
 * 用法：
 * ```
 * tt-agent --host im.virjar.com --port 5100 --user xx --pass yy --api 127.0.0.1:8600
 * tt-agent --register --prefix my-bot            # 无账号时注册新号
 * ```
 */
fun main(args: Array<String>) {
    // 子命令：install（systemd 服务化，Linux）/ uninstall
    if (args.firstOrNull() == "install") {
        AgentService.install(args.drop(1))
        return
    }
    if (args.firstOrNull() == "uninstall") {
        AgentService.uninstall()
        return
    }
    val opts = AgentCli.parse(args)
    val env = System.getenv()
    val host = opts["host"] ?: env["TK_HOST"] ?: "im.virjar.com"
    val port = (opts["port"] ?: env["TK_PORT"] ?: "5100").toInt()
    val apiBind = opts["api"] ?: "127.0.0.1:8600"
    val dataDir = File(opts["data-dir"] ?: env["TK_AGENT_DIR"] ?: "${System.getProperty("user.home")}/.tt-agent")
    // 上传/下载走 HTTP 文件服务的 serverUrl（与 TCP host 区分——HTTPS 域名），
    // 不设则 defaultServerConfig 回退 localhost（曾致 upload 挂死无超时）
    val serverUrl = opts["server-url"] ?: env["TK_SERVER_URL"] ?: "https://$host"

    AgentRuntime(host, port, dataDir, serverUrl).use { agent ->
        // 凭据：dataDir/credentials.properties 持久化，重启静默重连
        val cred = AgentCredentials.load(dataDir)
        val username = opts["user"] ?: env["TK_USER"] ?: cred?.first
        val password = opts["pass"] ?: env["TK_PASS"] ?: cred?.second

        var regPassword: String? = null
        val bot = when {
            opts.containsKey("register") -> runBlocking {
                // 自持随机密码（ImBot.register 内部生成无法取回，重启静默重连需要可复现凭据）
                regPassword = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
                ImBot.register(host, port, opts["prefix"] ?: "agent", regPassword!!).also { it.awaitState() }
            }
            username != null && password != null -> runBlocking {
                ImBot.login(host, port, username, password).also { it.awaitState() }
            }
            cred != null -> runBlocking {
                // token 重连（refresh token 在 credentials 第三段）
                ImBot.login(host, port, cred.first, cred.second).also { it.awaitState() }
            }
            else -> {
                System.err.println("[tt-agent] 缺少凭据：--user/--pass 或 --register；或 dataDir 无保存凭据")
                return
            }
        }
        // register 模式 username 为 null——从 bot 会话取注册后的真实用户名持久化（重启可静默重连）
        val effectiveUser = username ?: bot.userSession.username
        val effectivePass = password ?: regPassword ?: env["TK_PASS"]
        agent.attach(bot, effectiveUser, effectivePass)
        agent.startHttp(apiBind)
        println("[tt-agent] ready uid=${bot.uid} user=${bot.userSession.username} api=http://$apiBind token=${agent.apiToken}")
        // 常驻：主线程阻塞（HttpServer 线程池 + bot 协程自治）
        Thread.currentThread().join()
    }
}

/** agent 运行时：bot 会话 + 消息环形缓冲 + REST。 */
class AgentRuntime(
    private val host: String,
    private val port: Int,
    private val dataDir: File,
    val serverUrl: String,
) : AutoCloseable {

    lateinit var bot: ImBot
    var apiToken: String = ""
        private set
    private val ring = ConcurrentLinkedDeque<Message>()
    private val waiters = ConcurrentLinkedDeque<Channel<Message>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var server: HttpServer? = null

    fun attach(bot: ImBot, username: String?, password: String?) {
        this.bot = bot
        dataDir.mkdirs()
        apiToken = AgentCredentials.ensureToken(dataDir)
        username?.let { u -> password?.let { p -> AgentCredentials.save(dataDir, u, p) } }
        // 收消息 → 环形缓冲 + 唤醒长轮询
        scope.launch {
            bot.messages.collect { msg ->
                ring.addLast(msg)
                while (ring.size > 1000) ring.pollFirst()
                waiters.forEach { it.trySend(msg) }
            }
        }
    }

    fun startHttp(bind: String) {
        val (addr, apiPort) = bind.split(":").let { it[0] to it[1].toInt() }
        val s = HttpServer.create(InetSocketAddress(addr, apiPort), 0)
        s.executor = Executors.newCachedThreadPool()
        val api = AgentApi(this)
        for (path in listOf(
            "/v1/status", "/v1/messages", "/v1/recv-wait", "/v1/send-text", "/v1/send-rich",
            "/v1/send-file", "/v1/upload", "/v1/history", "/v1/revoke", "/v1/forward",
            "/v1/mark-read", "/v1/conversations", "/v1/friends", "/v1/friend-apply",
            "/v1/friend-accept", "/v1/friend-pending", "/v1/users-search", "/v1/group-create",
            "/v1/group-members", "/v1/group-invite", "/v1/chat-personal", "/v1/selftest",
        )) {
            s.createContext(path) { ex -> api.handle(ex, path) }
        }
        s.start()
        server = s
    }

    /** 长轮询注册（AgentApi 调）。 */
    fun waitMessage(chatId: String?, timeoutSec: Int): Message? {
        val ch = Channel<Message>(1)
        // 先查历史命中
        ring.lastOrNull { (chatId == null || it.chatId == chatId) }?.let { return it }
        waiters.add(ch)
        return try {
            runBlocking {
                kotlinx.coroutines.withTimeout(timeoutSec * 1000L) { ch.receive() }
            }
        } catch (_: Exception) {
            null
        } finally {
            waiters.remove(ch)
        }
    }

    val connectionState get() = bot.session.imClient.state.value
    val bufferedCount get() = ring.size

    fun bufferedMessages(chatId: String?, limit: Int, afterSeq: Long): List<Message> =
        ring.toList()
            .filter { (chatId == null || it.chatId == chatId) && it.serverSeq > afterSeq }
            .takeLast(limit)

    override fun close() {
        server?.stop(0)
        scope.cancel()
        runCatching { bot.shutdown() }
    }
}

/** CLI 参数解析（--k v / --k=v / 裸 flag）。 */
object AgentCli {
    fun parse(args: Array<String>): Map<String, String> = buildMap {
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (!a.startsWith("--")) { i++; continue }
            val key = a.removePrefix("--")
            if ('=' in a) put(key, a.substringAfter("="))
            else if (i + 1 < args.size && !args[i + 1].startsWith("--")) { put(key, args[i + 1]); i++ }
            else put(key, "")
            i++
        }
    }
}
