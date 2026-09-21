package com.virjar.tk.desktop

import com.virjar.tk.protocol.body.EmbeddedAssetPresentation
import com.virjar.tk.desktop.media.DesktopSessionResources
import com.virjar.tk.app.ui.bridge.ChatAssetImportDelegate
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportEventSink
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportGateway
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportCoordinator
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportRegistration
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportSource
import com.virjar.tk.app.ui.bridge.EmbeddedAssetLocalSelection
import com.virjar.tk.shared.repository.asUploadSource
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.Closeable
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal fun desktopEmbeddedAssetSelection(
    file: File,
    presentation: EmbeddedAssetPresentation,
    source: EmbeddedAssetImportSource,
    deleteAfterImport: Boolean = false,
    displayName: String = file.name,
): EmbeddedAssetLocalSelection {
    require(file.isFile) { "文件不存在: ${file.name}" }
    return EmbeddedAssetLocalSelection(
        localReference = file.absolutePath,
        displayName = displayName,
        contentType = desktopContentType(file.name),
        size = file.length(),
        presentation = presentation,
        source = source,
        deleteAfterImport = deleteAfterImport,
    )
}

/** 释放 adapter 持有的剪贴板副本，绝不删除选择器/拖放的原始文件。 */
internal fun releaseDesktopEmbeddedAssetSelection(selection: EmbeddedAssetLocalSelection) {
    if (selection.deleteAfterImport) runCatching { File(selection.localReference).delete() }
}

/** 只转换本地 file:// 的拖放，并把每个被接纳的文件路由到共享导入器。 */
internal fun importDesktopDroppedAssetUris(
    uris: List<String>,
    gateway: EmbeddedAssetImportGateway,
): Boolean {
    var imported = false
    uris.forEach { rawUri ->
        val file = runCatching {
            val uri = java.net.URI(rawUri)
            uri.takeIf { it.scheme.equals("file", ignoreCase = true) }?.let(::File)
        }.getOrNull()?.takeIf(File::isFile) ?: return@forEach
        val presentation = if (desktopContentType(file.name).startsWith("image/", ignoreCase = true)) {
            EmbeddedAssetPresentation.IMAGE
        } else {
            EmbeddedAssetPresentation.FILE
        }
        gateway.import(
            desktopEmbeddedAssetSelection(
                file = file,
                presentation = presentation,
                source = EmbeddedAssetImportSource.DESKTOP_DROP,
            ),
        )
        imported = true
    }
    return imported
}

/** 只消费二进制剪贴板内容；普通文本粘贴仍由编辑器负责。 */
internal fun importDesktopClipboardAsset(gateway: EmbeddedAssetImportGateway): Boolean = runCatching {
    val contents = Toolkit.getDefaultToolkit().systemClipboard.getContents(null) ?: return@runCatching false
    if (contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
        @Suppress("UNCHECKED_CAST")
        val files = (contents.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
            .orEmpty()
            .filterIsInstance<File>()
            .filter(File::isFile)
        files.forEach { file ->
            val presentation = if (desktopContentType(file.name).startsWith("image/", ignoreCase = true)) {
                EmbeddedAssetPresentation.IMAGE
            } else {
                EmbeddedAssetPresentation.FILE
            }
            gateway.import(
                desktopEmbeddedAssetSelection(
                    file = file,
                    presentation = presentation,
                    source = EmbeddedAssetImportSource.DESKTOP_CLIPBOARD,
                ),
            )
        }
        return@runCatching files.isNotEmpty()
    }
    if (!contents.isDataFlavorSupported(DataFlavor.imageFlavor)) return@runCatching false
    val image = contents.getTransferData(DataFlavor.imageFlavor) as? java.awt.Image
        ?: return@runCatching false
    val width = image.getWidth(null)
    val height = image.getHeight(null)
    if (width <= 0 || height <= 0) return@runCatching false
    val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = buffered.createGraphics()
    try {
        graphics.drawImage(image, 0, 0, null)
    } finally {
        graphics.dispose()
    }
    val temporary = File.createTempFile("teamtalk-clipboard-", ".png")
    if (!ImageIO.write(buffered, "png", temporary)) {
        temporary.delete()
        return@runCatching false
    }
    gateway.import(
        desktopEmbeddedAssetSelection(
            file = temporary,
            presentation = EmbeddedAssetPresentation.IMAGE,
            source = EmbeddedAssetImportSource.DESKTOP_CLIPBOARD,
            deleteAfterImport = true,
            displayName = "剪贴板图片.png",
        ),
    )
    true
}.getOrDefault(false)

/** Swing picker/drop/clipboard adapt sources; app owns the shared import lifecycle. */
internal class DesktopEmbeddedAssetImportGateway(
    resources: DesktopSessionResources,
    transfer: DesktopFileTransfer,
    publishOnUi: (() -> Unit) -> Unit,
    durableImports: ChatAssetImportDelegate? = null,
) : EmbeddedAssetImportGateway, Closeable {
    private val scope = resources.childScope("embedded-asset-import")
    private val imports = EmbeddedAssetImportCoordinator<File>(
        launch = { action -> scope.launch { action() } },
        publishOnUi = { action -> publishOnUi { if (resources.canDeliverUiResult()) action() } },
        ensureOpen = resources::ensureOpen,
        prepare = { selection -> File(selection.localReference).also {
            require(it.isFile) { "文件不存在: ${selection.displayName}" }
        } },
        source = { it.asUploadSource() },
        upload = { file, selection, identity, progress ->
            transfer.uploadWithMeta(file, selection.contentType, identity, selection.displayName, progress)
        },
        releaseSelection = ::releaseDesktopEmbeddedAssetSelection,
        durableImports = durableImports,
    )
    override fun bind(ownerKey: String, sink: EmbeddedAssetImportEventSink, acceptNewImports: Boolean) =
        imports.bind(ownerKey, sink, acceptNewImports)
    override fun select(presentation: EmbeddedAssetPresentation) {
        val binding = imports.captureForImport() ?: return
        val file = when (presentation) {
            EmbeddedAssetPresentation.IMAGE -> DesktopFilePicker.chooseImage()
            EmbeddedAssetPresentation.FILE -> DesktopFilePicker.chooseFile("插入文件")
        } ?: return
        imports.import(desktopEmbeddedAssetSelection(file, presentation, EmbeddedAssetImportSource.DESKTOP_PICKER), binding)
    }
    override fun import(selection: EmbeddedAssetLocalSelection) = imports.import(selection)
    override fun cancel(jobId: String) = imports.cancel(jobId)
    override fun retry(jobId: String) = imports.retry(jobId)
    override fun close() { imports.close(); scope.cancel() }
}
