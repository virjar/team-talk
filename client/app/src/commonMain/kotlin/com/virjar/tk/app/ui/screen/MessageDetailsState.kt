package com.virjar.tk.app.ui.screen

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import com.virjar.tk.app.viewmodel.ChatViewModel
import com.virjar.tk.protocol.model.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

/** The page's open lifetime is independent of a bounded chat window or an unavailable local row. */
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
            // A cancelled read can finish after close/reopen, including another opening of the
            // same clientMsgId. Only this exact page opening may replace its snapshot.
            if (selection === selected) {
                selected.message = message
                selected.loading = false
            }
        }
    }

    internal companion object {
        // Large message bodies never enter Android's saved-state bundle.
        val StateSaver = Saver<MessageDetailsState, String>(
            save = { it.selection?.clientMsgId.orEmpty() },
            restore = { MessageDetailsState(it) },
        )
    }
}

/** Uses the existing ViewModel/local-data boundary; no message observer survives this composition. */
@Composable
fun rememberMessageDetails(viewModel: ChatViewModel): MessageDetailsState {
    val state = rememberSaveable(viewModel, saver = MessageDetailsState.StateSaver) { MessageDetailsState() }
    val selected = state.selection
    LaunchedEffect(viewModel, selected) {
        if (selected != null) state.collect(selected, viewModel.observeMessageDetails(selected.clientMsgId))
    }
    return state
}
