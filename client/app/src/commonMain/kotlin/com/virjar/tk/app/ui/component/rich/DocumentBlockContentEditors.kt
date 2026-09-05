package com.virjar.tk.app.ui.component.rich

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import com.virjar.tk.protocol.body.EmbeddedAssetPresentation

/**
 * RichTextState 限定在 UI 线程，因此它的 Markdown projection 必须在 UI 调度器上运行。
 * 在做该线性工作之前先合并普通输入；生命周期快照仍绕过此延迟，同步捕获最新编辑器状态。
 */
internal const val DOCUMENT_RICH_MARKDOWN_PROJECTION_DELAY_MILLIS = 250L

/**
 * 为第一个请求开启一个不滑动的 projection 窗口，并把其截止时间之前到达的每个请求都折叠进
 * 同一窗口。在 [onWindowEnd] 之前清空队列，可避免已经包含在最新同步读取中的变更再开启
 * 一个冗余的第二个窗口。
 *
 * owner 必须在调用 [request] 的同一 UI 调度器上收集此对象，因为窗口回调会读取限定在
 * UI 线程的 RichTextState。
 */
internal class FixedWindowMarkdownProjectionRequests {
    private val requests = Channel<Unit>(Channel.CONFLATED)

    fun request() {
        requests.trySend(Unit)
    }

    suspend fun collectFixedWindows(
        windowMillis: Long,
        onWindowEnd: () -> Unit,
    ) {
        require(windowMillis > 0L) { "windowMillis must be positive" }
        for (ignored in requests) {
            delay(windowMillis)
            while (requests.tryReceive().isSuccess) {
                // 当前排队的每个请求都属于刚刚结束的固定窗口。
            }
            onWindowEnd()
        }
    }
}

private class ReferentialContentVersion {
    private var current: Any? = null

    fun observe(value: Any): Boolean {
        if (current === value) return false
        current = value
        return true
    }
}

/**
 * 在文档画布的整个生命周期内持有富文本块的编辑器状态，而不是只持续 LazyColumn 条目的
 * 生命周期。把块滚出组合绝不能丢弃最后一次击键，或从过期的 Markdown projection 重建编辑器。
 */
@Stable
internal class DocumentRichEditorSession(
    val state: RichTextState,
    val originalBlock: DocumentMarkdownBlock,
) {
    var ready by mutableStateOf(false)
    var normalizedBaseline by mutableStateOf("")
    var lastReportedMarkdown by mutableStateOf("")
}

/**
 * 让收集器的生命周期与编辑器会话绑定，而已提交的组合只入队一个变更信号。因此重组绝不会
 * 重启一个已经打开的固定窗口。引用观察还避免了每次击键都对大型不可变 AnnotatedString
 * 再做一次线性相等比较。
 */
@Composable
private fun DocumentRichMarkdownProjection(
    session: DocumentRichEditorSession,
    contentVersion: Any,
    onMarkdown: (String) -> Unit,
) {
    val requests = remember(session) { FixedWindowMarkdownProjectionRequests() }
    val observedContent = remember(session) { ReferentialContentVersion() }
    val latestOnMarkdown by rememberUpdatedState(onMarkdown)

    SideEffect {
        if (observedContent.observe(contentVersion) && session.ready) requests.request()
    }
    LaunchedEffect(session, requests) {
        requests.collectFixedWindows(DOCUMENT_RICH_MARKDOWN_PROJECTION_DELAY_MILLIS) {
            if (!session.ready) return@collectFixedWindows
            val markdown = session.state.toMarkdown()
            if (markdown == session.lastReportedMarkdown) return@collectFixedWindows
            session.lastReportedMarkdown = markdown
            latestOnMarkdown(markdown)
        }
    }
}

@Composable
internal fun DocumentRichRunEditor(
    block: DocumentRichRun,
    session: DocumentRichEditorSession,
    initiallyActive: Boolean,
    pendingActivation: Boolean,
    pendingFocus: Boolean,
    onActivate: (RichTextState, () -> Unit) -> Unit,
    onConsumePendingActivation: (RichTextState, () -> Unit) -> Unit,
    onSnapshot: ((DocumentMarkdownBlock) -> DocumentMarkdownBlock) -> Unit,
    onChange: (DocumentRichRun) -> Unit,
) {
    val state = session.state
    val initialBlock = session.originalBlock as DocumentRichRun
    val focusRequester = remember(block.key) { FocusRequester() }

    LaunchedEffect(session) {
        if (!session.ready) {
            state.setMarkdown(initialBlock.markdown)
            withFrameNanos { }
            withFrameNanos { }
            session.normalizedBaseline = state.toMarkdown()
            session.lastReportedMarkdown = session.normalizedBaseline
            session.ready = true
            if (initiallyActive && !pendingActivation) {
                onActivate(state) { focusRequester.requestFocus() }
            }
        }
    }
    SideEffect {
        onSnapshot { currentBlock ->
            val current = currentBlock as? DocumentRichRun ?: block
            val markdown = if (session.ready) state.toMarkdown() else null
            when {
                !session.ready -> current
                markdown == session.normalizedBaseline -> initialBlock.withDocumentLayout(
                    leadingMarkdown = current.leadingMarkdown,
                    trailingMarkdown = current.trailingMarkdown,
                )
                else -> current.copy(markdown = requireNotNull(markdown), dirty = true)
            }
        }
    }
    DocumentRichMarkdownProjection(session, state.annotatedString) { markdown ->
        onChange(
            if (markdown == session.normalizedBaseline) {
                initialBlock.withDocumentLayout(
                    leadingMarkdown = block.leadingMarkdown,
                    trailingMarkdown = block.trailingMarkdown,
                ) as DocumentRichRun
            }
            else block.copy(markdown = markdown, dirty = true)
        )
    }
    LaunchedEffect(session.ready, pendingActivation, pendingFocus) {
        if (session.ready && pendingActivation) {
            onConsumePendingActivation(state) { focusRequester.requestFocus() }
        }
    }

    Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
        BasicRichTextEditor(
            state = state,
            enabled = session.ready,
            minLines = 2,
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { focus ->
                    if (session.ready && focus.isFocused) onActivate(state) { focusRequester.requestFocus() }
                }
                .testTag("documents.editor.rich.${block.key}"),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = LocalContentColor.current),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        )
        if (session.ready && state.annotatedString.text.isEmpty()) {
            Text(
                "输入正文，或从工具栏插入引用、代码块和表格…",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun DocumentQuoteBlockEditor(
    block: DocumentQuoteBlock,
    session: DocumentRichEditorSession?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    initiallyActive: Boolean,
    pendingActivation: Boolean,
    pendingFocus: Boolean,
    onActivate: (RichTextState, () -> Unit) -> Unit,
    onConsumePendingActivation: (RichTextState, () -> Unit) -> Unit,
    onActivateSource: () -> Unit,
    onSnapshot: ((DocumentMarkdownBlock) -> DocumentMarkdownBlock) -> Unit,
    onChange: (DocumentQuoteBlock) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag("documents.editor.quote.${block.key}"),
    ) {
        Column {
            DocumentBlockHeader(
                icon = { Icon(Icons.Filled.FormatQuote, null) },
                label = "引用",
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                onDelete = onDelete,
                testTagPrefix = "documents.editor.quote.${block.key}",
            )
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                Spacer(
                    Modifier.width(4.dp).fillMaxHeight().heightIn(min = 70.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
                Spacer(Modifier.width(12.dp))
                if (session == null) {
                    BasicTextField(
                        value = block.innerMarkdown,
                        onValueChange = { onChange(block.copy(innerMarkdown = it, dirty = true)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp)
                            .onFocusChanged { if (it.isFocused) onActivateSource() }
                            .testTag("documents.editor.quote.source.${block.key}"),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = LocalContentColor.current,
                            fontFamily = FontFamily.Monospace,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    )
                } else {
                    DocumentQuoteRichEditor(
                        block = block,
                        session = session,
                        initiallyActive = initiallyActive,
                        pendingActivation = pendingActivation,
                        pendingFocus = pendingFocus,
                        onActivate = onActivate,
                        onConsumePendingActivation = onConsumePendingActivation,
                        onSnapshot = onSnapshot,
                        onChange = onChange,
                    )
                }
            }
        }
    }
}

@Composable
private fun DocumentQuoteRichEditor(
    block: DocumentQuoteBlock,
    session: DocumentRichEditorSession,
    initiallyActive: Boolean,
    pendingActivation: Boolean,
    pendingFocus: Boolean,
    onActivate: (RichTextState, () -> Unit) -> Unit,
    onConsumePendingActivation: (RichTextState, () -> Unit) -> Unit,
    onSnapshot: ((DocumentMarkdownBlock) -> DocumentMarkdownBlock) -> Unit,
    onChange: (DocumentQuoteBlock) -> Unit,
) {
    val state = session.state
    val initialBlock = session.originalBlock as DocumentQuoteBlock
    val focusRequester = remember(block.key) { FocusRequester() }
    LaunchedEffect(session) {
        if (!session.ready) {
            state.setMarkdown(initialBlock.innerMarkdown)
            withFrameNanos { }
            withFrameNanos { }
            session.normalizedBaseline = state.toMarkdown()
            session.lastReportedMarkdown = session.normalizedBaseline
            session.ready = true
            if (initiallyActive && !pendingActivation) {
                onActivate(state) { focusRequester.requestFocus() }
            }
        }
    }
    SideEffect {
        onSnapshot { currentBlock ->
            val current = currentBlock as? DocumentQuoteBlock ?: block
            val markdown = if (session.ready) state.toMarkdown() else null
            when {
                !session.ready -> current
                markdown == session.normalizedBaseline -> initialBlock.withDocumentLayout(
                    leadingMarkdown = current.leadingMarkdown,
                    trailingMarkdown = current.trailingMarkdown,
                )
                else -> current.copy(innerMarkdown = requireNotNull(markdown), dirty = true)
            }
        }
    }
    DocumentRichMarkdownProjection(session, state.annotatedString) { markdown ->
        onChange(
            if (markdown == session.normalizedBaseline) {
                initialBlock.withDocumentLayout(
                    leadingMarkdown = block.leadingMarkdown,
                    trailingMarkdown = block.trailingMarkdown,
                ) as DocumentQuoteBlock
            }
            else block.copy(innerMarkdown = markdown, dirty = true)
        )
    }
    LaunchedEffect(session.ready, pendingActivation, pendingFocus) {
        if (session.ready && pendingActivation) {
            onConsumePendingActivation(state) { focusRequester.requestFocus() }
        }
    }
    Box(Modifier.fillMaxWidth()) {
        BasicRichTextEditor(
            state = state,
            enabled = session.ready,
            minLines = 2,
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)
                .focusRequester(focusRequester)
                .onFocusChanged {
                    if (session.ready && it.isFocused) onActivate(state) { focusRequester.requestFocus() }
                }
                .testTag("documents.editor.quote.body.${block.key}"),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = LocalContentColor.current),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        )
        if (session.ready && state.annotatedString.text.isEmpty()) {
            Text("输入引用内容…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun DocumentCodeBlockEditor(
    block: DocumentCodeFenceBlock,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onActivate: () -> Unit,
    onChange: (DocumentCodeFenceBlock) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag("documents.editor.code.${block.key}"),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Code, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(7.dp))
                BasicTextField(
                    value = block.infoString ?: block.language.orEmpty(),
                    onValueChange = { value ->
                        // 保留输入中的尾部空格，让用户能顺畅输入完整的 info 字符串
                        // （例如 `kotlin title="sample"`），而不用和字段较劲。
                        val info = value.ifEmpty { null }
                        onChange(
                            block.copy(
                                language = info?.trimStart()?.takeWhile { !it.isWhitespace() }
                                    ?.takeIf(String::isNotEmpty),
                                infoString = info,
                                dirty = true,
                            )
                        )
                    },
                    modifier = Modifier.widthIn(min = 90.dp, max = 180.dp)
                        .onFocusChanged { if (it.isFocused) onActivate() }
                        .testTag("documents.editor.code.language.${block.key}"),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.labelMedium.copy(color = LocalContentColor.current),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { inner ->
                        Box(Modifier.padding(horizontal = 6.dp, vertical = 7.dp)) {
                            if (block.infoString.isNullOrBlank()) Text(
                                "语言 / 信息（可选）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            inner()
                        }
                    },
                )
                Spacer(Modifier.weight(1f))
                DocumentBlockMenu(
                    canMoveUp = canMoveUp,
                    canMoveDown = canMoveDown,
                    onMoveUp = onMoveUp,
                    onMoveDown = onMoveDown,
                    onDelete = onDelete,
                    testTagPrefix = "documents.editor.code.${block.key}",
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.fillMaxWidth()) {
                BasicTextField(
                    value = block.code,
                    onValueChange = { onChange(block.copy(code = it, dirty = true)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 132.dp).padding(14.dp)
                        .onFocusChanged { if (it.isFocused) onActivate() }
                        .testTag("documents.editor.code.body.${block.key}"),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = LocalContentColor.current,
                        fontFamily = FontFamily.Monospace,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                )
                if (block.code.isEmpty()) Text(
                    "输入代码，换行和缩进会原样保存…",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }
    }
}

@Composable
internal fun DocumentRawBlockEditor(
    block: DocumentOpaqueRawBlock,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onActivate: () -> Unit,
    onChange: (DocumentOpaqueRawBlock) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.30f),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag("documents.editor.raw.${block.key}"),
    ) {
        Column {
            DocumentBlockHeader(
                icon = { Icon(Icons.Filled.Code, null) },
                label = "Markdown 扩展块 · ${block.features.joinToString { it.displayName() }.ifBlank { "未知语法" }}",
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                onDelete = onDelete,
                testTagPrefix = "documents.editor.raw.${block.key}",
            )
            Text(
                "只有这个暂未建模的内容块使用源码编辑，文档其他部分仍保持可视化编辑。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
            BasicTextField(
                value = block.rawMarkdown,
                onValueChange = { onChange(block.copy(rawMarkdown = it, dirty = true)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp).padding(14.dp)
                    .onFocusChanged { if (it.isFocused) onActivate() }
                    .testTag("documents.editor.raw.body.${block.key}"),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = LocalContentColor.current,
                    fontFamily = FontFamily.Monospace,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
internal fun DocumentEmbeddedAssetBlockEditor(
    block: DocumentEmbeddedAssetBlock,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    embeddedAssetContent: EmbeddedAssetMarkdownContent?,
    onActivate: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    val presentation = when (block) {
        is DocumentEmbeddedImageBlock -> EmbeddedAssetPresentation.IMAGE
        is DocumentEmbeddedFileBlock -> EmbeddedAssetPresentation.FILE
    }
    val prefix = "documents.editor.asset.${presentation.name.lowercase()}.${block.asset.assetId}"
    Surface(
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag(prefix),
        onClick = onActivate,
    ) {
        Column {
            DocumentBlockHeader(
                icon = {
                    Icon(
                        if (presentation == EmbeddedAssetPresentation.IMAGE) Icons.Filled.Image
                        else Icons.Filled.AttachFile,
                        null,
                    )
                },
                label = if (presentation == EmbeddedAssetPresentation.IMAGE) "内嵌图片" else "内嵌文件",
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                onDelete = onDelete,
                testTagPrefix = prefix,
            )
            if (embeddedAssetContent != null) {
                embeddedAssetContent(
                    block.asset,
                    presentation,
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                )
            } else {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(block.label.ifBlank { block.asset.attachment.name })
                    Text(
                        block.asset.attachment.contentType,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun DocumentBlockHeader(
    icon: @Composable () -> Unit,
    label: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    testTagPrefix: String,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, end = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(7.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        DocumentBlockMenu(
            canMoveUp = canMoveUp,
            canMoveDown = canMoveDown,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            onDelete = onDelete,
            testTagPrefix = testTagPrefix,
        )
    }
}

@Composable
private fun DocumentBlockMenu(
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    testTagPrefix: String,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(36.dp).testTag("$testTagPrefix.more"),
        ) { Icon(Icons.Filled.MoreVert, contentDescription = "内容块操作", Modifier.size(19.dp)) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("上移") },
                leadingIcon = { Icon(Icons.Filled.ArrowUpward, null) },
                enabled = canMoveUp,
                onClick = { expanded = false; onMoveUp() },
            )
            DropdownMenuItem(
                text = { Text("下移") },
                leadingIcon = { Icon(Icons.Filled.ArrowDownward, null) },
                enabled = canMoveDown,
                onClick = { expanded = false; onMoveDown() },
            )
            DropdownMenuItem(
                text = { Text("删除内容块", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Filled.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                onClick = { expanded = false; onDelete() },
                modifier = Modifier.testTag("$testTagPrefix.delete"),
            )
        }
    }
}

private fun RichEditorUnsupportedMarkdownFeature.displayName(): String = when (this) {
    RichEditorUnsupportedMarkdownFeature.FENCED_CODE_BLOCK -> "代码块"
    RichEditorUnsupportedMarkdownFeature.INDENTED_CODE_BLOCK -> "缩进代码"
    RichEditorUnsupportedMarkdownFeature.BLOCK_QUOTE -> "引用"
    RichEditorUnsupportedMarkdownFeature.TABLE -> "表格"
    RichEditorUnsupportedMarkdownFeature.TASK_LIST -> "任务清单"
    RichEditorUnsupportedMarkdownFeature.IMAGE -> "图片"
    RichEditorUnsupportedMarkdownFeature.RAW_HTML -> "HTML"
    RichEditorUnsupportedMarkdownFeature.SETEXT_HEADING -> "Setext 标题"
    RichEditorUnsupportedMarkdownFeature.REFERENCE_LINK -> "引用式链接"
    RichEditorUnsupportedMarkdownFeature.HORIZONTAL_RULE -> "分隔线"
    RichEditorUnsupportedMarkdownFeature.MATH -> "公式"
    RichEditorUnsupportedMarkdownFeature.HARD_LINE_BREAK -> "硬换行"
    RichEditorUnsupportedMarkdownFeature.NON_CANONICAL_ORDERED_LIST -> "自定义编号"
    RichEditorUnsupportedMarkdownFeature.LINK_TITLE -> "链接标题"
    RichEditorUnsupportedMarkdownFeature.FORMATTED_LINK_LABEL -> "富格式链接"
    RichEditorUnsupportedMarkdownFeature.MULTI_BACKTICK_CODE_SPAN -> "多反引号代码"
    RichEditorUnsupportedMarkdownFeature.EXCESSIVE_NESTING -> "过深嵌套结构"
    RichEditorUnsupportedMarkdownFeature.EXCESSIVE_STRUCTURE -> "超出编辑器结构预算"
}
