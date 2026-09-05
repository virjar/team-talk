package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.ProtocolCorruptionException
import com.virjar.tk.protocol.ProtocolEncodingException
import com.virjar.tk.protocol.ProtoCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OrganizationPageTest {
    @Test
    fun `page envelopes reject truncation signals duplicates and oversized collections`() {
        assertFailsWith<ProtocolEncodingException> {
            OrganizationUnitPage(
                revision = 1,
                items = listOf(OrganizationUnit("one", name = "one")),
                nextCursor = "next",
            )
        }
        assertFailsWith<ProtocolEncodingException> {
            OrganizationUnitPage(
                revision = 2,
                items = listOf(OrganizationUnit("one", name = "one")),
                nextCursor = null,
                snapshotChanged = true,
            )
        }
        assertFailsWith<ProtocolEncodingException> {
            OrganizationMemberPage(
                revision = 1,
                items = listOf(
                    OrganizationMember("unit", "uid"),
                    OrganizationMember("unit", "uid"),
                ),
                nextCursor = null,
            )
        }
        assertFailsWith<ProtocolCorruptionException> {
            val payload = PacketBuffer().apply {
                writeVarLong(1)
                writeVarInt(OrganizationUnitPage.MAX_PAGE_SIZE + 1)
            }.toByteArray()
            ProtoCodec.withPayload(payload) { OrganizationUnitPage.readFrom(this) }
        }
    }

    @Test
    fun `request cursors and member scope have bounded canonical wire values`() {
        assertFailsWith<IllegalArgumentException> { OrganizationUnitPageRequest("not+base64") }
        assertFailsWith<IllegalArgumentException> {
            OrganizationUnitPageRequest("a".repeat(OrganizationPagePolicy.MAX_CURSOR_BYTES + 1))
        }
        assertFailsWith<IllegalArgumentException> {
            OrganizationMemberPageRequest("x".repeat(OrganizationPagePolicy.MAX_ID_CHARACTERS + 1), false)
        }
        assertEquals(
            OrganizationMemberPageRequest("unit", true, "cursor"),
            ProtoCodec.decode(
                OrganizationMemberPageRequest,
                ProtoCodec.encode(OrganizationMemberPageRequest("unit", true, "cursor")),
            ),
        )
    }

    @Test
    fun `capacity policy explicitly covers the ten-thousand-person product boundary`() {
        assertEquals(10_000, OrganizationCapacityPolicy.MAX_MEMBERS_PER_UNIT)
        assertEquals(10_000, OrganizationCapacityPolicy.MAX_ACTIVE_UNITS)
        assertEquals(64, OrganizationCapacityPolicy.MAX_TREE_DEPTH)
        assertEquals(20_000, OrganizationCapacityPolicy.MAX_UNIT_RECORDS)
        assertEquals(20_000, OrganizationCapacityPolicy.MAX_MANAGED_CHAT_PROJECTIONS)
        assertEquals(100_000, OrganizationCapacityPolicy.MAX_MEMBERSHIP_RELATIONS)
        assertEquals(32, OrganizationCapacityPolicy.MAX_MEMBERSHIPS_PER_USER)
    }
}
