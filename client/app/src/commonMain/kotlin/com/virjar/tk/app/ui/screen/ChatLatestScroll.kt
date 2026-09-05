package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import com.virjar.tk.protocol.model.Message
import kotlinx.coroutines.flow.first

/**
 * 为一次可见聊天组合持有最新消息锚点。
 *
 * 反向布局的消息列表以 index 0 作为最新边界。冷的本地优先 projection 在空首帧之后到达，
 * 而缓存的富内容可能让首次测量停在更旧的消息上。我们在对应 lazy-list 条目进入布局后，
 * 显式地把第一个非空 projection 锚定一次。之后的新消息插入只有在之前的 projection
 * 恰好已在该边界时才跟随 index 0。用户在翻阅历史时收到的旧页与插入绝不会把视口拉回底部。
 */
@Composable
internal fun ChatLatestScrollEffect(
    chatId: String,
    messages: List<Message>,
    messageListState: LazyListState,
    suppressInitialAnchor: Boolean = false,
    suppressLatestFollow: Boolean = false,
) {
    val policy = remember(chatId, messageListState) {
        ChatLatestScrollPolicy(initialAnchorSuppressed = suppressInitialAnchor)
    }
    // Desktop 把新的聊天身份及其可选搜索目标发布为两个可观察导航字段。因此冷目标可以先
    // 在没有目标的情况下组合一次。锁存 bootstrap 完成前到达的目标，并给 effect 设置 key，
    // 让它取消任何挂起的 index-0 布局等待，而不是与精确消息滚动竞争。
    if (suppressInitialAnchor) policy.suppressInitialAnchor()
    val hasMessages = messages.isNotEmpty()
    val latestClientMsgId = messages.firstOrNull()?.clientMsgId
    // 在组合期间读取布局前位置。有了稳定的条目 key，LazyColumn 在 index-0 插入后会保留旧的
    // 可见消息，并在下一次布局时把它移到 index 1；记住之前的 projection 是否在边界，
    // 我们就能跟随该插入。
    val latestFollowRequest = policy.observeProjection(
        latestClientMsgId = latestClientMsgId,
        isAtLatestEdge = messageListState.firstVisibleItemIndex == 0 &&
            messageListState.firstVisibleItemScrollOffset == 0 &&
            !messageListState.isScrollInProgress,
        suppressLatestFollow = suppressLatestFollow,
    )

    // 在整个非空阶段保持 key 稳定。如果首次 lazy 布局尚未完成时到达更新的本地/实时行，
    // 它绝不能取消拥有初始 index-0 定位的协程。
    LaunchedEffect(policy, hasMessages, suppressInitialAnchor) {
        if (policy.shouldBeginInitialAnchor(hasMessages)) {
            snapshotFlow { messageListState.layoutInfo.totalItemsCount }
                .first { totalItems -> totalItems > 0 }
            messageListState.scrollToItem(0)
            policy.completeInitialAnchor()
        }
    }

    LaunchedEffect(policy, latestFollowRequest, suppressLatestFollow) {
        val request = latestFollowRequest ?: return@LaunchedEffect
        if (suppressLatestFollow) return@LaunchedEffect
        snapshotFlow { messageListState.layoutInfo.totalItemsCount }
            .first { totalItems -> totalItems > 0 }
        messageListState.scrollToItem(0)
        policy.completeLatestFollow(request)
    }
}

/** 纯决策 owner，与 Compose 分离，使初始/实时/历史行为可测试。 */
internal class ChatLatestScrollPolicy(initialAnchorSuppressed: Boolean = false) {
    private var initialAnchorComplete = initialAnchorSuppressed
    private var latestClientMsgId: String? = null
    private var wasAtLatestEdge = false
    private var nextLatestFollowRequest = 0L
    private var pendingLatestFollowRequest: Long? = null

    fun shouldBeginInitialAnchor(hasMessages: Boolean): Boolean =
        hasMessages && !initialAnchorComplete

    fun completeInitialAnchor() {
        initialAnchorComplete = true
        wasAtLatestEdge = true
    }

    fun suppressInitialAnchor() {
        initialAnchorComplete = true
    }

    /**
     * 只观察最新的稳定身份。追加更旧的页或确认同一乐观行都不能请求滚动。边界决策属于
     * 之前的非空 projection，因此临时同步重置墓碑不能把历史阅读者变成跟随者。
     */
    fun observeProjection(
        latestClientMsgId: String?,
        isAtLatestEdge: Boolean,
        suppressLatestFollow: Boolean = false,
    ): Long? {
        if (suppressLatestFollow) pendingLatestFollowRequest = null
        if (latestClientMsgId == null) return pendingLatestFollowRequest
        val previous = this.latestClientMsgId
        if (previous == null) {
            this.latestClientMsgId = latestClientMsgId
            wasAtLatestEdge = isAtLatestEdge
            return pendingLatestFollowRequest
        }
        if (previous == latestClientMsgId) {
            if (pendingLatestFollowRequest == null) wasAtLatestEdge = isAtLatestEdge
            return pendingLatestFollowRequest
        }

        this.latestClientMsgId = latestClientMsgId
        val shouldFollow = initialAnchorComplete && !suppressLatestFollow && wasAtLatestEdge
        wasAtLatestEdge = isAtLatestEdge
        if (shouldFollow) {
            check(nextLatestFollowRequest < Long.MAX_VALUE) {
                "latest-message follow request exhausted"
            }
            pendingLatestFollowRequest = ++nextLatestFollowRequest
        }
        return pendingLatestFollowRequest
    }

    fun completeLatestFollow(request: Long) {
        if (pendingLatestFollowRequest != request) return
        pendingLatestFollowRequest = null
        wasAtLatestEdge = true
    }
}
