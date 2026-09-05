package com.virjar.tk.server.domain.document

import com.virjar.tk.protocol.model.DocumentDirectorySnapshotVersion
import com.virjar.tk.protocol.PacketBuffer
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DocumentSpacePageCursorTest {
    @Test
    fun `version two cursor round trips the ordering key and every authority coordinate`() {
        val anchor = DocumentSpacePageAnchor(
            spaceId = "00000000-0000-0000-0000-000000000123",
            snapshotVersion = DocumentDirectorySnapshotVersion(
                documentDirectoryRevision = 12L,
                organizationRevision = 34L,
                actorCredentialEpoch = 56L,
            ),
        )

        val encoded = DocumentSpacePageCursorCodec.encode(anchor)

        assertEquals(anchor, DocumentSpacePageCursorCodec.decode(encoded))
        assertEquals(encoded, DocumentSpacePageCursorCodec.encode(checkNotNull(DocumentSpacePageCursorCodec.decode(encoded))))
    }

    @Test
    fun `legacy malformed and non canonical cursors fail closed`() {
        val legacy = PacketBuffer().apply {
            writeByte(1)
            writeString("00000000-0000-0000-0000-000000000123")
        }.toByteArray().base64Url()
        val zeroCredentialEpoch = PacketBuffer().apply {
            writeByte(2)
            writeString("00000000-0000-0000-0000-000000000123")
            writeVarLong(1L)
            writeVarLong(2L)
            writeVarLong(0L)
        }.toByteArray().base64Url()
        val trailingBytes = PacketBuffer().apply {
            writeByte(2)
            writeString("00000000-0000-0000-0000-000000000123")
            writeVarLong(1L)
            writeVarLong(2L)
            writeVarLong(3L)
            writeByte(0)
        }.toByteArray().base64Url()

        listOf(legacy, zeroCredentialEpoch, trailingBytes, "not+base64url", "YQ==").forEach { cursor ->
            assertFailsWith<IllegalArgumentException>(cursor) {
                DocumentSpacePageCursorCodec.decode(cursor)
            }
        }
    }

    private fun ByteArray.base64Url(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)
}
