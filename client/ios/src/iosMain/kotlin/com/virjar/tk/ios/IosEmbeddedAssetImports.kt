package com.virjar.tk.ios

import com.virjar.tk.protocol.body.EmbeddedAssetPresentation
import com.virjar.tk.app.ui.bridge.*
import com.virjar.tk.shared.platform.*
import com.virjar.tk.shared.repository.asUploadSource
import kotlinx.coroutines.*

internal fun releaseIosEmbeddedAssetSelection(selection: EmbeddedAssetLocalSelection) {
    if (selection.deleteAfterImport) PlatformFile(selection.localReference).delete()
}

/** UIKit selection remains platform-owned; import ordering and retry belong to app. */
internal class IosEmbeddedAssetImportGateway(
    private val resources: IosMediaResources,
    transfer: IosFileTransfer,
    private val native: IosNativeMedia,
    publishOnUi: (() -> Unit) -> Unit,
    durableImports: ChatAssetImportDelegate? = null,
) : EmbeddedAssetImportGateway, AutoCloseable {
    private val scope = resources.childScope("embedded-asset-import")
    private val imports = EmbeddedAssetImportCoordinator<PlatformFile>(
        launch = { action -> scope.launch { action() } },
        publishOnUi = { action -> publishOnUi { if (resources.canDeliverUiResult()) action() } },
        ensureOpen = resources::ensureOpen,
        prepare = { selection -> PlatformFile(selection.localReference).also {
            require(it.isFile) { "文件不存在: ${selection.displayName}" }
        } },
        source = { it.asUploadSource() },
        upload = { file, selection, identity, progress ->
            transfer.uploadWithMeta(file, selection.contentType, identity, selection.displayName, progress)
        },
        releaseSelection = ::releaseIosEmbeddedAssetSelection,
        durableImports = durableImports,
    )
    override fun bind(ownerKey: String, sink: EmbeddedAssetImportEventSink, acceptNewImports: Boolean) =
        imports.bind(ownerKey, sink, acceptNewImports)
    override fun select(presentation: EmbeddedAssetPresentation) {
        val binding = imports.captureForImport() ?: return
        native.select(presentation) { selection ->
            if (resources.canDeliverUiResult() && imports.isCurrent(binding)) imports.import(selection, binding)
            else releaseIosEmbeddedAssetSelection(selection)
        }
    }
    override fun import(selection: EmbeddedAssetLocalSelection) = imports.import(selection)
    override fun cancel(jobId: String) = imports.cancel(jobId)
    override fun retry(jobId: String) = imports.retry(jobId)
    override fun close() { imports.close(); scope.cancel() }
}
