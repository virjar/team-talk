package com.virjar.tk.app.navigation.feature.chat

import kotlinx.coroutines.*

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.virjar.tk.app.navigation.AppDataStateDestroyGate
import com.virjar.tk.app.navigation.UiLocalDataBoundary
import com.virjar.tk.app.telemetry.ClientUiTelemetrySink
import com.virjar.tk.app.ui.bridge.ChatAssetImportDelegate
import com.virjar.tk.app.ui.bridge.DurableChatAssetImports
import com.virjar.tk.app.viewmodel.ChatViewModel
import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.client.ClientSession
import com.virjar.tk.shared.repository.ChatAssetSpool

/**
 * 会话作用域的聊天域控制器：编辑器热上下文、跨设备草稿生命周期、附件导入 worker、
 * chat ViewModel 绑定与本地草稿/已读写入门面。
 *
 * 平台壳只经 [composerContexts] 与 [draftLifecycle] 挂接编辑器；所有 SQLite 写入仍由
 * SDK 单写者经 [ClientSession] 完成，本类不持有持久化资源。
 */
class ChatFeature(
    private val session: ClientSession,
    private val scope: CoroutineScope,
    val composerContexts: ChatComposerContextStore = ChatComposerContextStore(),
    private val destroyGate: AppDataStateDestroyGate,
    private val localData: UiLocalDataBoundary,
    private val telemetry: ClientUiTelemetrySink,
    private val reportError: (Throwable, String) -> Unit,
    private val onAuthExpired: () -> Unit,
) {
    val draftLifecycle = ChatDraftLifecycleBridge()

    private val localMutations = session.localMutations
    private val activeChat = ActiveChatBinding()
    private var assetImports: ChatAssetImportDelegate? = null

    var viewModel by mutableStateOf<ChatViewModel?>(null)
        private set

    /** 存在未读 @我 提示的会话集合（MENTION_SYNC 驱动的进程内投影）。 */
    val mentionedChatIds: kotlinx.coroutines.flow.StateFlow<Set<String>>
        get() = session.eventProcessor.mentionedChatIds

    init {
        composerContexts.bindPersistence(
            localCache = session.localCache,
            localMutations = localMutations,
            localData = localData,
            onFailure = { reportLocalMutationFailure(it, "保存聊天草稿失败") },
        )
    }

    /** Platform supplies its private storage root; the session owns the durable upload worker. */
    fun chatAssetImports(createSpool: () -> ChatAssetSpool): ChatAssetImportDelegate =
        checkNotNull(destroyGate.readIfOpen {
            assetImports ?: DurableChatAssetImports(
                drafts = session.localCache.chatDrafts,
                uploads = session.localCache.chatAssetUploads,
                coordinator = scope.async(Dispatchers.IO) {
                    session.createChatAssetUploads(createSpool())
                },
                scope = scope,
                localData = localData,
                reportFailure = reportError,
            ).also { assetImports = it }
        }) { "Chat asset owner has retired" }

    /**
     * 确保 session 作用域的 chat ViewModel 属于正在渲染的 route。
     *
     * Android 可以恢复 CHAT 返回栈条目，而无需重放最初导航到那里的点击。
     * 保持这个操作幂等让目的地拥有准备工作，同时在正常进入时保留一个已经存活的
     * ViewModel（及其已加载的消息窗口）。
     */
    fun prepareChat(chatId: String): Boolean = destroyGate.runIfOpen {
        if (!activeChat.needsPreparation(chatId, viewModel != null)) return@runIfOpen
        viewModel?.destroy()
        viewModel = ChatViewModel(
            chatId = chatId,
            localCache = session.localCache,
            messageRepo = session.messageRepo,
            eventProcessor = session.eventProcessor,
            connectionState = session.connectionState,
            myUid = session.userSession.uid,
            localMutations = localMutations,
            trySendTyping = session::trySendTyping,
            localData = localData,
            telemetry = telemetry,
            onAuthExpired = onAuthExpired,
            prepareFailedMessageReplacement = session::prepareChatAssetReplacement,
            chatDraftRepository = session.chatDraftRepo,
        )
        activeChat.markPrepared(chatId)
    }

    /** 绝不在导航/返回栈转换期间暴露另一条 route 的 ViewModel。 */
    fun chatViewModelFor(chatId: String): ChatViewModel? =
        destroyGate.readIfOpen {
            viewModel.takeIf { activeChat.matches(chatId, it != null) }
        }

    /** 供平台渲染器使用的纯驻留查询；它绝不在 Compose/Main 上执行 SQLite 工作。 */
    fun residentChatUser(uid: String) = destroyGate.readIfOpen {
        viewModel?.residentSender(uid)
    }

    fun saveDraft(chatId: String, draft: String?) {
        val normalized = draft?.takeIf { it.isNotBlank() }
        draftLifecycle.publishIfOpen {
            // Main 只准入每个会话最新的命令。确切 session 的单一写者在 LocalCache
            // 退役之前提交它，然后调度现有的持久镜像路径。
            localMutations.setDraft(chatId, normalized) { failure ->
                reportLocalMutationFailure(failure, "保存草稿失败")
            }
        }
    }

    /** 首次收集直接呈现 SDK 的持久投影；UI 不另建头像映射或事件桥。 */
    val chatAvatars = localData.projection(session.localCache::observeChatAvatars)

    /** 观察会话成员（含已解析用户）；聊天记录搜索的发送人筛选项来源。 */
    fun observeChatMembers(chatId: String) =
        localData.projection { session.localCache.observeMembers(chatId) }

    /** 本机清空水位：聊天记录搜索用它排除已清空消息（服务端读取不经过本地落库路径）。 */
    fun chatHistoryClearedBefore(chatId: String): Long =
        destroyGate.readIfOpen { session.localCache.clearedChatHistoryBefore(chatId) } ?: 0L

    /**
     * 只读倒序历史页（不写本地缓存、不触碰常驻窗口）；聊天记录扫描器使用。
     * 失败向上抛出，由调用方区分“到底”与“失败”。
     */
    suspend fun queryHistoryPage(
        chatId: String,
        fromSeq: Long,
        limit: Int,
    ): List<com.virjar.tk.protocol.model.Message> {
        if (!destroyGate.acceptsWork) return emptyList()
        return localData.run {
            session.messageRepo.queryHistory(chatId, fromSeq, limit).getOrThrow()
        }
    }

    /** 群头像设置：owner/管理员经既认证 staging 上传后调用；本地投影即时更新。 */
    fun setGroupAvatar(chatId: String, attachment: com.virjar.tk.protocol.model.Attachment?) {
        destroyGate.runIfOpen {
            scope.launch {
                try {
                    localData.run { session.chatRepo.setGroupAvatar(chatId, attachment).getOrThrow() }
                } catch (failure: Throwable) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    reportError(failure, "设置群头像失败")
                }
            }
        }
    }

    /** 懒加载会话列表可见群的当前头像。 */
    fun ensureGroupAvatars(chatIds: List<String>) {
        destroyGate.runIfOpen {
            scope.launch {
                // 懒加载失败保留旧投影；离线列表不因自动刷新重复弹错误。Outcome 保持取消语义。
                localData.run { session.chatRepo.ensureGroupAvatars(chatIds) }
            }
        }
    }

    /** 系统账号会话（内测反馈 T058）是否已在本会话拉起。 */
    private var systemChatsEnsured = false

    /**
     * 登录后拉起固定系统账号会话（文件传输助手/服务号）：幂等 RPC，已存在时不产生事件。
     * 失败静默（网络/旧服务端 minor 2 协商下会被本地拒绝），下次登录重试。
     */
    fun ensureSystemChats() {
        if (systemChatsEnsured) return
        systemChatsEnsured = true
        destroyGate.runIfOpen {
            scope.launch {
                for (systemUid in listOf("sys_assistant", "sys_service")) {
                    runCatching { session.chatRepo.getOrCreateSystemChat(systemUid) }
                        .onFailure { failure ->
                            if (failure !is kotlinx.coroutines.CancellationException) {
                                reportError(failure, "拉起系统会话失败")
                            }
                        }
                }
            }
        }
    }

    /** 在每一台设备上持久化显式的会话列表"标记已读"动作。 */
    fun markConversationRead(chatId: String, readSeq: Long) {
        if (readSeq <= 0L) return
        destroyGate.runIfOpen {
            localMutations.markRead(chatId, readSeq) { failure ->
                reportLocalMutationFailure(failure, "标记已读失败")
            }
        }
    }

    /**
     * 清空单个会话的本机聊天记录（不影响其他设备，也不改变会话与已读水位）。
     * 返回 null 表示成功，否则为可展示的失败原因（由调用页面就地呈现）。
     */
    suspend fun clearChatHistory(chatId: String): String? {
        if (!destroyGate.acceptsWork) return "会话已关闭"
        return try {
            localData.run { session.localCache.clearChatHistory(chatId) }
            null
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            "清空聊天记录失败：${failure.message ?: "未知错误"}"
        }
    }

    private fun reportLocalMutationFailure(failure: Throwable, fallback: String) {
        if (failure is CancellationException) throw failure
        scope.launch { reportError(failure, fallback) }
    }

    // ── 退役钩子：由 AppDataState.destroy 按既有顺序调用 ──

    /** 当前 Compose 编辑器拥有的帧比 350 ms 防抖更新；必须在其余 owner 退役前发布。 */
    fun captureAndRetireDraftCapture() {
        draftLifecycle.captureAndRetire()
    }

    fun destroyViewModel() {
        val closing = viewModel
        viewModel = null
        closing?.destroy()
    }

    fun clearActiveChat() {
        activeChat.clear()
    }

    fun clearComposerContexts() {
        composerContexts.clear()
    }
}

/** 纯 route 到 owner 的绑定保持分离，这样生命周期/幂等规则可以单元测试。 */
internal class ActiveChatBinding {
    private var preparedChatId: String? = null

    fun needsPreparation(routeChatId: String, hasViewModel: Boolean): Boolean =
        !hasViewModel || preparedChatId != routeChatId

    fun matches(routeChatId: String, hasViewModel: Boolean): Boolean =
        hasViewModel && preparedChatId == routeChatId

    fun markPrepared(chatId: String) {
        preparedChatId = chatId
    }

    fun clear() {
        preparedChatId = null
    }
}
