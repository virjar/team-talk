package com.virjar.tk.app.viewmodel

import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.shared.client.LocalCache
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.MessageReactionGroup
import com.virjar.tk.app.navigation.UiLocalDataBoundary
import com.virjar.tk.shared.repository.MessageRepository
import com.virjar.tk.shared.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 聊天页表情回应（CLIENT-05）的展示与动作 owner。
 *
 * 状态来自 [LocalCache.observeMessageReactions] 的行级服务端投影；增删走 RPC-first，
 * 收敛依赖服务端事件回环（含本端多设备）。权威快照在驻留窗口下界推进与重新认证后按需补齐。
 */
internal class ChatReactionPresentationOwner(
    private val chatId: String,
    private val localCache: LocalCache,
    private val messageRepo: MessageRepository,
    private val connectionState: StateFlow<ConnectionState>,
    private val myUid: String,
    private val localData: UiLocalDataBoundary,
    private val scope: CoroutineScope,
    private val setError: (String) -> Unit,
    private val handleAuthExpired: () -> Unit,
) {
    private val _reactions = MutableStateFlow<Map<Long, List<MessageReactionGroup>>>(emptyMap())
    val reactions: StateFlow<Map<Long, List<MessageReactionGroup>>> = _reactions

    /** 由 [convergenceMutex] 守护；已被权威快照覆盖的最低 seq。 */
    private var convergedFloor: Long = Long.MAX_VALUE
    private val convergenceMutex = Mutex()
    private var observer: Job? = null
    private var connectionObserver: Job? = null
    private var latestWindow: LongRange? = null

    /** 启动投影收集器；从拥有它的 ViewModel 的 init 调用一次是安全的。 */
    fun start() {
        observer = scope.launch {
            try {
                localData.projection { localCache.observeMessageReactions(chatId) }
                    .collect { projected -> _reactions.value = projected }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AppLog.trace("ChatReactionOwner", "reaction projection unavailable: ${failure::class.simpleName}")
            }
        }
        connectionObserver = scope.launch {
            connectionState.collect { state ->
                if (state == ConnectionState.AUTHENTICATED) {
                    // 本次同步可能安装了 checkpoint 并清空回应。保留的聊天页也要补当前窗口，
                    // 不能复用上次连接的 floor，把清空后的投影误当成已经收敛。
                    convergenceMutex.withLock { convergedFloor = Long.MAX_VALUE }
                    requestWindowConvergence()
                }
            }
        }
    }

    /**
     * 按当前驻留窗口收敛一次服务端权威回应快照。
     *
     * 实时与离线事件（MESSAGE_REACTION delta）覆盖登录后的全部变化；快照只补事件保留窗之外的
     * 历史消息。floor 去重限于本次连接；失败或被新 delta 取代时不推进 floor，后续窗口刷新或
     * 重新认证仍可重试。SDK 负责把完整区间的空结果与迟到快照正确收敛到本地投影。
     */
    fun refreshForWindow(messages: List<Message>) {
        val sequences = messages.asSequence().map(Message::serverSeq).filter { it > 0L }.toList()
        latestWindow = sequences.minOrNull()?.let { it..sequences.max() }
        requestWindowConvergence()
    }

    private fun requestWindowConvergence() {
        val window = latestWindow ?: return
        if (connectionState.value != ConnectionState.AUTHENTICATED) return
        scope.launch {
            convergenceMutex.withLock {
                if (window.first >= convergedFloor) return@withLock
                try {
                    localData.run { messageRepo.loadReactions(chatId, window.first, window.last).getOrThrow() }
                    convergedFloor = window.first
                } catch (_: AppError.Network) {
                    AppLog.trace("ChatReactionOwner", "reaction snapshot refresh deferred offline")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // 聚合快照是收敛优化；实时事件仍保持投影可用。
                }
            }
        }
    }

    private fun isMine(serverSeq: Long, emoji: String): Boolean =
        myUid.isNotEmpty() && _reactions.value[serverSeq]
            ?.any { group -> group.emoji == emoji && group.reactorUids.contains(myUid) } == true

    /** chips 与快捷栏的切换语义：已有则移除，没有则添加。 */
    fun toggle(serverSeq: Long, emoji: String) = mutate(serverSeq, emoji, removeIfMine = true)

    /** 完整选择器的添加语义：我已回应该 emoji 时无操作，避免选择器误删。 */
    fun pick(serverSeq: Long, emoji: String) = mutate(serverSeq, emoji, removeIfMine = false)

    private fun mutate(serverSeq: Long, emoji: String, removeIfMine: Boolean) {
        if (serverSeq <= 0L) return
        if (connectionState.value != ConnectionState.AUTHENTICATED) return
        val mine = isMine(serverSeq, emoji)
        if (mine && !removeIfMine) return
        scope.launch {
            try {
                localData.run {
                    if (mine) {
                        messageRepo.removeReaction(chatId, serverSeq, emoji)
                    } else {
                        messageRepo.addReaction(chatId, serverSeq, emoji)
                    }.getOrThrow()
                }
            } catch (e: AppError.AuthExpired) {
                handleAuthExpired()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                setError("回应失败: ${e.message}")
            }
        }
    }

    /** 调用方拥有 ViewModel 退役；取消收集器并丢弃投影。 */
    fun close() {
        observer?.cancel()
        observer = null
        connectionObserver?.cancel()
        connectionObserver = null
        latestWindow = null
        _reactions.value = emptyMap()
    }
}
