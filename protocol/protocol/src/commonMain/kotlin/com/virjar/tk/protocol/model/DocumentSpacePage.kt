package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.ProtocolCorruptionException
import com.virjar.tk.protocol.ProtocolEncodingException
import com.virjar.tk.protocol.payload.MAX_RPC_ENVELOPE_BODY_BYTES
import kotlinx.serialization.Serializable

/** 行动者当前可访问文档空间的一个有界 keyset 请求。 */
@Serializable
data class DocumentSpacePageRequest(
    /** 服务端返回的 opaque 排他游标；客户端必须逐字节保留它。 */
    val cursor: String? = null,
    val limit: Int = DocumentSpacePage.DEFAULT_PAGE_SIZE,
) : IProto {
    init {
        DocumentSpacePagePolicy.requireOpaqueCursor(cursor)
        require(limit in 1..DocumentSpacePage.MAX_PAGE_SIZE) {
            "Document space page size must be in 1..${DocumentSpacePage.MAX_PAGE_SIZE}"
        }
    }

    override fun writeTo(buf: PacketBuffer) {
        DocumentSpacePagePolicy.requireOpaqueCursor(cursor)
        buf.writeString(cursor)
        buf.writeVarInt(limit)
    }

    companion object : IProtoReader<DocumentSpacePageRequest> {
        override fun readFrom(buf: PacketBuffer): DocumentSpacePageRequest = DocumentSpacePageRequest(
            cursor = buf.readString(DocumentSpacePagePolicy.MAX_CURSOR_BYTES),
            limit = buf.readVarInt(),
        )
    }
}

/** 按服务端稳定且唯一的 space 键排序的有界页。 */
@Serializable
data class DocumentSpacePage(
    /** 冻结进首页游标、并在每次续页中重复的权威输入。 */
    val snapshotVersion: DocumentDirectorySnapshotVersion,
    val items: List<DocumentSpace>,
    /** 仅当 [items] 之后不存在当前可访问的行时为 null。 */
    val nextCursor: String?,
    /** 当续页冻结的权威输入不再匹配时的显式重启信号。 */
    val snapshotChanged: Boolean = false,
) : IProto {
    init {
        if (items.size > MAX_PAGE_SIZE) {
            throw ProtocolEncodingException(
                "Document space page cannot contain more than $MAX_PAGE_SIZE items",
            )
        }
        if (items.mapTo(hashSetOf(), DocumentSpace::spaceId).size != items.size) {
            throw ProtocolEncodingException("Document space page contains duplicate spaceId values")
        }
        try {
            DocumentSpacePagePolicy.requireOpaqueCursor(nextCursor)
        } catch (invalidCursor: IllegalArgumentException) {
            throw ProtocolEncodingException(invalidCursor.message ?: "Document space cursor is invalid")
        }
        if (nextCursor != null && items.isEmpty()) {
            throw ProtocolEncodingException("An empty Document space page cannot advertise another page")
        }
        if (snapshotChanged && (items.isNotEmpty() || nextCursor != null)) {
            throw ProtocolEncodingException(
                "A changed Document space snapshot page must be empty and terminal",
            )
        }
    }

    override fun writeTo(buf: PacketBuffer) {
        val before = buf.readableBytes()
        snapshotVersion.writeTo(buf)
        buf.writeVarInt(items.size)
        items.forEach { it.writeTo(buf) }
        buf.writeString(nextCursor)
        buf.writeBoolean(snapshotChanged)
        val encodedBytes = buf.readableBytes() - before
        if (encodedBytes > MAX_ENCODED_BYTES) {
            throw ProtocolEncodingException(
                "Document space page size $encodedBytes exceeds inner budget $MAX_ENCODED_BYTES",
            )
        }
    }

    companion object : IProtoReader<DocumentSpacePage> {
        const val DEFAULT_PAGE_SIZE = 32
        const val MAX_PAGE_SIZE = 64

        /** 空间元数据很小；让它的页面远低于传输层全局响应上限。 */
        const val MAX_ENCODED_BYTES = 256 * 1024

        init {
            check(MAX_ENCODED_BYTES < MAX_RPC_ENVELOPE_BODY_BYTES) {
                "Document space page budget must remain stricter than the RPC envelope budget"
            }
        }

        override fun readFrom(buf: PacketBuffer): DocumentSpacePage {
            val before = buf.readableBytes()
            val snapshotVersion = DocumentDirectorySnapshotVersion.readFrom(buf)
            val count = buf.readCollectionSize(
                maximum = MAX_PAGE_SIZE,
                minimumBytesPerEntry = 16,
                fieldName = "document space page",
            )
            val page = DocumentSpacePage(
                snapshotVersion = snapshotVersion,
                items = List(count) { DocumentSpace.readFrom(buf) },
                nextCursor = buf.readString(DocumentSpacePagePolicy.MAX_CURSOR_BYTES),
                snapshotChanged = buf.readBoolean("document space snapshot change"),
            )
            val encodedBytes = before - buf.readableBytes()
            if (encodedBytes > MAX_ENCODED_BYTES) {
                throw ProtocolCorruptionException(
                    "Document space page size $encodedBytes exceeds inner budget $MAX_ENCODED_BYTES",
                )
            }
            return page
        }
    }
}

/**
 * 每个能够改变 `listSpaces` 成员关系的权威输入。
 *
 * 文档与组织 revision 在空数据集上合法地从零开始；
 * 凭据 epoch 按行动者计并从 1 开始，这样账号替换永远不会复用一个未初始化的权威戳。
 */
@Serializable
data class DocumentDirectorySnapshotVersion(
    val documentDirectoryRevision: Long,
    val organizationRevision: Long,
    val actorCredentialEpoch: Long,
) : IProto {
    init {
        require(documentDirectoryRevision >= 0L) {
            "document directory revision must not be negative"
        }
        require(organizationRevision >= 0L) {
            "document directory organization revision must not be negative"
        }
        require(actorCredentialEpoch > 0L) {
            "document directory actor credential epoch must be positive"
        }
    }

    override fun writeTo(buf: PacketBuffer) {
        buf.writeVarLong(documentDirectoryRevision)
        buf.writeVarLong(organizationRevision)
        buf.writeVarLong(actorCredentialEpoch)
    }

    companion object : IProtoReader<DocumentDirectorySnapshotVersion> {
        override fun readFrom(buf: PacketBuffer): DocumentDirectorySnapshotVersion {
            val documentDirectoryRevision = buf.readVarLong()
            val organizationRevision = buf.readVarLong()
            val actorCredentialEpoch = buf.readVarLong()
            if (documentDirectoryRevision < 0L) {
                throw ProtocolCorruptionException("Negative document directory revision")
            }
            if (organizationRevision < 0L) {
                throw ProtocolCorruptionException("Negative document directory organization revision")
            }
            if (actorCredentialEpoch <= 0L) {
                throw ProtocolCorruptionException("Non-positive document directory credential epoch")
            }
            return DocumentDirectorySnapshotVersion(
                documentDirectoryRevision = documentDirectoryRevision,
                organizationRevision = organizationRevision,
                actorCredentialEpoch = actorCredentialEpoch,
            )
        }
    }
}

/** 仅共享线格式边界；游标内容与排序语义完全归服务端所有。 */
object DocumentSpacePagePolicy {
    const val MAX_CURSOR_BYTES = 128

    fun requireOpaqueCursor(cursor: String?) {
        if (cursor == null) return
        require(cursor.isNotEmpty() && cursor.length <= MAX_CURSOR_BYTES) {
            "Document space cursor has an invalid length"
        }
        require(cursor.all(::isBase64UrlCharacter)) {
            "Document space cursor is not canonical base64url"
        }
    }

    private fun isBase64UrlCharacter(character: Char): Boolean =
        character in 'A'..'Z' ||
            character in 'a'..'z' ||
            character in '0'..'9' ||
            character == '-' ||
            character == '_'
}
