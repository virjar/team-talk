package com.virjar.tk.ui.component.rich

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.virjar.tk.body.MessageBodyPolicy

/**
 * 聊天正文始终以 Markdown 为权威源；这里的模式只决定作者如何编辑或查看它。
 *
 * [VISUAL] 只接收当前富文本编辑器能够无损往返的子集。高级语法继续留在 [MARKDOWN]
 * 或 [PREVIEW]，避免一次模式切换就静默改写代码围栏、表格等结构。
 */
internal enum class ChatComposerMode {
    VISUAL,
    MARKDOWN,
    PREVIEW,
}

/** WYSIWYG 每次编辑都需维护 AST/selection；更长内容继续留在按需解析的源码模式。 */
internal const val MAX_CHAT_VISUAL_MARKDOWN_LENGTH = 20_000

internal fun canUseChatVisualEditor(markdown: String): Boolean =
    markdown.length <= MAX_CHAT_VISUAL_MARKDOWN_LENGTH &&
        !RichEditorMarkdownCapability.inspect(markdown).requiresSourceMode

internal fun acceptsChatSourceInput(candidate: TextFieldValue): Boolean =
    candidate.text.length <= MessageBodyPolicy.MAX_MARKDOWN_LENGTH

internal fun TextFieldValue.replaceComposerRange(start: Int, end: Int, replacement: String): TextFieldValue {
    val safeStart = minOf(start, end).coerceIn(0, text.length)
    val safeEnd = maxOf(start, end).coerceIn(safeStart, text.length)
    val updated = text.replaceRange(safeStart, safeEnd, replacement)
    return copy(
        text = updated,
        selection = TextRange(safeStart + replacement.length),
        composition = null,
    )
}

/**
 * 源码编辑器对选区包裹 Markdown 标记。空选区时插入一对标记，并把光标留在中间。
 */
internal fun TextFieldValue.wrapComposerSelection(prefix: String, suffix: String = prefix): TextFieldValue {
    val start = selection.min.coerceIn(0, text.length)
    val end = selection.max.coerceIn(start, text.length)
    val selected = text.substring(start, end)
    val replacement = prefix + selected + suffix
    val updated = text.replaceRange(start, end, replacement)
    val nextSelection = if (start == end) {
        TextRange(start + prefix.length)
    } else {
        TextRange(start + prefix.length, start + prefix.length + selected.length)
    }
    return copy(text = updated, selection = nextSelection, composition = null)
}

/**
 * WYSIWYG 会规范化 Markdown 写法。只要可视内容没有真正改动，就继续使用用户原始源码；
 * 只有编辑后才接受 codec 的规范化输出。
 */
internal data class ChatVisualMarkdownBaseline(
    val originalMarkdown: String,
    val normalizedMarkdown: String,
) {
    fun snapshot(currentMarkdown: String): String =
        if (currentMarkdown == normalizedMarkdown) originalMarkdown else currentMarkdown
}

@Composable
internal fun ChatComposerModeSwitcher(
    mode: ChatComposerMode,
    visualEnabled: Boolean,
    onModeChange: (ChatComposerMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ChatComposerMode.entries.forEach { candidate ->
            FilterChip(
                selected = mode == candidate,
                onClick = { onModeChange(candidate) },
                enabled = candidate != ChatComposerMode.VISUAL || visualEnabled,
                label = {
                    Text(
                        when (candidate) {
                            ChatComposerMode.VISUAL -> "可视"
                            ChatComposerMode.MARKDOWN -> "源码"
                            ChatComposerMode.PREVIEW -> "预览"
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                modifier = Modifier
                    .height(34.dp)
                    .testTag(
                        "chat.composer.mode." + when (candidate) {
                            ChatComposerMode.VISUAL -> "visual"
                            ChatComposerMode.MARKDOWN -> "source"
                            ChatComposerMode.PREVIEW -> "preview"
                        },
                    ),
            )
        }
    }
}
