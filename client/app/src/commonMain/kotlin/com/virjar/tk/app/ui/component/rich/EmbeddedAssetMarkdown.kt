package com.virjar.tk.app.ui.component.rich

import com.virjar.tk.protocol.body.decodeCommonMarkPunctuationEscapes
import com.virjar.tk.protocol.body.EmbeddedAssetPresentation
import com.virjar.tk.protocol.body.MarkdownAssetPolicy
import com.virjar.tk.protocol.body.MarkdownAssetReference
import com.virjar.tk.protocol.model.EmbeddedAsset
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode

/** 内容级 TeamTalk 资源的规范、非网络 Markdown URI 命名空间。 */
const val TEAMTALK_ASSET_URI_PREFIX: String = EmbeddedAsset.URI_PREFIX

/**
 * 返回 [uri] 携带的规范小写 UUID；其他任何值都返回 `null`。
 *
 * 这里刻意不接受大小写折叠、查询字符串、片段、多余路径段、编码分隔符或网络 URL。
 * 调用方只能从当前渲染的消息/文档所属的 manifest 中解析返回的 id。
 */
fun embeddedAssetIdOrNull(uri: String): String? = EmbeddedAsset.assetIdFromUri(uri)

fun embeddedAssetUri(assetId: String): String = EmbeddedAsset.uri(assetId)

/** 只输出非网络作用域的 URI；附件存储 path 永不进入 Markdown。 */
fun embeddedAssetMarkdown(
    asset: EmbeddedAsset,
    presentation: EmbeddedAssetPresentation,
    label: String = asset.attachment.name,
): String = embeddedAssetMarkdown(asset.assetId, presentation, label)

/** 在描述符存在之前输出稳定的占位符，使提交准入能看到上传。 */
fun embeddedAssetMarkdown(
    assetId: String,
    presentation: EmbeddedAssetPresentation,
    label: String,
): String {
    val escapedLabel = buildString(label.length) {
        label.forEach { character ->
            if (character == '\\' || character == '[' || character == ']') append('\\')
            append(character)
        }
    }
    val link = "[$escapedLabel](${embeddedAssetUri(assetId)})"
    return if (presentation == EmbeddedAssetPresentation.IMAGE) "!$link" else link
}

/** 为正在预览或提交的消息体提供精确、按首次引用排序的描述符 projection。 */
fun projectEmbeddedAssetManifest(
    markdown: String,
    availableAssets: List<EmbeddedAsset>,
): List<EmbeddedAsset> {
    val byId = availableAssets
        .groupBy(EmbeddedAsset::assetId)
        .mapNotNull { (assetId, candidates) -> candidates.singleOrNull()?.let { assetId to it } }
        .toMap()
    return embeddedAssetMarkdownReferences(markdown)
        .mapNotNull(MarkdownAssetReference::assetId)
        .distinct()
        .mapNotNull(byId::get)
}

/**
 * 单次渲染通过的已准入描述符。该作用域刻意不包含全局查找，也没有兜底解析器：
 * 不在当前消息体/文档 manifest 中的 id 不可能成为媒体。
 */
class EmbeddedAssetRenderScope(manifestAssets: List<EmbeddedAsset>) {
    private val admittedAssets: Map<String, EmbeddedAsset> = manifestAssets
        .groupBy(EmbeddedAsset::assetId)
        .mapNotNull { (assetId, candidates) ->
            candidates.singleOrNull()
                ?.takeIf { EmbeddedAsset.assetIdFromUri(EmbeddedAsset.URI_PREFIX + assetId) == assetId }
                ?.takeIf { it.attachment.path.isNotBlank() }
                ?.let { assetId to it }
        }
        .toMap()

    internal fun resolve(
        assetId: String,
        presentation: EmbeddedAssetPresentation,
    ): EmbeddedAsset? = admittedAssets[assetId]?.takeIf { asset ->
        presentation != EmbeddedAssetPresentation.IMAGE ||
            asset.attachment.contentType.startsWith("image/", ignoreCase = true)
    }

    internal val cacheKey: List<EmbeddedAsset> = admittedAssets.values.sortedBy(EmbeddedAsset::assetId)

    companion object {
        val Empty = EmbeddedAssetRenderScope(emptyList())
    }
}

/**
 * 查找显式的内联图片/文件引用，同时自然忽略代码片段与围栏。即使 UUID 畸形也返回引用，
 * 以便提交准入可以失败关闭（fail closed）。
 */
fun embeddedAssetMarkdownReferences(markdown: String): List<MarkdownAssetReference> =
    MarkdownAssetPolicy.references(markdown)

/** 仅用于恢复的扫描，用于移除已经超出提交准入上限的内容。 */
internal fun embeddedAssetMarkdownReferencesForRecovery(markdown: String): List<MarkdownAssetReference> =
    MarkdownAssetPolicy.recoveryReferences(markdown)

/**
 * 移除 [assetId] 的每一处语义放置，且不规范化任何周边 Markdown。内联或围栏代码中的
 * 字面示例保持不动，因为共享扫描器不会把它们报告为资源放置。
 */
fun removeEmbeddedAssetReferences(markdown: String, assetId: String): String {
    val references = embeddedAssetReferencesForRemoval(markdown, assetId)
    return removeEmbeddedAssetReferenceRanges(markdown, references)
}

internal fun embeddedAssetReferencesForRemoval(
    markdown: String,
    assetId: String,
): List<MarkdownAssetReference> = embeddedAssetMarkdownReferencesForRecovery(markdown)
    .filter { it.assetId == assetId }

internal fun removeEmbeddedAssetReferenceRanges(
    markdown: String,
    references: List<MarkdownAssetReference>,
): String {
    if (references.isEmpty()) return markdown
    return buildString(markdown.length) {
        var cursor = 0
        references.forEach { reference ->
            append(markdown, cursor, reference.startOffset)
            cursor = reference.endOffsetExclusive
        }
        append(markdown, cursor, markdown.length)
    }
}

internal fun ASTNode.embeddedAssetLinkDestination(markdown: String): String? =
    findEmbeddedAssetDescendant(MarkdownElementTypes.LINK_DESTINATION)
        ?.getTextInNode(markdown)
        ?.toString()
        ?.let(::decodeCommonMarkPunctuationEscapes)

/** 在 JetBrains Markdown 中，IMAGE 的链接子树比 INLINE_LINK 深一层。 */
internal fun ASTNode.findEmbeddedAssetDescendant(type: org.intellij.markdown.IElementType): ASTNode? {
    val pending = ArrayDeque<ASTNode>()
    children.asReversed().forEach(pending::addLast)
    var visited = 0
    while (pending.isNotEmpty() && visited < 128) {
        val node = pending.removeLast()
        visited += 1
        if (node.type == type) return node
        node.children.asReversed().forEach(pending::addLast)
    }
    return null
}
