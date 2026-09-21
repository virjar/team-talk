@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.virjar.tk.ios

import com.virjar.tk.app.ui.bridge.*
import com.virjar.tk.protocol.body.EmbeddedAssetPresentation
import com.virjar.tk.shared.platform.*
import kotlinx.coroutines.*
import platform.Foundation.*
import platform.UIKit.*
import platform.UniformTypeIdentifiers.*
import platform.darwin.NSObject

/** UIKit owns pickers and export sheets; their results remain bound to the creating session. */
internal class IosNativeMedia(private val resources: IosMediaResources) : AutoCloseable {
    private var pickerDelegate: NSObject? = null
    private var presented: UIViewController? = null
    private var exportLease: IosMediaLease? = null
    private var preview: UIDocumentInteractionController? = null
    private var previewDelegate: NSObject? = null
    private var previewLease: IosMediaLease? = null
    private var preparingExport: Job? = null
    private var closed = false
    private val temporary = resources.stagingDirectory

    private fun canPresent(): Boolean = !closed && resources.canDeliverUiResult() &&
        presented == null && preview == null && preparingExport == null

    fun select(presentation: EmbeddedAssetPresentation, selected: (EmbeddedAssetLocalSelection) -> Unit) {
        if (!canPresent()) return
        if (presentation == EmbeddedAssetPresentation.IMAGE) selectVisual(false, false, selected)
        else {
            val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeItem), asCopy = true)
            picker.allowsMultipleSelection = false
            val delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
                override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
                    finishPicker()
                    val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
                    deliver(url, presentation, selected)
                }
                override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) = finishPicker()
            }
            pickerDelegate = delegate
            picker.delegate = delegate
            show(picker)
        }
    }

    fun selectVisual(video: Boolean, camera: Boolean, selected: (EmbeddedAssetLocalSelection) -> Unit) {
        if (!canPresent()) return
        val source = if (camera) UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera
            else UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypePhotoLibrary
        if (!UIImagePickerController.isSourceTypeAvailable(source)) {
            showIosError("此设备不支持所选拍摄方式")
            return
        }
        val picker = UIImagePickerController()
        picker.sourceType = source
        picker.mediaTypes = listOf(if (video) "public.movie" else "public.image")
        if (video) picker.videoQuality = UIImagePickerControllerQualityTypeHigh
        val delegate = object : NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {
            override fun imagePickerController(picker: UIImagePickerController, didFinishPickingMediaWithInfo: Map<Any?, *>) {
                val url = didFinishPickingMediaWithInfo[if (video) UIImagePickerControllerMediaURL else UIImagePickerControllerImageURL] as? NSURL
                if (url != null) {
                    deliver(url, if (video) EmbeddedAssetPresentation.FILE else EmbeddedAssetPresentation.IMAGE, selected)
                } else {
                    val image = didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage
                    val data = image?.let { UIImageJPEGRepresentation(it, 0.95) }
                    if (data != null) {
                        val file = temporary.resolve("${platformRandomUuid()}.jpg")
                        if (data.writeToFile(file.path, atomically = true)) deliverOwned(file, "照片.jpg", EmbeddedAssetPresentation.IMAGE, selected)
                        else showIosError("无法保存所选照片")
                    } else showIosError("无法读取所选媒体")
                }
                picker.dismissViewControllerAnimated(true, completion = null)
                finishPicker()
            }
            override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
                picker.dismissViewControllerAnimated(true, completion = null)
                finishPicker()
            }
        }
        pickerDelegate = delegate
        picker.delegate = delegate
        show(picker)
    }

    private fun deliver(url: NSURL, presentation: EmbeddedAssetPresentation, selected: (EmbeddedAssetLocalSelection) -> Unit) {
        val accessed = url.startAccessingSecurityScopedResource()
        // Start immediately so cancellation before the first dispatch still releases the security scope.
        resources.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var pending: PlatformFile? = null
            try {
                val name = url.lastPathComponent?.takeIf { it.isNotBlank() } ?: "附件"
                withContext(Dispatchers.IO) {
                    val suffix = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 12 && it.all(Char::isLetterOrDigit) }
                    val file = temporary.resolve(platformRandomUuid() + (suffix?.let { ".$it" } ?: ""))
                    pending = file
                    check(NSFileManager.defaultManager.copyItemAtURL(url, NSURL.fileURLWithPath(file.path), null)) { "无法读取所选文件" }
                }
                deliverOwned(checkNotNull(pending), name, presentation, selected)
                pending = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (resources.canDeliverUiResult()) showIosError("无法读取所选文件，请重新选择") }
            finally {
                pending?.delete()
                if (accessed) url.stopAccessingSecurityScopedResource()
            }
        }
    }

    private fun deliverOwned(file: PlatformFile, name: String, presentation: EmbeddedAssetPresentation,
        selected: (EmbeddedAssetLocalSelection) -> Unit) {
        if (!resources.canDeliverUiResult()) { file.delete(); return }
        try {
            selected(EmbeddedAssetLocalSelection(file.path, name, iosContentType(name), file.length(), presentation,
                EmbeddedAssetImportSource.IOS_PICKER, deleteAfterImport = true))
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        }
    }

    fun importClipboard(gateway: EmbeddedAssetImportGateway): Boolean {
        if (!resources.canDeliverUiResult()) return false
        val image = UIPasteboard.generalPasteboard.image ?: return false
        val data = UIImagePNGRepresentation(image) ?: return false
        val file = temporary.resolve("${platformRandomUuid()}.png")
        if (!data.writeToFile(file.path, atomically = true)) { file.delete(); return false }
        try {
            gateway.import(EmbeddedAssetLocalSelection(file.path, "剪贴板图片.png", "image/png", file.length(),
                EmbeddedAssetPresentation.IMAGE, EmbeddedAssetImportSource.IOS_CLIPBOARD, deleteAfterImport = true))
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        }
        return true
    }

    suspend fun preview(lease: IosMediaLease, name: String) = presentFile(lease, name, previewFile = true)

    suspend fun share(lease: IosMediaLease, name: String) = presentFile(lease, name, previewFile = false)

    /** UIKit gets a named, account-owned copy; the authenticated cache keeps its hashed filename. */
    private suspend fun presentFile(lease: IosMediaLease, name: String, previewFile: Boolean) {
        if (!canPresent()) { lease.close(); return }
        val operation = currentCoroutineContext().job
        preparingExport = operation
        val directory = temporary.resolve("export-${platformRandomUuid()}")
        val staged = IosMediaLease(directory.resolve(iosExportFileName(name))) { directory.deleteRecursively() }
        var transferred = false
        try {
            withContext(Dispatchers.IO) {
                resources.ensureOpen()
                // Do not recreate a retired account's staging parents. Both directory and file are private.
                check(directory.mkdir()) { "无法准备附件分享" }
                lease.file.copyTo(staged.file)
                currentCoroutineContext().ensureActive()
            }
            currentCoroutineContext().ensureActive()
            if (closed || !resources.canDeliverUiResult()) return
            if (previewFile) presentPreview(staged) else presentShare(staged)
            transferred = true
        } finally {
            lease.close()
            // Includes a completed IO copy discarded by prompt cancellation before returning to Main.
            if (!transferred) staged.close()
            if (preparingExport === operation) preparingExport = null
        }
    }

    private fun presentPreview(lease: IosMediaLease) {
        val controller = UIDocumentInteractionController.interactionControllerWithURL(NSURL.fileURLWithPath(lease.file.path))
        val delegate = object : NSObject(), UIDocumentInteractionControllerDelegateProtocol {
            override fun documentInteractionControllerViewControllerForPreview(controller: UIDocumentInteractionController): UIViewController = iosPresenter()
            override fun documentInteractionControllerDidEndPreview(controller: UIDocumentInteractionController) {
                if (preview === controller) {
                    previewLease = null; preview = null; previewDelegate = null
                    lease.close()
                }
            }
        }
        preview = controller; previewDelegate = delegate; previewLease = lease
        controller.delegate = delegate
        if (!controller.presentPreviewAnimated(true)) {
            preview = null; previewDelegate = null; previewLease = null
            presentShare(lease)
        }
    }

    /** Completion covers successful exports and cancellation; retirement also dismisses and releases it. */
    private fun presentShare(lease: IosMediaLease) {
        val sheet = UIActivityViewController(activityItems = listOf(NSURL.fileURLWithPath(lease.file.path)), applicationActivities = null)
        exportLease = lease
        sheet.completionWithItemsHandler = { _, _, _, _ ->
            sheet.completionWithItemsHandler = null
            lease.close()
            if (exportLease === lease) exportLease = null
            if (presented === sheet) presented = null
        }
        // iPad requires an explicit anchor for an activity sheet.
        val presenter = iosPresenter()
        sheet.popoverPresentationController?.sourceView = presenter.view
        sheet.popoverPresentationController?.sourceRect = presenter.view.bounds
        show(sheet)
    }
    private fun show(controller: UIViewController) {
        presented = controller
        iosPresenter().presentViewController(controller, animated = true, completion = null)
    }
    private fun finishPicker() { presented = null; pickerDelegate = null }
    override fun close() {
        closed = true
        preparingExport?.cancel(); preparingExport = null
        preview?.dismissPreviewAnimated(false)
        preview = null; previewDelegate = null; previewLease?.close(); previewLease = null
        (presented as? UIActivityViewController)?.completionWithItemsHandler = null
        presented?.dismissViewControllerAnimated(false, completion = null)
        presented = null
        pickerDelegate = null
        exportLease?.close(); exportLease = null
    }
}

/** Preserve Unicode names and the extension, excluding path components and platform-invalid characters. */
private fun iosExportFileName(name: String): String {
    val clean = name.map { if (it < ' ' || it == '\u007f' || it in "<>:\"/\\|?*") '_' else it }
        .joinToString("").trim().trimEnd('.').ifBlank { "附件" }
    val extension = clean.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.encodeToByteArray().size <= 16 }
        ?.let { ".$it" }.orEmpty()
    var stem = clean.removeSuffix(extension)
    // APFS limits a component to 255 UTF-8 bytes; leave margin and never split a surrogate pair.
    while ((stem + extension).encodeToByteArray().size > 240) {
        val paired = stem.length >= 2 && stem[stem.lastIndex].isLowSurrogate() && stem[stem.lastIndex - 1].isHighSurrogate()
        stem = stem.dropLast(if (paired) 2 else 1)
    }
    return stem + extension
}

internal fun iosContentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"
    "heic", "heif" -> "image/heic"; "mov" -> "video/quicktime"; "mp4", "m4v" -> "video/mp4"
    "m4a", "aac" -> "audio/mp4"; "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"
    "pdf" -> "application/pdf"; "txt", "md", "log" -> "text/plain"; "json" -> "application/json"
    else -> "application/octet-stream"
}
internal fun iosPresenter(): UIViewController {
    var controller = checkNotNull(IosApplicationRuntime.rootViewController)
    while (controller.presentedViewController != null) controller = checkNotNull(controller.presentedViewController)
    return controller
}
internal fun showIosError(message: String) {
    val dialog = UIAlertController.alertControllerWithTitle("操作未完成", message, UIAlertControllerStyleAlert)
    dialog.addAction(UIAlertAction.actionWithTitle("确定", UIAlertActionStyleDefault, null))
    iosPresenter().presentViewController(dialog, animated = true, completion = null)
}
internal fun openIosUrl(url: String) {
    val parsed = NSURL.URLWithString(url) ?: return
    if (url != UIApplicationOpenSettingsURLString && parsed.scheme?.lowercase() !in setOf("http", "https", "mailto")) return
    UIApplication.sharedApplication.openURL(parsed, options = emptyMap<Any?, Any>(), completionHandler = null)
}
