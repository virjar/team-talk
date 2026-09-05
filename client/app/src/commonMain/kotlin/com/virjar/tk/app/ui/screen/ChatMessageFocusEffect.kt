package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.app.ui.UiActionAdmission
import com.virjar.tk.app.viewmodel.ChatViewModel
import com.virjar.tk.app.viewmodel.MessageFocusState
import com.virjar.tk.app.viewmodel.MessageFocusTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun rememberMessageFocus(
    viewModel: ChatViewModel,
    target: MessageFocusTarget?,
    requestId: Long,
    state: MessageFocusState,
    messages: List<Message>,
    messageListState: LazyListState,
    actionAdmission: UiActionAdmission,
): Long? {
    MessageFocusIntentEffect(viewModel, target, requestId, actionAdmission)
    return rememberMessageFocusHighlight(
        state = state,
        messages = messages,
        messageListState = messageListState,
        onPositioned = { focusTarget, generation ->
            actionAdmission.runIfOpen {
                viewModel.markMessageFocusPositioned(focusTarget, generation)
            }
        },
        onPositionUnavailable = { focusTarget, generation ->
            actionAdmission.runIfOpen {
                viewModel.markMessageFocusPositionUnavailable(focusTarget, generation)
            }
        },
    )
}

/** 恰好持有一个路由级聚焦意图，并在该路由值被替换时取消它。 */
@Composable
private fun MessageFocusIntentEffect(
    viewModel: ChatViewModel,
    target: MessageFocusTarget?,
    requestId: Long,
    actionAdmission: UiActionAdmission,
) {
    DisposableEffect(viewModel, target, requestId) {
        var ownedGeneration: Long? = null
        if (target == null) {
            viewModel.clearMessageFocus()
        } else {
            actionAdmission.runIfOpen { ownedGeneration = viewModel.focusMessage(target) }
        }
        onDispose {
            ownedGeneration?.let { generation -> viewModel.clearMessageFocus(target!!, generation) }
        }
    }
}

/**
 * 只消费已解析的精确服务端序号。ViewModel 确认定位后 generation 仍是 effect 的 key，
 * 因此该状态迁移无法取消高亮计时器。
 */
@Composable
private fun rememberMessageFocusHighlight(
    state: MessageFocusState,
    messages: List<Message>,
    messageListState: LazyListState,
    onPositioned: (MessageFocusTarget, Long) -> Unit,
    onPositionUnavailable: (MessageFocusTarget, Long) -> Unit,
): Long? {
    var highlightedServerSeq by remember(messageListState) { mutableStateOf<Long?>(null) }
    val latestMessages by rememberUpdatedState(messages)
    val resolved = state as? MessageFocusState.Resolved
    val activeGeneration = when (state) {
        is MessageFocusState.Resolved -> state.generation
        is MessageFocusState.Positioned -> state.generation
        else -> null
    }

    LaunchedEffect(activeGeneration, messageListState) {
        val focus = resolved ?: return@LaunchedEffect
        val targetSeq = focus.target.serverSeq
        highlightedServerSeq = targetSeq
        try {
            val positioned = withTimeoutOrNull(MESSAGE_FOCUS_POSITION_TIMEOUT_MILLIS) {
                while (true) {
                    val targetIndex = snapshotFlow {
                        val index = latestMessages.indexOfFirst { message ->
                            message.chatId == focus.target.chatId && message.serverSeq == targetSeq
                        }
                        index.takeIf { candidate ->
                            candidate >= 0 && messageListState.layoutInfo.totalItemsCount > candidate
                        }
                    }.filterNotNull().first()
                    messageListState.scrollToItem(targetIndex)
                    val settledIndex = latestMessages.indexOfFirst { message ->
                        message.chatId == focus.target.chatId && message.serverSeq == targetSeq
                    }
                    if (settledIndex == targetIndex) break
                }
                true
            }
            if (positioned == true) {
                onPositioned(focus.target, focus.generation)
                delay(MESSAGE_FOCUS_HIGHLIGHT_MILLIS)
            } else {
                onPositionUnavailable(focus.target, focus.generation)
            }
        } finally {
            if (highlightedServerSeq == targetSeq) highlightedServerSeq = null
        }
    }

    return highlightedServerSeq
}

internal fun MessageFocusState.isLoadingOrAwaitingPosition(): Boolean =
    this is MessageFocusState.Loading || this is MessageFocusState.Resolved

/** 稳定的发布闸门语义，用于把路由所有权与聚焦进度分开观察。 */
internal fun messageFocusSemanticsTag(
    routeTarget: MessageFocusTarget?,
    state: MessageFocusState,
): String {
    val route = routeTarget?.serverSeq?.toString() ?: "none"
    val phase = when (state) {
        MessageFocusState.Idle -> "idle"
        is MessageFocusState.Loading -> "loading.${state.target.serverSeq}"
        is MessageFocusState.Resolved -> "resolved.${state.target.serverSeq}"
        is MessageFocusState.Positioned -> "positioned.${state.target.serverSeq}"
        is MessageFocusState.Failed -> "failed.${state.target.serverSeq}.${state.reason.name.lowercase()}"
    }
    return "chat.focus.route.$route.$phase"
}

private const val MESSAGE_FOCUS_HIGHLIGHT_MILLIS = 2_400L
private const val MESSAGE_FOCUS_POSITION_TIMEOUT_MILLIS = 5_000L
