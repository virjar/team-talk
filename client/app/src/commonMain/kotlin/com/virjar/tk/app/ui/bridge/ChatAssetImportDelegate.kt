package com.virjar.tk.app.ui.bridge

import com.virjar.tk.shared.repository.UploadSource

/** Optional durable chat path. Document imports and message edits keep their existing owner. */
interface ChatAssetImportDelegate {
    fun handles(ownerKey: String): Boolean

    /** Replay persisted jobs to this editor; must not insert a second Markdown placement. */
    fun bind(ownerKey: String, sink: EmbeddedAssetImportEventSink): EmbeddedAssetImportRegistration

    /** Return only after immutable source bytes and the upload command have both been persisted. */
    suspend fun prepare(
        ownerKey: String,
        assetId: String,
        source: UploadSource,
        selection: EmbeddedAssetLocalSelection,
    )

    fun cancel(assetId: String): Boolean
    fun retry(assetId: String): Boolean

    /** Preparation failed before a recoverable Markdown node could be admitted. */
    fun preparationFailed(ownerKey: String)
}
