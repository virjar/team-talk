package com.virjar.tk.app.viewmodel

/** 在其 chat 内揭示一条权威消息的稳定导航身份。 */
data class MessageFocusTarget(
    val chatId: String,
    val serverSeq: Long,
) {
    init {
        require(chatId.isNotBlank()) { "message focus chatId must not be blank" }
        require(serverSeq > 0L) { "message focus serverSeq must be positive" }
    }
}

/** 搜索结果消息揭示的共享 Android/Desktop 状态机。 */
sealed interface MessageFocusState {
    data object Idle : MessageFocusState

    data class Loading(
        val target: MessageFocusTarget,
        val generation: Long,
    ) : MessageFocusState

    data class Resolved(
        val target: MessageFocusTarget,
        val generation: Long,
        val revoked: Boolean,
    ) : MessageFocusState

    data class Positioned(
        val target: MessageFocusTarget,
        val generation: Long,
        val revoked: Boolean,
    ) : MessageFocusState

    data class Failed(
        val target: MessageFocusTarget,
        val generation: Long,
        val reason: MessageFocusFailure,
    ) : MessageFocusState
}

enum class MessageFocusFailure {
    /** 缺失和未授权刻意共享一个不披露信息的同一种产品结果。 */
    UNAVAILABLE,
    NETWORK,
    AUTH_EXPIRED,
    POSITION_TIMEOUT,
}

internal fun MessageFocusFailure.userMessage(): String = when (this) {
    MessageFocusFailure.UNAVAILABLE -> "该消息不存在或已无法访问"
    MessageFocusFailure.NETWORK -> "网络不可用，暂时无法定位该消息"
    MessageFocusFailure.AUTH_EXPIRED -> "认证失效，请重新登录"
    MessageFocusFailure.POSITION_TIMEOUT -> "定位消息超时，请重试"
}

/**
 * 从目标之后的几个序列槽位开始有界的包含式历史窗口。最多四条消息可以占据那些槽位，
 * 因此一页十条的降序页在提供更旧上下文的同时必然包含现存的目标。
 * 序列空洞不影响该保证。
 */
internal fun messageFocusHistoryFromSeq(targetSeq: Long): Long =
    if (targetSeq > Long.MAX_VALUE - MESSAGE_FOCUS_NEWER_CONTEXT_SLOTS) {
        Long.MAX_VALUE
    } else {
        targetSeq + MESSAGE_FOCUS_NEWER_CONTEXT_SLOTS
    }

/** 线程安全的最近者胜出身份；被取消的工作在发布之前也必须通过这个围栏。 */
internal class MessageFocusGenerationGate {
    class Token internal constructor(
        val target: MessageFocusTarget,
        val generation: Long,
    )

    private val lock = Any()
    private var nextGeneration = 0L
    private var current: Token? = null

    fun begin(target: MessageFocusTarget): Token = synchronized(lock) {
        check(nextGeneration < Long.MAX_VALUE) { "message focus generation exhausted" }
        Token(target, ++nextGeneration).also { current = it }
    }

    fun isCurrent(token: Token): Boolean = synchronized(lock) { current == token }

    fun isCurrent(target: MessageFocusTarget, generation: Long): Boolean = synchronized(lock) {
        current?.target == target && current?.generation == generation
    }

    fun currentToken(target: MessageFocusTarget, generation: Long): Token? = synchronized(lock) {
        current?.takeIf { token -> token.target == target && token.generation == generation }
    }

    fun invalidate() = synchronized(lock) {
        current = null
    }
}

private const val MESSAGE_FOCUS_NEWER_CONTEXT_SLOTS = 4L
