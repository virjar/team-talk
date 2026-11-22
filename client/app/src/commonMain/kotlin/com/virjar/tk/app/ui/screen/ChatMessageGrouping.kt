package com.virjar.tk.app.ui.screen

import com.virjar.tk.app.ui.platform.localUiDateTime
import com.virjar.tk.protocol.model.Message
import kotlinx.datetime.TimeZone

/** 连续消息阈值：同一发送者 5 分钟内的非撤回消息视为连续，隐藏头像与昵称行。 */
internal const val CONTINUATION_THRESHOLD_MS = 5 * 60 * 1000L

/** 时间分隔阈值：相邻两条消息间隔超过 5 分钟显示时间提示。 */
internal const val TIME_SEPARATOR_THRESHOLD_MS = 5 * 60 * 1000L

/** 同一时间块最长跨度：距上一个时间锚点累计满 15 分钟时重新切分时间提示。 */
internal const val MAX_TIME_BLOCK_SPAN_MS = 15 * 60 * 1000L

/**
 * 聊天消息流中单条消息的瀑布流分组信息。
 *
 * @property showTimeSeparator 是否在该消息气泡上方展示时间提示条。
 * @property isContinuation 是否作为上一条同发送者消息的连续气泡（隐藏头像与昵称）。
 */
internal data class ChatMessageItemGrouping(
    val showTimeSeparator: Boolean,
    val isContinuation: Boolean,
)

/**
 * 计算倒序消息列表（index = 0 为最新，index + 1 为更早消息）的瀑布流分组状态。
 *
 * 在 `LazyColumn(reverseLayout = true)` 下，单个 item 内部自上而下先渲染时间分隔条、
 * 再渲染气泡本身；因此必须按时间从旧到新扫描（`lastIndex downTo 0`），比较当前消息与
 * 更早一条消息（`index + 1`），把时间提示挂在每个时间块的**第一条（最早一条）**消息上方。
 */
internal fun computeChatMessageGroupings(
    messages: List<Message>,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): List<ChatMessageItemGrouping> {
    if (messages.isEmpty()) return emptyList()
    val result = arrayOfNulls<ChatMessageItemGrouping>(messages.size)
    var lastAnchorTimestamp = 0L
    for (index in messages.lastIndex downTo 0) {
        val msg = messages[index]
        val olderMsg = messages.getOrNull(index + 1)
        val showTimeSeparator = olderMsg == null ||
            (msg.timestamp - olderMsg.timestamp) > TIME_SEPARATOR_THRESHOLD_MS ||
            localUiDateTime(msg.timestamp, timeZone).date != localUiDateTime(olderMsg.timestamp, timeZone).date ||
            (msg.timestamp - lastAnchorTimestamp) >= MAX_TIME_BLOCK_SPAN_MS
        if (showTimeSeparator) {
            lastAnchorTimestamp = msg.timestamp
        }
        // 头顶插入了时间提示条时强制断开连续合并，避免时间分隔夹在无头像气泡中间。
        val isContinuation = !showTimeSeparator &&
            isContinuousMessagePair(olderMsg = olderMsg, currentMsg = msg, timeZone = timeZone)
        result[index] = ChatMessageItemGrouping(
            showTimeSeparator = showTimeSeparator,
            isContinuation = isContinuation,
        )
    }
    return result.map { checkNotNull(it) }
}

/**
 * 判断 [currentMsg] 是否可以合并为紧接在更早一条 [olderMsg] 之后的连续气泡。
 * 当任一方已撤回、跨本地自然日、间隔超过 5 分钟或发生明显时钟回退时断开合并。
 */
internal fun isContinuousMessagePair(
    olderMsg: Message?,
    currentMsg: Message,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): Boolean = olderMsg != null &&
    olderMsg.senderUid == currentMsg.senderUid &&
    (currentMsg.timestamp - olderMsg.timestamp) in 0 until CONTINUATION_THRESHOLD_MS &&
    localUiDateTime(currentMsg.timestamp, timeZone).date == localUiDateTime(olderMsg.timestamp, timeZone).date &&
    (currentMsg.flags and Message.FLAG_REVOKED) == 0 &&
    (olderMsg.flags and Message.FLAG_REVOKED) == 0
