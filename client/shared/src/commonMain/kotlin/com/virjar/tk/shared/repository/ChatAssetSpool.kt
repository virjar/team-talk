package com.virjar.tk.shared.repository

import com.virjar.tk.protocol.body.AttachmentPolicy
import com.virjar.tk.shared.client.AccountDataOwner
import java.io.File

/** An immutable, account-owned source. Its identifier never exposes a picker URI or user path. */
data class StagedChatAsset(val sourceId: String, val length: Long, val sha256: String)

/**
 * Durable source files belong to drafts/upload commands, not a screen or a running HTTP attempt.
 * Only the owner which has durably removed the last reference may [delete] a source. Closing a
 * transport or losing authentication does not remove it. All methods except [stage] perform
 * bounded blocking filesystem work and belong on the caller's storage dispatcher.
 */
interface ChatAssetSpool {
    suspend fun stage(source: UploadSource): StagedChatAsset
    fun open(sourceId: String): UploadSource
    fun delete(sourceId: String)
    fun list(): List<StagedChatAsset>
}

/** Android supplies noBackupFilesDir; Desktop supplies its already claimed private installation root. */
expect fun createChatAssetSpool(
    dataDir: File,
    owner: AccountDataOwner,
    quotaBytes: Long = AttachmentPolicy.MAX_UPLOAD_BYTES,
    maxEntries: Int = 128,
): ChatAssetSpool

/** Exact namespace shared by construction and the existing account-ban cleanup hooks. */
fun chatAssetSpoolDirectories(owner: AccountDataOwner): List<String> =
    listOf("chat-assets", owner.deploymentFingerprint, owner.datasetId, owner.uid)
