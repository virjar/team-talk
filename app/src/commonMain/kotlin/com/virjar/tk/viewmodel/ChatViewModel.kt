package com.virjar.tk.viewmodel

import com.virjar.tk.protocol.ChannelType
import com.virjar.tk.client.SendResult
import com.virjar.tk.client.UserContext
import com.virjar.tk.protocol.PacketType
import com.virjar.tk.protocol.payload.*
import com.virjar.tk.util.AppLog
import com.virjar.tk.util.saveFileToDisk
import com.virjar.tk.repository.HistoryResult
import com.virjar.tk.util.toUserMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChatState(
    val messages: List<Message> = emptyList(),
    val isLoading: Boolean = false,
    val isSending: Boolean = false,
    val error: String = "",
    val hasMoreHistory: Boolean = true,
    val inputText: String = "",
    val readSeq: Long = 0,
    val scrollToSeq: Long = 0,
    val replyingTo: Message? = null,
    val typingUser: String? = null,
    val editingMessage: Message? = null,
    val uploadProgress: Float? = null,
    val isRecording: Boolean = false,
    val recordingDuration: Int = 0,
    val recordingAmplitude: Float = 0f,
    /** 上一次 messages 更新是由于加载历史（前置插入），ChatScreen 据此保持滚动位置 */
    val historyLoadedCount: Int = 0,
)

class ChatViewModel(
    private val ctx: UserContext,
    private val channelId: String,
    private val channelType: ChannelType,
    initialReadSeq: Long = 0,
    initialScrollToSeq: Long = 0,
) : BaseViewModel() {
    private val chatRepo = ctx.chatRepo
    private val fileRepo = ctx.fileRepo
    private val myUid = ctx.uid

    private val _state = MutableStateFlow(ChatState(readSeq = initialReadSeq, scrollToSeq = initialScrollToSeq))
    val state: StateFlow<ChatState> = _state.asStateFlow()

    // ── 录音 ──
    private val recordingController = RecordingController()

    // ── 消息加载 ──

    suspend fun loadMessages() {
        _state.value = _state.value.copy(isLoading = true)
        try {
            val messages = chatRepo.getMessages(channelId)
            AppLog.i("ChatVM", "loadMessages: channelId=$channelId got ${messages.size} msgs from local DB")
            _state.value = ChatState(messages = messages, readSeq = _state.value.readSeq, scrollToSeq = _state.value.scrollToSeq)
            val maxSeq = messages.maxOfOrNull { it.serverSeq } ?: 0L
            ctx.subscribeChannel(channelId, maxSeq)
        } catch (e: Exception) {
            AppLog.e("ChatVM", "loadMessages failed: channelId=$channelId", e)
            _state.value = _state.value.copy(isLoading = false, error = e.toUserMessage())
        }
    }

    suspend fun loadMoreHistory() {
        val currentMessages = _state.value.messages
        if (currentMessages.isEmpty()) return
        val oldestSeq = currentMessages.minOfOrNull { it.serverSeq } ?: return
        try {
            val result = chatRepo.getMessagesBefore(channelId, oldestSeq, limit = 50)
            if (result.messages.isEmpty()) {
                _state.value = _state.value.copy(hasMoreHistory = false)
            } else {
                val merged = (result.messages + currentMessages)
                    .distinctBy { it.messageId }
                    .sortedBy { it.serverSeq }
                _state.value = _state.value.copy(
                    messages = merged,
                    hasMoreHistory = result.hasMore,
                    historyLoadedCount = result.messages.size,
                )
                AppLog.i("ChatVM", "loadMoreHistory: loaded ${result.messages.size} older messages, hasMore=${result.hasMore}")
            }
        } catch (e: Exception) {
            AppLog.e("ChatVM", "loadMoreHistory failed", e)
        }
    }

    fun clearScrollToSeq() {
        _state.value = _state.value.copy(scrollToSeq = 0)
    }

    // ── 乐观更新 ──

    private suspend fun withOptimisticUpdate(
        optimisticMsg: Message,
        clearInput: Boolean = false,
        action: suspend () -> SendResult,
    ): Boolean {
        _state.value = _state.value.copy(
            messages = _state.value.messages + optimisticMsg,
            isSending = true,
            inputText = if (clearInput) "" else _state.value.inputText,
        )
        return try {
            val result = action()
            val updatedMessages = _state.value.messages.map { msg ->
                if (msg.clientMsgNo == optimisticMsg.clientMsgNo) {
                    Message(msg.header.copy(serverSeq = result.serverSeq, messageId = result.messageId), msg.body)
                } else msg
            }
            val confirmedMsg = updatedMessages.first { it.clientMsgNo == optimisticMsg.clientMsgNo }
            try { ctx.localCache.insertMessage(confirmedMsg) } catch (_: Exception) {}
            _state.value = _state.value.copy(messages = updatedMessages, isSending = false)
            true
        } catch (e: Exception) {
            AppLog.e("ChatVM", "[OptUpdate] FAILED: channelId=$channelId error=${e.message}", e)
            _state.value = _state.value.copy(
                messages = _state.value.messages.filter { it.clientMsgNo != optimisticMsg.clientMsgNo },
                isSending = false,
                error = e.toUserMessage(),
            )
            false
        }
    }

    private fun optimisticHeader(idPrefix: String) = MessageHeader(
        channelId = channelId,
        clientMsgNo = "${idPrefix}${System.currentTimeMillis()}",
        senderUid = myUid,
        channelType = channelType,
        serverSeq = -1,
        timestamp = System.currentTimeMillis(),
    )

    // ── 带上传进度的文件上传 ──

    private suspend fun <T> uploadWithProgress(
        upload: suspend ((Float) -> Unit) -> T,
    ): T {
        _state.value = _state.value.copy(uploadProgress = 0f)
        return try {
            upload { progress -> _state.value = _state.value.copy(uploadProgress = progress) }
        } catch (e: Exception) {
            _state.value = _state.value.copy(uploadProgress = null)
            throw e
        } finally {
            _state.value = _state.value.copy(uploadProgress = null)
        }
    }

    // ── 消息发送 ──

    suspend fun sendMessage(text: String): Boolean {
        val optimisticMsg = Message(optimisticHeader("local_"), TextBody(text, emptyList()))
        return withOptimisticUpdate(optimisticMsg, clearInput = true) {
            chatRepo.sendTextMessage(channelId, channelType, text)
        }
    }

    suspend fun sendImageMessage(bytes: ByteArray, width: Int, height: Int): Boolean {
        val result = uploadWithProgress { onProgress ->
            fileRepo.uploadImage(bytes, onProgress = onProgress)
        }
        val optimisticMsg = Message(
            optimisticHeader("local_img_"),
            ImageBody(
                url = result.path, width = width, height = height, size = bytes.size.toLong(),
                thumbnailUrl = result.thumbnailPath, caption = null,
            ),
        )
        return withOptimisticUpdate(optimisticMsg) {
            chatRepo.sendImageMessage(channelId, channelType, result.path, width, height, bytes.size.toLong())
        }
    }

    suspend fun sendReplyMessage(
        text: String,
        replyToMessageId: String,
        replyToSenderUid: String,
        replyToSenderName: String,
        replyToMessageType: Int,
    ): Boolean {
        val optimisticMsg = Message(
            optimisticHeader("local_reply_"),
            ReplyBody(
                replyToMessageId = replyToMessageId,
                replyToSenderUid = replyToSenderUid,
                replyToSenderName = replyToSenderName,
                replyToPacketType = replyToMessageType.toByte(),
                text = text,
                mentionUids = emptyList(),
            ),
        )
        return withOptimisticUpdate(optimisticMsg, clearInput = true) {
            chatRepo.sendReplyMessage(
                channelId, channelType, text,
                replyToMessageId, replyToSenderUid, replyToSenderName,
                replyToMessageType,
            )
        }
    }

    suspend fun sendFileMessage(bytes: ByteArray, fileName: String): Boolean {
        val result = uploadWithProgress { onProgress ->
            fileRepo.uploadFile(bytes, fileName, onProgress = onProgress)
        }
        val optimisticMsg = Message(
            optimisticHeader("local_file_"),
            FileBody(
                url = result.path, fileName = fileName, fileSize = bytes.size.toLong(),
                mimeType = null, thumbnailUrl = null,
            ),
        )
        return withOptimisticUpdate(optimisticMsg) {
            chatRepo.sendFileMessage(channelId, channelType, result.path, fileName, bytes.size.toLong())
        }
    }

    suspend fun sendVideoMessage(bytes: ByteArray, fileName: String): Boolean {
        val contentType = videoContentType(fileName)
        val result = uploadWithProgress { onProgress ->
            fileRepo.uploadFile(bytes, fileName, contentType, onProgress)
        }
        val optimisticMsg = Message(
            optimisticHeader("local_video_"),
            VideoBody(
                url = result.path, width = 0, height = 0, size = bytes.size.toLong(),
                duration = 0, coverUrl = "",
            ),
        )
        return withOptimisticUpdate(optimisticMsg) {
            chatRepo.sendVideoMessage(channelId, channelType, result.path, 0, 0, bytes.size.toLong(), 0, "")
        }
    }

    suspend fun sendVoiceMessage(bytes: ByteArray, durationSeconds: Int): Boolean {
        val result = uploadWithProgress { onProgress ->
            fileRepo.uploadFile(bytes, "voice_${System.currentTimeMillis()}.wav", "audio/wav", onProgress)
        }
        val optimisticMsg = Message(
            optimisticHeader("local_voice_"),
            VoiceBody(url = result.path, duration = durationSeconds, size = bytes.size.toLong(), waveform = null),
        )
        return withOptimisticUpdate(optimisticMsg) {
            chatRepo.sendVoiceMessage(channelId, channelType, result.path, durationSeconds, bytes.size.toLong())
        }
    }

    // ── 消息操作 ──

    suspend fun revokeMessage(seq: Long): Boolean {
        return try {
            chatRepo.revokeMessage(channelId, seq)
            loadMessages()
            true
        } catch (e: Exception) {
            AppLog.e("ChatVM", "revokeMessage failed: seq=$seq", e)
            false
        }
    }

    suspend fun deleteMessage(messageId: String, seq: Long): Boolean {
        return try {
            chatRepo.deleteMessageLocal(messageId)
            _state.value = _state.value.copy(
                messages = _state.value.messages.filter { it.messageId != messageId }
            )
            true
        } catch (e: Exception) {
            AppLog.e("ChatVM", "deleteMessage failed: msgId=$messageId", e)
            false
        }
    }

    suspend fun sendForwardMessage(targetChannelId: String, targetChannelType: ChannelType, msg: Message): Boolean {
        val forwardBody = msg.body as? ForwardBody ?: return false
        return try {
            chatRepo.sendForwardMessage(
                targetChannelId, targetChannelType,
                forwardBody.forwardFromChannelId,
                forwardBody.forwardFromMessageId,
                forwardBody.forwardFromSenderUid,
                forwardBody.forwardFromSenderName,
                forwardBody.forwardPacketType,
                forwardBody.forwardPayload,
            )
            true
        } catch (e: Exception) {
            AppLog.e("ChatVM", "sendForwardMessage failed", e)
            false
        }
    }

    fun updateInputText(text: String) {
        _state.value = _state.value.copy(inputText = text)
    }

    fun setReplyingTo(message: Message?) {
        _state.value = _state.value.copy(replyingTo = message)
    }

    fun setEditingMessage(message: Message?) {
        _state.value = _state.value.copy(editingMessage = message)
    }

    suspend fun editMessage(newText: String): Boolean {
        val msg = _state.value.editingMessage ?: return false
        return try {
            chatRepo.editMessage(channelId, msg.serverSeq, newText)
            setEditingMessage(null)
            loadMessages()
            true
        } catch (e: Exception) {
            AppLog.e("ChatVM", "editMessage failed", e)
            false
        }
    }

    fun onEditReceived(channelId: String, targetMessageId: String, newPayload: String, editedAt: Long) {
        if (channelId != this.channelId) return
        val updated = _state.value.messages.map { msg ->
            if (msg.messageId == targetMessageId && msg.body is TextBody) {
                val newHeader = msg.header.copy(flags = msg.header.flags or 1)
                Message(newHeader, TextBody(newPayload, (msg.body as TextBody).mentionUids))
            } else msg
        }
        _state.value = _state.value.copy(messages = updated)
    }

    // ── 输入状态管理 ──
    private val typingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var typingJob: Job? = null

    fun onTypingReceived(channelId: String, senderUid: String, senderName: String? = null) {
        if (channelId != this.channelId) return
        if (senderUid == myUid) return
        _state.value = _state.value.copy(typingUser = senderName ?: senderUid.take(8))
        typingJob?.cancel()
        typingJob = typingScope.launch {
            delay(5000)
            _state.value = _state.value.copy(typingUser = null)
        }
    }

    fun onNewMessage(payload: Message) {
        if (payload.channelId == channelId) {
            val exists = _state.value.messages.any { it.messageId == payload.messageId }
            if (!exists) {
                val updated = (_state.value.messages + payload).sortedBy { it.serverSeq }
                _state.value = _state.value.copy(messages = updated)
                AppLog.i("ChatVM", "onNewMessage: appended msgId=${payload.messageId} seq=${payload.serverSeq}")
            }
        }
    }

    suspend fun downloadFile(msg: Message): Boolean {
        val fileBody = msg.body as? FileBody ?: return false
        return try {
            val bytes = fileRepo.downloadFile(fileBody.url)
            saveFileToDisk(bytes, fileBody.fileName.ifEmpty { "download" })
        } catch (e: Exception) {
            AppLog.e("ChatVM", "downloadFile failed: ${fileBody.fileName}", e)
            false
        }
    }

    // ── 录音 ──

    fun startRecording() {
        recordingController.start { controller ->
            val result = controller.stop()
            if (result != null) {
                scope.launch { sendVoiceMessage(result.bytes, result.durationSeconds) }
            }
        }
        observeRecordingState()
    }

    fun stopAndSendRecording() {
        val result = recordingController.stop()
        if (result != null) {
            scope.launch { sendVoiceMessage(result.bytes, result.durationSeconds) }
        }
    }

    fun cancelRecording() {
        recordingController.cancel()
    }

    private fun observeRecordingState() {
        scope.launch {
            recordingController.state.collect { info ->
                _state.value = _state.value.copy(
                    isRecording = info.isRecording,
                    recordingDuration = info.duration,
                    recordingAmplitude = info.amplitude,
                )
            }
        }
    }

    override fun cleanup() {
        typingScope.cancel()
        recordingController.destroy()
        super.cleanup()
    }
}

private fun videoContentType(fileName: String): String {
    val ext = fileName.substringAfterLast('.', "mp4").lowercase()
    return when (ext) {
        "avi" -> "video/avi"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "wmv" -> "video/x-ms-wmv"
        "flv" -> "video/x-flv"
        else -> "video/mp4"
    }
}
