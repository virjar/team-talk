package com.virjar.tk.protocol

import com.virjar.tk.protocol.model.*
import kotlin.test.*

class ChatDraftContractTest {
    @Test fun richDraftCommandsAndSnapshotsRoundTrip() {
        val content = ChatDraftContent("正文", mode = 2, replyToClientMsgId = "message-1", replyToServerSeq = 42)
        val command = ChatDraftCommand("chat", 8, "00000000-0000-0000-0000-000000000001", 100, content)
        assertEquals(command, ProtoCodec.decode(ChatDraftCommand, ProtoCodec.encode(command)))
        val snapshot = ChatDraftSnapshot("chat", 9, 100, content, false)
        val result = ChatDraftMutationResult(true, 9, snapshot)
        assertEquals(result, ProtoCodec.decode(ChatDraftMutationResult, ProtoCodec.encode(result)))
        assertEquals(ChatDraftChangedPayload("chat", 9), ProtoCodec.decode(ChatDraftChangedPayload,
            ProtoCodec.encode(ChatDraftChangedPayload("chat", 9))))
    }

    @Test fun clearConflictAndSendConsumeAreDistinct() {
        val clear = ChatDraftCommand("chat", 9, "00000000-0000-0000-0000-000000000001", 100,
            consumedClientMsgId = "accepted-message")
        assertEquals(clear, ProtoCodec.decode(ChatDraftCommand, ProtoCodec.encode(clear)))
        val tombstone = ChatDraftSnapshot("chat", 10, 101, null)
        val conflict = ChatDraftMutationResult(false, 0, tombstone)
        assertEquals(conflict, ProtoCodec.decode(ChatDraftMutationResult, ProtoCodec.encode(conflict)))
        assertFailsWith<IllegalArgumentException> { clear.copy(content = ChatDraftContent("仍有正文")) }
        assertFailsWith<IllegalArgumentException> { ChatDraftContent("回复", replyToServerSeq = 1) }
        assertFailsWith<IllegalArgumentException> { ChatDraftContent("![缺少清单](teamtalk-asset://asset/00000000-0000-0000-0000-000000000001)") }
        assertFailsWith<IllegalArgumentException> { ChatDraftMutationResult(true, 11, tombstone) }
    }
}
