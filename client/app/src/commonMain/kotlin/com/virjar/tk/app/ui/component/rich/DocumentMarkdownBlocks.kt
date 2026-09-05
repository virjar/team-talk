package com.virjar.tk.app.ui.component.rich

import com.virjar.tk.protocol.model.EmbeddedAsset

/**
 * 权威 Markdown 的无损、文档专属 projection。
 *
 * 富文本编辑器仍负责普通段落与内联格式。块模型持有精确的源码切片，使未改动的内容可以
 * 逐字节输出；只有显式标记为 [dirty] 的块才从它的可编辑字段编码。
 */
internal sealed interface DocumentMarkdownBlock {
    /** 在已解析编辑器模型的整个生命周期内稳定；调用方可为插入操作提供自己的值。 */
    val key: String

    /** 该块所拥有的精确源码，包括它的块间分隔符。 */
    val originalMarkdown: String

    /** 前一个块与本块语义正文之间的源码。 */
    val leadingMarkdown: String

    /** 本块之后的收尾源码。只有最后一个已解析块才非空。 */
    val trailingMarkdown: String

    /** false 表示必须不经规范化直接使用 [originalMarkdown]。 */
    val dirty: Boolean
}

internal data class DocumentRichRun(
    override val key: String,
    val markdown: String,
    override val originalMarkdown: String = "",
    override val leadingMarkdown: String = "",
    override val trailingMarkdown: String = "",
    override val dirty: Boolean = true,
) : DocumentMarkdownBlock

internal data class DocumentQuoteBlock(
    override val key: String,
    /** 最外层 `>` 容器内的 Markdown。嵌套 Markdown 仍保持权威。 */
    val innerMarkdown: String,
    override val originalMarkdown: String = "",
    override val leadingMarkdown: String = "",
    override val trailingMarkdown: String = "",
    override val dirty: Boolean = true,
) : DocumentMarkdownBlock

internal data class DocumentCodeFenceBlock(
    override val key: String,
    val language: String? = null,
    /** 完整的围栏 info 字符串。代码变化时保留；[language] 是它的第一个 token。 */
    val infoString: String? = language,
    val code: String = "",
    val fenceChar: Char = '`',
    val fenceLength: Int = 3,
    val openingIndent: String = "",
    val lineEnding: String = "\n",
    /** 闭围栏之后的精确行尾；它不是块间分隔符。 */
    val terminalLineEnding: String = "",
    override val originalMarkdown: String = "",
    override val leadingMarkdown: String = "",
    override val trailingMarkdown: String = "",
    override val dirty: Boolean = true,
) : DocumentMarkdownBlock {
    init {
        require(fenceChar == '`' || fenceChar == '~') { "A Markdown fence must use backticks or tildes" }
        require(fenceLength >= 3) { "A Markdown fence must contain at least three markers" }
    }
}

internal enum class DocumentTableAlignment { NONE, LEFT, CENTER, RIGHT }

internal data class DocumentGfmTableBlock(
    override val key: String,
    /** 单元格值是内联 Markdown，不含外围竖线或填充。 */
    val headers: List<String>,
    val alignments: List<DocumentTableAlignment> = emptyList(),
    val rows: List<List<String>> = emptyList(),
    val lineEnding: String = "\n",
    override val originalMarkdown: String = "",
    override val leadingMarkdown: String = "",
    override val trailingMarkdown: String = "",
    override val dirty: Boolean = true,
) : DocumentMarkdownBlock

internal sealed interface DocumentEmbeddedAssetBlock : DocumentMarkdownBlock {
    val asset: EmbeddedAsset
    val label: String
    val sourceMarkdown: String
}

internal data class DocumentEmbeddedImageBlock(
    override val key: String,
    override val asset: EmbeddedAsset,
    override val label: String,
    override val sourceMarkdown: String,
    override val originalMarkdown: String = "",
    override val leadingMarkdown: String = "",
    override val trailingMarkdown: String = "",
    override val dirty: Boolean = true,
) : DocumentEmbeddedAssetBlock

internal data class DocumentEmbeddedFileBlock(
    override val key: String,
    override val asset: EmbeddedAsset,
    override val label: String,
    override val sourceMarkdown: String,
    override val originalMarkdown: String = "",
    override val leadingMarkdown: String = "",
    override val trailingMarkdown: String = "",
    override val dirty: Boolean = true,
) : DocumentEmbeddedAssetBlock

internal data class DocumentOpaqueRawBlock(
    override val key: String,
    /** 精确的语义正文。块内源码编辑器可以更新此字段。 */
    val rawMarkdown: String,
    val features: Set<RichEditorUnsupportedMarkdownFeature> = emptySet(),
    override val originalMarkdown: String = "",
    override val leadingMarkdown: String = "",
    override val trailingMarkdown: String = "",
    override val dirty: Boolean = true,
) : DocumentMarkdownBlock
