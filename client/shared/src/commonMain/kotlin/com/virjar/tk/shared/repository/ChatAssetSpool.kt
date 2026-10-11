package com.virjar.tk.shared.repository

import com.virjar.tk.shared.platform.*
import com.virjar.tk.protocol.body.AttachmentPolicy
import com.virjar.tk.shared.client.AccountDataOwner
import com.virjar.tk.shared.platform.PlatformFile as File

/** 不可变的、账号所有的源；其标识符绝不暴露选择器 URI 或用户路径。 */
data class StagedChatAsset(val sourceId: String, val length: Long, val sha256: String)

/**
 * 持久源文件归属于草稿/上传命令，而不是某个界面或一次进行中的 HTTP 尝试。
 * 只有已持久移除最后一个引用的 owner 才能 [delete] 某个源；关闭 transport
 * 或失去认证都不会移除它。除 [stage] 外的所有方法都执行有界阻塞的文件系统
 * 操作，应运行在调用方的存储 dispatcher 上。
 */
interface ChatAssetSpool {
    suspend fun stage(source: UploadSource): StagedChatAsset
    fun open(sourceId: String): UploadSource
    fun delete(sourceId: String)
    fun list(): List<StagedChatAsset>
}

/** 宿主提供其私有安装根目录；账号归属与磁盘格式由公共代码共享。 */
expect fun createChatAssetSpool(
    dataDir: File,
    owner: AccountDataOwner,
    quotaBytes: Long = AttachmentPolicy.MAX_UPLOAD_BYTES,
    maxEntries: Int = 128,
): ChatAssetSpool

/** 构造与既有账号封禁清理钩子共享的确切命名空间。 */
fun chatAssetSpoolDirectories(owner: AccountDataOwner): List<String> =
    listOf("chat-assets", owner.deploymentFingerprint, owner.datasetId, owner.uid)
