@file:OptIn(com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi::class)

package com.virjar.tk.app.ui.component.rich

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.trigger.Trigger
import com.mohamedrejeb.richeditor.model.trigger.TriggerQuery
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import com.virjar.tk.app.ui.component.input.AutoCompleteOverlay
import com.virjar.tk.app.ui.component.input.mentionAutoCompleteItems
import com.virjar.tk.app.ui.component.input.filterMentionCandidates
import com.virjar.tk.app.ui.component.input.mentionDisplayName
import com.virjar.tk.protocol.body.EmbeddedAssetPresentation
import com.virjar.tk.protocol.model.User

/** 文档 @ 提及在编辑器内部使用的 Trigger id（Token 序列化会带上它）。 */
internal const val DOCUMENT_MENTION_TRIGGER_ID = "mention"

/**
 * 文档正文以 `mention://uid` 为权威 Markdown；编辑器内部 Token 序列化为
 * `trigger:mention:uid`。两组语法在块编辑器边界互转，保存/预览永远只见权威形态。
 */
private val MENTION_LINK_DESTINATION = Regex("""\]\(mention://([^)\s]+)\)""")
private val MENTION_TOKEN_DESTINATION = Regex("""\]\(trigger:mention:([^)\s]+)\)""")

/** 权威 Markdown → 编辑器内部形态（mention:// 转为 Token 语法）。 */
internal fun markdownWithEditorMentionTokens(markdown: String): String =
    markdown.replace(MENTION_LINK_DESTINATION, "](trigger:mention:$1)")

/** 编辑器内部形态 → 权威 Markdown（Token 语法转回 mention://）。 */
internal fun markdownWithMentionLinks(markdown: String): String =
    markdown.replace(MENTION_TOKEN_DESTINATION, "](mention://$1)")

/** 文档 @ 提及的 Trigger：`@` 触发、空白取消、词边界生效。Token 仅着主题色——
 * 附加字重/背景会被 Markdown 序列化成粗体等包裹语法，污染权威正文。 */
internal val DocumentMentionTrigger = Trigger(
    id = DOCUMENT_MENTION_TRIGGER_ID,
    char = '@',
    style = { SpanStyle(color = it.linkColor) },
)

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

/**
 * 文档块内的 @ 补全层：Trigger 查询活跃（编辑器持焦且光标处于 @ 查询上下文）时出现；
 * 选中候选以原子 Token（`insertToken`）写回该块的 [state]——退格整体删除、
 * 悬停手型光标、点击打开资料卡。候选过滤与聊天共用同一实现。
 */
@Composable
private fun DocumentMentionCompleteLayer(
    state: RichTextState,
    editorFocused: Boolean,
    sessionReady: Boolean,
    mentionCandidates: List<User>,
    modifier: Modifier = Modifier,
) {
    if (!sessionReady || mentionCandidates.isEmpty()) return
    val triggerQuery = if (editorFocused) state.activeTriggerQuery else null
    if (triggerQuery == null || triggerQuery.triggerId != DOCUMENT_MENTION_TRIGGER_ID) return
    val candidates = filterMentionCandidates(mentionCandidates, triggerQuery.query, myUid = null)
    if (candidates.isEmpty()) return
    AutoCompleteOverlay(
        title = "提及成员",
        items = mentionAutoCompleteItems(candidates).take(5),
        modifier = modifier,
        onPick = { item ->
            candidates.find { it.uid == item.payload }?.let { user ->
                // 原子 Token：随后的退格/删除按整体处理，序列化回 mention:// 权威语法。
                state.insertToken(
                    triggerId = DOCUMENT_MENTION_TRIGGER_ID,
                    id = user.uid,
                    label = "@" + mentionDisplayName(user),
                )
            }
        },
    )
}

/**
 * 文档块编辑器的链接路由：`mention://uid` 点击打开用户资料卡，其余协议交给
 * 平台默认处理器。编辑器内点击链接走 fork 的 LocalUriHandler 管道，系统不认识
 * mention 协议会静默失败，因此必须在块编辑组合内替换为感知 mention 的实现。
 */
@Composable
private fun rememberDocumentEditorUriHandler(): androidx.compose.ui.platform.UriHandler {
    val parentUriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val onMentionProfileOpen = com.virjar.tk.app.ui.bridge.LocalDocumentMentionSupport.current.onMentionProfileOpen
    return remember(parentUriHandler, onMentionProfileOpen) {
        object : androidx.compose.ui.platform.UriHandler {
            override fun openUri(uri: String) {
                if (uri.startsWith("mention://")) {
                    val uid = uri.removePrefix("mention://")
                    if (uid.isNotBlank()) onMentionProfileOpen(uid)
                    return
                }
                runCatching { parentUriHandler.openUri(uri) }
            }
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
    mentionCandidates: List<User> = emptyList(),
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
            runCatching { state.registerTrigger(DocumentMentionTrigger) }
            state.setMarkdown(markdownWithEditorMentionTokens(initialBlock.markdown))
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
                else -> current.copy(
                    markdown = markdownWithMentionLinks(requireNotNull(markdown)),
                    dirty = true,
                )
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
            else block.copy(markdown = markdownWithMentionLinks(markdown), dirty = true)
        )
    }
    LaunchedEffect(session.ready, pendingActivation, pendingFocus) {
        if (session.ready && pendingActivation) {
            onConsumePendingActivation(state) { focusRequester.requestFocus() }
        }
    }

    var editorFocused by remember(block.key) { mutableStateOf(false) }
    val editorUriHandler = rememberDocumentEditorUriHandler()
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
        Box(Modifier.fillMaxWidth()) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalUriHandler provides editorUriHandler,
            ) {
                BasicRichTextEditor(
                    state = state,
                    enabled = session.ready,
                    minLines = 2,
                    onLinkClick = editorUriHandler::openUri,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)
                        .focusRequester(focusRequester)
                        .onFocusChanged { focus ->
                            editorFocused = focus.isFocused
                            if (session.ready && focus.isFocused) onActivate(state) { focusRequester.requestFocus() }
                        }
                        .testTag("documents.editor.rich.${block.key}"),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = LocalContentColor.current),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                )
            }
            if (session.ready && state.annotatedString.text.isEmpty()) {
                Text(
                    "输入正文，或从工具栏插入引用、代码块和表格…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DocumentMentionCompleteLayer(
            state = state,
            editorFocused = editorFocused,
            sessionReady = session.ready,
            mentionCandidates = mentionCandidates,
        )
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
    mentionCandidates: List<User> = emptyList(),
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
                        mentionCandidates = mentionCandidates,
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
    mentionCandidates: List<User> = emptyList(),
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
            runCatching { state.registerTrigger(DocumentMentionTrigger) }
            state.setMarkdown(markdownWithEditorMentionTokens(initialBlock.innerMarkdown))
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
                else -> current.copy(
                    innerMarkdown = markdownWithMentionLinks(requireNotNull(markdown)),
                    dirty = true,
                )
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
            else block.copy(innerMarkdown = markdownWithMentionLinks(markdown), dirty = true)
        )
    }
    LaunchedEffect(session.ready, pendingActivation, pendingFocus) {
        if (session.ready && pendingActivation) {
            onConsumePendingActivation(state) { focusRequester.requestFocus() }
        }
    }
    var editorFocused by remember(block.key) { mutableStateOf(false) }
    val editorUriHandler = rememberDocumentEditorUriHandler()
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalUriHandler provides editorUriHandler,
            ) {
                BasicRichTextEditor(
                    state = state,
                    enabled = session.ready,
                    minLines = 2,
                    onLinkClick = editorUriHandler::openUri,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)
                        .focusRequester(focusRequester)
                        .onFocusChanged {
                            editorFocused = it.isFocused
                            if (session.ready && it.isFocused) onActivate(state) { focusRequester.requestFocus() }
                        }
                        .testTag("documents.editor.quote.body.${block.key}"),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = LocalContentColor.current),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                )
            }
            if (session.ready && state.annotatedString.text.isEmpty()) {
                Text("输入引用内容…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DocumentMentionCompleteLayer(
            state = state,
            editorFocused = editorFocused,
            sessionReady = session.ready,
            mentionCandidates = mentionCandidates,
        )
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
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    when (block) {
        // 图片走无容器渲染（内测 T032）：默认只显示图片本体，hover/菜单时浮出操作。
        is DocumentEmbeddedImageBlock -> DocumentEmbeddedImageBlockEditor(
            block = block,
            canMoveUp = canMoveUp,
            canMoveDown = canMoveDown,
            embeddedAssetContent = embeddedAssetContent,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            onDelete = onDelete,
            modifier = modifier,
        )
        is DocumentEmbeddedFileBlock -> DocumentEmbeddedFileBlockEditor(
            block = block,
            canMoveUp = canMoveUp,
            canMoveDown = canMoveDown,
            embeddedAssetContent = embeddedAssetContent,
            onActivate = onActivate,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            onDelete = onDelete,
            modifier = modifier,
        )
    }
}

/**
 * 文档内嵌图片块（内测 T032 重构）：文档以内容展示为核心——默认不渲染卡片容器与
 * 操作头栏，只在 hover 时浮出操作工具条；点击图片进入全幅查看。
 */
@Composable
internal fun DocumentEmbeddedImageBlockEditor(
    block: DocumentEmbeddedImageBlock,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    embeddedAssetContent: EmbeddedAssetMarkdownContent?,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    var hovered by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    Box(
        modifier
            .testTag("documents.editor.asset.image.${block.asset.assetId}")
            .clickable { com.virjar.tk.app.ui.screen.DocumentImageFullscreenHost.show(block.asset) {} }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Enter -> hovered = true
                            PointerEventType.Exit -> hovered = false
                            else -> {}
                        }
                    }
                }
            },
    ) {
        if (embeddedAssetContent != null) {
            embeddedAssetContent(
                block.asset,
                EmbeddedAssetPresentation.IMAGE,
                Modifier.fillMaxWidth(),
            )
        } else {
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Text(block.label.ifBlank { block.asset.attachment.name })
                Text(
                    block.asset.attachment.contentType,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (hovered || menuExpanded) {
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DocumentBlockMenu(
                    canMoveUp = canMoveUp,
                    canMoveDown = canMoveDown,
                    onMoveUp = onMoveUp,
                    onMoveDown = onMoveDown,
                    onDelete = onDelete,
                    testTagPrefix = "documents.editor.asset.image.${block.asset.assetId}",
                    compactIcon = true,
                    expanded = menuExpanded,
                    onExpandedChange = { menuExpanded = it },
                )
            }
        }
    }
}

@Composable
private fun DocumentEmbeddedFileBlockEditor(
    block: DocumentEmbeddedFileBlock,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    embeddedAssetContent: EmbeddedAssetMarkdownContent?,
    onActivate: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    val presentation = EmbeddedAssetPresentation.FILE
    val prefix = "documents.editor.asset.file.${block.asset.assetId}"
    Surface(
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.testTag(prefix),
        onClick = onActivate,
    ) {
        Column {
            DocumentBlockHeader(
                icon = { Icon(Icons.Filled.AttachFile, null) },
                label = "内嵌文件",
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
    compact: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (compact) 8.dp else 12.dp, top = 4.dp, end = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) { icon() }
        if (!compact) {
            Spacer(Modifier.width(7.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
    /** 图片块 hover 工具条模式：受控展开、紧凑图标（内测 T032）。 */
    compactIcon: Boolean = false,
    expanded: Boolean = false,
    onExpandedChange: (Boolean) -> Unit = {},
) {
    val isOpen = if (compactIcon) expanded else expanded || run {
        var state by remember { mutableStateOf(false) }
        state
    }
    Box {
        IconButton(
            onClick = {
                if (compactIcon) onExpandedChange(!isOpen) else onExpandedChange(true)
            },
            modifier = Modifier.size(if (compactIcon) 26.dp else 36.dp).testTag("$testTagPrefix.more"),
        ) { Icon(Icons.Filled.MoreVert, contentDescription = "内容块操作", Modifier.size(if (compactIcon) 17.dp else 19.dp)) }
        DropdownMenu(expanded = isOpen, onDismissRequest = { onExpandedChange(false) }) {
            DropdownMenuItem(
                text = { Text("上移") },
                leadingIcon = { Icon(Icons.Filled.ArrowUpward, null) },
                enabled = canMoveUp,
                onClick = { onExpandedChange(false); onMoveUp() },
            )
            DropdownMenuItem(
                text = { Text("下移") },
                leadingIcon = { Icon(Icons.Filled.ArrowDownward, null) },
                enabled = canMoveDown,
                onClick = { onExpandedChange(false); onMoveDown() },
            )
            DropdownMenuItem(
                text = { Text("删除内容块", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Filled.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                onClick = { onExpandedChange(false); onDelete() },
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
