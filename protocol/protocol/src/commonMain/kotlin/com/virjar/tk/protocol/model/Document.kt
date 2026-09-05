package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.body.MarkdownAssetPolicy
import kotlinx.serialization.Serializable

/** 文档空间中的 Markdown 当前快照；[revision] 是乐观并发坐标。 */
@Serializable
data class Document(
    val documentId: String,
    val spaceId: String,
    val parentId: String? = null,
    val title: String,
    val markdown: String,
    val revision: Long = 1,
    val createdBy: String,
    val createdAt: Long,
    val updatedBy: String,
    val updatedAt: Long,
    /** 当前文档的祖先文档链，顺序固定为 root → parent，不包含文档自身。 */
    val ancestorIds: List<String> = emptyList(),
    /** [markdown] 中内部资源引用的 canonical 清单。 */
    val assets: List<EmbeddedAsset> = emptyList(),
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        DocumentPolicy.validateMarkdownEnvelope(markdown)
        MarkdownAssetPolicy.requireCanonical(markdown, assets)
        buf.writeString(documentId)
        buf.writeString(spaceId)
        buf.writeString(parentId)
        buf.writeString(title)
        buf.writeString(markdown)
        buf.writeVarLong(revision)
        buf.writeString(createdBy)
        buf.writeVarLong(createdAt)
        buf.writeString(updatedBy)
        buf.writeVarLong(updatedAt)
        require(ancestorIds.size <= MAX_ANCESTOR_DEPTH) { "文档层级超过限制" }
        buf.writeVarInt(ancestorIds.size)
        ancestorIds.forEach(buf::writeString)
        buf.writeVarInt(assets.size)
        assets.forEach { it.writeTo(buf) }
    }

    companion object : IProtoReader<Document> {
        const val MAX_ANCESTOR_DEPTH = 128

        override fun readFrom(buf: PacketBuffer): Document {
            val documentId = buf.readRequiredString()
            val spaceId = buf.readRequiredString()
            val parentId = buf.readString()
            val title = buf.readRequiredString()
            val markdown = DocumentPolicy.validateMarkdownEnvelope(
                buf.readRequiredString(DocumentPolicy.MAX_MARKDOWN_LENGTH * 4),
            )
            val revision = buf.readVarLong()
            val createdBy = buf.readRequiredString()
            val createdAt = buf.readVarLong()
            val updatedBy = buf.readRequiredString()
            val updatedAt = buf.readVarLong()
            // String 的最短 wire 形态为 present + zero-length VarInt（2B）。数量与当前
            // payload 必须在建立 List 前同时验证，避免畸形小包触发大容量预分配。
            val ancestorCount = buf.readCollectionSize(
                maximum = MAX_ANCESTOR_DEPTH,
                minimumBytesPerEntry = 2,
                fieldName = "document ancestors",
            )
            val ancestorIds = List(ancestorCount) { buf.readRequiredString() }
            val assetCount = buf.readCollectionSize(
                maximum = EmbeddedAsset.MAX_ASSETS_PER_CONTENT,
                minimumBytesPerEntry = 1,
                fieldName = "document embedded assets",
            )
            return Document(
                documentId = documentId,
                spaceId = spaceId,
                parentId = parentId,
                title = title,
                markdown = markdown,
                revision = revision,
                createdBy = createdBy,
                createdAt = createdAt,
                updatedBy = updatedBy,
                updatedAt = updatedAt,
                ancestorIds = ancestorIds,
                assets = MarkdownAssetPolicy.canonicalize(
                    markdown,
                    List(assetCount) { EmbeddedAsset.readFrom(buf) },
                ),
            )
        }
    }
}
