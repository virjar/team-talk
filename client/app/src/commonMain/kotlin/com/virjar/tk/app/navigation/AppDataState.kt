package com.virjar.tk.app.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.client.ClientSession
import com.virjar.tk.shared.client.SessionEndReason
import com.virjar.tk.app.client.collapseClientLifecycleFailures
import com.virjar.tk.app.client.isFatalClientLifecycleFailure
import com.virjar.tk.shared.client.logUnhandledError
import com.virjar.tk.app.navigation.feature.AccountFeature
import com.virjar.tk.app.navigation.feature.DiscoveryFeature
import com.virjar.tk.app.navigation.feature.GroupFeature
import com.virjar.tk.app.navigation.feature.OrganizationFeature
import com.virjar.tk.app.navigation.feature.GroupFilesFeature
import com.virjar.tk.app.navigation.feature.document.DocumentDraftStore
import com.virjar.tk.app.navigation.feature.document.DocumentDraftOwnerKey
import com.virjar.tk.app.navigation.feature.document.DocumentWorkspaceFeature
import com.virjar.tk.app.navigation.feature.MessageActionsFeature
import com.virjar.tk.app.ui.UiActionAdmission
import com.virjar.tk.app.ui.SessionUiActionExecutor
import com.virjar.tk.app.ui.screen.ChatComposerContextStore
import com.virjar.tk.app.ui.screen.ChatDraftLifecycleBridge
import com.virjar.tk.app.telemetry.ClientUiTelemetrySink
import com.virjar.tk.app.telemetry.ClientUiAction
import com.virjar.tk.app.telemetry.ClientUiPage
import com.virjar.tk.app.telemetry.FeedbackOrigin
import com.virjar.tk.app.telemetry.SessionClientUiTelemetrySink
import com.virjar.tk.app.telemetry.UserFeedbackCode
import com.virjar.tk.app.telemetry.UserFeedbackNotice
import com.virjar.tk.app.telemetry.UserFeedbackReporter
import com.virjar.tk.shared.log.AppLog
import com.virjar.tk.app.viewmodel.ChatViewModel
import com.virjar.tk.app.viewmodel.ContactViewModel
import com.virjar.tk.app.viewmodel.ConversationViewModel
import com.virjar.tk.app.viewmodel.GlobalSearchUserViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Session 作用域的客户端组合根。
 *
 * 这个对象拥有共享的 ViewModel、feature 控制器及其协程生命周期。
 * 平台导航留在 Android/Desktop 外壳中；feature 状态和动作位于
 * [account]、[groups] 和 [discovery] 中。
 */
open class AppDataState(
    private val session: ClientSession,
    val chatComposerContexts: ChatComposerContextStore = ChatComposerContextStore(),
    val chatDraftLifecycle: ChatDraftLifecycleBridge = ChatDraftLifecycleBridge(),
    val documentDrafts: DocumentDraftStore,
    private val onAuthExpired: () -> Unit = { session.close(reason = SessionEndReason.AUTH_REVOKED) },
    private val onHttpAuthExpired: (rejectedAccessToken: String) -> Unit = { onAuthExpired() },
    private val localData: UiLocalDataBoundary = UiLocalDataBoundary(),
    val telemetry: ClientUiTelemetrySink = SessionClientUiTelemetrySink(session.telemetryRecorder),
) {
    val userSession get() = session.userSession
    val deploymentIdentity get() = session.deploymentIdentity
    /** 平台所拥有的缓存和其他 session 资源的不可变权威数据集。 */
    val datasetId get() = session.datasetId
    val documentDraftOwnerKey = DocumentDraftOwnerKey(
        deploymentFingerprint = session.deploymentIdentity.fingerprint,
        datasetId = session.datasetId,
        uid = session.ownerUid,
    )
    fun httpCredentialsSnapshot() = session.httpCredentialsSnapshot()
    val feedbackReporter = UserFeedbackReporter(telemetry)

    private val activeChat = ActiveChatBinding()
    private val destroyGate = AppDataStateDestroyGate()
    private val localMutations = session.localMutations
    /** Android/common UI 执行围栏；Desktop 提供它更早的呈现围栏。 */
    val uiActionAdmission = UiActionAdmission(destroyGate::runIfOpen)

    /** 退役一开始就变为 false；过期的平台组合绝不能渲染任何业务 UI。 */
    val acceptsRendering: Boolean get() = destroyGate.acceptsWork

    /** 供平台渲染器使用的纯驻留查询；它绝不在 Compose/Main 上执行 SQLite 工作。 */
    fun residentChatUser(uid: String) = destroyGate.readIfOpen {
        chatViewModel?.residentSender(uid)
    }

    /**
     * 在 [admission] 仍然租用期间，于 session 拥有的 scope 中启动一个挂起的 UI 命令。
     * UNDISPATCHED 覆盖第一次挂起之前的每一个同步 owner 借用；此后 [actionScope]
     * 是由 [destroy] 退役的可取消 session owner。资源准入 gate 将任何取消清理
     * 与之后的 ClientSession close 线性化。调用方只等待一个结果，因此不可能把工作
     * 附着到过时的 Window/route scope 上。
     */
    suspend fun <T> runAdmittedUiAction(
        admission: UiActionAdmission,
        onClosed: () -> T,
        action: suspend () -> T,
    ): T = uiActionExecutor.execute(admission, onClosed, action)

    /**
     * 直接在 session 拥有的 scope 中启动 fire-and-forget 的 UI 工作。原生结果回调
     * 使用它而不是 route/Window scope，这样迟到的 picker 或权限结果在退役之后会被拒绝，
     * 而被准入的工作会由 [destroy] 取消。
     */
    fun launchAdmittedUiAction(
        admission: UiActionAdmission = uiActionAdmission,
        action: suspend () -> Unit,
    ): Boolean = uiActionExecutor.launch(admission, action)

    /** 返回 session 拥有的上传协程，让富资源网关可以按 job id 取消。 */
    fun launchCancellableAdmittedUiAction(
        admission: UiActionAdmission = uiActionAdmission,
        action: suspend () -> Unit,
    ): Job? = uiActionExecutor.launchCancellable(admission, action)

    private val actionScope = CoroutineScope(
        Dispatchers.Main + SupervisorJob() +
            CoroutineExceptionHandler { _, throwable -> logUnhandledError("AppDataState", throwable) },
    )
    private val uiActionExecutor = SessionUiActionExecutor(actionScope)
    private val httpAuthExpiredBinding = session.bindHttpAuthExpiredHandler(::reportHttpAuthExpired)
    // 声明在 feature 控制器之前，因为当测试或即时 dispatcher 在 GroupFilesFeature 的 init 块中
    // 启动其收集器时，重放的完成可能立即发布。
    private val uiErrors = UiEventMailbox<String>()
    private val uiNotices = UiEventMailbox<UserFeedbackNotice>()

    val errorSignal: UiEventSignal?
        get() = uiErrors.signal

    val noticeSignal: UiEventSignal?
        get() = uiNotices.signal

    val conversationViewModel = ConversationViewModel(
        localCache = session.localCache,
        conversationRepo = session.conversationRepo,
        connectionState = session.connectionState,
        onAuthExpired = { this@AppDataState.onAuthExpired() },
        localData = localData,
    )
    val contactViewModel = ContactViewModel(
        localCache = session.localCache,
        contactRepo = session.contactRepo,
        myUid = userSession.uid,
        contactEvents = session.eventProcessor.contactEvents,
        connectionState = session.connectionState,
        friendPresenceByUid = session.friendPresenceByUid,
        onAuthExpired = { this@AppDataState.onAuthExpired() },
        localData = localData,
    )
    val globalSearchUserViewModel = GlobalSearchUserViewModel(
        localCache = session.localCache,
        localData = localData,
    )
    var chatViewModel by mutableStateOf<ChatViewModel?>(null)
        private set

    val account = AccountFeature(session, contactViewModel, actionScope, ::handleError, localData)
    val groups = GroupFeature(session, actionScope, ::handleError, localData, telemetry)
    val discovery = DiscoveryFeature(session, ::handleError, localData)
    val messageActions = MessageActionsFeature(session) { action ->
        launchAdmittedUiAction(action = action)
    }
    val organization = OrganizationFeature(session, actionScope, ::handleError, localData)
    val groupFiles = GroupFilesFeature(
        session = session,
        scope = actionScope,
        reportError = ::handleError,
        localData = localData,
        telemetry = telemetry,
        reportFeedback = ::handleFeedback,
    )
    val documents = DocumentWorkspaceFeature(
        session,
        actionScope,
        ::handleError,
        documentDrafts,
        localData,
        telemetry,
    )

    fun destroy(
        clearComposerContexts: Boolean = true,
        clearDocumentDrafts: Boolean = clearComposerContexts,
    ) {
        val completion = destroyGate.destroy {
            val failures = mutableListOf<Pair<String, Throwable>>()
            fun release(owner: String, block: () -> Unit) {
                try {
                    block()
                } catch (failure: Throwable) {
                    failures += owner to failure
                }
            }

            // 当前 Compose 编辑器拥有的帧比 350 ms 防抖更新。在 ConversationRepository
            // 仍被准入时发布它，并退役桥接，这样延迟的 Window/Dialog 销毁
            // 就不可能穿透一个已经静默的 ClientSession 写入。
            release("chat draft capture", chatDraftLifecycle::captureAndRetire)
            release("HTTP auth expiry binding", httpAuthExpiredBinding::close)
            release("document draft capture") {
                check(documents.retireDraftCapture()) { "Document editor final-frame capture failed" }
            }
            release("conversation ViewModel", conversationViewModel::destroy)
            release("contact ViewModel", contactViewModel::destroy)
            release("global search user ViewModel", globalSearchUserViewModel::destroy)
            val closingChat = chatViewModel
            chatViewModel = null
            release("chat ViewModel") { closingChat?.destroy() }
            release("active chat binding", activeChat::clear)
            if (clearComposerContexts) {
                release("chat composer contexts", chatComposerContexts::clear)
            }
            // 平台持久化拥有它自己的异步落盘队列。保留草稿在这里不需要阻塞工作；
            // Android 捕获编辑器并在 destroy 之前调度一个屏障。
            if (clearDocumentDrafts) {
                release("document drafts") { documentDrafts.clearAndRetire(documentDraftOwnerKey) }
            }
            release("action scope") { actionScope.cancel() }
            failures
        }
        if (completion.completedNow && completion.failures.isNotEmpty()) {
            val first = completion.failures.first()
            try {
                AppLog.fault(
                    "AppDataState",
                    "Destroy completed with ${completion.failures.size} cleanup failure(s); first owner=${first.first}",
                    first.second,
                )
            } catch (failure: Throwable) {
                if (isFatalClientLifecycleFailure(failure)) {
                    throw destroyGate.recordTerminalFatal(failure, completion.failures)
                }
            }
        }
    }

    fun prepareChat(chatId: String): Boolean = ensureChat(chatId)

    /**
     * 确保 session 作用域的 chat ViewModel 属于正在渲染的 route。
     *
     * Android 可以恢复 CHAT 返回栈条目，而无需重放最初导航到那里的点击。
     * 保持这个操作幂等让目的地拥有准备工作，同时在正常进入时保留一个已经存活的
     * ViewModel（及其已加载的消息窗口）。
     */
    fun ensureChat(chatId: String): Boolean = destroyGate.runIfOpen { ensureChatWhileOpen(chatId) }

    private fun ensureChatWhileOpen(chatId: String) {
        if (!activeChat.needsPreparation(chatId, chatViewModel != null)) return
        chatViewModel?.destroy()
        chatViewModel = ChatViewModel(
            chatId = chatId,
            localCache = session.localCache,
            messageRepo = session.messageRepo,
            eventProcessor = session.eventProcessor,
            connectionState = session.connectionState,
            myUid = userSession.uid,
            localMutations = localMutations,
            trySendTyping = session::trySendTyping,
            localData = localData,
            telemetry = telemetry,
            onAuthExpired = { this@AppDataState.onAuthExpired() },
        )
        activeChat.markPrepared(chatId)
    }

    /** 绝不在导航/返回栈转换期间暴露另一条 route 的 ViewModel。 */
    fun chatViewModelFor(chatId: String): ChatViewModel? =
        destroyGate.readIfOpen {
            chatViewModel.takeIf { activeChat.matches(chatId, it != null) }
        }

    /** 为某个可见宿主保留最旧的错误，直到该宿主完成或释放它。 */
    fun acquireError(owner: Any): UiEventLease<String>? =
        destroyGate.readIfOpen { uiErrors.acquire(owner) }

    fun completeError(lease: UiEventLease<String>): Boolean =
        destroyGate.readIfOpen { uiErrors.complete(lease) } == true

    fun releaseError(lease: UiEventLease<String>): Boolean =
        destroyGate.readIfOpen { uiErrors.release(lease) } == true

    fun markErrorDisplayed(lease: UiEventLease<String>): Boolean =
        destroyGate.readIfOpen { uiErrors.markDisplayed(lease) } == true

    /** 通知使用单独的 FIFO，这样良性的反馈绝不会覆盖终止性错误。 */
    fun acquireNotice(owner: Any): UiEventLease<UserFeedbackNotice>? =
        destroyGate.readIfOpen { uiNotices.acquire(owner) }

    fun completeNotice(lease: UiEventLease<UserFeedbackNotice>): Boolean =
        destroyGate.readIfOpen { uiNotices.complete(lease) } == true

    fun releaseNotice(lease: UiEventLease<UserFeedbackNotice>): Boolean =
        destroyGate.readIfOpen { uiNotices.release(lease) } == true

    fun markNoticeDisplayed(lease: UiEventLease<UserFeedbackNotice>): Boolean =
        destroyGate.readIfOpen { uiNotices.markDisplayed(lease) } == true

    /** Session 作用域的平台 HTTP/media 终止点；委托给现有的确切认证 owner。 */
    fun reportAuthExpired() {
        handleError(AppError.AuthExpired, "认证失效，请重新登录")
    }

    /** 携带被拒绝请求的确切 request bearer 到 AuthController 的平台 HTTP 401 终止点。 */
    fun reportHttpAuthExpired(rejectedAccessToken: String) {
        destroyGate.runIfOpen { onHttpAuthExpired(rejectedAccessToken) }
    }

    /** 在平台把这个状态作为 session owner 发布之后，开始投递 HTTP 401。 */
    fun activateHttpAuthExpiredDelivery() {
        destroyGate.runIfOpen(httpAuthExpiredBinding::activate)
    }

    suspend fun loadScreenDataByKey(key: ScreenDataKey) {
        when (key) {
            ScreenDataKey.Devices -> account.loadDevices()
            ScreenDataKey.Blacklist -> account.loadBlacklist()
            ScreenDataKey.FriendApplies -> account.loadFriendApplies()
            is ScreenDataKey.GroupDetail -> groups.loadDetail(key.chatId)
            is ScreenDataKey.UserProfile -> account.loadProfile(key.uid)
            is ScreenDataKey.InviteLinks -> groups.loadInviteLinks(key.chatId)
            is ScreenDataKey.GroupFiles -> groupFiles.open(key.chatId)
            is ScreenDataKey.GroupBots -> groups.loadGroupBots(key.chatId)
            ScreenDataKey.Documents -> documents.open()
        }
    }

    fun saveDraft(chatId: String, draft: String?) {
        val normalized = draft?.takeIf { it.isNotBlank() }
        chatDraftLifecycle.publishIfOpen {
            // Main 只准入每个会话最新的命令。确切 session 的单一写者在 LocalCache
            // 退役之前提交它，然后调度现有的持久镜像路径。
            localMutations.setDraft(chatId, normalized) { failure ->
                reportLocalMutationFailure(failure, "保存草稿失败")
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

    private fun reportLocalMutationFailure(failure: Throwable, fallback: String) {
        if (failure is CancellationException) throw failure
        actionScope.launch { handleError(failure, fallback) }
    }

    private fun handleError(throwable: Throwable, fallbackMessage: String) {
        if (throwable is CancellationException) throw throwable
        destroyGate.runIfOpen {
            when (throwable) {
                is AppError.AuthExpired -> {
                    uiErrors.publish("认证失效，请重新登录")
                    onAuthExpired()
                }

                is AppError.FatalCodec -> {
                    uiErrors.publish("⚠️ 数据协议错误，请联系开发者：${throwable.message}")
                }

                is AppError -> uiErrors.publish(throwable.message ?: fallbackMessage)
                else -> uiErrors.publish(fallbackMessage)
            }
        }
    }

    private fun handleFeedback(feedbackCode: UserFeedbackCode) {
        destroyGate.runIfOpen {
            uiNotices.publish(
                UserFeedbackNotice(
                    feedbackCode = feedbackCode,
                    page = ClientUiPage.GROUP_FILES,
                    action = ClientUiAction.PUBLISH_GROUP_FILE,
                    origin = FeedbackOrigin.SNACKBAR,
                ),
            )
        }
    }
}

/** Compose 可观察的唤醒令牌。即使两个载荷相等，修订号也会变化。 */
data class UiEventSignal(
    val revision: Long,
    val eventId: Long,
)

/** 平台宿主在完成或被销毁时传回的不透明预留。 */
class UiEventLease<T : Any> internal constructor(
    internal val eventId: Long,
    internal val leaseId: Long,
    internal val owner: Any,
    val value: T,
)

/**
 * 带单一宿主租约的 session 本地 FIFO。
 *
 * Snackbar 宿主只有在呈现完成之后才移除事件。如果它的 Window/Activity 在挂起时被销毁，
 * 释放租约会让 [signal] 变化，这样另一个可见宿主可以继续同一个事件。
 * 这使交接保持轻量，同时避免一个应用级的事件框架。
 */
internal class UiEventMailbox<T : Any> {
    private val lock = Any()
    private val queued = ArrayDeque<QueuedEvent<T>>()
    private var revision by mutableStateOf(0L)
    private var nextEventId = 1L
    private var nextLeaseId = 1L

    val signal: UiEventSignal?
        get() = synchronized(lock) {
            val observedRevision = revision
            queued.firstOrNull()?.let { UiEventSignal(observedRevision, it.eventId) }
        }

    fun publish(value: T) = synchronized(lock) {
        queued.addLast(QueuedEvent(nextEventId++, value))
        revision += 1L
    }

    fun acquire(owner: Any): UiEventLease<T>? = synchronized(lock) {
        val event = queued.firstOrNull() ?: return@synchronized null
        if (event.owner != null) return@synchronized null
        val leaseId = nextLeaseId++
        event.owner = owner
        event.leaseId = leaseId
        revision += 1L
        UiEventLease(event.eventId, leaseId, owner, event.value)
    }

    fun markDisplayed(lease: UiEventLease<T>): Boolean = synchronized(lock) {
        val event = matchingLeasedHead(lease) ?: return@synchronized false
        if (event.displayed) return@synchronized false
        event.displayed = true
        true
    }

    fun complete(lease: UiEventLease<T>): Boolean = synchronized(lock) {
        matchingLeasedHead(lease) ?: return@synchronized false
        queued.removeFirst()
        revision += 1L
        true
    }

    fun release(lease: UiEventLease<T>): Boolean = synchronized(lock) {
        val event = matchingLeasedHead(lease) ?: return@synchronized false
        event.owner = null
        event.leaseId = null
        revision += 1L
        true
    }

    private fun matchingLeasedHead(lease: UiEventLease<T>): QueuedEvent<T>? {
        val event = queued.firstOrNull() ?: return null
        return event.takeIf {
            it.eventId == lease.eventId && it.leaseId == lease.leaseId && it.owner === lease.owner
        }
    }

    private data class QueuedEvent<T : Any>(
        val eventId: Long,
        val value: T,
        var owner: Any? = null,
        var leaseId: Long? = null,
        var displayed: Boolean = false,
    )
}

/**
 * 显式退役和之后的 Compose 销毁共享的一次性完成 gate。
 *
 * 共享源监视器在整个最大努力式清理期间保持持有。因此另一个线程在观察到 CLOSED 之前
 * 会自然地加入领导者；同一线程的可重入 destroy 会看到 CLOSING 并直接无操作，
 * 而不是使自己死锁。
 */
internal class AppDataStateDestroyGate {
    private val lock = Any()
    private var phase = AppDataStateDestroyPhase.OPEN
    private var terminalFailures = emptyList<Pair<String, Throwable>>()
    private var terminalFatalFailure: Throwable? = null

    val acceptsWork: Boolean get() = synchronized(lock) {
        phase == AppDataStateDestroyPhase.OPEN
    }

    /**
     * 把退役挡在同步平台读取之外，并在退役获胜之后返回 null。
     * 块刻意与 [destroy] 处于同一个监视器之下：在退役之前被准入的缓存读取先完成，
     * 而每一个迟到的渲染器在不触碰已经静默的 ClientSession 的情况下观察到终止 gate。
     */
    fun <T> readIfOpen(block: () -> T): T? = synchronized(lock) {
        if (phase != AppDataStateDestroyPhase.OPEN) return@synchronized null
        block()
    }

    fun runIfOpen(block: () -> Unit): Boolean = synchronized(lock) {
        if (phase != AppDataStateDestroyPhase.OPEN) return@synchronized false
        block()
        true
    }

    fun destroy(cleanup: () -> List<Pair<String, Throwable>>): AppDataStateDestroyCompletion = synchronized(lock) {
        when (phase) {
            AppDataStateDestroyPhase.CLOSED -> {
                terminalFatalFailure?.let { throw it }
                return@synchronized AppDataStateDestroyCompletion(
                    completedNow = false,
                    failures = terminalFailures,
                )
            }
            AppDataStateDestroyPhase.CLOSING -> return@synchronized AppDataStateDestroyCompletion(
                completedNow = false,
                failures = terminalFailures,
            )
            AppDataStateDestroyPhase.OPEN -> phase = AppDataStateDestroyPhase.CLOSING
        }

        terminalFailures = try {
            cleanup()
        } catch (failure: Throwable) {
            listOf("destroy boundary" to failure)
        } finally {
            phase = AppDataStateDestroyPhase.CLOSED
        }
        terminalFatalFailure = fatalDestroyFailure(terminalFailures)
        terminalFatalFailure?.let { throw it }
        AppDataStateDestroyCompletion(
            completedNow = true,
            failures = terminalFailures,
        )
    }

    fun recordTerminalFatal(
        failure: Throwable,
        observedFailures: List<Pair<String, Throwable>>,
    ): Throwable = synchronized(lock) {
        terminalFatalFailure?.let { return@synchronized it }
        val causes = observedFailures.map { it.second } + failure
        check(isFatalClientLifecycleFailure(failure)) { "Only fatal destroy diagnostics are replayable" }
        checkNotNull(collapseClientLifecycleFailures(causes)).also { terminalFatalFailure = it }
    }

    private fun fatalDestroyFailure(failures: List<Pair<String, Throwable>>): Throwable? {
        val causes = failures.map { it.second }
        if (causes.none(::isFatalClientLifecycleFailure)) return null
        return collapseClientLifecycleFailures(causes)
    }
}

internal data class AppDataStateDestroyCompletion(
    val completedNow: Boolean,
    val failures: List<Pair<String, Throwable>>,
)

private enum class AppDataStateDestroyPhase { OPEN, CLOSING, CLOSED }

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

sealed class ScreenDataKey {
    data object Devices : ScreenDataKey()
    data object Blacklist : ScreenDataKey()
    data object FriendApplies : ScreenDataKey()
    data class GroupDetail(val chatId: String) : ScreenDataKey()
    data class UserProfile(val uid: String) : ScreenDataKey()
    data class InviteLinks(val chatId: String) : ScreenDataKey()
    data class GroupFiles(val chatId: String) : ScreenDataKey()
    data class GroupBots(val chatId: String) : ScreenDataKey()
    data object Documents : ScreenDataKey()
}
