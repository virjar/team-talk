package com.virjar.tk.server.domain.document

import com.virjar.tk.protocol.model.DocumentSpacePagePolicy
import com.virjar.tk.protocol.model.DocumentDirectorySnapshotVersion
import com.virjar.tk.protocol.PacketBuffer
import java.util.Base64
import java.util.UUID

/** 服务端持有的游标编解码器；客户端逐字节保留这个值，绝不检查它。 */
internal object DocumentSpacePageCursorCodec {
    private const val FORMAT_VERSION = 2
    private const val CANONICAL_UUID_LENGTH = 36
    private const val MAX_DECODED_BYTES = 80
    private const val INVALID_CURSOR = "文档空间分页游标无效"
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(anchor: DocumentSpacePageAnchor): String {
        requireCanonicalSpaceId(anchor.spaceId)
        val raw = PacketBuffer().apply {
            writeByte(FORMAT_VERSION)
            writeString(anchor.spaceId)
            anchor.snapshotVersion.writeTo(this)
        }.toByteArray()
        check(raw.size <= MAX_DECODED_BYTES) { "Document space cursor encoding exceeded its budget" }
        return encoder.encodeToString(raw).also(DocumentSpacePagePolicy::requireOpaqueCursor)
    }

    fun decode(encoded: String?): DocumentSpacePageAnchor? {
        if (encoded == null) return null
        return try {
            DocumentSpacePagePolicy.requireOpaqueCursor(encoded)
            val raw = decoder.decode(encoded)
            require(raw.size <= MAX_DECODED_BYTES) { INVALID_CURSOR }
            val buffer = PacketBuffer(raw)
            require(buffer.readByte() == FORMAT_VERSION) { INVALID_CURSOR }
            val anchor = DocumentSpacePageAnchor(
                spaceId = buffer.readRequiredString(
                    maxByteLength = CANONICAL_UUID_LENGTH,
                    fieldName = "document space cursor spaceId",
                ),
                snapshotVersion = DocumentDirectorySnapshotVersion.readFrom(buffer),
            )
            buffer.requireExhausted("document space cursor")
            require(encoder.encodeToString(raw) == encoded) { INVALID_CURSOR }
            requireCanonicalSpaceId(anchor.spaceId)
            anchor
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException(INVALID_CURSOR)
        }
    }

    private fun requireCanonicalSpaceId(spaceId: String) {
        require(
            spaceId.length == CANONICAL_UUID_LENGTH &&
                runCatching { UUID.fromString(spaceId).toString() }.getOrNull() == spaceId,
        ) { INVALID_CURSOR }
    }
}
