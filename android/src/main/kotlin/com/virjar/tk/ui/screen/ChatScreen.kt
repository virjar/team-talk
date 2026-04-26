package com.virjar.tk.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.virjar.tk.protocol.ChannelType
import com.virjar.tk.protocol.payload.Message
import com.virjar.tk.protocol.payload.TextBody
import com.virjar.tk.ui.component.FilePickerType
import com.virjar.tk.ui.component.chat.ChatInputBar
import com.virjar.tk.ui.component.chat.ImageViewerDialog
import com.virjar.tk.ui.component.chat.MessageBubble
import com.virjar.tk.ui.component.chat.TimeSeparator
import com.virjar.tk.ui.component.chat.VideoPlayerDialog
import com.virjar.tk.ui.component.rememberFilePicker
import com.virjar.tk.util.shouldShowTimeSeparator
import com.virjar.tk.viewmodel.ChatState
import android.graphics.BitmapFactory
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream
import com.virjar.tk.audio.VoicePlayer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    channelId: String,
    channelName: String,
    myUid: String,
    channelType: ChannelType,
    state: ChatState,
    onSendMessage: (String) -> Unit,
    onRevokeMessage: (seq: Long) -> Unit,
    onDeleteMessage: (String, Long) -> Unit = { _, _ -> },
    onLoadMoreHistory: () -> Unit,
    onBack: (currentInputText: String) -> Unit,
    onSendImage: (ByteArray, Int, Int) -> Unit = { _, _, _ -> },
    onSendFile: (ByteArray, String) -> Unit = { _, _ -> },
    onSendVideo: (ByteArray, String) -> Unit = { _, _ -> },
    onReplyMessage: (text: String, replyToMessageId: String, replyToSenderUid: String, replyToSenderName: String, replyToMessageType: Int) -> Unit = { _, _, _, _, _ -> },
    onClearReply: () -> Unit = {},
    onSetReply: (Message) -> Unit = {},
    onForward: (Message) -> Unit = {},
    onGroupDetail: () -> Unit = {},
    onUserProfile: () -> Unit = {},
    onFileDownload: (Message) -> Unit = {},
    imageBaseUrl: String = "http://10.0.2.2:8080",
    initialDraft: String = "",
    onDraftChanged: (String) -> Unit = {},
    showBackButton: Boolean = true,
    onTyping: () -> Unit = {},
    onSetEdit: (Message) -> Unit = {},
    onEditMessage: (String) -> Unit = {},
    onClearEdit: () -> Unit = {},
    voicePlayer: VoicePlayer = VoicePlayer(),
    onStartRecording: () -> Unit = {},
    onStopAndSendRecording: () -> Unit = {},
    onCancelRecording: () -> Unit = {},
    onClearScrollTarget: () -> Unit = {},
) {
    var inputText by remember { mutableStateOf(initialDraft) }
    var lastTypingSent by remember { mutableStateOf(0L) }

    // 编辑模式：将编辑消息的文本填入输入框
    LaunchedEffect(state.editingMessage) {
        val editMsg = state.editingMessage
        if (editMsg != null) {
            inputText = (editMsg.body as? TextBody)?.text ?: ""
        }
    }

    // Debounced draft save — writes to DB after 500ms of inactivity
    LaunchedEffect(inputText) {
        delay(500)
        onDraftChanged(inputText)
        // 发送 TYPING（3 秒防抖）
        if (inputText.isNotBlank()) {
            val now = System.currentTimeMillis()
            if (now - lastTypingSent > 3000) {
                onTyping()
                lastTypingSent = now
            }
        }
    }

    val listState = rememberLazyListState()
    var pendingImagePicker by remember { mutableStateOf(false) }
    var pendingFilePicker by remember { mutableStateOf(false) }
    var pendingVideoPicker by remember { mutableStateOf(false) }
    var fullScreenImageUrl by remember { mutableStateOf<String?>(null) }
    var playingVideoUrl by remember { mutableStateOf<String?>(null) }

    val launchImagePicker = rememberFilePicker(onFileSelected = { bytes, fileName ->
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(ByteArrayInputStream(bytes), null, options)
        if (options.outWidth > 0 && options.outHeight > 0) {
            onSendImage(bytes, options.outWidth, options.outHeight)
        }
    })

    val launchFilePicker = rememberFilePicker(onFileSelected = { bytes, fileName ->
        onSendFile(bytes, fileName)
    })

    val launchVideoPicker = rememberFilePicker(
        onFileSelected = { bytes, fileName -> onSendVideo(bytes, fileName) },
        fileType = FilePickerType.Video,
    )

    LaunchedEffect(pendingImagePicker) {
        if (pendingImagePicker) {
            launchImagePicker()
            pendingImagePicker = false
        }
    }

    LaunchedEffect(pendingFilePicker) {
        if (pendingFilePicker) {
            launchFilePicker()
            pendingFilePicker = false
        }
    }

    LaunchedEffect(pendingVideoPicker) {
        if (pendingVideoPicker) {
            launchVideoPicker()
            pendingVideoPicker = false
        }
    }

    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)

    // Scroll to target message (from search result) or to bottom on new messages
    // 加载历史后保持滚动位置不变（通过 historyLoadedCount 判断）
    // 键盘弹出时即时滚到底部（用 scrollToItem 避免 imePadding 动画冲突）
    LaunchedEffect(state.messages.size, state.scrollToSeq, imeBottom) {
        if (state.messages.isNotEmpty()) {
            if (state.scrollToSeq > 0) {
                val targetIndex = state.messages.indexOfFirst { it.serverSeq == state.scrollToSeq }
                if (targetIndex >= 0) {
                    listState.animateScrollToItem(targetIndex)
                    onClearScrollTarget()
                }
            } else if (state.historyLoadedCount > 0) {
                listState.scrollToItem(state.historyLoadedCount)
            } else if (imeBottom > 0) {
                listState.scrollToItem(state.messages.size - 1)
            } else {
                listState.animateScrollToItem(state.messages.size - 1)
            }
        }
    }

    LaunchedEffect(listState.firstVisibleItemIndex) {
        if (listState.firstVisibleItemIndex == 0 && state.hasMoreHistory && state.messages.isNotEmpty()) {
            onLoadMoreHistory()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val clickModifier = when (channelType) {
                        ChannelType.PERSONAL -> Modifier.clickable(onClick = onUserProfile)
                        ChannelType.GROUP -> Modifier.clickable(onClick = onGroupDetail)
                        else -> Modifier
                    }
                    Column(modifier = clickModifier) {
                        Text(channelName.ifEmpty { channelId.take(20) })
                        if (state.typingUser != null) {
                            Text(
                                text = if (channelType == ChannelType.GROUP) "${state.typingUser} is typing..." else "typing...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (showBackButton) {
                        IconButton(onClick = { onBack(inputText) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding()
        ) {
            if (state.isLoading && state.messages.isEmpty()) {
                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                val messageMap = remember(state.messages) { state.messages.associateBy { it.messageId ?: it.clientMsgNo } }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(state.messages, key = { it.messageId ?: it.clientMsgNo }) { msg ->
                        val prevIndex = state.messages.indexOf(msg)
                        val showTimeSep = prevIndex == 0 ||
                            (prevIndex > 0 && shouldShowTimeSeparator(
                                state.messages[prevIndex - 1].timestamp, msg.timestamp
                            ))

                        if (showTimeSep) {
                            TimeSeparator(timestamp = msg.timestamp)
                        }

                        MessageBubble(
                            msg = msg,
                            senderName = "",
                            isMe = msg.senderUid == myUid,
                            isGroup = channelType == ChannelType.GROUP,
                            readSeq = state.readSeq,
                            imageBaseUrl = imageBaseUrl,
                            onRevoke = { onRevokeMessage(msg.serverSeq) },
                            onDelete = { onDeleteMessage(msg.messageId ?: "", msg.serverSeq) },
                            onReply = { onSetReply(msg) },
                            onForward = { onForward(msg) },
                            onEdit = { onSetEdit(msg) },
                            onImageClick = { url -> fullScreenImageUrl = url },
                            onFileDownload = { onFileDownload(msg) },
                            onVideoPlay = { url -> playingVideoUrl = url },
                            voicePlayer = voicePlayer,
                            replyLookup = { id -> messageMap[id] },
                        )
                    }
                }
            }

            ChatInputBar(
                inputText = inputText,
                onInputTextChange = { inputText = it },
                isSending = state.isSending,
                replyingTo = state.replyingTo,
                onClearReply = onClearReply,
                editingMessage = state.editingMessage,
                onClearEdit = onClearEdit,
                onSend = {
                    if (inputText.isNotBlank()) {
                        val editingMsg = state.editingMessage
                        val replyingTo = state.replyingTo
                        when {
                            editingMsg != null -> onEditMessage(inputText)
                            replyingTo != null -> onReplyMessage(
                                inputText,
                                replyingTo.messageId ?: "",
                                replyingTo.senderUid ?: "",
                                "",
                                replyingTo.packetType.code.toInt(),
                            )
                            else -> onSendMessage(inputText)
                        }
                        inputText = ""
                        onDraftChanged("")
                    }
                },
                onPickImage = { pendingImagePicker = true },
                onPickFile = { pendingFilePicker = true },
                onPickVideo = { pendingVideoPicker = true },
                onStartRecording = onStartRecording,
                isRecording = state.isRecording,
                recordingDuration = state.recordingDuration,
                recordingAmplitude = state.recordingAmplitude,
                onSendRecording = onStopAndSendRecording,
                onCancelRecording = onCancelRecording,
            )
        }
    }

    // Full-screen image viewer dialog
    fullScreenImageUrl?.let { url ->
        ImageViewerDialog(imageUrl = url, onDismiss = { fullScreenImageUrl = null })
    }

    // Video player dialog
    playingVideoUrl?.let { url ->
        VideoPlayerDialog(videoUrl = url, onDismiss = { playingVideoUrl = null })
    }
}
