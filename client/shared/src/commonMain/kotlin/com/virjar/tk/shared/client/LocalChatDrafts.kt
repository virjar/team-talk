package com.virjar.tk.shared.client

import com.virjar.tk.protocol.model.EmbeddedAsset
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** 本安装的完整聊天创作上下文；平台选择令牌和外部路径不进入此模型。 */
@Serializable
data class ChatDraftSnapshot(
    val chatId: String,
    val revision: Long = 0,
    val markdown: String = "",
    val assets: List<EmbeddedAsset> = emptyList(),
    val pendingAssetIds: List<String> = emptyList(),
    val mode: Int = 0,
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
    val replyToClientMsgId: String? = null,
    val replyToServerSeq: Long = 0,
    /** 当前编辑帧的服务器草稿基线；不能用本机 revision 代替。 */
    val sharedRevision: Long? = null,
)

@Serializable
enum class ChatAssetUploadState { QUEUED, UPLOADING, READY, FAILED }

/** 上传 identity 在第一次请求之前持久化，READY 仍然持有私有源文件。 */
@Serializable
data class ChatAssetUpload(
    val assetId: String,
    val chatId: String,
    val sourceId: String,
    val length: Long,
    val sha256: String,
    val fileName: String,
    val contentType: String,
    val isImage: Boolean,
    val uploadId: String,
    val issuedAt: Long,
    val state: ChatAssetUploadState = ChatAssetUploadState.QUEUED,
    val attempt: Long = 0,
    val nextAttemptAt: Long = 0,
    val asset: EmbeddedAsset? = null,
    val failure: String? = null,
    val repairForClientMsgId: String? = null,
)

/** 完整聊天草稿的读取与保存；所有同步 API 在 storage dispatcher 调用。 */
interface LocalChatDrafts {
    val changes: StateFlow<Long>
    fun get(chatId: String): ChatDraftSnapshot?
    fun maxRevision(): Long
    fun save(snapshot: ChatDraftSnapshot): ChatDraftSnapshot
}

/**
 * 聊天内嵌资产上传引擎的持久化意图：注册、认领、终态与恢复全部在此闭环，
 * 与草稿正文是两个独立的生命周期。所有同步 API 在 storage dispatcher 调用。
 */
interface LocalChatAssetUploads {
    /** 与草稿视图共享同一存储的变更流；单一实现同时满足两个接口的同名声明。 */
    val changes: StateFlow<Long>
    fun jobs(chatId: String? = null): List<ChatAssetUpload>
    fun retainedSourceIds(): Set<String>
    fun upload(assetId: String): ChatAssetUpload?
    fun nextUploadWakeAt(includeRetries: Boolean): Long?
    fun outgoingAssets(chatId: String, clientMsgId: String): List<ChatAssetUpload>
    fun prepareReplacement(ownerUid: String, chatId: String, clientMsgId: String, assetIds: Set<String>? = null): List<ChatAssetUpload>
    fun register(job: ChatAssetUpload): ChatAssetUpload
    fun claimNext(now: Long): ChatAssetUpload?
    fun complete(assetId: String, attempt: Long, asset: EmbeddedAsset): Boolean
    fun fail(assetId: String, attempt: Long, reason: String, retryAt: Long): Boolean
    fun retry(assetId: String)
    fun remove(assetId: String)
    fun expireUploads(now: Long)
    fun recoverUploads()
}
