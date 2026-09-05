package com.virjar.tk.app.viewmodel

import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.shared.client.EventProcessor
import com.virjar.tk.shared.client.LocalCache
import com.virjar.tk.shared.client.MessagePager
import com.virjar.tk.shared.client.MessagePageLoadResult
import com.virjar.tk.shared.client.OutgoingFailureCode
import com.virjar.tk.shared.client.SessionLocalMutationWriter
import com.virjar.tk.app.client.collapseClientLifecycleFailures
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.User
import com.virjar.tk.app.navigation.UiLocalDataBoundary
import com.virjar.tk.shared.repository.MessageRepository
import com.virjar.tk.app.telemetry.ClientFaultCode
import com.virjar.tk.app.telemetry.ClientFaultReason
import com.virjar.tk.app.telemetry.ClientUiAction
import com.virjar.tk.app.telemetry.ClientUiPage
import com.virjar.tk.app.telemetry.ClientUiTelemetrySink
import com.virjar.tk.app.telemetry.FeedbackOrigin
import com.virjar.tk.app.telemetry.NoopClientUiTelemetrySink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 带一个有界 [LocalCache.pager] 租约和显式退役的 chat 状态 owner。 */
class ChatViewModel(
    private val chatId: String,
    private val localCache: LocalCache,
    private val messageRepo: MessageRepository,
    eventProcessor: EventProcessor,
    typingEvents: Flow<Pair<String, String>> = eventProcessor.typingEvents,
    private val connectionState: StateFlow<ConnectionState>,
    private val myUid: String = "",
    /** 每一个 UI 发起的本地修改的确切 session 非阻塞准入。 */
    private val localMutations: SessionLocalMutationWriter,
    /** 最大努力式的确切 session TYPING 准入；拒绝刻意保持静默。 */
    private val trySendTyping: (chatId: String) -> Boolean = { false },
    private val monotonicNowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val localData: UiLocalDataBoundary = UiLocalDataBoundary(),
    private val telemetry: ClientUiTelemetrySink = NoopClientUiTelemetrySink,
    dispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
    onAuthExpired: () -> Unit = {},
) : BaseViewModel(dispatcher, onAuthExpired) {

    // Pager 创建会加载 SQLite。内存 owner 让构造可以在 Main 之外发生，
    // 而不会把刚获取的租约丢给取消，或在 ViewModel 退役之后发布它。
    private val pagerOwner = AsyncMessagePagerOwner()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    /** 当前存在于驻留窗口中的失败乐观行的安全、稳定原因。 */
    private val _outgoingFailureCodes = MutableStateFlow<Map<String, OutgoingFailureCode>>(emptyMap())
    val outgoingFailureCodes: StateFlow<Map<String, OutgoingFailureCode>> = _outgoingFailureCodes.asStateFlow()
    private val failureCodeProbeLock = Any()
    private val pendingFailureCodeProbes = mutableSetOf<String>()

    private val senderProjections = ChatSenderProjections(scope) { uid ->
        localData.projection { localCache.observeUser(uid) }
    }
    val senderUsers: StateFlow<Map<String, User>> = senderProjections.users

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _loadingOlder = MutableStateFlow(false)
    val loadingOlder: StateFlow<Boolean> = _loadingOlder.asStateFlow()

    private val _messageFocusState = MutableStateFlow<MessageFocusState>(MessageFocusState.Idle)
    val messageFocusState: StateFlow<MessageFocusState> = _messageFocusState.asStateFlow()
    private val messageFocusGate = MessageFocusGenerationGate()
    private var messageFocusJob: Job? = null

    /** 最新、更旧和随机访问历史共享一条已提交的请求车道。 */
    private val historyRequestMutex = Mutex()
    private val latestHistoryChainReady = MutableStateFlow(false)
    /** 由 [historyRequestMutex] 守护；这是驻留快捷方式背后的有界权威。 */
    private var latestHistoryPage = emptyList<Message>()

    private val _localHasMore = MutableStateFlow(false)
    private val _remoteHasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = combine(_localHasMore, _remoteHasMore) { local, remote ->
        local || remote
    }.stateIn(scope, SharingStarted.Eagerly, true)

    private val typing = ChatTypingPresentationOwner(
        chatId, myUid, connectionState, typingEvents, scope, trySendTyping, monotonicNowMillis,
    )
    val typingUid: StateFlow<String?> = typing.typingUid
    private val reactionOwner =
        ChatReactionPresentationOwner(chatId, localCache, messageRepo, connectionState, myUid, localData, scope, ::setError, ::handleAuthExpired)
    val reactions = reactionOwner.reactions // 回应聚合：seq -> emoji 分组；断网保留 stale 展示
    private val optimisticEdits = OptimisticMessageEditRetirement(
        reserve = localCache::reserveOptimisticMessageEdit,
        publish = localCache::publishOptimisticMessageEdit,
        commitLease = localCache::commitOptimisticMessageEdit,
        rollbackLease = localCache::rollbackOptimisticMessageEdit,
    )
    private val actionTelemetry = ChatActionTelemetryTracker(telemetry)

    init {
        // 完全在 local-data dispatcher 上获取 SQLite 支撑的 pager 和它的初始快照。
        // 如果 destroy 赢得发布竞争，acquireOwned 会在那里关闭租约。
        scope.launch {
            try {
                localData.acquireOwned(
                    acquire = { localCache.pager(chatId) },
                    install = pagerOwner::install,
                    release = MessagePager::close,
                )
                val pager = pagerOwner.current() ?: return@launch
                if (!pagerOwner.beginCollection(pager)) return@launch
                try {
                    coroutineScope {
                        launch {
                            pager.messages.collect { projectedMessages ->
                                var terminalCandidates = emptyList<String>()
                                var failureCodeCandidates = emptyList<String>()
                                pagerOwner.publishMessagesIfOpen(pager) {
                                    _messages.value = projectedMessages
                                    typing.onMessagesChanged(projectedMessages)
                                    senderProjections.bind(projectedMessages)
                                    failureCodeCandidates = bindOutgoingFailureCodes(projectedMessages)
                                    terminalCandidates =
                                        actionTelemetry.terminalSendCandidates(projectedMessages)
                                }
                                (terminalCandidates + failureCodeCandidates)
                                    .distinct()
                                    .forEach(::probeSendTerminal)
                            }
                        }
                        launch {
                            pager.hasMore.collect { hasMore ->
                                pagerOwner.publishIfOpen(pager) { _localHasMore.value = hasMore }
                            }
                        }
                    }
                } finally {
                    pagerOwner.finishCollection(pager)?.let(localMutations::closePager)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (pagerOwner.acceptsResource) {
                    setError("加载本地消息失败: ${failure.message}")
                }
            }
        }

        // 当持久会话投影达到它请求的水位线时，一次读取动作就成功了。
        // 本地路径原子地推进投影 + outbox；一个已经收敛的权威投影
        // 满足同样的幂等用户可见效果。
        scope.launch {
            try {
                localData.projection { localCache.observeConversations() }.collect { conversations ->
                    conversations.firstOrNull { it.chatId == chatId }
                        ?.let { actionTelemetry.observeReadSeq(it.readSeq) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                com.virjar.tk.shared.log.AppLog.trace(
                    "ChatViewModel",
                    "read telemetry projection unavailable: ${failure::class.simpleName}",
                )
            }
        }

        reactionOwner.start()

        // 本地窗口始终可渲染；认证边界只同步历史，不在后台消费红点。
        scope.launch {
            var firstConnectionEmission = true
            connectionState.collectLatest { state ->
                val coalesceWithEstablishedInitialChain = firstConnectionEmission
                firstConnectionEmission = false
                if (state == ConnectionState.AUTHENTICATED) {
                    val failedFocus = _messageFocusState.value as? MessageFocusState.Failed
                    if (
                        failedFocus?.reason != MessageFocusFailure.NETWORK ||
                        !retryMessageFocus(failedFocus.target, failedFocus.generation)
                    ) {
                        refreshLatestHistory(
                            coalesceWithEstablishedInitialChain = coalesceWithEstablishedInitialChain,
                        )
                    }
                } else {
                    typing.onConnectionStateChanged(state)
                    // 一次同步重置会使 LocalCache 的历史租约链失效。认证必须
                    // 在任何有界随机访问页之前重新建立 newest。
                    latestHistoryChainReady.value = false
                }
            }
        }

    }

    fun onUserTextChanged(chatForegroundActive: Boolean) = typing.onUserTextChanged(chatForegroundActive)
    fun onPresentationActiveChanged(active: Boolean) = typing.onPresentationActiveChanged(active)

    private suspend fun refreshLatestHistory(
        coalesceWithEstablishedInitialChain: Boolean = false,
    ) {
        // 在驻留 pager 存在之前请求的历史租约刻意是非驻留的，
        // 不能锚定随后的非零目标页。先绑定窗口。
        pagerOwner.awaitReady()
        historyRequestMutex.withLock {
            // 一个已解析的随机访问页被钉住，直到 Compose 确认确切的行走位完成
            // （或其有界等待失败）。这里重置到 newest 可能驱逐那一行，
            // 让 focus 和普通分页互相等待。
            if (
                _messageFocusState.value is MessageFocusState.Loading ||
                _messageFocusState.value is MessageFocusState.Resolved
            ) return@withLock
            // 首帧搜索 focus 可能赢得这条车道，并在连接收集器的初始发射恢复之前
            // 建立同样的认证 newest 链。只折叠那一次初始 bootstrap 请求。
            // 显式刷新和之后的认证边界即使已有先前的链，仍然必须获取。
            if (coalesceWithEstablishedInitialChain && latestHistoryChainReady.value) return@withLock
            try {
                _loading.value = true
                fetchLatestHistoryLocked()
            } catch (e: AppError.AuthExpired) {
                handleAuthExpired()
            } catch (_: AppError.Network) {
                // 驻留 pager 就是离线 UI。连接状态已经拥有全局横幅，
                // 因此冷启动刷新 miss 绝不能添加一条重复的错误 snackbar。
                com.virjar.tk.shared.log.AppLog.trace("ChatViewModel", "latest history refresh deferred offline")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                setError("加载消息失败: ${e.message}")
            } finally {
                _loading.value = false
            }
        }
    }

    /** 调用方拥有 [historyRequestMutex]。成功响应提交缓存的 newest 链。 */
    private suspend fun fetchLatestHistoryLocked(): List<Message> {
        val latest = localData.run {
            messageRepo.getHistory(chatId, fromSeq = 0, limit = HISTORY_PAGE_SIZE).getOrThrow()
        }
        latestHistoryPage = latest
        latestHistoryChainReady.value = true
        _remoteHasMore.value = latest.size == HISTORY_PAGE_SIZE
        return latest
    }

    /** 用 newest 加上至多一个有界包含式目标页来解析一个搜索身份。 */
    fun focusMessage(target: MessageFocusTarget): Long {
        require(target.chatId == chatId) { "message focus target belongs to another chat" }
        val token = messageFocusGate.begin(target)
        launchMessageFocus(token)
        return token.generation
    }

    private fun retryMessageFocus(target: MessageFocusTarget, generation: Long): Boolean {
        val token = messageFocusGate.currentToken(target, generation) ?: return false
        launchMessageFocus(token)
        return true
    }

    private fun launchMessageFocus(token: MessageFocusGenerationGate.Token) {
        val target = token.target
        messageFocusJob?.cancel()
        _messageFocusState.value = MessageFocusState.Loading(target, token.generation)
        messageFocusJob = scope.launch {
            try {
                pagerOwner.awaitReady()
                val evidence = historyRequestMutex.withLock {
                    if (connectionState.value != ConnectionState.AUTHENTICATED) {
                        _messages.value.firstOrNull { message ->
                            message.chatId == target.chatId && message.serverSeq == target.serverSeq
                        } ?: throw AppError.Network
                    } else {
                        if (!latestHistoryChainReady.value) fetchLatestHistoryLocked()
                        latestHistoryPage.firstOrNull { message -> message.serverSeq == target.serverSeq }
                            ?: localData.run {
                                messageRepo.getHistory(
                                    chatId = chatId,
                                    fromSeq = messageFocusHistoryFromSeq(target.serverSeq),
                                    limit = HISTORY_PAGE_SIZE,
                                ).getOrThrow()
                            }.firstOrNull { message ->
                                message.chatId == target.chatId && message.serverSeq == target.serverSeq
                            }
                    }
                }
                val resolved = evidence?.let { message ->
                    localData.run { localCache.findMessage(message.chatId, message.clientMsgId) }
                }?.takeIf { message ->
                    message.chatId == target.chatId && message.serverSeq == target.serverSeq
                }
                if (resolved == null) {
                    failMessageFocus(token, MessageFocusFailure.UNAVAILABLE)
                } else {
                    publishResolvedMessageFocus(token, resolved)
                }
            } catch (cancelled: CancellationException) {
                if (currentCoroutineContext().isActive && messageFocusGate.isCurrent(token)) {
                    // LocalCache 重置/取代在下一次认证边界可恢复。
                    failMessageFocus(token, MessageFocusFailure.NETWORK)
                } else {
                    throw cancelled
                }
            } catch (_: AppError.AuthExpired) {
                failMessageFocus(token, MessageFocusFailure.AUTH_EXPIRED)
                if (messageFocusGate.isCurrent(token)) handleAuthExpired()
            } catch (_: AppError.Network) {
                failMessageFocus(token, MessageFocusFailure.NETWORK)
            } catch (_: Exception) {
                // 失去成员资格、被删除的目标和畸形/不存在的身份共享同一个
                // 产品结果。不要通过搜索导航暴露服务器区分。
                failMessageFocus(token, MessageFocusFailure.UNAVAILABLE)
            }
        }
    }

    /** 无条件清除导航意图；在可见 route 没有目标时使用。 */
    fun clearMessageFocus() {
        messageFocusGate.invalidate()
        messageFocusJob?.cancel()
        messageFocusJob = null
        _messageFocusState.value = MessageFocusState.Idle
    }

    /** 只清除一个组合拥有的意图；更新的重复点击保持完整。 */
    fun clearMessageFocus(target: MessageFocusTarget, generation: Long) {
        if (!messageFocusGate.isCurrent(target, generation)) return
        clearMessageFocus()
    }

    fun markMessageFocusPositioned(target: MessageFocusTarget, generation: Long) {
        if (!messageFocusGate.isCurrent(target, generation)) return
        val current = _messageFocusState.value as? MessageFocusState.Resolved ?: return
        if (current.target != target || current.generation != generation) return
        _messageFocusState.value = MessageFocusState.Positioned(
            target = target,
            generation = generation,
            revoked = current.revoked,
        )
    }

    fun markMessageFocusPositionUnavailable(target: MessageFocusTarget, generation: Long) {
        if (!messageFocusGate.isCurrent(target, generation)) return
        val current = _messageFocusState.value as? MessageFocusState.Resolved ?: return
        if (current.target != target || current.generation != generation) return
        _messageFocusState.value = MessageFocusState.Failed(
            target = target,
            generation = generation,
            reason = MessageFocusFailure.POSITION_TIMEOUT,
        )
        setError(MessageFocusFailure.POSITION_TIMEOUT.userMessage())
    }

    private fun publishResolvedMessageFocus(
        token: MessageFocusGenerationGate.Token,
        message: Message,
    ) {
        if (!messageFocusGate.isCurrent(token)) return
        val revoked = message.flags and Message.FLAG_REVOKED != 0
        _messageFocusState.value = MessageFocusState.Resolved(
            target = token.target,
            generation = token.generation,
            revoked = revoked,
        )
        if (revoked) setError("原消息已撤回")
    }

    private fun failMessageFocus(
        token: MessageFocusGenerationGate.Token,
        reason: MessageFocusFailure,
    ) {
        if (!messageFocusGate.isCurrent(token)) return
        _messageFocusState.value = MessageFocusState.Failed(
            target = token.target,
            generation = token.generation,
            reason = reason,
        )
        setError(reason.userMessage())
    }

    /**
     * 加载下一更旧页。本地持久化行先被消费；一旦耗尽，就在当前可见的
     * 最旧已确认序列之前立即请求服务器页。
     */
    fun loadOlder() {
        if (
            _loading.value ||
            _loadingOlder.value ||
            !hasMore.value ||
            _messageFocusState.value is MessageFocusState.Loading ||
            _messageFocusState.value is MessageFocusState.Resolved
        ) return
        val pager = pagerOwner.current() ?: return
        scope.launch {
            _loadingOlder.value = true
            try {
                var remoteCursor: Long? = null
                if (pager.hasMore.value) {
                    when (val localResult = localData.run { pager.loadMore(HISTORY_PAGE_SIZE) }) {
                        MessagePageLoadResult.LocalLoaded -> return@launch
                        MessagePageLoadResult.Exhausted -> Unit
                        is MessagePageLoadResult.RemoteRequired -> {
                            remoteCursor = localResult.beforeServerSeq
                            // 这个请求修复从有界权威窗口刻意裁剪掉的历史，
                            // 即使先前的 RPC 已经到达末尾。
                            _remoteHasMore.value = true
                        }
                    }
                }
                if (!_remoteHasMore.value) return@launch
                if (connectionState.value != ConnectionState.AUTHENTICATED) {
                    // 本地翻页已保留；服务端游标保持开放，用户可在恢复后继续上滑。
                    return@launch
                }
                // MessageWindow 原子地把可见列表锚定到最新的服务器页，因此
                // 它的最小值就是那条已证明响应链的底。同步前的过期本地尾部
                // 刻意不出现在这里；页内的合法序列空洞会出现。
                val oldestSeq = remoteCursor ?: _messages.value.asSequence()
                    .map(Message::serverSeq)
                    .filter { it > 0L }
                    .minOrNull()
                    ?: 0L
                if (oldestSeq <= 1L) {
                    _remoteHasMore.value = false
                    return@launch
                }
                val older = historyRequestMutex.withLock {
                    localData.run {
                        messageRepo.getHistory(
                            chatId = chatId,
                            // RocksDB 的后向游标是包含式的；向左步进一次，
                            // 这样边界消息就不会在每一页都返回。
                            fromSeq = oldestSeq - 1L,
                            limit = HISTORY_PAGE_SIZE,
                        ).getOrThrow()
                    }
                }
                _remoteHasMore.value = older.size == HISTORY_PAGE_SIZE
                // MessageRepository 把权威响应作为一个原子页应用到 MessageWindow，
                // 包括合法空洞，因此不需要二次 SQLite 追加。
            } catch (e: AppError.AuthExpired) {
                handleAuthExpired()
            } catch (_: AppError.Network) {
                // 为之后的认证尝试保持远程游标开放；上面已经加载的
                // 本地持久化历史保持完全可用。
                com.virjar.tk.shared.log.AppLog.trace("ChatViewModel", "older history refresh deferred offline")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                setError("加载更早消息失败: ${e.message}")
            } finally {
                _loadingOlder.value = false
            }
        }
    }

    /**
     * 媒体上传占位：先本地插入 UPLOADING 消息（气泡立即渲染上传动画），
     * 上传完成后调 [sendMessage] 以同 clientMsgId 覆盖（upsert）为真实消息。
     */
    fun insertUploadingPlaceholder(message: Message) {
        localMutations.insertUploadingPlaceholder(message) { failure ->
            setError("创建上传任务失败: ${failure.message ?: "本地缓存不可用"}")
        }
    }

    /** 更新上传进度（驱动气泡进度动画；纯 UI 状态不落库）。 */
    fun updateUploadProgress(chatId: String, clientMsgId: String, progress: Float) {
        localMutations.updateUploadProgress(chatId, clientMsgId, progress)
    }

    fun sendMessage(message: Message) {
        val sending = message.copy(sendStatus = Message.SEND_STATUS_SENDING)
        actionTelemetry.startSend(sending.clientMsgId)
        // 持久队列原子地创建乐观投影和 outbox 事实。
        // 一次单独的预插入会在进程死于那次插入与队列准入之间时，
        // 留下一条不可发送的 SENDING 气泡。
        val admitted = localMutations.enqueueOutgoing(sending) { failure ->
            actionTelemetry.failSend(sending.clientMsgId)
            // 媒体可能已经拥有一个 UPLOADING 占位。确切 session 写者只把
            // 它 seq=0 的乐观行标记为失败；同 id 的服务器消息绝不被触碰。
            localMutations.markMessageFailed(sending.chatId, sending.clientMsgId)
            setError("发送失败: ${failure.message ?: "本地队列不可用"}")
        }
        // 生产写者在返回 false 之前通知拒绝。对于任何只报告布尔结果的
        // 合规测试/平台适配器，保持动作终止性健壮。
        if (!admitted) actionTelemetry.failSend(sending.clientMsgId)
    }

    private fun probeSendTerminal(clientMsgId: String) {
        scope.launch {
            try {
                val (receipt, failureCode) = localData.run {
                    localCache.getOutgoingMessage(chatId, clientMsgId) to
                        localCache.findOutgoingFailureCode(chatId, clientMsgId)
                }
                actionTelemetry.completeSendProbe(clientMsgId, receipt?.state)
                failureCode?.let {
                    val stillFailed = _messages.value.any { message ->
                        message.clientMsgId == clientMsgId &&
                            message.serverSeq == 0L &&
                            message.sendStatus == Message.SEND_STATUS_FAILED
                    }
                    if (stillFailed) {
                        _outgoingFailureCodes.update { it + (clientMsgId to failureCode) }
                    }
                }
            } catch (cancelled: CancellationException) {
                actionTelemetry.completeSendProbe(clientMsgId, state = null)
                throw cancelled
            } catch (failure: Exception) {
                actionTelemetry.completeSendProbe(clientMsgId, state = null)
                com.virjar.tk.shared.log.AppLog.trace(
                    "ChatViewModel",
                    "send telemetry receipt unavailable: ${failure::class.simpleName}",
                )
            } finally {
                synchronized(failureCodeProbeLock) {
                    pendingFailureCodeProbes.remove(clientMsgId)
                }
            }
        }
    }

    /** 把原因投影限定在失败行，并至多调度一次回执读取/key。 */
    private fun bindOutgoingFailureCodes(projectedMessages: List<Message>): List<String> {
        val failedIds = projectedMessages.asSequence()
            .filter { message ->
                message.serverSeq == 0L && message.sendStatus == Message.SEND_STATUS_FAILED
            }
            .map(Message::clientMsgId)
            .toSet()
        _outgoingFailureCodes.update { reasons -> reasons.filterKeys(failedIds::contains) }
        val known = _outgoingFailureCodes.value.keys
        return synchronized(failureCodeProbeLock) {
            (failedIds - known - pendingFailureCodeProbes).toList().also {
                pendingFailureCodeProbes += it
            }
        }
    }

    /** 显式移除一个终止性本地失败；权威行总是 fail closed。 */
    fun discardFailedMessage(clientMsgId: String, onResult: (Boolean) -> Unit = {}) {
        val admitted = localMutations.discardTerminalFailure(
            chatId = chatId,
            clientMsgId = clientMsgId,
            onResult = { discarded ->
                if (!discarded) setError("该失败消息已更新，无法丢弃")
                onResult(discarded)
            },
            onFailure = {
                setError("丢弃失败：本地恢复队列不可用")
                onResult(false)
            },
        )
        if (!admitted) {
            com.virjar.tk.shared.log.AppLog.trace("ChatViewModel", "failed-message discard was not admitted")
        }
    }

    /** 原子地把一个终止性失败替换为一个新的持久 outbox 身份。 */
    fun replaceFailedMessage(
        failedClientMsgId: String,
        replacement: Message,
        onResult: (Boolean) -> Unit = {},
    ) {
        actionTelemetry.startSend(replacement.clientMsgId)
        val admitted = localMutations.replaceTerminalFailure(
            chatId = chatId,
            clientMsgId = failedClientMsgId,
            replacement = replacement,
            onResult = { receipt ->
                val replaced = receipt != null
                if (!replaced) {
                    actionTelemetry.failSend(replacement.clientMsgId)
                    setError("该失败消息不可安全重发，或已被更新")
                }
                onResult(replaced)
            },
            onFailure = {
                actionTelemetry.failSend(replacement.clientMsgId)
                setError("重发失败：本地恢复队列不可用")
                onResult(false)
            },
        )
        if (!admitted) actionTelemetry.failSend(replacement.clientMsgId)
    }

    /**
     * 供平台会话媒体服务调用，设置错误提示。
     */
    fun onError(msg: String) = setError(msg)

    /** 上传失败的占位消息标记为 FAILED（气泡显示失败态）。 */
    fun markUploadFailed(chatId: String, clientMsgId: String) {
        localMutations.markMessageFailed(chatId, clientMsgId) { failure ->
            setError("更新上传状态失败: ${failure.message ?: "本地缓存不可用"}")
        }
    }

    /** Android/Desktop 渲染器使用的纯内存查询。 */
    fun residentSender(uid: String): User? = senderUsers.value[uid]

    /**
     * 标记已读。
     * @param seq 显式指定的 readSeq；null 时取当前消息列表的最新 seq。
     *
     * Main 只提交单调水位；会话单写者在后台原子推进本地投影与持久 outbox，随后
     * 唤醒镜像。退休会先封闭 admission，再 drain 已接收水位，因此最终已读不会丢失。
     */
    fun markRead(seq: Long? = null) {
        val readSeq = seq ?: _messages.value.maxOfOrNull(Message::serverSeq) ?: return
        if (readSeq <= 0L) return
        actionTelemetry.startMarkRead(readSeq)
        val admitted = localMutations.markRead(chatId, readSeq) { failure ->
            actionTelemetry.failMarkRead(readSeq)
            telemetry.recordFault(
                code = ClientFaultCode.MARK_READ_LOCAL_FAILURE,
                page = ClientUiPage.CHAT,
                action = ClientUiAction.MARK_READ,
                origin = FeedbackOrigin.SYSTEM,
                reason = ClientFaultReason.SQLITE,
            )
            com.virjar.tk.shared.log.AppLog.trace(
                "ChatViewModel",
                "markRead local projection unavailable: ${failure::class.simpleName}",
            )
        }
        if (!admitted) actionTelemetry.failMarkRead(readSeq)
    }

    fun refreshReactionsForWindow() = reactionOwner.refreshForWindow(_messages.value)
    fun toggleReaction(serverSeq: Long, emoji: String) = reactionOwner.toggle(serverSeq, emoji)
    fun pickReaction(serverSeq: Long, emoji: String) = reactionOwner.pick(serverSeq, emoji)
    fun revokeMessage(serverSeq: Long) {
        scope.launch {
            try {
                localData.run { messageRepo.revokeMessage(chatId, serverSeq).getOrThrow() }
            } catch (e: AppError.AuthExpired) {
                handleAuthExpired()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                setError("撤回失败: ${e.message}")
            }
        }
    }

    /**
     * 编辑一条已确认消息，同时保留每一个持久步骤的 worker/local-data 所有权。
     * [onResult] 从这个 ViewModel 的 worker scope 调用；UI 调用方在修改 Compose 状态之前
     * 必须把它交给它们呈现拥有的 dispatcher。
     */
    fun editMessage(message: Message, onResult: (Boolean) -> Unit = {}) {
        val optimistic = message.copy(flags = message.flags or Message.FLAG_EDITED)
        scope.launch {
            val edit = when (val admission = localData.run { optimisticEdits.begin(optimistic) }) {
                is OptimisticMessageEditAdmission.Started -> admission.token
                OptimisticMessageEditAdmission.Rejected -> {
                    setError("消息已更新或正在编辑，请重试")
                    onResult(false)
                    return@launch
                }
                OptimisticMessageEditAdmission.Retired -> return@launch
            }
            try {
                localData.run { messageRepo.editMessage(message).getOrThrow() }
                if (localData.run { optimisticEdits.commit(edit) }) onResult(true)
            } catch (e: AppError.AuthExpired) {
                if (localData.runCleanup { optimisticEdits.rollback(edit) }) {
                    handleAuthExpired()
                    onResult(false)
                }
            } catch (cancelled: CancellationException) {
                try {
                    localData.runCleanup { optimisticEdits.rollback(edit) }
                } catch (rollbackFailure: Throwable) {
                    if (rollbackFailure !== cancelled) cancelled.addSuppressed(rollbackFailure)
                }
                throw cancelled
            } catch (e: Exception) {
                if (localData.runCleanup { optimisticEdits.rollback(edit) }) {
                    setError("编辑失败: ${e.message}")
                    onResult(false)
                }
            }
        }
    }

    /** 终止观察并精确释放本 ViewModel 的消息窗口租约。 */
    override fun destroy() {
        val failures = mutableListOf<Throwable>()
        val rollbackLeases = optimisticEdits.detachForRetirement()
        clearMessageFocus()
        pagerOwner.retire()
        listOf<() -> Unit>(
            typing::close,
            senderProjections::close,
            {
                _outgoingFailureCodes.value = emptyMap()
                synchronized(failureCodeProbeLock) { pendingFailureCodeProbes.clear() }
            },
            { super.destroy() },
            {
                rollbackLeases.forEach { lease ->
                    localMutations.rollbackOptimisticEdit(lease)
                }
            },
            { reactionOwner.close() },
            { pagerOwner.allowClose()?.let(localMutations::closePager) },
        ).forEach { release ->
            try {
                release()
            } catch (failure: Throwable) {
                failures += failure
            }
        }
        collapseClientLifecycleFailures(failures)?.let { throw it }
    }

    private companion object {
        const val HISTORY_PAGE_SIZE = 10
    }
}
