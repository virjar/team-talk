package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.ProtocolCorruptionException
import com.virjar.tk.protocol.rpc.gen.UserRpcContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfilePatchTest {
    @Test
    fun `profile patch round trip preserves unchanged value and explicit null`() {
        val patch = ProfilePatch(
            name = ProfilePatchValue.Set("New Name"),
            avatar = ProfilePatchValue.Set(null),
            sex = ProfilePatchValue.Unchanged,
            phone = ProfilePatchValue.Set("13800000000"),
        )

        val decoded = ProtoCodec.decode(ProfilePatch, ProtoCodec.encode(patch))

        assertEquals(patch, decoded)
        assertFalse(decoded.isEmpty)
        assertEquals(ProfilePatchValue.Unchanged, decoded.sex)
        assertEquals(ProfilePatchValue.Set(null), decoded.avatar)
    }

    @Test
    fun `empty profile patch remains an explicit no-op`() {
        val decoded = ProtoCodec.decode(ProfilePatch, ProtoCodec.encode(ProfilePatch()))

        assertTrue(decoded.isEmpty)
        assertEquals(ProfilePatch(), decoded)
    }

    @Test
    fun `user update RPC uses the profile patch wire contract`() {
        val patch = ProfilePatch(
            avatar = ProfilePatchValue.Set(avatar),
            phone = ProfilePatchValue.Set(null),
        )

        val decoded = ProtoCodec.withPayload(UserRpcContract.encodeUpdateProfile(patch)) {
            ProfilePatch.readFrom(this)
        }

        assertEquals(patch, decoded)
    }

    private val avatar = Attachment(
        path = "u1/avatar.png",
        name = "avatar.png",
        contentType = "image/png",
        size = 128,
    )

    @Test
    fun `profile patch rejects unknown fields and null required values`() {
        assertFailsWith<ProtocolCorruptionException> {
            ProtoCodec.decode(ProfilePatch, byteArrayOf(0x10))
        }
        assertFailsWith<ProtocolCorruptionException> {
            ProtoCodec.decode(ProfilePatch, byteArrayOf(0x01, 0x00))
        }
    }
}
