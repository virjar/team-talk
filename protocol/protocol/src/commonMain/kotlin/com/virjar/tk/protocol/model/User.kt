package com.virjar.tk.protocol.model

import kotlinx.serialization.Serializable

import com.virjar.tk.protocol.body.AttachmentPolicy
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.ProtocolCorruptionException
import com.virjar.tk.protocol.ProtocolEncodingException

@Serializable
data class User(
    val uid: String,
    val username: String,
    val name: String,
    val avatar: Attachment? = null,
    val phone: String? = null,
    val sex: Int = 0,
    val role: Int = 0,
    val status: Int = 1,
    /** 该用户快照中每个对外可见事实的单调版本号。 */
    val revision: Long = 1,
) : IProto {

    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(uid)
        buf.writeString(username)
        buf.writeString(name)
        buf.writeBoolean(avatar != null)
        avatar?.let(UserAvatarPolicy::requireCanonical)?.writeTo(buf)
        buf.writeString(phone)
        buf.writeVarInt(sex)
        buf.writeVarInt(role)
        buf.writeVarInt(status)
        if (revision <= 0L) throw ProtocolEncodingException("User revision is invalid")
        buf.writeVarLong(revision)
    }

    companion object : com.virjar.tk.protocol.IProtoReader<User> {
        override fun readFrom(buf: PacketBuffer): User = User(
            uid = buf.readRequiredString(),
            username = buf.readRequiredString(),
            name = buf.readRequiredString(),
            avatar = if (buf.readBoolean("user avatar presence")) {
                UserAvatarPolicy.readFrom(buf, "user avatar")
            } else {
                null
            },
            phone = buf.readString(),
            sex = buf.readVarInt(),
            role = buf.readVarInt(),
            status = buf.readVarInt(),
            revision = buf.readVarLong().also { revision ->
                if (revision <= 0L) throw ProtocolCorruptionException("user revision is invalid")
            },
        )
    }
}

/** 用户头像是已认证的 TeamTalk 图片附件，绝不是任意 URL。 */
object UserAvatarPolicy {
    const val MAX_BYTES: Long = 8L * 1024L * 1024L

    val allowedContentTypes: Set<String> = setOf(
        "image/jpeg",
        "image/png",
        "image/webp",
    )

    const val MAX_TEXT_CHARACTERS: Int =
        AttachmentPolicy.MAX_REFERENCE_LENGTH +
            AttachmentPolicy.MAX_NAME_LENGTH +
            AttachmentPolicy.MAX_CONTENT_TYPE_LENGTH

    fun canonicalize(avatar: Attachment): Attachment =
        AttachmentPolicy.canonicalizeDescriptor(avatar).also { canonical ->
            require(canonical.size <= MAX_BYTES) { "用户头像不能超过 8 MiB" }
            require(canonical.contentType in allowedContentTypes) {
                "用户头像必须是支持的图片附件"
            }
        }

    fun requireCanonical(avatar: Attachment): Attachment = canonicalize(avatar).also { canonical ->
        require(canonical == avatar) { "用户头像必须使用 canonical FileStore 描述符" }
    }

    internal fun readFrom(buf: PacketBuffer, fieldName: String): Attachment = try {
        requireCanonical(Attachment.readFrom(buf))
    } catch (_: IllegalArgumentException) {
        throw ProtocolCorruptionException("$fieldName is invalid")
    }
}

/** 用户全局身份类型。群内 Member.role 是另一条独立维度。 */
object UserRole {
    const val HUMAN = 0
    const val BOT = 10
    const val SYSTEM = 20
}
