package com.virjar.tk.protocol

import com.virjar.tk.protocol.model.MessageReactionGroup
import com.virjar.tk.protocol.model.MessageReactionSummary
import com.virjar.tk.protocol.MessageReactionEventPayload
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.ProtocolCorruptionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MessageReactionModelTest {
    @Test
    fun `summary and group round trip`() {
        val summary = MessageReactionSummary(
            serverSeq = 7L,
            groups = listOf(
                MessageReactionGroup("👍", listOf("u2", "u1")),
                MessageReactionGroup("🎉", listOf("u3")),
            ),
        )
        val decoded = ProtoCodec.decode(MessageReactionSummary, ProtoCodec.encode(summary))
        assertEquals(summary, decoded)
        // canonical 排序在构造时收敛：emoji 升序、uid 升序
        assertEquals(listOf("🎉", "👍"), decoded.groups.map(MessageReactionGroup::emoji))
        assertEquals(listOf("u1", "u2"), decoded.groups[1].reactorUids)
    }

    @Test
    fun `event payload round trip`() {
        val payload = MessageReactionEventPayload("c1", 9L, "❤️", "u1", action = 1)
        assertEquals(
            payload,
            ProtoCodec.decode(MessageReactionEventPayload, ProtoCodec.encode(payload)),
        )
        assertTrue(payload.added)
    }

    @Test
    fun `duplicate reactors and duplicate emojis are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            MessageReactionGroup("👍", listOf("u1", "u1"))
        }
        assertFailsWith<IllegalArgumentException> {
            MessageReactionSummary(1L, listOf(MessageReactionGroup("👍", listOf("u1")), MessageReactionGroup("👍", listOf("u2"))))
        }
    }

    @Test
    fun `unsorted wire input is corruption`() {
        // 手工构造乱序 wire：writeTo 会输出 canonical 顺序，所以用 PacketBuffer 直接写非 canonical 输入
        val corruptBytes = PacketBuffer().apply {
            writeString("👍")
            writeVarInt(2)
            writeString("u2")
            writeString("u1")
        }.toByteArray()
        assertFailsWith<ProtocolCorruptionException> {
            MessageReactionGroup.readFrom(PacketBuffer(corruptBytes))
        }
    }
}
