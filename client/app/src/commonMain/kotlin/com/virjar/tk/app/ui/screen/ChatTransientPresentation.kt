package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.User
import com.virjar.tk.app.ui.UiActionAdmission
import com.virjar.tk.app.ui.UiResultHandoff
import com.virjar.tk.app.ui.theme.Tk
import com.virjar.tk.app.viewmodel.ChatViewModel

/** 单个失败消息丢弃对话框的本地展示 owner。 */
internal class ChatFailedMessageDiscardState(
    private val viewModel: ChatViewModel,
    private val uiResultHandoff: UiResultHandoff,
    private val actionAdmission: UiActionAdmission,
) {
    var candidate by mutableStateOf<Message?>(null)
        private set
    var saving by mutableStateOf(false)
        private set

    fun request(message: Message) {
        candidate = message
    }

    fun dismiss() {
        candidate = null
    }

    fun confirm(failed: Message) {
        saving = true
        viewModel.discardFailedMessage(failed.clientMsgId) { discarded ->
            uiResultHandoff.deliver(discarded, actionAdmission) {
                saving = false
                candidate = null
            }
        }
    }
}

/** 把保留的输入状态绑定到该面板实际的可见/前台展示。 */
@Composable
internal fun chatTypingPresentationUid(
    chatId: String,
    viewModel: ChatViewModel,
    chatForegroundActive: Boolean,
): String? {
    val typingUid by viewModel.typingUid.collectAsState()
    DisposableEffect(viewModel, chatId, chatForegroundActive) {
        viewModel.onPresentationActiveChanged(chatForegroundActive)
        onDispose { viewModel.onPresentationActiveChanged(false) }
    }
    return visibleChatTypingUid(typingUid, chatForegroundActive)
}

internal fun visibleChatTypingUid(typingUid: String?, chatForegroundActive: Boolean): String? =
    typingUid.takeIf { chatForegroundActive }

@Composable
internal fun ChatTypingIndicator(
    typingUid: String?,
    resolveSender: ((uid: String) -> User?)?,
) {
    val uid = typingUid ?: return
    Text(
        text = chatTypingLabel(uid, resolveSender),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Tk.spacing.md, vertical = Tk.spacing.xs)
            .testTag(CHAT_TYPING_TEST_TAG),
    )
}

@Composable
internal fun BoxScope.ChatPanelOverlays(
    snackbarHostState: SnackbarHostState,
    failedMessageDiscard: ChatFailedMessageDiscardState,
) {
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter),
    )
    ChatFailedMessageDiscardDialog(
        candidate = failedMessageDiscard.candidate,
        saving = failedMessageDiscard.saving,
        onDismiss = failedMessageDiscard::dismiss,
        onConfirm = failedMessageDiscard::confirm,
    )
}
