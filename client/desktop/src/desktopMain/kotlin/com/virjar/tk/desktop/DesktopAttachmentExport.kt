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
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
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
    // 错误提示可能等待用户确认；失败的复制已经不再持有缓存文件。
    failureToReport?.let { onFailure(it) }
}

internal suspend fun showDesktopAttachmentExportFailure(failure: Exception, canShow: () -> Boolean) {
    withContext(Dispatchers.Swing) {
        if (!canShow()) return@withContext
        suspendCancellableCoroutine { continuation ->
            val dialog = JOptionPane(
                "保存失败：${failure.message ?: "无法写入所选位置"}",
                JOptionPane.ERROR_MESSAGE,
            ).createDialog(null, "保存到设备")
            continuation.invokeOnCancellation { EventQueue.invokeLater(dialog::dispose) }
            try {
                if (continuation.isActive) dialog.isVisible = true
                continuation.resume(Unit)
            } catch (failure: Exception) {
                continuation.resumeWithException(failure)
            } finally {
                dialog.dispose()
            }
        }
    }
}

/** Swing 模态选择器仍在 EDT 上运行；取消导出也会关闭其窗口，释放挂起的工作。 */
private suspend fun selectDesktopAttachmentDestination(attachment: Attachment): File? =
    withContext(Dispatchers.Swing) {
        suspendCancellableCoroutine { continuation ->
            val chooser = JFileChooser().apply {
                dialogTitle = "保存到设备"
                selectedFile = File(attachment.name)
            }
            continuation.invokeOnCancellation {
                EventQueue.invokeLater { SwingUtilities.getWindowAncestor(chooser)?.dispose() }
            }
            if (!continuation.isActive) return@suspendCancellableCoroutine
            try {
                val selected = chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION
                continuation.resume(chooser.selectedFile.takeIf { selected })
            } catch (failure: Exception) {
                continuation.resumeWithException(failure)
            }
        }
    }
