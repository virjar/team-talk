package com.virjar.tk

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.virjar.tk.navigation.NavDestination
import com.virjar.tk.ui.screen.*
import com.virjar.tk.util.AppLog
import com.virjar.tk.viewmodel.ChatViewModel
import com.virjar.tk.audio.VoicePlayer
import kotlinx.coroutines.launch

// ──────────────────────────── Chat Panel ────────────────────────────

@Composable
internal fun DesktopChatPanel(
    target: ChatTarget,
) {
    val appState = LocalDesktopState.current
    val userContext = appState.userContext
    val conversationVm = appState.conversationVm
    val scope = rememberCoroutineScope()

    val voicePlayer = remember { VoicePlayer() }

    val chatVm = remember(target.channelId) {
        ChatViewModel(
            ctx = userContext,
            channelId = target.channelId,
            channelType = target.channelType,
            initialReadSeq = target.readSeq,
            initialScrollToSeq = target.scrollToSeq,
        )
    }

    LaunchedEffect(target.channelId) {
        chatVm.loadMessages()
        try {
            val state = chatVm.state.value
            if (state.messages.isNotEmpty()) {
                val maxSeq = state.messages.maxOf { it.serverSeq }
                conversationVm.markRead(target.channelId, maxSeq)
            }
        } catch (e: Exception) {
            AppLog.w("Desktop", "markRead on chat enter failed", e)
        }
    }

    DisposableEffect(target.channelId) {
        val removeListener = userContext.addMessageListener { incoming ->
            if (incoming.channelId == target.channelId) {
                scope.launch {
                    chatVm.loadMessages()
                    val state = chatVm.state.value
                    if (state.messages.isNotEmpty()) {
                        val maxSeq = state.messages.maxOf { it.serverSeq }
                        conversationVm.markRead(target.channelId, maxSeq)
                    }
                }
            }
        }
        val removeTypingListener = userContext.addTypingListener { channelId, senderUid, _ ->
            chatVm.onTypingReceived(channelId, senderUid)
        }
        val removeEditListener = userContext.addEditListener { channelId, targetMessageId, newPayload, editedAt ->
            chatVm.onEditReceived(channelId, targetMessageId, newPayload, editedAt)
        }
        onDispose {
            removeListener()
            removeTypingListener()
            removeEditListener()
        }
    }

    // Forward result snackbar state
    var forwardResult by remember { mutableStateOf<String?>(null) }

    // If there's an overlay, the parent DesktopDetailPanel handles routing via DesktopOverlayPanel
    // This composable only renders when there is no overlay active over this chat
    val draft = conversationVm.state.value.conversations
        .find { it.channelId == target.channelId }?.draft ?: ""

    ChatScreen(
        channelId = target.channelId,
        channelName = target.channelName,
        myUid = userContext.uid,
        channelType = target.channelType,
        state = chatVm.state.collectAsState().value,
        onSendMessage = { text -> scope.launch { chatVm.sendMessage(text) } },
        onRevokeMessage = { seq -> scope.launch { chatVm.revokeMessage(seq) } },
        onDeleteMessage = { msgId, seq -> scope.launch { chatVm.deleteMessage(msgId, seq) } },
        onLoadMoreHistory = { scope.launch { chatVm.loadMoreHistory() } },
        onBack = { currentInputText ->
            if (currentInputText.isBlank()) {
                scope.launch {
                    try { conversationVm.clearDraft(target.channelId) } catch (e: Exception) {
                        AppLog.w("Desktop", "clearDraft failed", e)
                    }
                }
            }
            // 非空草稿已由防抖 LaunchedEffect 保存，无需重复保存
        },
        onSendImage = { bytes, width, height ->
            scope.launch { chatVm.sendImageMessage(bytes, width, height) }
        },
        onSendFile = { bytes, fileName ->
            scope.launch { chatVm.sendFileMessage(bytes, fileName) }
        },
        onSendVideo = { bytes, fileName ->
            scope.launch { chatVm.sendVideoMessage(bytes, fileName) }
        },
        onReplyMessage = { text, replyToMsgId, replyToUid, replyToName, replyToType ->
            scope.launch {
                chatVm.sendReplyMessage(text, replyToMsgId, replyToUid, replyToName, replyToType)
            }
        },
        onClearReply = { chatVm.setReplyingTo(null) },
        onSetReply = { msg -> chatVm.setReplyingTo(msg) },
        onForward = { msg ->
            appState.navigateOverlay(
                NavDestination.Forward(msg)
            )
        },
        onGroupDetail = {
            appState.navigateOverlay(
                NavDestination.GroupDetail(target.channelId, target.channelType)
            )
        },
        onUserProfile = {
            target.otherUid?.let { uid ->
                appState.navigateOverlay(NavDestination.UserProfile(uid))
            }
        },
        onFileDownload = { payload ->
            scope.launch { chatVm.downloadFile(payload) }
        },
        imageBaseUrl = userContext.baseUrl,
        initialDraft = draft,
        onDraftChanged = { newDraft ->
            scope.launch {
                try {
                    if (newDraft.isBlank()) {
                        conversationVm.clearDraft(target.channelId)
                    } else {
                        conversationVm.updateDraft(target.channelId, newDraft)
                    }
                } catch (e: Exception) {
                    AppLog.w("Desktop", "debounced updateDraft failed", e)
                }
            }
        },
        showBackButton = false,
        onTyping = { userContext.sendTyping(target.channelId, target.channelType) },
        onSetEdit = { msg -> chatVm.setEditingMessage(msg) },
        onEditMessage = { text -> scope.launch { chatVm.editMessage(text) } },
        onClearEdit = { chatVm.setEditingMessage(null) },
        voicePlayer = voicePlayer,
        onStartRecording = { chatVm.startRecording() },
        onStopAndSendRecording = { chatVm.stopAndSendRecording() },
        onCancelRecording = { chatVm.cancelRecording() },
        onClearScrollTarget = { chatVm.clearScrollToSeq() },
        compactHeader = true,
    )

    // Forward result snackbar (only visible when forward overlay is active and returns result)
    forwardResult?.let { msg ->
        Snackbar(
            modifier = Modifier.padding(16.dp),
            action = { TextButton(onClick = { forwardResult = null }) { Text("OK") } }
        ) { Text(msg) }
    }
}
