package com.virjar.tk.server.e2e

import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.shared.client.ImClient
import com.virjar.tk.shared.client.RpcClient
import com.virjar.tk.server.domain.auth.AuthenticationAttemptGuardConfig
import com.virjar.tk.server.domain.auth.AuthenticationOperation
import com.virjar.tk.protocol.model.Conversation
import com.virjar.tk.protocol.model.ContactApply
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.payload.NotifyPayload
import com.virjar.tk.protocol.payload.ResponsePayload
import com.virjar.tk.protocol.rpc.gen.ContactRpcContract
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * 远程部署 E2E 测试工具。
 *
 * 与 [TcpE2eEnvironment] 的区别：不启动任何 in-process 服务端（PG/Koin/TcpServer），
 * 只用真实客户端代码（[ImClient] + [RpcClient]）直连已部署服务器。
 *
 * 连接目标通过系统属性配置（公开任务从 `gradle/deployment.json` 注入）：
 *   - `tk.e2e.host`（默认 `im.virjar.com`）
 *   - `tk.e2e.port`（默认 `5100`）
 *
 * 测试账号每次动态注册（用户名前缀 `e2e-`，总长度严格控制在 3..50 之间——
 * 服务端 UserService.require(username.length in 3..50)），便于识别和清理测试数据。
 * 整个开关由 [RemoteAcceptanceTest] 上的 `@EnabledIfSystemProperty("tk.e2e.remote")` 控制。
 */
object RemoteAcceptanceSupport {

    /** 远程服务器主机。 */
    val host: String = System.getProperty("tk.e2e.host") ?: "im.virjar.com"

    /** 远程服务器 TCP 端口。 */
    val port: Int = (System.getProperty("tk.e2e.port") ?: "5100").toInt()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val productionAuthenticationLimits = AuthenticationAttemptGuardConfig()
    private val registrationPacer = SlidingWindowPacer(
        // 保留一个来源槽位给运维人员或共享同一出口 IP 的其他真实客户端。
        maxAttempts = (
            productionAuthenticationLimits.limits
                .getValue(AuthenticationOperation.REGISTER)
                .sourceAttempts - 1
            ).coerceAtLeast(1),
        windowNanos = productionAuthenticationLimits.windowNanos,
        safetyNanos = 250_000_000L,
    )

    /**
     * 轻量会话包装，复用真实 ImClient + RpcClient。
     * 结构与 [ProtocolE2eTest] 内的 E2eSession 一致，抽出来供远程测试复用。
     */
    class Session internal constructor(
        val imClient: ImClient,
        val rpc: RpcClient,
        val userSession: com.virjar.tk.shared.client.UserSession,
        private val eventProjection: E2eEventProjection,
        private val successfulAuthentications: AtomicInteger,
    ) {
        private val notifyBuffer = mutableListOf<NotifyPayload>()
        private var collectJob: Job? = null

        val uid: String get() = userSession.uid
        val authenticationCount: Int get() = successfulAuthentications.get()

        /** registerUser 注册时使用的真实 username（loginUser 登录的会话为 null）。 */
        var registeredUsername: String? = null

        fun startCollecting(scope: CoroutineScope) {
            collectJob = scope.launch {
                imClient.packets.collect { proto ->
                    if (proto is NotifyPayload) {
                        synchronized(notifyBuffer) { notifyBuffer.add(proto) }
                    }
                }
            }
        }

        suspend fun invoke(serviceId: String, methodId: Int, payload: ByteArray? = null): ResponsePayload =
            rpc.invoke(serviceId, methodId, payload)

        /** 好友处理 token 只属于收件人，测试也必须从收件箱读取，不能依赖 apply 响应。 */
        suspend fun pendingApplyToken(fromUid: String): String {
            val response = invoke("contact", ContactRpcContract.M_LIST_PENDING_APPLIES)
            require(response.status == 0 && response.payload != null) { "无法读取待处理好友申请" }
            return ProtoCodec.decodeList(ContactApply, response.payload!!)
                .single { it.fromUid == fromUid && it.status == 0 }
                .token ?: error("待处理申请缺少收件人 token")
        }

        suspend fun awaitNotify(notifyType: Int? = null, timeoutMs: Long = 5000): NotifyPayload =
            withTimeout(timeoutMs) {
                var found: NotifyPayload? = null
                while (found == null) {
                    found = synchronized(notifyBuffer) {
                        notifyBuffer.firstOrNull { notifyType == null || it.notifyType == notifyType }
                            ?.also { notifyBuffer.remove(it) }
                    }
                    if (found == null) delay(50)
                }
                found
            }

        fun conversation(chatId: String): Conversation? = eventProjection.conversation(chatId)

        fun message(chatId: String, serverSeq: Long): Message? =
            eventProjection.message(chatId, serverSeq)

        fun messages(chatId: String): List<Message> = eventProjection.messages(chatId)

        fun observedMessageEvents(): List<Pair<Long, Message>> =
            eventProjection.observedMessages().map { event -> event.eventId to event.message }

        fun syncCursor(): Long = eventProjection.syncCursor()

        suspend fun awaitConversation(
            chatId: String,
            timeoutMs: Long = 10_000,
            predicate: (Conversation) -> Boolean = { true },
        ): Conversation = withTimeout(timeoutMs) {
            while (true) {
                val current = conversation(chatId)
                if (current != null && predicate(current)) return@withTimeout current
                delay(50)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

        suspend fun awaitMessage(
            chatId: String,
            serverSeq: Long,
            timeoutMs: Long = 10_000,
            predicate: (Message) -> Boolean = { true },
        ): Message = withTimeout(timeoutMs) {
            while (true) {
                val current = message(chatId, serverSeq)
                if (current != null && predicate(current)) return@withTimeout current
                delay(50)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

        suspend fun awaitSyncCursorAtLeast(eventId: Long, timeoutMs: Long = 10_000): Long =
            withTimeout(timeoutMs) {
                while (true) {
                    val current = syncCursor()
                    if (current >= eventId) return@withTimeout current
                    delay(50)
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }

        suspend fun awaitAuthenticationAfter(previousCount: Int, timeoutMs: Long = 20_000): Int =
            withTimeout(timeoutMs) {
                while (true) {
                    val current = authenticationCount
                    if (current > previousCount && imClient.state.value == ConnectionState.AUTHENTICATED) {
                        return@withTimeout current
                    }
                    delay(50)
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }

        fun close() {
            var failure: Throwable? = null
            fun release(action: () -> Unit) {
                try {
                    action()
                } catch (releaseFailure: Throwable) {
                    val primary = failure
                    if (primary == null) failure = releaseFailure else primary.addSuppressed(releaseFailure)
                }
            }
            release {
                collectJob?.cancel()
                collectJob = null
            }
            release(rpc::stop)
            release(eventProjection::close)
            // E2E 测试会话是一次性的，彻底销毁线程资源；前一阶段失败也不能跳过。
            release(imClient::destroy)
            failure?.let { throw it }
        }
    }

    /** 建立一条到远程部署的已认证会话（注册一个新账号）。 */
    suspend fun registerUser(
        suffix: String,
        password: String = "password123",
        displayName: String = "TestUser $suffix",
        deviceId: String = "e2e-device",
        deviceName: String = "E2E",
    ): Session {
        registrationPacer.awaitPermit()
        val session = createSession()
        // 用户名：e2e-<suffix>-<8位hex>，总长度远小于 50（服务端限制 3..50）。
        // 用短随机串而非完整 UUID，避免测试前缀 + UUID 超过 50 字符被拒。
        val username = "e2e-$suffix-" + UUID.randomUUID().toString().take(8)
        return try {
            session.imClient.register(username, password, displayName, deviceId, deviceName)
            session.awaitAuthentication()
            session.registeredUsername = username
            session
        } catch (failure: Throwable) {
            closeAfterFailure(session, failure)
        }
    }

    /** 用已有账号登录建立会话（验证登录流程时使用）。 */
    suspend fun loginUser(
        username: String,
        password: String,
        deviceId: String = "e2e-device",
        deviceName: String = "E2E",
    ): Session {
        val session = createSession()
        return try {
            session.imClient.login(username, password, deviceId, deviceName)
            session.awaitAuthentication()
            session
        } catch (failure: Throwable) {
            closeAfterFailure(session, failure)
        }
    }

    /** 仅建立 TCP 连接（不认证），用于测试登录失败等场景。 */
    suspend fun createSession(): Session {
        val userSession = com.virjar.tk.shared.client.UserSession()
        val successfulAuthentications = AtomicInteger()
        lateinit var eventProjection: E2eEventProjection
        val imClient = ImClient(onAuthResult = {
                success, uid, username, name, refreshToken, accessToken, datasetId, failureReason ->
            if (success) {
                val authoritativeDatasetId = requireNotNull(datasetId) {
                    "Successful remote AUTH omitted datasetId"
                }
                userSession.onAuthSuccess(
                    uid ?: "", username, name, refreshToken, accessToken, authoritativeDatasetId,
                )
                // AUTH 回调是凭据/dataset 准入屏障。这里绑定是同步的，
                // 因此在 ImClient 首次做出 SYNC_REQUEST 决策之前就已就位。
                eventProjection.bindDataset(authoritativeDatasetId)
                successfulAuthentications.incrementAndGet()
            }
            else userSession.onAuthFailed(failureReason)
        })
        eventProjection = imClient.installE2eEventProjection()
        var rpc: RpcClient? = null
        try {
            imClient.connect(host, port)
            withTimeout(10_000) { imClient.state.first { it == ConnectionState.CONNECTED } }

            rpc = RpcClient(imClient)
            rpc.start()

            return Session(imClient, rpc, userSession, eventProjection, successfulAuthentications)
                .also { session -> session.startCollecting(scope) }
        } catch (failure: Throwable) {
            fun release(action: () -> Unit) {
                runCatching(action).exceptionOrNull()?.let(failure::addSuppressed)
            }
            release { rpc?.stop() }
            release(eventProjection::close)
            release(imClient::destroy)
            throw failure
        }
    }

    /** 测试结束清理 scope（PER_CLASS 生命周期结束时调用）。 */
    fun shutdown() {
        scope.cancel()
    }

    private suspend fun Session.awaitAuthentication() {
        val terminal = withTimeout(10_000) {
            imClient.state.first {
                it == ConnectionState.AUTHENTICATED || it == ConnectionState.AUTH_FAILED
            }
        }
        check(terminal == ConnectionState.AUTHENTICATED) {
            "Remote authentication failed: ${userSession.authFailureReason ?: "unspecified reason"}"
        }
    }

    private fun closeAfterFailure(session: Session, failure: Throwable): Nothing {
        runCatching(session::close).exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    }
}

/** 将单个测试进程串行化到已部署服务器滚动来源配额之下。 */
internal class SlidingWindowPacer(
    private val maxAttempts: Int,
    private val windowNanos: Long,
    private val safetyNanos: Long,
    private val monotonicNanos: () -> Long = System::nanoTime,
    private val waitNanos: suspend (Long) -> Unit = { nanos ->
        delay((nanos + 999_999L) / 1_000_000L)
    },
) {
    private val mutex = Mutex()
    private val admittedAt = ArrayDeque<Long>()

    init {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        require(windowNanos > 0L) { "windowNanos must be positive" }
        require(safetyNanos >= 0L) { "safetyNanos must not be negative" }
    }

    suspend fun awaitPermit() {
        mutex.lock()
        try {
            while (true) {
                val now = monotonicNanos()
                while (admittedAt.firstOrNull()?.let { now - it >= windowNanos } == true) {
                    admittedAt.removeFirst()
                }
                if (admittedAt.size < maxAttempts) {
                    admittedAt.addLast(now)
                    return
                }
                val elapsed = now - admittedAt.first()
                waitNanos(windowNanos - elapsed + safetyNanos)
            }
        } finally {
            mutex.unlock()
        }
    }
}
