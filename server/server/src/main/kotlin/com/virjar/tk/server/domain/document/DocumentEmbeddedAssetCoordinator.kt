package com.virjar.tk.server.domain.document

import com.virjar.tk.protocol.body.MarkdownAssetPolicy
import com.virjar.tk.server.domain.attachment.AttachmentCatalog
import com.virjar.tk.server.domain.attachment.AttachmentLifecycleGate
import com.virjar.tk.protocol.model.DocumentContent
import com.virjar.tk.protocol.model.DocumentPolicy
import com.virjar.tk.protocol.model.EmbeddedAsset

/**
 * 校验文档内容，并协调其内嵌资产的跨存储生命周期。
 *
 * PostgreSQL 拥有文档清单，而 [AttachmentCatalog] 拥有已上传的字节。把描述符解析、暂存
 * 权威检查与发布放在同一个协作者里，可以防止文档命令意外地只应用该边界协议的一部分。
 */
internal class DocumentEmbeddedAssetCoordinator(
    private val attachmentCatalog: AttachmentCatalog?,
    private val attachmentLifecycle: AttachmentLifecycleGate,
) {
    fun validateContent(content: DocumentContent): DocumentContent {
        val markdown = validateMarkdown(content.markdown)
        return DocumentContent(
            markdown = markdown,
            assets = MarkdownAssetPolicy.canonicalize(markdown, content.assets),
        )
    }

    fun resolve(
        actorUid: String,
        declared: List<EmbeddedAsset>,
        knownById: Map<String, EmbeddedAsset>,
    ): List<EmbeddedAsset> {
        if (declared.isEmpty()) return emptyList()
        val catalog = requireNotNull(attachmentCatalog) { "文档内嵌资产存储未配置" }
        val distinctPaths = linkedSetOf<String>()
        return declared.map { asset ->
            val known = knownById[asset.assetId]
            known?.let {
                require(it == asset) { "同一文档的内嵌资产标识不能改绑到其他文件: ${asset.assetId}" }
            }
            require(asset.thumbnail?.path != asset.attachment.path) { "内嵌资产缩略图不能与主文件相同" }
            val resolvedAttachments = asset.attachments().map { declaredAttachment ->
                require(distinctPaths.add(declaredAttachment.path)) {
                    "文档内嵌资产不能重复绑定同一文件: ${declaredAttachment.path}"
                }
                val actual = catalog.getAttachment(declaredAttachment.path)
                    ?: throw IllegalArgumentException("内嵌资产不存在: path=${declaredAttachment.path}")
                require(actual == declaredAttachment) {
                    "内嵌资产元数据不匹配: path=${declaredAttachment.path}"
                }
                if (known == null) {
                    require(catalog.getOwnerUid(declaredAttachment.path) == actorUid) {
                        "只能把本人新上传的文件绑定为文档内嵌资产"
                    }
                    require(catalog.isStaging(declaredAttachment.path)) {
                        "只能把尚未绑定的暂存文件绑定为新的文档内嵌资产"
                    }
                }
                actual
            }
            asset.copy(
                attachment = resolvedAttachments.first(),
                thumbnail = resolvedAttachments.getOrNull(1),
            )
        }
    }

    suspend fun <T> withReferenceMutation(
        assets: List<EmbeddedAsset>,
        block: suspend () -> T,
    ): T {
        val paths = assets.flatMap(EmbeddedAsset::attachments).map { it.path }
        return if (paths.isEmpty()) block() else attachmentLifecycle.withReferenceMutation(paths, block)
    }

    fun markBusinessBound(assets: List<EmbeddedAsset>) {
        val paths = assets.flatMap(EmbeddedAsset::attachments).map { it.path }
        if (paths.isNotEmpty()) requireNotNull(attachmentCatalog).markBusinessBound(paths)
    }

    fun fingerprintFields(assets: List<EmbeddedAsset>): List<String?> = buildList {
        assets.forEach { asset ->
            add(asset.assetId)
            add(asset.attachment.path)
            add(asset.attachment.name)
            add(asset.attachment.contentType)
            add(asset.attachment.size.toString())
            add(asset.thumbnail?.path)
            add(asset.thumbnail?.name)
            add(asset.thumbnail?.contentType)
            add(asset.thumbnail?.size?.toString())
            add(asset.width.toString())
            add(asset.height.toString())
        }
    }

    private fun validateMarkdown(value: String): String {
        DocumentPolicy.validateMarkdownEnvelope(value)
        DocumentMarkdownStructurePolicy.validate(
            markdown = value,
            maxQuoteDepth = DocumentService.MAX_MARKDOWN_QUOTE_DEPTH,
            maxTableColumns = DocumentService.MAX_MARKDOWN_TABLE_COLUMNS,
            maxTableCells = DocumentService.MAX_MARKDOWN_TABLE_CELLS,
            maxLines = DocumentService.MAX_MARKDOWN_LINES,
            maxRenderableBlocks = DocumentService.MAX_MARKDOWN_RENDERABLE_BLOCKS,
        )
        return value
    }
}
