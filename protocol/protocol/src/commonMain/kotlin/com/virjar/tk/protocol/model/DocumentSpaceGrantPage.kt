package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.ProtocolCorruptionException
import com.virjar.tk.protocol.ProtocolEncodingException
import com.virjar.tk.protocol.payload.MAX_RPC_ENVELOPE_BODY_BYTES
import kotlinx.serialization.Serializable

/** 一个文档空间的完整、有界 ACL 快照。 */
@Serializable
data class DocumentSpaceGrantPage(
    val items: List<DocumentSpaceGrant>,
) : IProto {
    init {
        validate(items, ::ProtocolEncodingException)
    }

    override fun writeTo(buf: PacketBuffer) {
        validate(items, ::ProtocolEncodingException)
        val before = buf.readableBytes()
        buf.writeVarInt(items.size)
        items.forEach { it.writeTo(buf) }
        val encodedBytes = buf.readableBytes() - before
        if (encodedBytes > MAX_ENCODED_BYTES) {
            throw ProtocolEncodingException(
                "Document grant page size $encodedBytes exceeds inner budget $MAX_ENCODED_BYTES",
            )
        }
    }

    companion object : IProtoReader<DocumentSpaceGrantPage> {
        /** ACL 元数据必须远低于传输层全局响应上限。 */
        const val MAX_ENCODED_BYTES = 512 * 1024

        init {
            check(MAX_ENCODED_BYTES < MAX_RPC_ENVELOPE_BODY_BYTES) {
                "Document grant page budget must remain stricter than the RPC envelope budget"
            }
        }

        override fun readFrom(buf: PacketBuffer): DocumentSpaceGrantPage {
            val before = buf.readableBytes()
            val count = buf.readCollectionSize(
                maximum = DocumentSpaceGrant.MAX_GRANTS_PER_SPACE,
                minimumBytesPerEntry = 8,
                fieldName = "document grant page",
            )
            val items = List(count) { DocumentSpaceGrant.readFrom(buf) }
            validate(items, ::ProtocolCorruptionException)
            val encodedBytes = before - buf.readableBytes()
            if (encodedBytes > MAX_ENCODED_BYTES) {
                throw ProtocolCorruptionException(
                    "Document grant page size $encodedBytes exceeds inner budget $MAX_ENCODED_BYTES",
                )
            }
            return DocumentSpaceGrantPage(items)
        }

        private inline fun validate(
            items: List<DocumentSpaceGrant>,
            failure: (String) -> IllegalArgumentException,
        ) {
            if (items.size > DocumentSpaceGrant.MAX_GRANTS_PER_SPACE) {
                throw failure("Document grant count exceeds ${DocumentSpaceGrant.MAX_GRANTS_PER_SPACE}")
            }
            val identities = HashSet<Pair<Int, String>>(items.size)
            items.forEach { grant ->
                DocumentSpaceGrant.validationError(grant)?.let { throw failure(it) }
                if (!identities.add(grant.principalType to grant.principalId)) {
                    throw failure("Document grant page contains a duplicate principal")
                }
            }
        }
    }
}
