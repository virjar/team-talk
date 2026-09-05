package com.virjar.tk.app.ui.component.rich

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.RichTextState
import com.virjar.tk.protocol.model.EmbeddedAsset

/**
 * 文档专属的块编辑器。
 *
 * Markdown 始终是权威值。普通正文交给富文本分支，而引用、围栏代码块和表格等非线性文本
 * 结构拥有各自的视觉编辑器。不支持的扩展被隔离在本地源码卡片中，而不是把整个文档
 * 降级为源码 textarea。
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
    var embeddedAssetActionsBound: Boolean by mutableStateOf(false)
        private set

    private var focusAction: () -> Unit = {}
    private var insertAction: (DocumentBlockInsertKind) -> Unit = {}
    private var appendAction: (DocumentBlockInsertKind) -> Unit = {}
    private var insertEmbeddedAssetAction: (String, String, String?) -> Boolean = { _, _, _ -> false }
    private var snapshotAction: (String) -> String = { it }
    private var lastVisualAssetId: String? = null

    fun requestRichTextFocus() = focusAction()
    fun insertParagraph() = insertAction(DocumentBlockInsertKind.RICH)
    fun insertQuote() = insertAction(DocumentBlockInsertKind.QUOTE)
    fun insertCodeFence() = insertAction(DocumentBlockInsertKind.CODE)
    fun insertTable() = insertAction(DocumentBlockInsertKind.TABLE)
    fun appendParagraph() = appendAction(DocumentBlockInsertKind.RICH)
    fun appendQuote() = appendAction(DocumentBlockInsertKind.QUOTE)
    fun appendCodeFence() = appendAction(DocumentBlockInsertKind.CODE)
    fun appendTable() = appendAction(DocumentBlockInsertKind.TABLE)
    fun insertEmbeddedAsset(assetId: String, markdown: String): Boolean {
        val inserted = insertEmbeddedAssetAction(assetId, markdown, lastVisualAssetId)
        if (inserted) lastVisualAssetId = assetId
        return inserted
    }
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
     * 在 LazyColumn 条目/编辑器会话就绪之前选中一个富文本块。该条目在挂接其 FocusRequester
     * 之后恰好消费此请求一次，因此之后回收它不会意外抢占焦点。
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
        insertEmbeddedAsset: (String, String, String?) -> Boolean = { _, _, _ -> false },
        snapshot: (String) -> String,
    ) {
        insertAction = insert
        appendAction = append
        insertEmbeddedAssetAction = insertEmbeddedAsset
        snapshotAction = snapshot
        embeddedAssetActionsBound = true
    }

    internal fun clear(retainedMarkdown: String) {
        activeBlockKey = null
        activeRichState = null
        pendingActivationKey = null
        pendingFocusKey = null
        focusAction = {}
        insertAction = {}
        appendAction = {}
        insertEmbeddedAssetAction = { _, _, _ -> false }
        lastVisualAssetId = null
        embeddedAssetActionsBound = false
        // 退役中的子组件可能在其父级捕获最终草稿之前离开组合。调用方必须先物化该值，
        // 在整个源码或预览模式下只保留一个 String，而不是完整的块/会话对象图。
        snapshotAction = { retainedMarkdown }
    }
}

internal enum class DocumentBlockInsertKind { RICH, QUOTE, CODE, TABLE }

/**
 * 在块模型可能已被 READY sidecar 重建之后解析视觉资源的插入锚点。仍处于活动状态的块优先；
 * 否则最近放置的稳定 asset id 让连续的选择器结果紧挨其前驱，而不是被送到文档末尾。
 */
internal fun documentEmbeddedAssetInsertionAnchorIndex(
    blocks: List<DocumentMarkdownBlock>,
    activeBlockKey: String?,
    lastVisualAssetId: String?,
): Int {
    val activeIndex = blocks.indexOfFirst { it.key == activeBlockKey }
    if (activeIndex >= 0) return activeIndex
    val previousAssetIndex = blocks.indexOfFirst { block ->
        block is DocumentEmbeddedAssetBlock && block.asset.assetId == lastVisualAssetId
    }
    return previousAssetIndex.takeIf { it >= 0 } ?: blocks.lastIndex
}

/** 仅当活动编辑器会话拥有一个顶层正文块时才插入。 */
internal fun insertDocumentEmbeddedAssetAtRichSelection(
    block: DocumentMarkdownBlock?,
    activeState: RichTextState?,
    sessionState: RichTextState?,
    syntax: String,
): Boolean {
    if (block !is DocumentRichRun || activeState == null || sessionState !== activeState) {
        return false
    }
    activeState.insertMarkdownAfterSelection(syntax)
    return true
}

@Composable
internal fun rememberDocumentBlockEditorController(documentKey: String): DocumentBlockEditorController =
    remember(documentKey) { DocumentBlockEditorController() }

@Composable
internal fun DocumentBlockEditor(
    documentKey: String,
    initialMarkdown: String,
    controller: DocumentBlockEditorController,
    onMarkdownChange: (String) -> Unit,
    assets: List<EmbeddedAsset> = emptyList(),
    embeddedAssetContent: EmbeddedAssetMarkdownContent? = null,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(documentKey, assets) {
        mutableStateListOf<DocumentMarkdownBlock>().apply {
            addAll(DocumentMarkdownBlockCodec.parse(initialMarkdown, assets))
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

        // 从格式栏插入的结构块应落在光标处，而不是可能非常长的 RichRun 的末尾。对非折叠
        // 选区，我们在选区之后插入而不是删除它：结构编辑还没有文档级撤销，
        // 因此保留用户内容是安全的默认行为。
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

    fun insertEmbeddedAsset(
        assetId: String,
        syntax: String,
        lastVisualAssetId: String?,
    ): Boolean {
        materializeRichSnapshots()
        val activeIndex = blocks.indexOfFirst { it.key == controller.activeBlockKey }
        val active = blocks.getOrNull(activeIndex)
        val activeState = controller.activeRichState

        // 只有顶层正文块拥有字符级的文档放置权。引用/源码/表格块具有不同的源码域，
        // 因此从它们中选择的资源会作为下一个视觉块插入，而不是藏在块内编辑器中。
        if (
            insertDocumentEmbeddedAssetAtRichSelection(
                block = active,
                activeState = activeState,
                sessionState = active?.let { richSessions[it.key]?.state },
                syntax = syntax,
            )
        ) {
            return true
        }

        val anchorIndex = documentEmbeddedAssetInsertionAnchorIndex(
            blocks = blocks,
            activeBlockKey = controller.activeBlockKey,
            lastVisualAssetId = lastVisualAssetId,
        )
        val insertionIndex = (anchorIndex + 1).coerceIn(0, blocks.size)
        val anchor = blocks.getOrNull(anchorIndex)
        val leadingMarkdown = if (anchor is DocumentEmbeddedAssetBlock) {
            // 连续的规范资源节点是内联兄弟。保持精确相邻还能让后续 RichRun 的
            // 原始空白不被改动。
            ""
        } else {
            separatorAfter(DocumentMarkdownBlockCodec.encode(blocks.take(insertionIndex)))
        }
        val inserted = DocumentRichRun(
            key = nextBlockKey("asset-$assetId"),
            markdown = syntax,
            leadingMarkdown = leadingMarkdown,
        )
        blocks.add(insertionIndex, inserted)
        controller.requestRichActivation(inserted.key, requestFocus = false)
        return true
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
            insertEmbeddedAsset = ::insertEmbeddedAsset,
            snapshot = { fallback ->
                if (blocks.isEmpty()) fallback else DocumentMarkdownBlockCodec.encode(
                    blocks.map(::snapshotBlock)
                )
            },
        )
    }
    DisposableEffect(documentKey) {
        onDispose {
            val retainedMarkdown = controller.snapshotMarkdown(initialMarkdown)
            controller.clear(retainedMarkdown)
        }
    }

    // RichTextState 更新会在每次击键时重组活动块。从派生状态编码可让可能高达 1 MB 的
    // 文档 projection 与实际的块列表发布绑定，而不是与每次无关的编辑器重组绑定。
    val currentMarkdown by remember(documentKey, assets) {
        derivedStateOf { DocumentMarkdownBlockCodec.encode(blocks) }
    }
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
                        val useRichEditor = remember(block.key, block.innerMarkdown) {
                            !RichEditorMarkdownCapability.inspect(block.innerMarkdown)
                                .requiresSourceMode
                        }
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
                                // 该引用刚切换到它的块内源码编辑器。
                                // 立即丢弃现在已卸载的 RichTextState/FocusRequester。
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
                    is DocumentEmbeddedAssetBlock -> DocumentEmbeddedAssetBlockEditor(
                        block = block,
                        canMoveUp = index > 0,
                        canMoveDown = index < blocks.lastIndex,
                        embeddedAssetContent = embeddedAssetContent,
                        onActivate = { controller.activate(block.key) },
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

/** 重排/删除是显式的结构编辑，因此分隔符可以安全地规范化。 */
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
        is DocumentEmbeddedImageBlock -> copy(
            originalMarkdown = original(),
            leadingMarkdown = leadingMarkdown,
            trailingMarkdown = trailingMarkdown,
        )
        is DocumentEmbeddedFileBlock -> copy(
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
