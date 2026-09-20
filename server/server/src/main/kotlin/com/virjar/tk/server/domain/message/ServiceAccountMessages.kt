package com.virjar.tk.server.domain.message

import com.virjar.tk.server.domain.chat.ChatService
import com.virjar.tk.server.domain.user.SystemAccountUids
import com.virjar.tk.server.domain.user.UserRepository
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.HexFormat

/**
 * 服务号官方触达（原始需求：新用户欢迎语 + 管理员全员广播）。
 *
 * 欢迎语在客户端首次拉起（或后续重入）sys_service 系统会话时按稳定消息身份自愈补发：
 * 已发送过则零成本跳过；发送中断在下次进入会话时重试，不依赖进程存活。模板由管理台
 * 覆盖式更新，只影响之后首次收到欢迎语的用户，不重发历史欢迎语。
 *
 * 广播由管理员发起：台账先落 PostgreSQL，随后按 uid 分页为每个用户确保系统会话、写入
 * 待回复记录并立即派发。待回复记录复用 CODE-01 的持久发件箱——进程中断后由启动恢复
 * 排空已写记录，未扫描的尾部广播在启动时自动续跑；同id重复执行对每个用户幂等，
 * 不会产生第二条广播消息。
 */
class ServiceAccountMessages(
    private val messages: MessageRepository,
    private val messageService: MessageService,
    private val chats: ChatService,
    private val users: UserRepository,
    private val store: ServiceAccountDirectory,
    private val handler: SystemCommandHandler,
) {
    private val logger = LoggerFactory.getLogger(ServiceAccountMessages::class.java)

    /** 幂等补发欢迎语；调用方为系统会话入口（RPC 拉起 sys_service 会话后）。 */
    suspend fun ensureWelcome(uid: String, chatId: String) {
        if (messages.findCommittedMessage(chatId, WELCOME_MESSAGE_ID) != null) return
        val template = store.getWelcomeTemplate() ?: DEFAULT_WELCOME_TEMPLATE
        try {
            messageService.sendServiceReply(chatId, WELCOME_MESSAGE_ID, template)
        } catch (failure: Exception) {
            // 欢迎语不改变会话拉起结果；下次进入会话时按稳定身份重试。
            logger.warn("服务号欢迎语暂未送达：uid={}", uid, failure)
        }
    }

    suspend fun getWelcomeTemplate(): String = store.getWelcomeTemplate() ?: DEFAULT_WELCOME_TEMPLATE

    suspend fun setWelcomeTemplate(content: String, operator: String): String {
        val template = requireBroadcastContent(content)
        store.setWelcomeTemplate(template, operator)
        return template
    }

    /** 创建并启动一次全员广播；[launch] 由运行时提供（异步执行 [runBroadcast]）。 */
    suspend fun createBroadcast(markdown: String, operator: String, launch: (String) -> Unit): ServiceBroadcastEntry {
        val content = requireBroadcastContent(markdown)
        val broadcastId = newBroadcastId()
        store.insertBroadcast(broadcastId, content, operator)
        launch(broadcastId)
        return checkNotNull(store.getBroadcast(broadcastId)) { "广播台账写入后必须可读" }
    }

    /** 重新执行一次广播（覆盖此前中断或失败的尾部；已送达用户幂等跳过）。 */
    suspend fun reissueBroadcast(broadcastId: String, launch: (String) -> Unit): ServiceBroadcastEntry {
        checkNotNull(store.getBroadcast(broadcastId)) { "未知的广播：$broadcastId" }
        launch(broadcastId)
        return checkNotNull(store.getBroadcast(broadcastId))
    }

    /** 启动续跑：进程上次关闭时未完成的广播重新执行（已写记录同时由指令运行时恢复排空）。 */
    fun resumeUnfinishedBroadcasts(launch: (String) -> Unit): List<String> {
        val unfinished = store.listUnfinishedBroadcastIds()
        unfinished.forEach(launch)
        return unfinished
    }

    fun listBroadcasts(): List<ServiceBroadcastEntry> = store.listBroadcasts()

    fun getBroadcast(broadcastId: String): ServiceBroadcastEntry? = store.getBroadcast(broadcastId)

    /** 广播执行体：分页确保会话、写持久记录、立即派发，并按页回写进度。 */
    internal suspend fun runBroadcast(broadcastId: String) {
        val record = store.getBroadcast(broadcastId) ?: return
        store.markBroadcastTotal(broadcastId, users.countHumans().let { if (it > Int.MAX_VALUE) Int.MAX_VALUE else it.toInt() })
        var covered = 0
        var failed = 0
        var lastError: String? = null
        var afterUid: String? = null
        try {
            while (true) {
                val page = users.listHumanUidPage(afterUid, BROADCAST_USER_PAGE_SIZE)
                if (page.isEmpty()) break
                for (uid in page) {
                    try {
                        val chat = chats.getOrCreateSystemChat(uid, SystemAccountUids.SERVICE)
                        ensureWelcome(uid, chat.chatId)
                        val pending = PendingServiceReply(
                            chatId = chat.chatId,
                            clientMsgId = "$BROADCAST_RECORD_PREFIX$broadcastId",
                            replyClientMsgId = "$BROADCAST_REPLY_PREFIX$broadcastId",
                            markdown = record.markdown,
                        )
                        messages.appendPendingServiceReply(pending)
                        handler.dispatchServiceReply(pending)
                        covered += 1
                    } catch (failure: Exception) {
                        failed += 1
                        lastError = failure.message ?: failure.javaClass.simpleName
                        logger.warn("服务号广播单用户失败：broadcastId={}, uid={}", broadcastId, uid, failure)
                    }
                }
                store.recordBroadcastProgress(broadcastId, covered, failed, lastError)
                afterUid = page.last()
            }
        } finally {
            store.finishBroadcast(broadcastId)
        }
    }

    private fun requireBroadcastContent(content: String): String {
        val trimmed = content.trim()
        require(trimmed.isNotEmpty()) { "内容不能为空" }
        require(trimmed.length <= MAX_CONTENT_LENGTH) {
            "内容超过 ${MAX_CONTENT_LENGTH} 字符上限"
        }
        return trimmed
    }

    companion object {
        /** 欢迎语稳定消息身份；尾部版本号允许未来有意识地重新欢迎一次。 */
        const val WELCOME_MESSAGE_ID = "sys-welcome-v1"

        /** 广播在待回复发件箱与消息链中的稳定身份前缀。 */
        const val BROADCAST_RECORD_PREFIX = "bcast-"
        const val BROADCAST_REPLY_PREFIX = "sys-bcast-"

        /** 内容上限对齐富文本正文预算（余量留给派生纯文本），超限在入口拒绝。 */
        const val MAX_CONTENT_LENGTH = 20_000

        private const val BROADCAST_USER_PAGE_SIZE = 100
        private val broadcastIdPattern = Regex("bc[0-9]{13}-[0-9a-f]{6}")

        fun isValidBroadcastId(broadcastId: String): Boolean = broadcastIdPattern.matches(broadcastId)

        private fun newBroadcastId(random: SecureRandom = SecureRandom()): String {
            val suffix = ByteArray(3).also(random::nextBytes).let { HexFormat.of().formatHex(it) }
            return "bc${System.currentTimeMillis()}-$suffix"
        }

        val DEFAULT_WELCOME_TEMPLATE = """
            欢迎使用 TeamTalk！

            我是服务号，随时为你提供帮助：
            - 发送 /help 查看可用指令
            - 常见问题与使用指引见客户端「关于」页面

            祝使用愉快。
        """.trimIndent()
    }
}
