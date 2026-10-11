package com.virjar.tk.app.ui.screen

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import com.virjar.tk.app.viewmodel.ChatViewModel
import com.virjar.tk.protocol.model.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

/** 页面的打开生命周期独立于有界的聊天窗口或不可用的本地行。 */
class MessageDetailsState internal constructor(restoredClientMsgId: String = "") {
    internal class Selection(val clientMsgId: String, initial: Message?) {
        var message by mutableStateOf(initial)
        var loading by mutableStateOf(initial == null)
    }

    internal var selection by mutableStateOf(
        restoredClientMsgId.takeIf(String::isNotBlank)?.let { Selection(it, null) },
    )
        private set

    val isOpen: Boolean get() = selection != null
    val message: Message? get() = selection?.message
    val loading: Boolean get() = selection?.loading == true

    fun open(message: Message) { selection = Selection(message.clientMsgId, message) }
    fun close() { selection = null }

    internal suspend fun collect(selected: Selection, projection: Flow<Message?>) {
        projection.collect { message ->
            // 被取消的读取可能在关闭/重开之后才完成，包括同一 clientMsgId 的再一次
            // 打开。只有这次确切的页面打开才能替换其快照。
            if (selection === selected) {
                selected.message = message
                selected.loading = false
            }
        }
    }

    internal companion object {
        // 大消息正文绝不进入 Android 的 saved-state bundle。
        val StateSaver = Saver<MessageDetailsState, String>(
            save = { it.selection?.clientMsgId.orEmpty() },
            restore = { MessageDetailsState(it) },
        )
    }
}

/** 使用既有的 ViewModel/local-data 边界；没有消息观察者会在该组合结束后存活。 */
@Composable
fun rememberMessageDetails(viewModel: ChatViewModel): MessageDetailsState {
    val state = rememberSaveable(viewModel, saver = MessageDetailsState.StateSaver) { MessageDetailsState() }
    val selected = state.selection
    LaunchedEffect(viewModel, selected) {
        if (selected != null) state.collect(selected, viewModel.observeMessageDetails(selected.clientMsgId))
    }
    return state
}
