package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.ProtocolCorruptionException
import com.virjar.tk.protocol.ProtocolEncodingException
import com.virjar.tk.protocol.payload.MAX_RPC_ENVELOPE_BODY_BYTES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentSpacePageTest {
    @Test
    fun `maximum legal metadata page round trips below its domain budget`() {
        val page = DocumentSpacePage(
            snapshotVersion = VERSION,
            items = List(DocumentSpacePage.MAX_PAGE_SIZE) { index ->
                DocumentSpace(
                    spaceId = uuid(index),
                    name = "名".repeat(DocumentPolicy.MAX_SPACE_NAME_LENGTH),
                    description = "说".repeat(DocumentPolicy.MAX_DESCRIPTION_LENGTH),
                    myRole = DocumentSpace.ROLE_VIEWER,
                    createdBy = uuid(index + 1_000),
                    createdAt = index.toLong(),
                    updatedAt = index.toLong(),
                )
            },
            nextCursor = "bmV4dA",
        )

        val encoded = ProtoCodec.encode(page)

        assertEquals(page, ProtoCodec.decode(DocumentSpacePage, encoded))
        assertTrue(encoded.size <= DocumentSpacePage.MAX_ENCODED_BYTES)
        assertTrue(DocumentSpacePage.MAX_ENCODED_BYTES < MAX_RPC_ENVELOPE_BODY_BYTES)
    }

    @Test
    fun `decoder rejects an item count above the single-page maximum before allocation`() {
        val malformed = ProtoCodec.encodePayload {
            VERSION.writeTo(this)
            writeVarInt(DocumentSpacePage.MAX_PAGE_SIZE + 1)
        }

        assertFailsWith<ProtocolCorruptionException> {
            ProtoCodec.decode(DocumentSpacePage, malformed)
        }
    }

    @Test
    fun `request rejects invalid limit and bounds opaque cursor bytes`() {
        assertFailsWith<IllegalArgumentException> {
            DocumentSpacePageRequest(limit = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            DocumentSpacePageRequest(limit = DocumentSpacePage.MAX_PAGE_SIZE + 1)
        }
        assertFailsWith<IllegalArgumentException> {
            DocumentSpacePageRequest(cursor = "not+base64")
        }

        val malformed = ProtoCodec.encodePayload {
            writeString("a".repeat(DocumentSpacePagePolicy.MAX_CURSOR_BYTES + 1))
        }
        assertFailsWith<ProtocolCorruptionException> {
            ProtoCodec.decode(DocumentSpacePageRequest, malformed)
        }
    }

    @Test
    fun `response rejects duplicate identities and an advancing empty page`() {
        val item = DocumentSpace(
            spaceId = uuid(1),
            name = "空间",
            myRole = DocumentSpace.ROLE_OWNER,
            createdBy = uuid(2),
            createdAt = 1,
            updatedAt = 1,
        )
        assertFailsWith<ProtocolEncodingException> {
            DocumentSpacePage(VERSION, listOf(item, item), null)
        }
        assertFailsWith<ProtocolEncodingException> {
            DocumentSpacePage(VERSION, emptyList(), "bmV4dA")
        }
        assertFailsWith<ProtocolEncodingException> {
            DocumentSpacePage(VERSION, listOf(item), null, snapshotChanged = true)
        }
        assertNull(DocumentSpacePage(VERSION, emptyList(), null, snapshotChanged = true).nextCursor)
    }

    @Test
    fun `snapshot version accepts zero dataset revisions but requires a credential epoch`() {
        assertEquals(
            VERSION,
            ProtoCodec.decode(DocumentDirectorySnapshotVersion, ProtoCodec.encode(VERSION)),
        )
        assertFailsWith<IllegalArgumentException> {
            DocumentDirectorySnapshotVersion(-1L, 0L, 1L)
        }
        assertFailsWith<IllegalArgumentException> {
            DocumentDirectorySnapshotVersion(0L, -1L, 1L)
        }
        assertFailsWith<IllegalArgumentException> {
            DocumentDirectorySnapshotVersion(0L, 0L, 0L)
        }

        val malformedWireEpoch = ProtoCodec.encodePayload {
            writeVarLong(0L)
            writeVarLong(0L)
            writeVarLong(0L)
        }
        assertFailsWith<ProtocolCorruptionException> {
            ProtoCodec.decode(DocumentDirectorySnapshotVersion, malformedWireEpoch)
        }
    }

    private fun uuid(index: Int): String =
        "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}"

    private companion object {
        val VERSION = DocumentDirectorySnapshotVersion(
            documentDirectoryRevision = 7L,
            organizationRevision = 3L,
            actorCredentialEpoch = 2L,
        )
    }
}
