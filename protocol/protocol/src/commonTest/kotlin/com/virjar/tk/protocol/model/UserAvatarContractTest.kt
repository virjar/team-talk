package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.body.CardBody
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.ProtocolCorruptionException
import com.virjar.tk.protocol.ProtocolEncodingException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UserAvatarContractTest {
    @Test
    fun `user conversation and contact card round trip one canonical avatar descriptor`() {
        val avatar = avatar()
        val user = User(uid = "u1", username = "alice", name = "Alice", avatar = avatar, revision = 7)
        val conversation = Conversation(
            chatId = "personal-chat",
            chatType = 1,
            peerUid = user.uid,
            peerRevision = user.revision,
            chatName = user.name,
            chatAvatar = avatar,
        )
        val card = CardBody(targetUid = user.uid, targetName = user.name, targetAvatar = avatar)

        assertEquals(user, ProtoCodec.decode(User, ProtoCodec.encode(user)))
        assertEquals(conversation, ProtoCodec.decode(Conversation, ProtoCodec.encode(conversation)))
        assertEquals(card, ProtoCodec.decode(CardBody, ProtoCodec.encode(card)))
    }

    @Test
    fun `user revision is positive and survives canonical wire round trip`() {
        val user = User(uid = "u1", username = "alice", name = "Alice", revision = 42)

        assertEquals(42L, ProtoCodec.decode(User, ProtoCodec.encode(user)).revision)
        assertFailsWith<ProtocolEncodingException> {
            ProtoCodec.encode(user.copy(revision = 0))
        }
        val malformed = ProtoCodec.encodePayload {
            writeString("u1")
            writeString("alice")
            writeString("Alice")
            writeBoolean(false)
            writeString(null)
            writeVarInt(0)
            writeVarInt(0)
            writeVarInt(1)
            writeVarLong(0)
        }
        assertFailsWith<ProtocolCorruptionException> { ProtoCodec.decode(User, malformed) }
    }

    @Test
    fun `avatar policy rejects arbitrary URLs unsupported media and oversized objects`() {
        assertFailsWith<IllegalArgumentException> {
            UserAvatarPolicy.requireCanonical(avatar(path = "https://example.test/api/v1/files/u1/avatar.png"))
        }
        listOf("application/octet-stream", "image/gif", "image/avif", "image/svg+xml").forEach { contentType ->
            assertFailsWith<IllegalArgumentException> {
                UserAvatarPolicy.requireCanonical(avatar(contentType = contentType))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            UserAvatarPolicy.requireCanonical(avatar(size = UserAvatarPolicy.MAX_BYTES + 1))
        }
        assertEquals(
            UserAvatarPolicy.MAX_BYTES,
            UserAvatarPolicy.requireCanonical(avatar(size = UserAvatarPolicy.MAX_BYTES)).size,
        )
        assertEquals(
            setOf("image/jpeg", "image/png", "image/webp"),
            UserAvatarPolicy.allowedContentTypes,
        )
    }

    @Test
    fun `conversation requires a personal peer and forbids group avatar descriptors`() {
        assertFailsWith<ProtocolEncodingException> {
            ProtoCodec.encode(Conversation(chatId = "personal", chatType = 1))
        }
        assertFailsWith<ProtocolEncodingException> {
            ProtoCodec.encode(Conversation(chatId = "personal", chatType = 1, peerUid = "u1"))
        }
        assertFailsWith<ProtocolEncodingException> {
            ProtoCodec.encode(
                Conversation(chatId = "personal", chatType = 1, peerUid = "u1", peerRevision = 0),
            )
        }
        assertFailsWith<ProtocolEncodingException> {
            ProtoCodec.encode(Conversation(chatId = "group", chatType = 2, peerRevision = 1))
        }
        assertFailsWith<ProtocolEncodingException> {
            ProtoCodec.encode(
                Conversation(
                    chatId = "group",
                    chatType = 2,
                    chatAvatar = avatar(),
                ),
            )
        }
    }

    private fun avatar(
        path: String = "u1/avatar.png",
        contentType: String = "image/png",
        size: Long = 512,
    ) = Attachment(
        path = path,
        name = "avatar.png",
        contentType = contentType,
        size = size,
    )
}
