package com.virjar.tk.protocol.model

import kotlinx.serialization.Serializable
import com.virjar.tk.protocol.WireCoded

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
    val role: UserRole = UserRole.HUMAN,
    val status: UserStatus = UserStatus.ACTIVE,
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
        buf.writeVarInt(role.code)
        buf.writeVarInt(status.code)
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
            role = UserRole.fromCode(buf.readVarInt()),
            status = UserStatus.fromCode(buf.readVarInt()),
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

/**
 * 用户全局身份类型。群内 [GroupMemberRole] 是另一条独立维度；未知取值承载为 [UserRole.Unknown]。
 */
@Serializable(with = UserRole.CodeSerializer::class)
sealed interface UserRole : WireCoded {
    data object HUMAN : UserRole {
        override val code = 0
    }

    data object BOT : UserRole {
        override val code = 10
    }

    data object SYSTEM : UserRole {
        override val code = 20
    }

    data class Unknown(override val code: Int) : UserRole

    object CodeSerializer : com.virjar.tk.protocol.WireCodedSerializer<UserRole>("UserRole") {
        override fun fromCode(code: Int) = UserRole.fromCode(code)
    }

    companion object {
        fun fromCode(code: Int): UserRole = when (code) {
            HUMAN.code -> HUMAN
            BOT.code -> BOT
            SYSTEM.code -> SYSTEM
            else -> Unknown(code)
        }
    }
}

/**
 * 用户账号状态；服务端是唯一权威，客户端只消费投影。与 users 表 status 列共用值空间；
 * 未知取值承载为 [UserStatus.Unknown]，按非活跃处理（fail closed）。
 */
@Serializable(with = UserStatus.CodeSerializer::class)
sealed interface UserStatus : WireCoded {
    data object ACTIVE : UserStatus {
        override val code = 1
    }
    data object BANNED : UserStatus {
        override val code = 2
    }

    data class Unknown(override val code: Int) : UserStatus

    object CodeSerializer : com.virjar.tk.protocol.WireCodedSerializer<UserStatus>("UserStatus") {
        override fun fromCode(code: Int) = UserStatus.fromCode(code)
    }

    companion object {
        fun fromCode(code: Int): UserStatus = when (code) {
            ACTIVE.code -> ACTIVE
            BANNED.code -> BANNED
            else -> Unknown(code)
        }
    }
}

/** 各业务入口共用的“活动普通用户”判定；封禁、服务身份与未知取值都不是有效业务 actor。 */
val User.isActiveHuman: Boolean
    get() = role == UserRole.HUMAN && status == UserStatus.ACTIVE
