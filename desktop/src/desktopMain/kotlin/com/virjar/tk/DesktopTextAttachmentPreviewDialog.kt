package com.virjar.tk

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.virjar.tk.model.Attachment
import com.virjar.tk.ui.component.MAX_TEXT_ATTACHMENT_PREVIEW_BYTES
import com.virjar.tk.ui.component.TextAttachmentPreviewContent
import com.virjar.tk.ui.component.TextAttachmentPreviewPlan
import com.virjar.tk.ui.component.TextAttachmentPreviewState
import com.virjar.tk.ui.component.decodeTextAttachmentPreview
import com.virjar.tk.ui.component.textAttachmentPreviewPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.net.URI

@Composable
internal fun DesktopTextAttachmentPreviewDialog(
    event: DesktopTextAttachmentPreviewEvent?,
    onDismiss: () -> Unit,
    onRetry: (Attachment) -> Unit,
    onOpenExternally: (Attachment) -> Unit,
) {
    val attachment = event?.attachment ?: return
    val state by produceState<TextAttachmentPreviewState>(
        initialValue = desktopTextAttachmentPreviewState(attachment),
        event,
    ) {
        value = when (event) {
            is DesktopTextAttachmentPreviewEvent.Loading -> desktopTextAttachmentPreviewState(attachment)
            is DesktopTextAttachmentPreviewEvent.Failed -> TextAttachmentPreviewState.Failed(event.message)
            is DesktopTextAttachmentPreviewEvent.Ready -> loadDesktopTextAttachmentPreview(attachment, event.file)
        }
    }
    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(700.dp).height(520.dp).testTag("attachment.preview.dialog"),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 3.dp,
            shadowElevation = 16.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(56.dp).padding(start = 20.dp, end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = attachment.name.ifBlank { "文本附件预览" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(40.dp).testTag("attachment.preview.close"),
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭")
                    }
                }
                TextAttachmentPreviewContent(
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    onUrlClick = { rawUrl ->
                        safeDesktopExternalLinkOrNull(rawUrl)?.let { url ->
                            scope.launch(Dispatchers.IO) {
                                runCatching { Desktop.getDesktop().browse(URI(url)) }
                            }
                        }
                    },
                    onRetry = if (state is TextAttachmentPreviewState.Failed) {
                        { onRetry(attachment) }
                    } else null,
                    onOpenExternally = if (
                        state is TextAttachmentPreviewState.TooLarge ||
                        state is TextAttachmentPreviewState.UnsupportedCharset ||
                        state is TextAttachmentPreviewState.Failed
                    ) {
                        {
                            onDismiss()
                            onOpenExternally(attachment)
                        }
                    } else null,
                )
            }
        }
    }
}

internal fun desktopTextAttachmentPreviewState(attachment: Attachment): TextAttachmentPreviewState =
    when (val plan = textAttachmentPreviewPlan(attachment)) {
        is TextAttachmentPreviewPlan.Preview -> TextAttachmentPreviewState.Loading
        is TextAttachmentPreviewPlan.TooLarge -> TextAttachmentPreviewState.TooLarge(plan.maxBytes)
        is TextAttachmentPreviewPlan.UnsupportedCharset ->
            TextAttachmentPreviewState.UnsupportedCharset(plan.charset)
        is TextAttachmentPreviewPlan.InvalidSize -> TextAttachmentPreviewState.Failed("附件大小信息无效")
        TextAttachmentPreviewPlan.UseExternalApplication -> TextAttachmentPreviewState.Failed("此文件不支持内嵌预览")
    }

private suspend fun loadDesktopTextAttachmentPreview(
    attachment: Attachment,
    file: File,
): TextAttachmentPreviewState = withContext(Dispatchers.IO) {
    val plan = textAttachmentPreviewPlan(attachment)
    if (plan !is TextAttachmentPreviewPlan.Preview) return@withContext desktopTextAttachmentPreviewState(attachment)
    try {
        val bytes = readDesktopTextAttachmentPreviewBytes(file)
        if (bytes.size.toLong() > MAX_TEXT_ATTACHMENT_PREVIEW_BYTES) {
            TextAttachmentPreviewState.TooLarge(MAX_TEXT_ATTACHMENT_PREVIEW_BYTES)
        } else {
            decodeTextAttachmentPreview(bytes, plan.kind)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        TextAttachmentPreviewState.Failed("文件内容无法预览，请重试或使用其他应用打开")
    }
}

internal fun readDesktopTextAttachmentPreviewBytes(
    file: File,
    maxBytes: Long = MAX_TEXT_ATTACHMENT_PREVIEW_BYTES,
): ByteArray {
    require(maxBytes in 1 until Int.MAX_VALUE) { "maxBytes is outside the supported range" }
    if (!file.isFile) throw IllegalStateException("预览缓存文件不存在")
    return file.inputStream().buffered().use { input ->
        input.readNBytes((maxBytes + 1L).toInt())
    }
}
