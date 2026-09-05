package com.virjar.tk.shared.client

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * 恢复可能在应用构造其功能 owner 之前完成。因此重放是有界完成台账；仅靠额外缓冲容量会在没有
 * 订阅者时丢弃每个发射。
 */
internal fun <T> reliableCommandRecoveryCompletionFlow(capacity: Int): MutableSharedFlow<T> {
    require(capacity > 0) { "reliable command completion capacity must be positive" }
    return MutableSharedFlow(
        replay = capacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
}
