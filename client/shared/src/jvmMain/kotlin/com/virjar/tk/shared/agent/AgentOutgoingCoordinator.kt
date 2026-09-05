package com.virjar.tk.shared.agent

import com.virjar.tk.shared.client.OutgoingMessage
import com.virjar.tk.shared.client.OutgoingMessageConflictException
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.shared.log.PlatformOnlyTkLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.security.MessageDigest

/** 调用方控制的路径在上传之前失败；绝不暴露原始路径或原因。 */
internal class AgentFileRequestException : IllegalArgumentException("file path is not allowed")

/**
 * 可安全重试的 `send-file` 的固定大小并发边界。
 *
 * 第一次回执查询发生在暂存/上传之前。对已经持有回执的重试，我们仍会暂存，
 * 以便失败关闭地校验其内容指纹，但绝不再上传它。
 * 上传之后、SQLite 准入之前的崩溃可能留下一个远程孤儿；它不可能创建
 * 重复消息，因为持久入队之前不会发生任何 wire 发送。
 */
internal class AgentFileSendCoordinator(
    private val findReceipt: (String, String, ByteArray?) -> OutgoingMessage?,
    private val stage: (String) -> AgentPreparedUpload,
    private val upload: suspend (AgentPreparedUpload, String) -> Attachment,
    private val enqueue: suspend (String, String, Attachment, ByteArray) -> OutgoingMessage,
    private val reportCleanupFailure: (Throwable) -> Unit = ::reportPreparedUploadCleanupFailure,
    stripeCount: Int = DEFAULT_STRIPES,
) {
    private val stripes = Array(stripeCount) { Mutex() }

    init {
        require(stripeCount > 0) { "stripeCount must be positive" }
    }

    suspend fun enqueueFile(
        chatId: String,
        clientMsgId: String,
        rawPath: String,
        contentType: String = DEFAULT_CONTENT_TYPE,
    ): OutgoingMessage = stripe(chatId, clientMsgId).withLock {
        val preexisting = findReceipt(chatId, clientMsgId, null)
        val prepared = try {
            stage(rawPath)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw AgentFileRequestException()
        }
        var operationFailure: Throwable? = null
        try {
            val fingerprint = fileRequestFingerprint(prepared, contentType)
            if (preexisting != null) {
                return@withLock findReceipt(chatId, clientMsgId, fingerprint)
                    ?: throw OutgoingMessageConflictException(
                        "clientMsgId receipt expired while the file snapshot was validated",
                    )
            }
            findReceipt(chatId, clientMsgId, fingerprint)?.let { return@withLock it }
            val attachment = upload(prepared, contentType)
            enqueue(chatId, clientMsgId, attachment, fingerprint)
        } catch (failure: Throwable) {
            operationFailure = failure
            throw failure
        } finally {
            // 不通过日志暴露调用方路径，也不用本地普通清理失败
            // 替换已准入的远程结果。致命清理仍然保留生命周期语义。
            try {
                prepared.close()
            } catch (cleanupFailure: Throwable) {
                handleAgentCleanupFailure(
                    primaryFailure = operationFailure,
                    cleanupFailure = cleanupFailure,
                    reportOrdinaryFailure = reportCleanupFailure,
                )
            }
        }
    }

    private fun stripe(chatId: String, clientMsgId: String): Mutex {
        val hash = 31 * chatId.hashCode() + clientMsgId.hashCode()
        return stripes[(hash and Int.MAX_VALUE) % stripes.size]
    }

    private fun fileRequestFingerprint(prepared: AgentPreparedUpload, contentType: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("teamtalk-agent-file-v1\u0000".encodeToByteArray())
        digest.update(prepared.contentSha256)
        digest.update(0.toByte())
        digest.update(prepared.originalFileName.encodeToByteArray())
        digest.update(0.toByte())
        digest.update(contentType.encodeToByteArray())
        digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(prepared.source.contentLength).array())
        return digest.digest()
    }

    private companion object {
        const val DEFAULT_STRIPES = 64
        const val DEFAULT_CONTENT_TYPE = "application/octet-stream"
    }
}

private val preparedUploadLogger = PlatformOnlyTkLogger("AgentFileSendCoordinator")

private fun reportPreparedUploadCleanupFailure(failure: Throwable) {
    preparedUploadLogger.fault("Prepared upload cleanup failed; staging recovery remains pending", failure)
}
