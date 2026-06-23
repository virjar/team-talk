package com.virjar.tk.client

import com.virjar.tk.repository.*
import com.virjar.tk.util.AppLog
import com.virjar.tk.util.LogBuffer
/**
 * 已认证会话的共享依赖容器。
 * 封装认证后创建的所有组件，统一生命周期管理。
 *
 * [userSession] 是用户层状态（uid/refreshToken），独立于 [imClient] 的 TCP 连接。
 */
class ClientSession(
    val imClient: ImClient,
    val userSession: UserSession,
    val localCache: LocalCache,
    val rpcClient: RpcClient,
    val eventProcessor: EventProcessor,
    val httpLogUploader: HttpLogUploader,
    val conversationRepo: ConversationRepository,
    val contactRepo: ContactRepository,
    val messageRepo: MessageRepository,
    val chatRepo: ChatRepository,
    val deviceRepo: DeviceRepository,
    val userRepo: UserRepository,
) {
    fun close() {
        httpLogUploader.stop()
        rpcClient.stop()
        eventProcessor.stop()
        imClient.disconnect()
    }
}

/**
 * 创建完整会话。在认证成功后调用。
 * @param createCache 平台提供的 LocalCache 工厂 (uid) -> LocalCache
 * @param deviceId 设备 ID，用于日志上传标识
 */
fun createSession(
    imClient: ImClient,
    userSession: UserSession,
    createCache: (String) -> LocalCache,
    deviceId: String,
): ClientSession {
    val cache = createCache(userSession.uid)
    val rpcClient = RpcClient(imClient)
    val conversationRepo = ConversationRepository(rpcClient, cache)
    val ep = EventProcessor(imClient, cache, onConversationsDirty = { conversationRepo.listConversations() })

    // 日志缓冲区（分级：trace + fault）
    val traceBuffer = LogBuffer(capacity = 2000)
    val faultBuffer = LogBuffer(capacity = 500)
    AppLog.traceBuffer = traceBuffer
    AppLog.faultBuffer = faultBuffer

    rpcClient.start()
    ep.start()

    // HTTP 日志上传器 + crash 持久化
    val serverUrl = defaultServerConfig().serverUrl
    val dataDir = java.io.File(System.getProperty("teamtalk.data.dir", System.getProperty("user.home") + "/.teamtalk"))
    val crashDumper = CrashDumper(dataDir)
    val httpLogUploader = HttpLogUploader(traceBuffer, faultBuffer, serverUrl, deviceId, crashDumper)
    httpLogUploader.start()
    AppLog.onFault = { httpLogUploader.trigger() }

    return ClientSession(
        imClient = imClient,
        userSession = userSession,
        localCache = cache,
        rpcClient = rpcClient,
        eventProcessor = ep,
        httpLogUploader = httpLogUploader,
        conversationRepo = conversationRepo,
        contactRepo = ContactRepository(rpcClient, cache),
        messageRepo = MessageRepository(rpcClient, cache),
        chatRepo = ChatRepository(rpcClient, cache),
        deviceRepo = DeviceRepository(rpcClient),
        userRepo = UserRepository(rpcClient, cache),
    )
}
