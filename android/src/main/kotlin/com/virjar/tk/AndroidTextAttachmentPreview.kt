package com.virjar.tk

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.virjar.tk.model.Attachment
import com.virjar.tk.ui.component.MAX_TEXT_ATTACHMENT_PREVIEW_BYTES
import com.virjar.tk.ui.component.ScreenHeader
import com.virjar.tk.ui.component.TextAttachmentPreviewContent
import com.virjar.tk.ui.component.TextAttachmentPreviewPlan
import com.virjar.tk.ui.component.TextAttachmentPreviewState
import com.virjar.tk.ui.component.decodeTextAttachmentPreview
import com.virjar.tk.ui.component.textAttachmentPreviewPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

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
    serverUrl: String,
    accessToken: String?,
    cacheNamespace: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cacheLease = remember(context, cacheNamespace) {
        acquireMediaCacheLease(context.cacheDir, cacheNamespace)
    }
    DisposableEffect(cacheLease) {
        onDispose { cacheLease.close() }
    }

    val plan = remember(attachment) { textAttachmentPreviewPlan(attachment) }
    var retryGeneration by remember(attachment.path) { mutableIntStateOf(0) }
    val previewState by produceState<TextAttachmentPreviewState>(
        initialValue = TextAttachmentPreviewState.Loading,
        attachment,
        plan,
        retryGeneration,
        serverUrl,
        accessToken,
        cacheNamespace,
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
                withContext(Dispatchers.IO) {
                    val cached = downloadAttachmentToCache(
                        cacheRoot = context.cacheDir,
                        cacheNamespace = cacheNamespace,
                        serverUrl = serverUrl,
                        accessToken = accessToken,
                        attachment = attachment,
                    )
                    val bytes = readTextAttachmentPreviewBytes(cached)
                    if (bytes.size.toLong() > MAX_TEXT_ATTACHMENT_PREVIEW_BYTES) {
                        TextAttachmentPreviewState.TooLarge(MAX_TEXT_ATTACHMENT_PREVIEW_BYTES)
                    } else {
                        decodeTextAttachmentPreview(bytes, plan.kind)
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
            runCatching {
                val cached = downloadAttachmentToCache(
                    cacheRoot = context.cacheDir,
                    cacheNamespace = cacheNamespace,
                    serverUrl = serverUrl,
                    accessToken = accessToken,
                    attachment = attachment,
                )
                MediaHelper.openFile(context.applicationContext, cached, attachment.contentType)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = attachment.name.ifBlank { "文本预览" }, onBack = onBack)
        TextAttachmentPreviewContent(
            state = previewState,
            modifier = Modifier.weight(1f),
            onUrlClick = { url -> openSafeExternalLink(context, url) },
            onRetry = { retryGeneration += 1 },
            onOpenExternally = ::openExternally,
        )
    }
}
