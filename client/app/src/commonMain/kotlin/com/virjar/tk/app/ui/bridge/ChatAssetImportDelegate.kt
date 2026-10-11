package com.virjar.tk.app.ui.bridge

import com.virjar.tk.shared.repository.UploadSource

/** 可选的聊天持久化通道。文档导入与消息编辑保留其既有 owner。 */
interface ChatAssetImportDelegate {
    fun handles(ownerKey: String): Boolean

    /** 向该编辑器重放持久化的任务；不得插入第二个 Markdown placement。 */
    fun bind(ownerKey: String, sink: EmbeddedAssetImportEventSink): EmbeddedAssetImportRegistration

    /** 只有在不可变源字节与上传命令都已持久化之后才返回。 */
    suspend fun prepare(
        ownerKey: String,
        assetId: String,
        source: UploadSource,
        selection: EmbeddedAssetLocalSelection,
    )

    fun cancel(assetId: String): Boolean
    fun retry(assetId: String): Boolean

    /** 准备失败时还未准入任何可恢复的 Markdown 节点。 */
    fun preparationFailed(ownerKey: String)
}
