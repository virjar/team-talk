package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.body.MarkdownAssetPolicy
import kotlinx.serialization.Serializable

/** 二进制文档命令信封：Markdown 放置内容加上其精确作用域内的资源伴随清单。 */
@Serializable
data class DocumentContent(
    val markdown: String,
    val assets: List<EmbeddedAsset> = emptyList(),
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        DocumentPolicy.validateMarkdownEnvelope(markdown)
        MarkdownAssetPolicy.requireCanonical(markdown, assets)
        buf.writeString(markdown)
        buf.writeVarInt(assets.size)
        assets.forEach { it.writeTo(buf) }
    }

    companion object : IProtoReader<DocumentContent> {
        override fun readFrom(buf: PacketBuffer): DocumentContent {
            val markdown = buf.readRequiredString(
                DocumentPolicy.MAX_MARKDOWN_LENGTH * 4,
            )
            val assetCount = buf.readCollectionSize(
                maximum = EmbeddedAsset.MAX_ASSETS_PER_CONTENT,
                minimumBytesPerEntry = 1,
                fieldName = "document content embedded assets",
            )
            val validatedMarkdown = DocumentPolicy.validateMarkdownEnvelope(markdown)
            val assets = MarkdownAssetPolicy.canonicalize(
                validatedMarkdown,
                List(assetCount) { EmbeddedAsset.readFrom(buf) },
            )
            return DocumentContent(
                markdown = validatedMarkdown,
                assets = assets,
            )
        }
    }
}
