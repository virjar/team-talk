@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.virjar.tk.ios

import com.virjar.tk.app.media.attachmentContentType
import com.virjar.tk.app.ui.bridge.*
import com.virjar.tk.protocol.body.EmbeddedAssetPresentation
import com.virjar.tk.shared.platform.*
import kotlinx.coroutines.*
import platform.Foundation.*
import platform.QuickLook.*
import platform.UIKit.*
import platform.UniformTypeIdentifiers.*
import platform.darwin.NSObject

/** UIKit owns pickers and export sheets; their results remain bound to the creating session. */
internal class IosNativeMedia(private val resources: IosMediaResources) : AutoCloseable {
    private var pickerDelegate: NSObject? = null
    private var presented: UIViewController? = null
    private var cameraResultHandler: ((EmbeddedAssetLocalSelection) -> Unit)? = null
    private var cameraPresented = false
    private var exportLease: IosMediaLease? = null
    private var previewDataSource: NSObject? = null
    private var previewDelegate: NSObject? = null
    private var previewLease: IosMediaLease? = null
    private var preparingExport: Job? = null
    private var closed = false
    private val temporary = resources.stagingDirectory

    private fun canPresent(): Boolean = !closed && !cameraPresented && resources.canDeliverUiResult() &&
        presented == null && previewDataSource == null && preparingExport == null

    /**
     * 应用内相机：点按拍照/长按录像，物理方向判定与震动在 Swift 控制器内完成。
     * 结果复用选择器管线：照片进嵌入资产导入（与相册选图同链路），视频按文件发送。
     */
    fun capture(selected: (EmbeddedAssetLocalSelection) -> Unit) {
        if (!canPresent()) return
        cameraResultHandler = selected
        cameraPresented = true
        val capturedDirectory = temporary.resolve("captured").also { check(it.mkdirs() || it.isDirectory) }
        IosApplicationRuntime.openChatCamera(
            capturedDirectory.path,
            { path -> deliverCameraResult(path, isImage = true) },
            { path -> deliverCameraResult(path, isImage = false) },
            {
                cameraPresented = false
                cameraResultHandler = null
            },
        )
    }

    private fun deliverCameraResult(path: String, isImage: Boolean) {
        cameraPresented = false
        val handler = cameraResultHandler
        cameraResultHandler = null
        if (handler == null) return
        val file = PlatformFile(path)
        if (!file.exists()) return
        val name = if (isImage) "拍摄照片.jpg" else "拍摄视频.mp4"
        handler(
            EmbeddedAssetLocalSelection(
                localReference = path,
                displayName = name,
                contentType = attachmentContentType(name),
                size = file.length(),
                presentation = if (isImage) EmbeddedAssetPresentation.IMAGE else EmbeddedAssetPresentation.FILE,
                source = EmbeddedAssetImportSource.IOS_PICKER,
                deleteAfterImport = true,
            ),
        )
    }

    fun select(presentation: EmbeddedAssetPresentation, selected: (EmbeddedAssetLocalSelection) -> Unit) {
        if (!canPresent()) return
        if (presentation == EmbeddedAssetPresentation.IMAGE) selectImage(false, selected)
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

    /**
     * 相册混选：图片与视频一次可选，按系统返回的媒体类型分流——图片走嵌入资产导入，
     * 视频按文件发送。
     */
    fun selectAlbumMedia(selected: (EmbeddedAssetLocalSelection) -> Unit) {
        if (!canPresent()) return
        val source = UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypePhotoLibrary
        if (!UIImagePickerController.isSourceTypeAvailable(source)) {
            showIosError("此设备没有可用的相册")
            return
        }
        val picker = UIImagePickerController()
        picker.sourceType = source
        picker.mediaTypes = listOf("public.image", "public.movie")
        picker.videoQuality = UIImagePickerControllerQualityTypeHigh
        val delegate = object : NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {
            override fun imagePickerController(picker: UIImagePickerController, didFinishPickingMediaWithInfo: Map<Any?, *>) {
                val mediaType = didFinishPickingMediaWithInfo[UIImagePickerControllerMediaType] as? String
                picker.dismissViewControllerAnimated(true, completion = null)
                finishPicker()
                if (mediaType == "public.movie") {
                    val url = didFinishPickingMediaWithInfo[UIImagePickerControllerMediaURL] as? NSURL
                    if (url != null) deliver(url, EmbeddedAssetPresentation.FILE, selected)
                    else showIosError("无法读取所选视频")
                } else {
                    val url = didFinishPickingMediaWithInfo[UIImagePickerControllerImageURL] as? NSURL
                    if (url != null) {
                        deliver(url, EmbeddedAssetPresentation.IMAGE, selected)
                    } else {
                        val image = didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage
                        val data = image?.let { UIImageJPEGRepresentation(it, 0.95) }
                        if (data != null) {
                            val file = temporary.resolve("${platformRandomUuid()}.jpg")
                            if (data.writeToFile(file.path, atomically = true)) {
                                deliverOwned(file, "图片.jpg", EmbeddedAssetPresentation.IMAGE, selected)
                            } else showIosError("无法读取所选图片")
                        } else showIosError("无法读取所选媒体")
                    }
                }
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

    fun selectImage(camera: Boolean, selected: (EmbeddedAssetLocalSelection) -> Unit) {
        if (!canPresent()) return
        val source = if (camera) UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera
            else UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypePhotoLibrary
        if (!UIImagePickerController.isSourceTypeAvailable(source)) {
            showIosError("此设备不支持所选拍摄方式")
            return
        }
        val picker = UIImagePickerController()
        picker.sourceType = source
        picker.mediaTypes = listOf("public.image")
        val delegate = object : NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {
            override fun imagePickerController(picker: UIImagePickerController, didFinishPickingMediaWithInfo: Map<Any?, *>) {
                val url = didFinishPickingMediaWithInfo[UIImagePickerControllerImageURL] as? NSURL
                if (url != null) {
                    deliver(url, EmbeddedAssetPresentation.IMAGE, selected)
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
            selected(EmbeddedAssetLocalSelection(file.path, name, attachmentContentType(name), file.length(), presentation,
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
        // 调用方在下载工作线程进入；UIKit 呈现与本类主线程独占状态必须收敛到 Main。
        withContext(Dispatchers.Main) {
            retireStalePresentation()
            if (!canPresent()) {
                NSLog(
                    "IosNativeMedia: present declined: " +
                        listOf(
                            "closed" to closed,
                            "camera" to cameraPresented,
                            "presented" to (presented != null),
                            "preview" to (previewDataSource != null),
                            "preparing" to (preparingExport != null),
                        ).filter { it.second }.joinToString { it.first },
                )
                lease.close(); return@withContext
            }
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
                if (closed || !resources.canDeliverUiResult()) return@withContext
                if (previewFile) presentPreview(staged) else presentShare(staged)
                transferred = true
            } finally {
                lease.close()
                // Includes a completed IO copy discarded by prompt cancellation before returning to Main.
                if (!transferred) staged.close()
                if (preparingExport === operation) preparingExport = null
            }
        }
    }

    /**
     * iOS 26+ 的 QuickLook 预览内容由独立扩展进程渲染，UIDocumentInteractionController
     * 的 DidEndPreview 与 QLPreviewController 的 previewControllerDidDismiss 均实测不再
     * 可靠送达，残留状态会永久堵死后续打开（第二次点击静默失效）。关闭检测因此不依赖
     * QuickLook 委托：由 VC 自身生命周期（viewDidDisappear + isBeingDismissed）与
     * "无 window 即陈旧"自愈兜底。
     */
    private fun presentPreview(lease: IosMediaLease) {
        // previewItemTitle 有默认实现（取 URL 文件名）；暂存文件已以显示名命名，标题随之正确。
        val item = object : NSObject(), QLPreviewItemProtocol {
            override fun previewItemURL(): NSURL? = NSURL.fileURLWithPath(lease.file.path)
        }
        val controller = StagedFilePreviewController()
        val dataSource = object : NSObject(), QLPreviewControllerDataSourceProtocol {
            override fun numberOfPreviewItemsInPreviewController(controller: QLPreviewController): Long = 1
            override fun previewController(controller: QLPreviewController, previewItemAtIndex: Long): QLPreviewItemProtocol = item
        }
        val delegate = object : NSObject(), QLPreviewControllerDelegateProtocol {
            override fun previewControllerDidDismiss(controller: QLPreviewController) {
                NSLog("IosNativeMedia: preview delegate didDismiss")
                retirePresentation(controller)
            }
        }
        controller.dataSource = dataSource
        controller.delegate = delegate
        previewDataSource = dataSource
        previewDelegate = delegate
        previewLease = lease
        show(controller)
    }

    /** 关闭检测的第一道兜底：本进程 VC 生命周期，UIKit 保证调用，与 QuickLook 委托无关。 */
    private inner class StagedFilePreviewController : QLPreviewController(nibName = null, bundle = null) {
        override fun viewDidDisappear(animated: Boolean) {
            super.viewDidDisappear(animated)
            if (presented === this && isBeingDismissed()) {
                NSLog("IosNativeMedia: preview vc viewDidDisappear retire")
                retirePresentation(this)
            }
        }
    }

    /** 幂等退役当前呈现：仅当 controller 仍是活跃呈现时清理预览状态与租约。 */
    private fun retirePresentation(controller: UIViewController) {
        if (presented !== controller) return
        presented = null
        previewDataSource = null
        previewDelegate = null
        previewLease?.close()
        previewLease = null
    }

    /** 第二道兜底：即便所有关闭回调都未送达，脱离 window 的呈现视为陈旧，下一次动作自愈。 */
    private fun retireStalePresentation() {
        val controller = presented ?: return
        if (controller.isViewLoaded() && controller.view.window != null) return
        NSLog("IosNativeMedia: retire stale presentation without window")
        retirePresentation(controller)
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
        previewDataSource = null
        (presented as? UIActivityViewController)?.completionWithItemsHandler = null
        presented?.dismissViewControllerAnimated(false, completion = null)
        presented = null
        pickerDelegate = null
        previewDelegate = null
        previewLease?.close(); previewLease = null
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
