package com.virjar.tk.desktop

import com.virjar.tk.desktop.media.DesktopMediaDownloadSizeException
import com.virjar.tk.desktop.media.DesktopMediaFileLease
import com.virjar.tk.protocol.model.Attachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 从选择目标到复制结束仅此处持有缓存租约；取消选择、会话关闭和复制失败均释放。 */
internal suspend fun exportDesktopAttachment(
    lease: DesktopMediaFileLease,
    attachment: Attachment,
    ensureOpen: () -> Unit,
    onFailure: suspend (Exception) -> Unit,
    selectDestination: suspend (Attachment) -> File? = ::selectDesktopAttachmentDestination,
    copyFile: suspend (File, File) -> Unit = { source, target ->
        withContext(Dispatchers.IO) {
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    },
) {
    var failureToReport: Exception? = null
    lease.use {
        try {
            currentCoroutineContext().ensureActive()
            ensureOpen()
            if (!lease.file.isFile || lease.file.length() != attachment.size) {
                throw DesktopMediaDownloadSizeException("缓存文件大小与附件声明不一致")
            }
            val target = selectDestination(attachment) ?: return
            currentCoroutineContext().ensureActive()
            ensureOpen()
            copyFile(lease.file, target)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failureToReport = failure
        }
    }
    // 失败反馈不再阻塞（应用内通知）；失败的复制已经不再持有缓存文件。
    failureToReport?.let { onFailure(it) }
}

/**
 * AWT 原生保存面板（macOS NSSavePanel、Windows/Linux 原生对话框），与文件选择路径
 * 同一套系统面板；取消时 file/directory 为 null。模态等待仍在 EDT 上运行；
 * 取消导出也会关闭其窗口，释放挂起的工作。
 */
private suspend fun selectDesktopAttachmentDestination(attachment: Attachment): File? =
    withContext(Dispatchers.Swing) {
        suspendCancellableCoroutine { continuation ->
            val owner = Frame()
            val dialog = FileDialog(owner, "保存到设备", FileDialog.SAVE).apply {
                file = attachment.name
            }
            continuation.invokeOnCancellation {
                EventQueue.invokeLater {
                    dialog.dispose()
                    owner.dispose()
                }
            }
            if (!continuation.isActive) return@suspendCancellableCoroutine
            try {
                dialog.isVisible = true
                val directory = dialog.directory
                val selected = dialog.file
                continuation.resume(
                    if (directory != null && selected != null) File(directory, selected) else null,
                )
            } catch (failure: Exception) {
                continuation.resumeWithException(failure)
            } finally {
                dialog.dispose()
                owner.dispose()
            }
        }
    }
