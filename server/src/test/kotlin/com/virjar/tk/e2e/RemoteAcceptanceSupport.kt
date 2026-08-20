package com.virjar.tk.e2e

import com.virjar.tk.client.ConnectionState
import com.virjar.tk.client.ImClient
import com.virjar.tk.client.RpcClient
import com.virjar.tk.model.ContactApply
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.payload.NotifyPayload
import com.virjar.tk.protocol.payload.ResponsePayload
import com.virjar.tk.protocol.payload.SubscribePayload
import com.virjar.tk.rpc.gen.ContactRpcContract
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.UUID

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

    /**
     * 轻量会话包装，复用真实 ImClient + RpcClient。
     * 结构与 [ProtocolE2eTest] 内的 E2eSession 一致，抽出来供远程测试复用。
     */
    class Session(
        val imClient: ImClient,
        val rpc: RpcClient,
        val userSession: com.virjar.tk.client.UserSession,
    ) {
        private val notifyBuffer = mutableListOf<NotifyPayload>()
        private var collectJob: Job? = null

        val uid: String get() = userSession.uid

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
            val response = invoke("contact", ContactRpcContract.M_LIST_APPLIES)
            require(response.status == 0 && response.payload != null) { "无法读取待处理好友申请" }
            return ProtoCodec.decodeList(ContactApply, response.payload!!)
                .single { it.fromUid == fromUid && it.status == 0 }
                .token ?: error("待处理申请缺少收件人 token")
        }

        fun subscribe(chatId: String, lastSeq: Long = 0) {
            imClient.send(SubscribePayload(chatId, lastSeq))
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

        fun close() {
            collectJob?.cancel()
            rpc.stop()
            // E2E 测试会话是一次性的，彻底销毁线程资源
            imClient.destroy()
        }
    }

    /** 建立一条到远程部署的已认证会话（注册一个新账号）。 */
    suspend fun registerUser(suffix: String): Session {
        val session = createSession()
        // 用户名：e2e-<suffix>-<8位hex>，总长度远小于 50（服务端限制 3..50）。
        // 用短随机串而非完整 UUID，避免测试前缀 + UUID 超过 50 字符被拒。
        val username = "e2e-$suffix-" + UUID.randomUUID().toString().take(8)
        session.imClient.register(username, "password123", "TestUser $suffix", "e2e-device", "E2E")
        withTimeout(10_000) { session.imClient.state.first { it == ConnectionState.AUTHENTICATED } }
        session.registeredUsername = username
        return session
    }

    /** 用已有账号登录建立会话（验证登录流程时使用）。 */
    suspend fun loginUser(username: String, password: String): Session {
        val session = createSession()
        session.imClient.login(username, password, "e2e-device", "E2E")
        withTimeout(10_000) { session.imClient.state.first { it == ConnectionState.AUTHENTICATED } }
        return session
    }

    /** 仅建立 TCP 连接（不认证），用于测试登录失败等场景。 */
    suspend fun createSession(): Session {
        val userSession = com.virjar.tk.client.UserSession()
        val imClient = ImClient(onAuthResult = { success, uid, username, name, refreshToken, accessToken, failureReason ->
            if (success) userSession.onAuthSuccess(uid ?: "", username, name, refreshToken, accessToken)
            else userSession.onAuthFailed(failureReason)
        })
        imClient.connect(host, port)
        withTimeout(10_000) { imClient.state.first { it == ConnectionState.CONNECTED } }

        val rpc = RpcClient(imClient)
        rpc.start()

        val session = Session(imClient, rpc, userSession)
        session.startCollecting(scope)
        return session
    }

    /** 测试结束清理 scope（PER_CLASS 生命周期结束时调用）。 */
    fun shutdown() {
        scope.cancel()
    }
}
