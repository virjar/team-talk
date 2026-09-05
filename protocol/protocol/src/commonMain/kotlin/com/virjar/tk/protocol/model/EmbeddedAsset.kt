package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.body.AttachmentPolicy
import com.virjar.tk.protocol.body.MarkdownAssetPolicy
import com.virjar.tk.protocol.body.MessageBodyPolicy
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import kotlinx.serialization.Serializable

/**
 * 一个从 Markdown 内容作用域引用的不可变资源描述符。
 *
 * [assetId] 是 `teamtalk-asset://asset/<assetId>` 使用的作用域内逻辑标识。
 * FileStore 路径保留在这个已认证的伴随清单中，绝不能写入 Markdown。
 * 外围的文档/消息才是授权上下文；知道 asset id 或存储路径并不能获得访问权限。
 */
@Serializable
data class EmbeddedAsset(
    val assetId: String,
    val attachment: Attachment,
    val thumbnail: Attachment? = null,
    val width: Int = 0,
    val height: Int = 0,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        require(MarkdownAssetPolicy.canonicalizeDescriptor(this) == this) {
            "内嵌资产描述符不是 canonical 形式"
        }
        buf.writeString(assetId)
        attachment.writeTo(buf)
        buf.writeBoolean(thumbnail != null)
        thumbnail?.writeTo(buf)
        buf.writeVarInt(width)
        buf.writeVarInt(height)
    }

    /** 必须与外围作用域共享生命周期的主要对象与派生对象。 */
    fun attachments(): List<Attachment> = listOfNotNull(attachment, thumbnail)

    companion object : IProtoReader<EmbeddedAsset> {
        const val MAX_ASSETS_PER_CONTENT = 256
        const val ASSET_ID_LENGTH = 36
        const val URI_PREFIX = "teamtalk-asset://asset/"

        private val CANONICAL_ASSET_ID = Regex(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
        )

        fun requireCanonicalAssetId(value: String): String {
            require(value.length == ASSET_ID_LENGTH && CANONICAL_ASSET_ID.matches(value)) {
                "内嵌资产标识必须是小写 canonical UUID"
            }
            return value
        }

        fun assetIdFromUri(destination: String): String? = destination
            .takeIf { it.startsWith(URI_PREFIX) }
            ?.removePrefix(URI_PREFIX)
            ?.takeIf { it.length == ASSET_ID_LENGTH && CANONICAL_ASSET_ID.matches(it) }

        fun uri(assetId: String): String = URI_PREFIX + requireCanonicalAssetId(assetId)

        override fun readFrom(buf: PacketBuffer): EmbeddedAsset {
            val assetId = buf.readRequiredString(
                MessageBodyPolicy.utf8WireLimit(ASSET_ID_LENGTH),
            )
            val attachment = Attachment.readFrom(buf)
            val thumbnail = if (buf.readBoolean("embedded asset thumbnail presence")) {
                Attachment.readFrom(buf)
            } else {
                null
            }
            val width = buf.readVarInt()
            val height = buf.readVarInt()
            requireCanonicalAssetId(assetId)
            require(width in 0..MessageBodyPolicy.MAX_MEDIA_DIMENSION) { "内嵌图片宽度非法" }
            require(height in 0..MessageBodyPolicy.MAX_MEDIA_DIMENSION) { "内嵌图片高度非法" }
            return EmbeddedAsset(
                assetId = assetId,
                attachment = AttachmentPolicy.canonicalizeDescriptor(attachment),
                thumbnail = thumbnail?.let(AttachmentPolicy::canonicalizeDescriptor),
                width = width,
                height = height,
            )
        }
    }
}
