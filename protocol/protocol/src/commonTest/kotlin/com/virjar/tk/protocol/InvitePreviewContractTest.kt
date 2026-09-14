package com.virjar.tk.protocol

import com.virjar.tk.protocol.model.InvitePreview
import com.virjar.tk.protocol.rpc.gen.ChatRpcContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InvitePreviewContractTest {
    @Test
    fun `preview supports unknown invitations and existing members without changing old invitation contracts`() {
        val chatId = "00000000-0000-4000-8000-000000000031"
        val previews = listOf(
            InvitePreview(InvitePreview.VALID, chatId, "受邀工作群", 12),
            InvitePreview(InvitePreview.REVOKED, chatId, "已加入的群", 3, alreadyJoined = true),
            InvitePreview(InvitePreview.EXHAUSTED),
            InvitePreview(InvitePreview.NOT_FOUND),
        )
        previews.forEach { preview ->
            assertEquals(preview, ProtoCodec.decode(InvitePreview, ProtoCodec.encode(preview)))
        }
        assertEquals(25, ChatRpcContract.M_PREVIEW_INVITE)
        val version = ChatRpcContract.METHOD_VERSIONS.getValue(ChatRpcContract.M_PREVIEW_INVITE)
        assertFalse(version.supports(ProtocolVersion(0, 2)))
        assertTrue(version.supports(ProtocolVersion(0, 3)))
        assertEquals(18, ChatRpcContract.M_JOIN_BY_INVITE)
        assertEquals(19, ChatRpcContract.M_GET_INVITE_INFO)
        ProtoCodec.withPayload(ChatRpcContract.encodePreviewInvite(chatId)) {
            assertEquals(chatId, readRequiredString())
        }
    }
}
