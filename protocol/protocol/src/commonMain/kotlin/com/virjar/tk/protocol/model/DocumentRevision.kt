package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.body.MarkdownAssetPolicy
import kotlinx.serialization.Serializable

/** 协作文档的不可变完整修订快照。 */
@Serializable
data class DocumentRevision(
    val documentId: String,
    val revision: Long,
    val title: String,
    val markdown: String,
    val editedBy: String,
    val editedAt: Long,
    /** 为这个不可变修订解析的清单。 */
    val assets: List<EmbeddedAsset> = emptyList(),
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        DocumentPolicy.validateMarkdownEnvelope(markdown)
        MarkdownAssetPolicy.requireCanonical(markdown, assets)
        buf.writeString(documentId)
        buf.writeVarLong(revision)
        buf.writeString(title)
        buf.writeString(markdown)
        buf.writeString(editedBy)
        buf.writeVarLong(editedAt)
        buf.writeVarInt(assets.size)
        assets.forEach { it.writeTo(buf) }
    }

    companion object : IProtoReader<DocumentRevision> {
        override fun readFrom(buf: PacketBuffer): DocumentRevision {
            val documentId = buf.readRequiredString()
            val revision = buf.readVarLong()
            val title = buf.readRequiredString()
            val markdown = DocumentPolicy.validateMarkdownEnvelope(
                buf.readRequiredString(DocumentPolicy.MAX_MARKDOWN_LENGTH * 4),
            )
            val editedBy = buf.readRequiredString()
            val editedAt = buf.readVarLong()
            val assetCount = buf.readCollectionSize(
                maximum = EmbeddedAsset.MAX_ASSETS_PER_CONTENT,
                minimumBytesPerEntry = 1,
                fieldName = "document revision embedded assets",
            )
            return DocumentRevision(
                documentId = documentId,
                revision = revision,
                title = title,
                markdown = markdown,
                editedBy = editedBy,
                editedAt = editedAt,
                assets = MarkdownAssetPolicy.canonicalize(
                    markdown,
                    List(assetCount) { EmbeddedAsset.readFrom(buf) },
                ),
            )
        }
    }
}
