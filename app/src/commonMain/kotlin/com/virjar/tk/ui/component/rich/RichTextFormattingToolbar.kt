package com.virjar.tk.ui.component.rich

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.StrikethroughS
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.HeadingStyle
import com.mohamedrejeb.richeditor.model.RichTextState

/**
 * TeamTalk 的富文本命令层。文档和聊天共享同一套 Markdown 可逆格式，避免两个工具栏各自漂移。
 *
 * [DOCUMENT] 暴露段落、历史和列表层级；[MESSAGE] 只保留聊天中高频的行内格式、链接和列表。
 * 字体、颜色、对齐等视觉属性没有稳定的 Markdown 持久化语义，刻意不在这里提供。
 */
internal enum class RichTextToolbarMode { DOCUMENT, MESSAGE }

@Composable
internal fun RichTextFormattingToolbar(
    state: RichTextState,
    mode: RichTextToolbarMode,
    onRequestFocus: () -> Unit,
    modifier: Modifier = Modifier,
    testTagPrefix: String,
) {
    val boldStyle = remember { SpanStyle(fontWeight = FontWeight.Bold) }
    val italicStyle = remember { SpanStyle(fontStyle = FontStyle.Italic) }
    val strikeStyle = remember { SpanStyle(textDecoration = TextDecoration.LineThrough) }
    var headingMenu by remember { mutableStateOf(false) }
    var linkDialog by remember { mutableStateOf<LinkDialogState?>(null) }
    var restoreFocusAfterDialog by remember { mutableStateOf(false) }

    LaunchedEffect(linkDialog, restoreFocusAfterDialog) {
        if (linkDialog == null && restoreFocusAfterDialog) {
            // AlertDialog 的 Popup 必须先退出焦点树，再把输入焦点还给编辑器。
            withFrameNanos { }
            restoreFocusAfterDialog = false
            onRequestFocus()
        }
    }

    linkDialog?.let { dialog ->
        RichTextLinkDialog(
            initialText = dialog.text,
            initialUrl = dialog.url,
            textLocked = !dialog.selection.collapsed || dialog.editing,
            allowRemove = dialog.editing,
            onDismiss = {
                linkDialog = null
                restoreFocusAfterDialog = true
            },
            onRemove = {
                state.selection = dialog.selection
                state.removeLink()
                linkDialog = null
                restoreFocusAfterDialog = true
            },
            onConfirm = { label, url ->
                state.selection = dialog.selection
                when {
                    dialog.editing -> state.updateLink(url)
                    !dialog.selection.collapsed -> state.addLinkToSelection(url)
                    else -> state.addLink(label, url)
                }
                linkDialog = null
                restoreFocusAfterDialog = true
            },
        )
    }

    BoxWithConstraints(modifier) {
        val compactMessage = mode == RichTextToolbarMode.MESSAGE && maxWidth < 300.dp
        val selectedUrl = state.selectedLinkUrl
        val isMentionLink = state.isLink && selectedUrl?.startsWith("mention://", ignoreCase = true) == true
        var moreMenu by remember { mutableStateOf(false) }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
        if (mode == RichTextToolbarMode.DOCUMENT) {
            RichTextToolButton(
                icon = { Icon(Icons.AutoMirrored.Filled.Undo, null) },
                description = "撤销",
                enabled = state.history.canUndo,
                testTag = "$testTagPrefix.undo",
            ) { state.history.undo(); onRequestFocus() }
            RichTextToolButton(
                icon = { Icon(Icons.AutoMirrored.Filled.Redo, null) },
                description = "重做",
                enabled = state.history.canRedo,
                testTag = "$testTagPrefix.redo",
            ) { state.history.redo(); onRequestFocus() }
            ToolbarDivider()
            Box {
                TextButton(
                    onClick = { headingMenu = true },
                    modifier = Modifier.height(36.dp).testTag("$testTagPrefix.heading"),
                    contentPadding = PaddingValues(horizontal = 9.dp),
                ) {
                    Text(headingLabel(state.currentHeadingStyle), style = MaterialTheme.typography.labelMedium)
                    Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(17.dp))
                }
                DropdownMenu(expanded = headingMenu, onDismissRequest = { headingMenu = false }) {
                    HeadingStyle.entries.forEach { heading ->
                        DropdownMenuItem(
                            text = { Text(headingLabel(heading), style = headingTypography(heading)) },
                            onClick = {
                                state.setHeadingStyle(heading)
                                headingMenu = false
                                onRequestFocus()
                            },
                            modifier = Modifier.testTag("$testTagPrefix.heading.${heading.level}"),
                        )
                    }
                }
            }
            ToolbarDivider()
        }

        RichTextToolButton(
            icon = { Icon(Icons.Filled.FormatBold, null) },
            description = "粗体",
            selected = (state.currentSpanStyle.fontWeight?.weight ?: 400) > 400,
            testTag = "$testTagPrefix.bold",
        ) { state.toggleSpanStyle(boldStyle); onRequestFocus() }
        RichTextToolButton(
            icon = { Icon(Icons.Filled.FormatItalic, null) },
            description = "斜体",
            selected = state.currentSpanStyle.fontStyle == FontStyle.Italic,
            testTag = "$testTagPrefix.italic",
        ) { state.toggleSpanStyle(italicStyle); onRequestFocus() }
        if (!compactMessage) {
            RichTextToolButton(
                icon = { Icon(Icons.Filled.StrikethroughS, null) },
                description = "删除线",
                selected = state.currentSpanStyle.textDecoration?.contains(TextDecoration.LineThrough) == true,
                testTag = "$testTagPrefix.strike",
            ) { state.toggleSpanStyle(strikeStyle); onRequestFocus() }
        }
        if (!compactMessage) {
            RichTextToolButton(
                icon = { Icon(Icons.Filled.Code, null) },
                description = "行内代码",
                selected = state.isCodeSpan,
                enabled = !isMentionLink,
                testTag = "$testTagPrefix.code",
            ) { state.toggleCodeSpan(); onRequestFocus() }
        }
        ToolbarDivider()
        RichTextToolButton(
            icon = { Icon(Icons.Filled.Link, null) },
            description = "链接",
            selected = state.isLink && state.selectedLinkUrl?.let(::normalizeRichTextLink) != null,
            enabled = !isMentionLink,
            testTag = "$testTagPrefix.link",
        ) {
            val selection = state.selection
            val selected = state.annotatedString.text.substring(selection.min, selection.max)
            val existingUrl = state.selectedLinkUrl?.let(::normalizeRichTextLink)
            linkDialog = LinkDialogState(
                selection = selection,
                text = state.selectedLinkText ?: selected,
                url = existingUrl.orEmpty(),
                editing = existingUrl != null,
            )
        }
        if (!compactMessage) {
            RichTextToolButton(
                icon = { Icon(Icons.AutoMirrored.Filled.FormatListBulleted, null) },
                description = "项目符号列表",
                selected = state.isUnorderedList,
                testTag = "$testTagPrefix.bullets",
            ) { state.toggleUnorderedList(); onRequestFocus() }
            RichTextToolButton(
                icon = { Icon(Icons.Filled.FormatListNumbered, null) },
                description = "编号列表",
                selected = state.isOrderedList,
                testTag = "$testTagPrefix.numbered",
            ) { state.toggleOrderedList(); onRequestFocus() }
        } else {
            Box {
                RichTextToolButton(
                    icon = { Icon(Icons.Filled.MoreVert, null) },
                    description = "更多格式",
                    selected = state.isCodeSpan ||
                        state.currentSpanStyle.textDecoration?.contains(TextDecoration.LineThrough) == true ||
                        state.isUnorderedList || state.isOrderedList,
                    testTag = "$testTagPrefix.more",
                ) { moreMenu = true }
                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("行内代码") },
                        leadingIcon = { Icon(Icons.Filled.Code, null) },
                        enabled = !isMentionLink,
                        onClick = {
                            moreMenu = false
                            state.toggleCodeSpan()
                            onRequestFocus()
                        },
                        modifier = Modifier.testTag("$testTagPrefix.code"),
                    )
                    DropdownMenuItem(
                        text = { Text("删除线") },
                        leadingIcon = { Icon(Icons.Filled.StrikethroughS, null) },
                        onClick = {
                            moreMenu = false
                            state.toggleSpanStyle(strikeStyle)
                            onRequestFocus()
                        },
                        modifier = Modifier.testTag("$testTagPrefix.strike"),
                    )
                    DropdownMenuItem(
                        text = { Text("项目符号列表") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.FormatListBulleted, null) },
                        onClick = {
                            moreMenu = false
                            state.toggleUnorderedList()
                            onRequestFocus()
                        },
                        modifier = Modifier.testTag("$testTagPrefix.bullets"),
                    )
                    DropdownMenuItem(
                        text = { Text("编号列表") },
                        leadingIcon = { Icon(Icons.Filled.FormatListNumbered, null) },
                        onClick = {
                            moreMenu = false
                            state.toggleOrderedList()
                            onRequestFocus()
                        },
                        modifier = Modifier.testTag("$testTagPrefix.numbered"),
                    )
                }
            }
        }

        if (mode == RichTextToolbarMode.DOCUMENT) {
            RichTextToolButton(
                icon = { Icon(Icons.AutoMirrored.Filled.FormatIndentIncrease, null) },
                description = "增加列表层级",
                enabled = state.canIncreaseListLevel,
                testTag = "$testTagPrefix.indent",
            ) { state.increaseListLevel(); onRequestFocus() }
            RichTextToolButton(
                icon = { Icon(Icons.AutoMirrored.Filled.FormatIndentDecrease, null) },
                description = "减少列表层级",
                enabled = state.canDecreaseListLevel,
                testTag = "$testTagPrefix.outdent",
            ) { state.decreaseListLevel(); onRequestFocus() }
        }
        }
    }
}

@Composable
private fun RichTextToolButton(
    icon: @Composable () -> Unit,
    description: String,
    testTag: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(36.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.13f) else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .semantics { contentDescription = description }
            .testTag(testTag),
    ) {
        Box(Modifier.size(19.dp), contentAlignment = Alignment.Center) { icon() }
    }
}

@Composable
private fun ToolbarDivider() {
    Spacer(
        Modifier.padding(horizontal = 3.dp).width(1.dp).height(20.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

private data class LinkDialogState(
    val selection: TextRange,
    val text: String,
    val url: String,
    val editing: Boolean,
)

@Composable
private fun RichTextLinkDialog(
    initialText: String,
    initialUrl: String,
    textLocked: Boolean,
    allowRemove: Boolean,
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var text by remember(initialText) { mutableStateOf(initialText) }
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    val normalized = normalizeRichTextLink(url)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (allowRemove) "编辑链接" else "插入链接") },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    enabled = !textLocked,
                    singleLine = true,
                    label = { Text("显示文字") },
                    modifier = Modifier.testTag("rich.link.text"),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    label = { Text("链接地址") },
                    supportingText = {
                        Text(if (url.isBlank() || normalized != null) "支持 https、http 和 mailto" else "链接格式不正确")
                    },
                    isError = url.isNotBlank() && normalized == null,
                    modifier = Modifier.testTag("rich.link.url"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { normalized?.let { onConfirm(text.trim(), it) } },
                enabled = text.isNotBlank() && normalized != null,
                modifier = Modifier.testTag("rich.link.confirm"),
            ) { Text("确定") }
        },
        dismissButton = {
            Row {
                if (allowRemove) TextButton(onClick = onRemove, modifier = Modifier.testTag("rich.link.remove")) {
                    Text("移除链接", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/** 返回可安全交给平台打开、也能稳定写回 Markdown 的 URL。 */
internal fun normalizeRichTextLink(raw: String): String? {
    val value = raw.trim()
    if (value.isEmpty() || value.any(Char::isWhitespace) || value.any { it in "\\<>" }) return null
    val lower = value.lowercase()
    val bareAuthority = value.substringBefore('/').substringBefore('?').substringBefore('#')
    return when {
        lower.startsWith("https://") || lower.startsWith("http://") -> {
            val authority = value.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
            value.takeIf { authority.isNotBlank() && '@' !in authority }
        }
        lower.startsWith("mailto:") && value.length > "mailto:".length -> value
        "://" in value || ':' in bareAuthority -> null
        '.' in bareAuthority && '@' !in bareAuthority -> "https://$value"
        else -> null
    }
}

private fun headingLabel(style: HeadingStyle): String = when (style) {
    HeadingStyle.Normal -> "正文"
    else -> "标题 ${style.level}"
}

@Composable
private fun headingTypography(style: HeadingStyle) = when (style) {
    HeadingStyle.H1 -> MaterialTheme.typography.titleLarge
    HeadingStyle.H2 -> MaterialTheme.typography.titleMedium
    HeadingStyle.H3 -> MaterialTheme.typography.titleSmall
    else -> MaterialTheme.typography.bodyMedium
}
