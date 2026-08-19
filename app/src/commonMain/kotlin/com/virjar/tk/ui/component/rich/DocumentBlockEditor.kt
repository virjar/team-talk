package com.virjar.tk.ui.component.rich

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor

/**
 * Document-only block editor.
 *
 * Markdown remains the authoritative value. Ordinary prose is delegated to the rich-text fork,
 * while structures that are not linear text (quote, fenced code, and table) own visual editors.
 * Unsupported extensions are isolated in a local source card instead of degrading the whole
 * document to a source textarea.
 */
@Stable
internal class DocumentBlockEditorController {
    var activeBlockKey: String? by mutableStateOf(null)
        private set
    var activeRichState: RichTextState? by mutableStateOf(null)
        private set
    var pendingActivationKey: String? by mutableStateOf(null)
        private set
    var pendingFocusKey: String? by mutableStateOf(null)
        private set

    private var focusAction: () -> Unit = {}
    private var insertAction: (DocumentBlockInsertKind) -> Unit = {}
    private var appendAction: (DocumentBlockInsertKind) -> Unit = {}
    private var snapshotAction: (String) -> String = { it }

    fun requestRichTextFocus() = focusAction()
    fun insertParagraph() = insertAction(DocumentBlockInsertKind.RICH)
    fun insertQuote() = insertAction(DocumentBlockInsertKind.QUOTE)
    fun insertCodeFence() = insertAction(DocumentBlockInsertKind.CODE)
    fun insertTable() = insertAction(DocumentBlockInsertKind.TABLE)
    fun appendParagraph() = appendAction(DocumentBlockInsertKind.RICH)
    fun appendQuote() = appendAction(DocumentBlockInsertKind.QUOTE)
    fun appendCodeFence() = appendAction(DocumentBlockInsertKind.CODE)
    fun appendTable() = appendAction(DocumentBlockInsertKind.TABLE)
    fun snapshotMarkdown(fallback: String): String = snapshotAction(fallback)

    internal fun activate(
        blockKey: String,
        richState: RichTextState? = null,
        requestFocus: () -> Unit = {},
    ) {
        pendingActivationKey = null
        pendingFocusKey = null
        activeBlockKey = blockKey
        activeRichState = richState
        focusAction = requestFocus
    }

    /**
     * Selects a rich block before its LazyColumn item/editor session is ready. The item consumes
     * this request exactly once after attaching its FocusRequester, so recycling it later cannot
     * unexpectedly steal focus.
     */
    internal fun requestRichActivation(blockKey: String, requestFocus: Boolean = true) {
        activeBlockKey = blockKey
        activeRichState = null
        focusAction = {}
        pendingActivationKey = blockKey
        pendingFocusKey = blockKey.takeIf { requestFocus }
    }

    internal fun consumePendingRichActivation(
        blockKey: String,
        richState: RichTextState,
        requestFocus: () -> Unit,
    ): Boolean {
        if (pendingActivationKey != blockKey) return false
        val shouldRequestFocus = pendingFocusKey == blockKey
        pendingActivationKey = null
        pendingFocusKey = null
        activeBlockKey = blockKey
        activeRichState = richState
        focusAction = requestFocus
        if (shouldRequestFocus) requestFocus()
        return true
    }

    internal fun deactivate(blockKey: String) {
        if (pendingActivationKey == blockKey) {
            pendingActivationKey = null
            pendingFocusKey = null
        }
        if (activeBlockKey == blockKey) {
            activeBlockKey = null
            activeRichState = null
            focusAction = {}
        }
    }

    internal fun bindActions(
        insert: (DocumentBlockInsertKind) -> Unit,
        append: (DocumentBlockInsertKind) -> Unit,
        snapshot: (String) -> String,
    ) {
        insertAction = insert
        appendAction = append
        snapshotAction = snapshot
    }

    internal fun clear() {
        activeBlockKey = null
        activeRichState = null
        pendingActivationKey = null
        pendingFocusKey = null
        focusAction = {}
        insertAction = {}
        appendAction = {}
        // Keep the last synchronous snapshot closure until this controller is collected. During
        // a tab/source/preview transition the child editor may dispose before its parent publishes
        // the draft; clearing this closure here would make that final publication stale.
    }
}

internal enum class DocumentBlockInsertKind { RICH, QUOTE, CODE, TABLE }

/**
 * Owns a rich block's editor state for the lifetime of the document canvas, rather than for the
 * lifetime of a LazyColumn item. Scrolling a block out of composition must never discard the last
 * keystroke or recreate an editor from a stale Markdown projection.
 */
@Stable
private class DocumentRichEditorSession(
    val state: RichTextState,
    val originalBlock: DocumentMarkdownBlock,
) {
    var ready by mutableStateOf(false)
    var normalizedBaseline by mutableStateOf("")
    var lastReportedMarkdown by mutableStateOf("")
}

@Composable
internal fun rememberDocumentBlockEditorController(documentKey: String): DocumentBlockEditorController =
    remember(documentKey) { DocumentBlockEditorController() }

@Composable
internal fun DocumentBlockFormattingToolbar(
    controller: DocumentBlockEditorController,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val compact = maxWidth < 720.dp
        var insertMenu by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val richState = controller.activeRichState
            if (richState != null) {
                RichTextFormattingToolbar(
                    state = richState,
                    mode = RichTextToolbarMode.DOCUMENT,
                    onRequestFocus = controller::requestRichTextFocus,
                    modifier = Modifier.weight(1f).padding(4.dp),
                    testTagPrefix = "documents.editor.format",
                )
            } else {
                Text(
                    "当前为结构化内容块",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
            }

            Spacer(
                Modifier.width(1.dp).height(24.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            if (compact) {
                Box {
                    TextButton(
                        onClick = { insertMenu = true },
                        modifier = Modifier.testTag("documents.editor.block.insert"),
                    ) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("插入")
                    }
                    DropdownMenu(expanded = insertMenu, onDismissRequest = { insertMenu = false }) {
                        DocumentBlockInsertMenuItems(
                            onRich = { insertMenu = false; controller.insertParagraph() },
                            onQuote = { insertMenu = false; controller.insertQuote() },
                            onCode = { insertMenu = false; controller.insertCodeFence() },
                            onTable = { insertMenu = false; controller.insertTable() },
                        )
                    }
                }
            } else {
                DocumentBlockInsertButton(
                    label = "正文",
                    icon = { Icon(Icons.Filled.Add, null) },
                    testTag = "documents.editor.block.rich",
                    onClick = controller::insertParagraph,
                )
                DocumentBlockInsertButton(
                    label = "引用",
                    icon = { Icon(Icons.Filled.FormatQuote, null) },
                    testTag = "documents.editor.block.quote",
                    onClick = controller::insertQuote,
                )
                DocumentBlockInsertButton(
                    label = "代码块",
                    icon = { Icon(Icons.Filled.Code, null) },
                    testTag = "documents.editor.block.code",
                    onClick = controller::insertCodeFence,
                )
                DocumentBlockInsertButton(
                    label = "表格",
                    icon = { Icon(Icons.Filled.TableChart, null) },
                    testTag = "documents.editor.block.table",
                    onClick = controller::insertTable,
                )
            }
        }
    }
}

@Composable
private fun DocumentBlockInsertButton(
    label: String,
    icon: @Composable () -> Unit,
    testTag: String,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, modifier = Modifier.testTag(testTag)) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(4.dp))
        Text(label)
    }
}

@Composable
private fun DocumentBlockInsertMenuItems(
    onRich: () -> Unit,
    onQuote: () -> Unit,
    onCode: () -> Unit,
    onTable: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text("正文段落") },
        leadingIcon = { Icon(Icons.Filled.Add, null) },
        onClick = onRich,
        modifier = Modifier.testTag("documents.editor.block.rich"),
    )
    DropdownMenuItem(
        text = { Text("引用") },
        leadingIcon = { Icon(Icons.Filled.FormatQuote, null) },
        onClick = onQuote,
        modifier = Modifier.testTag("documents.editor.block.quote"),
    )
    DropdownMenuItem(
        text = { Text("代码块") },
        leadingIcon = { Icon(Icons.Filled.Code, null) },
        onClick = onCode,
        modifier = Modifier.testTag("documents.editor.block.code"),
    )
    DropdownMenuItem(
        text = { Text("表格") },
        leadingIcon = { Icon(Icons.Filled.TableChart, null) },
        onClick = onTable,
        modifier = Modifier.testTag("documents.editor.block.table"),
    )
}

@Composable
internal fun DocumentBlockEditor(
    documentKey: String,
    initialMarkdown: String,
    controller: DocumentBlockEditorController,
    onMarkdownChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(documentKey) {
        mutableStateListOf<DocumentMarkdownBlock>().apply {
            addAll(DocumentMarkdownBlockCodec.parse(initialMarkdown))
        }
    }
    val richSnapshots = remember(documentKey) {
        mutableMapOf<String, (DocumentMarkdownBlock) -> DocumentMarkdownBlock>()
    }
    val richSessions = remember(documentKey) {
        mutableMapOf<String, DocumentRichEditorSession>()
    }
    val initialActiveKey = remember(documentKey) {
        blocks.firstOrNull { block ->
            block is DocumentRichRun ||
                (block is DocumentQuoteBlock &&
                    !RichEditorMarkdownCapability.inspect(block.innerMarkdown).requiresSourceMode)
        }?.key
    }
    var nextKey by remember(documentKey) { mutableStateOf(blocks.size + 1L) }
    fun nextBlockKey(type: String): String = "document-insert-$documentKey-$type-${nextKey++}"

    fun snapshotBlock(block: DocumentMarkdownBlock): DocumentMarkdownBlock {
        if (
            block is DocumentQuoteBlock &&
            RichEditorMarkdownCapability.inspect(block.innerMarkdown).requiresSourceMode
        ) {
            return block
        }
        return richSnapshots[block.key]?.invoke(block) ?: block
    }

    fun materializeRichSnapshots() {
        val latest = blocks.map(::snapshotBlock)
        latest.forEachIndexed { index, block ->
            if (blocks[index] != block) blocks[index] = block
        }
    }

    fun newRichRun(leadingMarkdown: String = ""): DocumentRichRun = DocumentRichRun(
        key = nextBlockKey("rich"),
        markdown = "",
        leadingMarkdown = leadingMarkdown,
    )

    fun separatorAfter(markdown: String): String = when {
        markdown.isEmpty() -> ""
        markdown.endsWith("\r\n\r\n") || markdown.endsWith("\n\n") || markdown.endsWith("\r\r") -> ""
        markdown.endsWith("\r\n") -> "\r\n"
        markdown.endsWith('\n') -> "\n"
        markdown.endsWith('\r') -> "\r"
        else -> "\n\n"
    }

    fun normalizeBlockLayout(finalTrailing: String = blocks.firstNotNullOfOrNull { block ->
        block.trailingMarkdown.takeIf(String::isNotEmpty)
    }.orEmpty()) {
        val normalized = blocks.mapIndexed { index, block ->
            block.withDocumentLayout(
                leadingMarkdown = if (index == 0) "" else "\n\n",
                trailingMarkdown = if (index == blocks.lastIndex) finalTrailing else "",
            )
        }
        blocks.clear()
        blocks.addAll(normalized)
    }

    fun createInsertedBlock(
        kind: DocumentBlockInsertKind,
        leadingMarkdown: String,
    ): DocumentMarkdownBlock = when (kind) {
        DocumentBlockInsertKind.RICH -> newRichRun(leadingMarkdown)
        DocumentBlockInsertKind.QUOTE -> DocumentQuoteBlock(
            key = nextBlockKey("quote"),
            innerMarkdown = "",
            leadingMarkdown = leadingMarkdown,
        )
        DocumentBlockInsertKind.CODE -> DocumentCodeFenceBlock(
            key = nextBlockKey("code"),
            language = null,
            code = "",
            leadingMarkdown = leadingMarkdown,
        )
        DocumentBlockInsertKind.TABLE -> DocumentGfmTableBlock(
            key = nextBlockKey("table"),
            headers = listOf("列 1", "列 2"),
            alignments = listOf(DocumentTableAlignment.NONE, DocumentTableAlignment.NONE),
            rows = listOf(listOf("", "")),
            leadingMarkdown = leadingMarkdown,
        )
    }

    fun activateBlockWhenReady(block: DocumentMarkdownBlock?) {
        when (block) {
            is DocumentRichRun -> controller.requestRichActivation(block.key)
            is DocumentQuoteBlock -> {
                if (RichEditorMarkdownCapability.inspect(block.innerMarkdown).requiresSourceMode) {
                    controller.activate(block.key)
                } else {
                    controller.requestRichActivation(block.key)
                }
            }
            else -> controller.activate(block?.key.orEmpty())
        }
    }

    fun insertBlock(kind: DocumentBlockInsertKind, appendToEnd: Boolean = false) {
        materializeRichSnapshots()
        val activeIndex = blocks.indexOfFirst { it.key == controller.activeBlockKey }
            .takeIf { it >= 0 } ?: blocks.lastIndex
        val active = blocks.getOrNull(activeIndex)
        val activeState = controller.activeRichState

        // A structural block inserted from the formatting bar belongs at the caret, not at the
        // end of a potentially very long RichRun. For a non-collapsed selection we insert after
        // the selection instead of deleting it: structural edits do not yet have document-level
        // undo, so preserving user content is the safe default.
        if (
            !appendToEnd && kind != DocumentBlockInsertKind.RICH &&
            active is DocumentRichRun && activeState != null
        ) {
            val latest = snapshotBlock(active) as DocumentRichRun
            val selection = activeState.selection
            val textLength = activeState.annotatedString.text.length
            val insertionOffset = selection.max
            val before = if (insertionOffset > 0) {
                activeState.toMarkdown(TextRange(0, insertionOffset))
            } else {
                ""
            }
            val after = if (insertionOffset < textLength) {
                activeState.toMarkdown(TextRange(insertionOffset, textLength))
            } else {
                ""
            }
            val prefixBlocks = blocks.take(activeIndex)
            val replacement = mutableListOf<DocumentMarkdownBlock>()
            if (before.isNotEmpty()) {
                replacement += DocumentRichRun(
                    key = nextBlockKey("rich-before"),
                    markdown = before,
                    leadingMarkdown = latest.leadingMarkdown,
                )
            }
            val prefixMarkdown = DocumentMarkdownBlockCodec.encode(prefixBlocks + replacement)
            val inserted = createInsertedBlock(
                kind = kind,
                leadingMarkdown = if (replacement.isEmpty()) latest.leadingMarkdown else separatorAfter(prefixMarkdown),
            )
            replacement += inserted
            val afterPrefix = DocumentMarkdownBlockCodec.encode(prefixBlocks + replacement)
            replacement += DocumentRichRun(
                key = nextBlockKey("rich-after"),
                markdown = after,
                leadingMarkdown = separatorAfter(afterPrefix),
                trailingMarkdown = latest.trailingMarkdown,
            )
            blocks.removeAt(activeIndex)
            richSnapshots.remove(active.key)
            richSessions.remove(active.key)
            blocks.addAll(activeIndex, replacement)
            activateBlockWhenReady(inserted)
            return
        }

        val insertionIndex = if (appendToEnd) blocks.size else (activeIndex + 1).coerceIn(0, blocks.size)
        val leadingMarkdown = separatorAfter(DocumentMarkdownBlockCodec.encode(blocks.take(insertionIndex)))
        val inserted = createInsertedBlock(kind, leadingMarkdown)
        blocks.add(insertionIndex, inserted)
        if (kind != DocumentBlockInsertKind.RICH && blocks.getOrNull(insertionIndex + 1) !is DocumentRichRun) {
            val richLeading = separatorAfter(DocumentMarkdownBlockCodec.encode(blocks.take(insertionIndex + 1)))
            blocks.add(insertionIndex + 1, newRichRun(richLeading))
        }
        activateBlockWhenReady(inserted)
    }

    fun replaceBlock(block: DocumentMarkdownBlock) {
        val index = blocks.indexOfFirst { it.key == block.key }
        if (index >= 0) blocks[index] = block
    }

    fun deleteBlock(index: Int) {
        if (index !in blocks.indices) return
        materializeRichSnapshots()
        val finalTrailing = blocks.firstNotNullOfOrNull { block ->
            block.trailingMarkdown.takeIf(String::isNotEmpty)
        }.orEmpty()
        val removed = blocks.removeAt(index)
        richSnapshots.remove(removed.key)
        richSessions.remove(removed.key)
        if (blocks.isEmpty()) blocks += newRichRun()
        else normalizeBlockLayout(finalTrailing)
        activateBlockWhenReady(blocks.getOrNull(index.coerceAtMost(blocks.lastIndex)))
    }

    fun moveBlock(index: Int, delta: Int) {
        materializeRichSnapshots()
        val destination = index + delta
        if (index !in blocks.indices || destination !in blocks.indices) return
        val finalTrailing = blocks.firstNotNullOfOrNull { candidate ->
            candidate.trailingMarkdown.takeIf(String::isNotEmpty)
        }.orEmpty()
        val block = blocks.removeAt(index)
        blocks.add(destination, block)
        normalizeBlockLayout(finalTrailing)
    }

    SideEffect {
        controller.bindActions(
            insert = { insertBlock(it, appendToEnd = false) },
            append = { insertBlock(it, appendToEnd = true) },
            snapshot = { fallback ->
                if (blocks.isEmpty()) fallback else DocumentMarkdownBlockCodec.encode(
                    blocks.map(::snapshotBlock)
                )
            },
        )
    }
    DisposableEffect(documentKey) {
        onDispose { controller.clear() }
    }

    val currentMarkdown = DocumentMarkdownBlockCodec.encode(blocks)
    LaunchedEffect(currentMarkdown) { onMarkdownChange(currentMarkdown) }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("documents.editor.blocks"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        itemsIndexed(blocks, key = { _, block -> block.key }) { index, block ->
            DisposableEffect(block.key) {
                onDispose { controller.deactivate(block.key) }
            }
            Box(Modifier.fillMaxWidth().widthIn(max = 920.dp)) {
                when (block) {
                    is DocumentRichRun -> {
                        val session = richSessions.getOrPut(block.key) {
                            DocumentRichEditorSession(RichTextState(), block)
                        }
                        DocumentRichRunEditor(
                            block = block,
                            session = session,
                            initiallyActive = block.key == initialActiveKey,
                            pendingActivation = controller.pendingActivationKey == block.key,
                            pendingFocus = controller.pendingFocusKey == block.key,
                            onActivate = { state, focus -> controller.activate(block.key, state, focus) },
                            onConsumePendingActivation = { state, focus ->
                                controller.consumePendingRichActivation(block.key, state, focus)
                            },
                            onSnapshot = { provider -> richSnapshots[block.key] = provider },
                            onChange = ::replaceBlock,
                        )
                    }
                    is DocumentQuoteBlock -> {
                        val useRichEditor = !RichEditorMarkdownCapability.inspect(block.innerMarkdown)
                            .requiresSourceMode
                        val session = if (useRichEditor) {
                            richSessions.getOrPut(block.key) {
                                DocumentRichEditorSession(RichTextState(), block)
                            }
                        } else {
                            richSnapshots.remove(block.key)
                            richSessions.remove(block.key)
                            null
                        }
                        LaunchedEffect(block.key, useRichEditor) {
                            if (
                                !useRichEditor && controller.activeBlockKey == block.key &&
                                controller.activeRichState != null
                            ) {
                                // The quote just switched to its block-local source editor. Drop
                                // the now-unmounted RichTextState/FocusRequester immediately.
                                controller.activate(block.key)
                            }
                        }
                        DocumentQuoteBlockEditor(
                            block = block,
                            session = session,
                            canMoveUp = index > 0,
                            canMoveDown = index < blocks.lastIndex,
                            initiallyActive = block.key == initialActiveKey,
                            pendingActivation = controller.pendingActivationKey == block.key,
                            pendingFocus = controller.pendingFocusKey == block.key,
                            onActivate = { state, focus -> controller.activate(block.key, state, focus) },
                            onConsumePendingActivation = { state, focus ->
                                controller.consumePendingRichActivation(block.key, state, focus)
                            },
                            onSnapshot = { provider -> richSnapshots[block.key] = provider },
                            onActivateSource = { controller.activate(block.key) },
                            onChange = ::replaceBlock,
                            onMoveUp = { moveBlock(index, -1) },
                            onMoveDown = { moveBlock(index, 1) },
                            onDelete = { deleteBlock(index) },
                        )
                    }
                    is DocumentCodeFenceBlock -> DocumentCodeBlockEditor(
                        block = block,
                        canMoveUp = index > 0,
                        canMoveDown = index < blocks.lastIndex,
                        onActivate = { controller.activate(block.key) },
                        onChange = ::replaceBlock,
                        onMoveUp = { moveBlock(index, -1) },
                        onMoveDown = { moveBlock(index, 1) },
                        onDelete = { deleteBlock(index) },
                    )
                    is DocumentGfmTableBlock -> DocumentTableBlockEditor(
                        block = block,
                        canMoveUp = index > 0,
                        canMoveDown = index < blocks.lastIndex,
                        onActivate = { controller.activate(block.key) },
                        onChange = ::replaceBlock,
                        onMoveUp = { moveBlock(index, -1) },
                        onMoveDown = { moveBlock(index, 1) },
                        onDelete = { deleteBlock(index) },
                    )
                    is DocumentOpaqueRawBlock -> DocumentRawBlockEditor(
                        block = block,
                        canMoveUp = index > 0,
                        canMoveDown = index < blocks.lastIndex,
                        onActivate = { controller.activate(block.key) },
                        onChange = ::replaceBlock,
                        onMoveUp = { moveBlock(index, -1) },
                        onMoveDown = { moveBlock(index, 1) },
                        onDelete = { deleteBlock(index) },
                    )
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().widthIn(max = 920.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                TextButton(
                    onClick = controller::appendParagraph,
                    modifier = Modifier.testTag("documents.editor.block.bottom.rich"),
                ) { Icon(Icons.Filled.Add, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text("正文") }
                TextButton(
                    onClick = controller::appendQuote,
                    modifier = Modifier.testTag("documents.editor.block.bottom.quote"),
                ) { Icon(Icons.Filled.FormatQuote, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text("引用") }
                TextButton(
                    onClick = controller::appendCodeFence,
                    modifier = Modifier.testTag("documents.editor.block.bottom.code"),
                ) { Icon(Icons.Filled.Code, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text("代码块") }
                TextButton(
                    onClick = controller::appendTable,
                    modifier = Modifier.testTag("documents.editor.block.bottom.table"),
                ) { Icon(Icons.Filled.TableChart, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text("表格") }
            }
        }
    }
}

@Composable
private fun DocumentRichRunEditor(
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
    val currentMarkdown = if (session.ready) state.toMarkdown() else block.markdown
    SideEffect {
        onSnapshot { currentBlock ->
            val current = currentBlock as? DocumentRichRun ?: block
            when {
                !session.ready -> current
                state.toMarkdown() == session.normalizedBaseline -> initialBlock.withDocumentLayout(
                    leadingMarkdown = current.leadingMarkdown,
                    trailingMarkdown = current.trailingMarkdown,
                )
                else -> current.copy(markdown = state.toMarkdown(), dirty = true)
            }
        }
    }
    LaunchedEffect(session.ready, currentMarkdown) {
        if (session.ready && currentMarkdown != session.lastReportedMarkdown) {
            session.lastReportedMarkdown = currentMarkdown
            onChange(
                if (currentMarkdown == session.normalizedBaseline) {
                    initialBlock.withDocumentLayout(
                        leadingMarkdown = block.leadingMarkdown,
                        trailingMarkdown = block.trailingMarkdown,
                    ) as DocumentRichRun
                }
                else block.copy(markdown = currentMarkdown, dirty = true)
            )
        }
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
private fun DocumentQuoteBlockEditor(
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
    val currentMarkdown = if (session.ready) state.toMarkdown() else block.innerMarkdown
    SideEffect {
        onSnapshot { currentBlock ->
            val current = currentBlock as? DocumentQuoteBlock ?: block
            when {
                !session.ready -> current
                state.toMarkdown() == session.normalizedBaseline -> initialBlock.withDocumentLayout(
                    leadingMarkdown = current.leadingMarkdown,
                    trailingMarkdown = current.trailingMarkdown,
                )
                else -> current.copy(innerMarkdown = state.toMarkdown(), dirty = true)
            }
        }
    }
    LaunchedEffect(session.ready, currentMarkdown) {
        if (session.ready && currentMarkdown != session.lastReportedMarkdown) {
            session.lastReportedMarkdown = currentMarkdown
            onChange(
                if (currentMarkdown == session.normalizedBaseline) {
                    initialBlock.withDocumentLayout(
                        leadingMarkdown = block.leadingMarkdown,
                        trailingMarkdown = block.trailingMarkdown,
                    ) as DocumentQuoteBlock
                }
                else block.copy(innerMarkdown = currentMarkdown, dirty = true)
            )
        }
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
private fun DocumentCodeBlockEditor(
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
                        // Keep an in-progress trailing space so users can type a complete info
                        // string (for example `kotlin title="sample"`) without fighting the field.
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
private fun DocumentTableBlockEditor(
    block: DocumentGfmTableBlock,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onActivate: () -> Unit,
    onChange: (DocumentGfmTableBlock) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    val columnCount = maxOf(
        1,
        block.headers.size,
        block.alignments.size,
        block.rows.maxOfOrNull { it.size } ?: 0,
    )
    fun normalizedHeaders(): List<String> = block.headers + List((columnCount - block.headers.size).coerceAtLeast(0)) { "" }
    fun normalizedRows(): List<List<String>> = block.rows.map { row ->
        row + List((columnCount - row.size).coerceAtLeast(0)) { "" }
    }
    fun normalizedAlignments(): List<DocumentTableAlignment> = block.alignments +
        List((columnCount - block.alignments.size).coerceAtLeast(0)) { DocumentTableAlignment.NONE }

    Surface(
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag("documents.editor.table.${block.key}"),
    ) {
        Column {
            DocumentBlockHeader(
                icon = { Icon(Icons.Filled.TableChart, null) },
                label = "表格 · $columnCount 列",
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                onDelete = onDelete,
                testTagPrefix = "documents.editor.table.${block.key}",
            )
            Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                DocumentTableEditorRow(
                    cells = normalizedHeaders(),
                    alignments = normalizedAlignments(),
                    header = true,
                    testTagPrefix = "documents.editor.table.header.${block.key}",
                    onActivate = onActivate,
                    onCellChange = { column, value ->
                        val headers = normalizedHeaders().toMutableList().apply { this[column] = value }
                        onChange(block.copy(headers = headers, dirty = true))
                    },
                    onAlignmentChange = { column, alignment ->
                        val alignments = normalizedAlignments().toMutableList().apply { this[column] = alignment }
                        onChange(block.copy(alignments = alignments, dirty = true))
                    },
                )
                normalizedRows().forEachIndexed { rowIndex, row ->
                    DocumentTableEditorRow(
                        cells = row,
                        alignments = normalizedAlignments(),
                        header = false,
                        testTagPrefix = "documents.editor.table.row.$rowIndex.${block.key}",
                        onActivate = onActivate,
                        onCellChange = { column, value ->
                            val rows = normalizedRows().map { it.toMutableList() }.toMutableList()
                            rows[rowIndex][column] = value
                            onChange(block.copy(rows = rows, dirty = true))
                        },
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = {
                        onChange(
                            block.copy(
                                rows = normalizedRows() + listOf(List(columnCount) { "" }),
                                dirty = true,
                            )
                        )
                    },
                    modifier = Modifier.testTag("documents.editor.table.addRow.${block.key}"),
                ) { Text("+ 行") }
                TextButton(
                    onClick = {
                        onChange(
                            block.copy(
                                headers = normalizedHeaders() + "列 ${columnCount + 1}",
                                alignments = normalizedAlignments() + DocumentTableAlignment.NONE,
                                rows = normalizedRows().map { it + "" },
                                dirty = true,
                            )
                        )
                    },
                    modifier = Modifier.testTag("documents.editor.table.addColumn.${block.key}"),
                ) { Text("+ 列") }
                if (block.rows.isNotEmpty()) TextButton(
                    onClick = { onChange(block.copy(rows = normalizedRows().dropLast(1), dirty = true)) },
                    modifier = Modifier.testTag("documents.editor.table.removeRow.${block.key}"),
                ) { Text("移除末行") }
                if (columnCount > 1) TextButton(
                    onClick = {
                        onChange(
                            block.copy(
                                headers = normalizedHeaders().dropLast(1),
                                alignments = normalizedAlignments().dropLast(1),
                                rows = normalizedRows().map { it.dropLast(1) },
                                dirty = true,
                            )
                        )
                    },
                    modifier = Modifier.testTag("documents.editor.table.removeColumn.${block.key}"),
                ) { Text("移除末列") }
            }
        }
    }
}

@Composable
private fun DocumentTableEditorRow(
    cells: List<String>,
    alignments: List<DocumentTableAlignment>,
    header: Boolean,
    testTagPrefix: String,
    onActivate: () -> Unit,
    onCellChange: (Int, String) -> Unit,
    onAlignmentChange: ((Int, DocumentTableAlignment) -> Unit)? = null,
) {
    Row {
        cells.forEachIndexed { index, value ->
            Surface(
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                color = if (header) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                else MaterialTheme.colorScheme.surface,
                modifier = Modifier.width(180.dp).heightIn(min = 48.dp),
            ) {
                val alignment = alignments.getOrNull(index) ?: DocumentTableAlignment.NONE
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = decodeDocumentTableCellForVisual(value),
                        onValueChange = { onCellChange(index, it.replace('\n', ' ')) },
                        modifier = Modifier.weight(1f).padding(vertical = 12.dp)
                            .onFocusChanged { if (it.isFocused) onActivate() }
                            .testTag("$testTagPrefix.$index"),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = LocalContentColor.current,
                            fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                            textAlign = alignment.textAlign(),
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        singleLine = true,
                    )
                    if (header && onAlignmentChange != null) {
                        IconButton(
                            onClick = { onAlignmentChange(index, alignment.next()) },
                            modifier = Modifier.size(32.dp).testTag("$testTagPrefix.align.$index"),
                        ) {
                            Icon(
                                imageVector = when (alignment) {
                                    DocumentTableAlignment.CENTER -> Icons.Filled.FormatAlignCenter
                                    DocumentTableAlignment.RIGHT -> Icons.AutoMirrored.Filled.FormatAlignRight
                                    else -> Icons.AutoMirrored.Filled.FormatAlignLeft
                                },
                                contentDescription = "第 ${index + 1} 列对齐方式",
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentRawBlockEditor(
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
private fun DocumentBlockHeader(
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

private fun DocumentTableAlignment.textAlign(): TextAlign = when (this) {
    DocumentTableAlignment.CENTER -> TextAlign.Center
    DocumentTableAlignment.RIGHT -> TextAlign.End
    else -> TextAlign.Start
}

private fun DocumentTableAlignment.next(): DocumentTableAlignment = when (this) {
    DocumentTableAlignment.NONE -> DocumentTableAlignment.CENTER
    DocumentTableAlignment.CENTER -> DocumentTableAlignment.RIGHT
    DocumentTableAlignment.RIGHT -> DocumentTableAlignment.LEFT
    DocumentTableAlignment.LEFT -> DocumentTableAlignment.NONE
}

/** A table's `\|` is storage syntax; the visual grid must show the literal pipe. */
internal fun decodeDocumentTableCellForVisual(markdown: String): String = buildString(markdown.length) {
    var index = 0
    while (index < markdown.length) {
        if (markdown[index] == '\\' && markdown.getOrNull(index + 1) == '|') {
            append('|')
            index += 2
        } else {
            append(markdown[index])
            index++
        }
    }
}

/** Reordering/deleting is an explicit structural edit, so separators are canonicalized safely. */
internal fun DocumentMarkdownBlock.withDocumentLayout(
    leadingMarkdown: String,
    trailingMarkdown: String,
): DocumentMarkdownBlock {
    val exactBody = if (!dirty) {
        originalMarkdown.substring(
            startIndex = this.leadingMarkdown.length.coerceAtMost(originalMarkdown.length),
            endIndex = (originalMarkdown.length - this.trailingMarkdown.length)
                .coerceAtLeast(this.leadingMarkdown.length.coerceAtMost(originalMarkdown.length)),
        )
    } else {
        null
    }
    fun original(): String = if (exactBody != null) leadingMarkdown + exactBody + trailingMarkdown else originalMarkdown

    return when (this) {
        is DocumentRichRun -> copy(
            originalMarkdown = original(),
            leadingMarkdown = leadingMarkdown,
            trailingMarkdown = trailingMarkdown,
        )
        is DocumentQuoteBlock -> copy(
            originalMarkdown = original(),
            leadingMarkdown = leadingMarkdown,
            trailingMarkdown = trailingMarkdown,
        )
        is DocumentCodeFenceBlock -> copy(
            originalMarkdown = original(),
            leadingMarkdown = leadingMarkdown,
            trailingMarkdown = trailingMarkdown,
        )
        is DocumentGfmTableBlock -> copy(
            originalMarkdown = original(),
            leadingMarkdown = leadingMarkdown,
            trailingMarkdown = trailingMarkdown,
        )
        is DocumentOpaqueRawBlock -> copy(
            originalMarkdown = original(),
            leadingMarkdown = leadingMarkdown,
            trailingMarkdown = trailingMarkdown,
        )
    }
}
