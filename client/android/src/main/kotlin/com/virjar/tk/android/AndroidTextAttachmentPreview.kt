package com.virjar.tk.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.app.ui.component.MAX_TEXT_ATTACHMENT_PREVIEW_BYTES
import com.virjar.tk.app.ui.component.ScreenHeader
import com.virjar.tk.app.ui.component.TextAttachmentPreviewContent
import com.virjar.tk.app.ui.component.TextAttachmentPreviewPlan
import com.virjar.tk.app.ui.component.TextAttachmentPreviewState
import com.virjar.tk.app.ui.component.decodeTextAttachmentPreview
import com.virjar.tk.app.ui.component.textAttachmentPreviewPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

internal const val TEXT_ATTACHMENT_EXTERNAL_OPEN_FAILURE_MESSAGE =
    "无法打开文件，请检查网络或是否安装了可处理此格式的应用"

internal fun textAttachmentExternalOpenFailureMessage(isCurrentOwner: Boolean): String? =
    TEXT_ATTACHMENT_EXTERNAL_OPEN_FAILURE_MESSAGE.takeIf { isCurrentOwner }

/** 最多读取 maxBytes + 1，多出的一字节只用于发现伪造偏小的 size 元数据。 */
internal fun readTextAttachmentPreviewBytes(
    file: File,
    maxBytes: Long = MAX_TEXT_ATTACHMENT_PREVIEW_BYTES,
): ByteArray {
    require(maxBytes in 1 until Int.MAX_VALUE.toLong()) { "invalid text preview limit" }
    if (!file.isFile) throw IllegalStateException("预览文件不存在")
    val readLimit = maxBytes + 1L
    val output = ByteArrayOutputStream(minOf(file.length(), readLimit, 64L * 1024L).toInt())
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(8 * 1024)
        var remaining = readLimit
        while (remaining > 0L) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            if (read == 0) continue
            output.write(buffer, 0, read)
            remaining -= read
        }
    }
    return output.toByteArray()
}

@Composable
internal fun AndroidTextAttachmentPreviewScreen(
    attachment: Attachment,
    mediaSession: AndroidMediaSession,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val plan = remember(attachment) { textAttachmentPreviewPlan(attachment) }
    var retryGeneration by remember(attachment.path) { mutableIntStateOf(0) }
    var externalOpenError by remember(attachment.path) { mutableStateOf<String?>(null) }
    var externalOpenLease by remember(attachment.path, mediaSession) {
        mutableStateOf<AndroidMediaCacheFileLease?>(null)
    }
    DisposableEffect(attachment.path, mediaSession) {
        onDispose { externalOpenLease?.close() }
    }
    val previewState by produceState<TextAttachmentPreviewState>(
        initialValue = TextAttachmentPreviewState.Loading,
        attachment,
        plan,
        retryGeneration,
        mediaSession,
    ) {
        value = when (plan) {
            is TextAttachmentPreviewPlan.TooLarge -> TextAttachmentPreviewState.TooLarge(plan.maxBytes)
            is TextAttachmentPreviewPlan.UnsupportedCharset ->
                TextAttachmentPreviewState.UnsupportedCharset(plan.charset)
            is TextAttachmentPreviewPlan.InvalidSize ->
                TextAttachmentPreviewState.Failed("文件大小无效，无法安全预览")
            TextAttachmentPreviewPlan.UseExternalApplication ->
                TextAttachmentPreviewState.Failed("该文件类型不支持内嵌预览")
            is TextAttachmentPreviewPlan.Preview -> try {
                val cacheRoot = resolveAndroidMediaCacheRoot(context)
                withContext(Dispatchers.IO) {
                    downloadAttachmentToCacheLease(
                        cacheRoot = cacheRoot,
                        mediaSession = mediaSession,
                        attachment = attachment,
                    ).use { lease ->
                        val bytes = readTextAttachmentPreviewBytes(lease.file)
                        if (bytes.size.toLong() > MAX_TEXT_ATTACHMENT_PREVIEW_BYTES) {
                            TextAttachmentPreviewState.TooLarge(MAX_TEXT_ATTACHMENT_PREVIEW_BYTES)
                        } else {
                            decodeTextAttachmentPreview(bytes, plan.kind)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                TextAttachmentPreviewState.Failed("加载失败，请检查网络或文件内容后重试")
            }
        }
    }

    fun openExternally() {
        scope.launch {
            externalOpenError = null
            try {
                val cacheRoot = resolveAndroidMediaCacheRoot(context)
                val lease = downloadAttachmentToCacheLease(
                    cacheRoot = cacheRoot,
                    mediaSession = mediaSession,
                    attachment = attachment,
                )
                var handedOff = false
                try {
                    // 感知租约的 IO 辅助函数已在触碰 UI 或 Intent 之前返回到 Main 线程。
                    if (!mediaSession.isCurrentOwner()) return@launch
                    MediaHelper.openFile(context.applicationContext, lease.file, attachment.contentType)
                    val previous = externalOpenLease
                    externalOpenLease = lease
                    handedOff = true
                    previous?.close()
                } finally {
                    if (!handedOff) lease.close()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                externalOpenError = textAttachmentExternalOpenFailureMessage(mediaSession.isCurrentOwner())
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = attachment.name.ifBlank { "文本预览" }, onBack = onBack)
        externalOpenError?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .testTag("text.attachment.external.error")
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        TextAttachmentPreviewContent(
            state = previewState,
            modifier = Modifier.weight(1f),
            onUrlClick = { url -> openSafeExternalLink(context, url) },
            onRetry = { retryGeneration += 1 },
            onOpenExternally = ::openExternally,
        )
    }
}
