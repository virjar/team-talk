package com.virjar.tk.shared.repository

import com.virjar.tk.protocol.ReliableCommandContract
import com.virjar.tk.protocol.http.AttachmentUploadIdentity
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.protocol.body.ReplyBody
import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.client.ChatAssetUpload
import com.virjar.tk.shared.client.ChatAssetUploadState
import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.shared.client.LocalChatAssetUploads
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 一个已认证会话拥有的单上传 worker。HTTP 与源文件副本从不依赖聊天页面生命周期。 */
class ChatAssetUploadCoordinator internal constructor(
    private val local: LocalChatAssetUploads,
    private val fileRepository: FileRepository,
    private val spool: ChatAssetSpool,
    private val connectionState: StateFlow<ConnectionState>,
) : AutoCloseable {
    private val owner = SupervisorJob()
    private val scope = CoroutineScope(owner + Dispatchers.IO)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val sourceMutation = Mutex()
    private var activeUpload: Pair<String, Job>? = null
    val changes: StateFlow<Long> get() = local.changes

    init {
        scope.launch {
            local.changes.collect {
                sourceMutation.withLock {
                    activeUpload?.let { (id, task) ->
                        if (local.upload(id) == null) task.cancel()
                    }
                }
                wake.trySend(Unit)
            }
        }
        scope.launch { connectionState.collect { wake.trySend(Unit) } }
        scope.launch {
            local.recoverUploads()
            while (isActive) {
                sourceMutation.withLock { cleanupSources() }
                local.expireUploads(System.currentTimeMillis())
                val selected = if (connectionState.value == ConnectionState.AUTHENTICATED) {
                    local.claimNext(System.currentTimeMillis())
                } else null
                if (selected != null) {
                    val task = sourceMutation.withLock {
                        if (local.upload(selected.assetId)?.attempt != selected.attempt) null
                        else scope.launch(start = CoroutineStart.LAZY) { upload(selected) }.also { activeUpload = selected.assetId to it }
                    }
                    task?.start()
                    task?.join()
                    sourceMutation.withLock { if (activeUpload?.second === task) activeUpload = null }
                    continue
                }
                val retryAt = local.nextUploadWakeAt(connectionState.value == ConnectionState.AUTHENTICATED)
                if (retryAt != null) {
                    withTimeoutOrNull((retryAt - System.currentTimeMillis()).coerceAtLeast(1_000)) { wake.receive() }
                } else wake.receive()
            }
        }
    }

    suspend fun registerPrepared(
        chatId: String,
        assetId: String,
        source: UploadSource,
        fileName: String,
        contentType: String,
        isImage: Boolean,
    ): ChatAssetUpload = owned {
        sourceMutation.withLock {
            val staged = spool.stage(source)
            try {
                val result = local.register(ChatAssetUpload(
                    assetId, chatId, staged.sourceId, staged.length, staged.sha256,
                    fileName, contentType, isImage, UUID.randomUUID().toString(), System.currentTimeMillis(),
                ))
                if (result.sourceId != staged.sourceId) spool.delete(staged.sourceId)
                result
            } catch (failure: Throwable) {
                spool.delete(staged.sourceId)
                throw failure
            }
        }
    }

    /** 同步 SQLite 读；组合层在 IO 上读取再向 UI 发布。 */
    fun jobs(chatId: String): List<ChatAssetUpload> = local.jobs(chatId)
    suspend fun retry(assetId: String) = owned { local.retry(assetId) }
    suspend fun remove(assetId: String) = owned {
        val active = sourceMutation.withLock {
            local.remove(assetId)
            activeUpload?.takeIf { it.first == assetId }?.second?.also { it.cancel() }
        }
        active?.join()
        sourceMutation.withLock { cleanupSources() }
    }

    /** 只为已被明确拒绝的旧请求准备新身份正文；旧 immutable outbox payload 始终不改写。 */
    internal suspend fun prepareFailedReplacement(ownerUid: String, failedClientMsgId: String, replacement: Message): Message = owned {
        val usedAssetIds = when (val body = replacement.body) {
            is RichTextBody -> body.assets.map { it.assetId }.toSet()
            is ReplyBody -> body.assets.map { it.assetId }.toSet()
            else -> emptySet()
        }
        if (usedAssetIds.isEmpty()) return@owned replacement
        val prepared = local.prepareReplacement(ownerUid, replacement.chatId, failedClientMsgId, usedAssetIds)
        if (prepared.isEmpty()) return@owned replacement
        check(connectionState.value == ConnectionState.AUTHENTICATED) { "重新上传附件需要联网；原消息和源文件已保留" }
        val ids = prepared.mapTo(hashSetOf()) { it.assetId }
        var ready: List<ChatAssetUpload>
        while (true) {
            val generation = local.changes.value
            val current = local.outgoingAssets(replacement.chatId, failedClientMsgId).filter { it.assetId in ids }
            check(current.size == ids.size) { "失败消息已更新，不能继续准备替换" }
            current.firstOrNull { it.state == ChatAssetUploadState.FAILED }?.let { error(it.failure ?: "重新上传失败，请重试") }
            if (current.all { it.state == ChatAssetUploadState.READY }) { ready = current; break }
            local.changes.first { it != generation }
        }
        val replacements = ready.associate { it.assetId to checkNotNull(it.asset) }
        replacement.copy(body = when (val body = replacement.body) {
            is RichTextBody -> buildRichTextBody(body.markdown, body.assets.map { replacements[it.assetId] ?: it })
            is ReplyBody -> body.copy(assets = body.assets.map { replacements[it.assetId] ?: it })
            else -> body
        })
    }

    private suspend fun upload(job: ChatAssetUpload) {
        try {
            val identity = AttachmentUploadIdentity(job.uploadId, job.issuedAt)
            identity.requireActiveAt(System.currentTimeMillis())
            when (val result = fileRepository.uploadWithMeta(spool.open(job.sourceId), job.fileName, job.contentType, identity)) {
                is Outcome.Success -> {
                    val uploaded = result.value
                    local.complete(job.assetId, job.attempt, EmbeddedAsset(
                        job.assetId, uploaded.file, uploaded.thumbnail, uploaded.width, uploaded.height,
                    ))
                }
                is Outcome.Failure -> {
                    val retryable = result.error == AppError.Network || result.error == AppError.Timeout ||
                        (result.error is AppError.Business && result.error.code >= 500)
                    val expired = result.error is AppError.Business && result.error.code == 410
                    local.fail(job.assetId, job.attempt,
                        if (expired) "上传凭据已过期，请重试以重新上传保留的源文件" else result.error.message.orEmpty(),
                        if (retryable) System.currentTimeMillis() + retryDelay(job.attempt) else 0)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            local.fail(job.assetId, job.attempt, failure.message ?: "附件上传失败", 0)
        }
    }

    private fun cleanupSources() {
        val referenced = local.retainedSourceIds()
        spool.list().filter { it.sourceId !in referenced }.forEach { spool.delete(it.sourceId) }
    }

    private suspend fun <T> owned(block: suspend () -> T): T {
        val operation = scope.async { block() }
        return try { operation.await() } catch (cancelled: CancellationException) {
            operation.cancel()
            throw cancelled
        }
    }

    /** 只等待自有 IO 工作，没有 Main 回调；必须早于 LocalCache 和专用 HTTP transport 关闭。 */
    override fun close() {
        owner.cancel()
        try { fileRepository.close() } finally { runBlocking { owner.join() } }
    }

    private fun retryDelay(attempt: Long): Long = 1_000L shl attempt.coerceIn(0, 6).toInt()
}
