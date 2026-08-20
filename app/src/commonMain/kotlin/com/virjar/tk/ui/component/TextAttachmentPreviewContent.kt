package com.virjar.tk.ui.component

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.virjar.tk.ui.component.rich.MarkdownText

/** Android 页面与 Desktop 小窗共用的附件正文、加载态及错误态。 */
@Composable
fun TextAttachmentPreviewContent(
    state: TextAttachmentPreviewState,
    modifier: Modifier = Modifier,
    onUrlClick: ((String) -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onOpenExternally: (() -> Unit)? = null,
) {
    when (state) {
        TextAttachmentPreviewState.Loading -> PreviewStatus(
            message = "正在加载…",
            loading = true,
            modifier = modifier.testTag("attachment.preview.loading"),
        )

        is TextAttachmentPreviewState.Ready -> SelectionContainer {
            when (state.kind) {
                TextAttachmentPreviewKind.MARKDOWN -> MarkdownText(
                    content = state.content,
                    modifier = modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                        .testTag("attachment.preview.markdown"),
                    onUrlClick = onUrlClick,
                )

                TextAttachmentPreviewKind.PLAIN_TEXT -> Box(
                    modifier = modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(16.dp)
                        .testTag("attachment.preview.text"),
                ) {
                    Text(
                        text = state.content,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        is TextAttachmentPreviewState.Failed -> PreviewStatus(
            message = state.message,
            modifier = modifier.testTag("attachment.preview.error"),
            onRetry = onRetry,
            onOpenExternally = onOpenExternally,
        )

        is TextAttachmentPreviewState.TooLarge -> PreviewStatus(
            message = "文件超过 ${formatPreviewLimit(state.maxBytes)}，请使用其他应用打开",
            modifier = modifier.testTag("attachment.preview.tooLarge"),
            onOpenExternally = onOpenExternally,
        )

        is TextAttachmentPreviewState.UnsupportedCharset -> PreviewStatus(
            message = "暂不支持 ${state.charset} 编码，请使用 UTF-8 或其他应用打开",
            modifier = modifier.testTag("attachment.preview.unsupportedCharset"),
            onOpenExternally = onOpenExternally,
        )
    }
}

@Composable
private fun PreviewStatus(
    message: String,
    modifier: Modifier,
    loading: Boolean = false,
    onRetry: (() -> Unit)? = null,
    onOpenExternally: (() -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(32.dp))
                Spacer(Modifier.size(16.dp))
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (onRetry != null) {
                Spacer(Modifier.size(16.dp))
                Button(onClick = onRetry, modifier = Modifier.testTag("attachment.preview.retry")) {
                    Text("重试")
                }
            }
            if (onOpenExternally != null) {
                Spacer(Modifier.size(12.dp))
                OutlinedButton(
                    onClick = onOpenExternally,
                    modifier = Modifier.testTag("attachment.preview.external"),
                ) {
                    Text("使用其他应用打开")
                }
            }
        }
    }
}

private fun formatPreviewLimit(bytes: Long): String = when {
    bytes % (1024L * 1024L) == 0L -> "${bytes / (1024L * 1024L)} MB"
    bytes % 1024L == 0L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}
