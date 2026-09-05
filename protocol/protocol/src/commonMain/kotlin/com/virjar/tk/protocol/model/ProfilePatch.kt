package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.ProtocolCorruptionException

/**
 * 部分更新契约使用的、带存在感知的值。
 *
 * [ProfilePatchValue.Unchanged] 表示服务端必须保持持久化字段不动。
 * [ProfilePatchValue.Set] 表示该字段属于本命令的一部分；可空的资料字段
 * 使用 `Set(null)` 表示显式清空。
 */
sealed interface ProfilePatchValue<out T> {
    val isPresent: Boolean
    val valueOrNull: T?

    data object Unchanged : ProfilePatchValue<Nothing> {
        override val isPresent: Boolean = false
        override val valueOrNull: Nothing? = null
    }

    data class Set<T>(val value: T) : ProfilePatchValue<T> {
        override val isPresent: Boolean = true
        override val valueOrNull: T? get() = value
    }
}

/**
 * 针对 [User] 可变部分的部分更新。身份与账户控制字段在构造上就被省略，
 * 因此调用方无法尝试替换 uid、username、role 或 status。
 *
 * 线格式：`fieldMask(VarInt)` 后跟按 name/avatar/sex/phone 顺序排列的每个存在值。
 * 可空值保留各自的存在标记，使 avatar 与 phone 能区分 `Unchanged` 和 `Set(null)`。
 * 非空 avatar 是完整的 FileStore [Attachment] 描述符，而不是任意 URL。
 */
data class ProfilePatch(
    val name: ProfilePatchValue<String> = ProfilePatchValue.Unchanged,
    val avatar: ProfilePatchValue<Attachment?> = ProfilePatchValue.Unchanged,
    val sex: ProfilePatchValue<Int> = ProfilePatchValue.Unchanged,
    val phone: ProfilePatchValue<String?> = ProfilePatchValue.Unchanged,
) : IProto {
    val isEmpty: Boolean
        get() = !name.isPresent && !avatar.isPresent && !sex.isPresent && !phone.isPresent

    override fun writeTo(buf: PacketBuffer) {
        var fieldMask = 0
        if (name.isPresent) fieldMask = fieldMask or FIELD_NAME
        if (avatar.isPresent) fieldMask = fieldMask or FIELD_AVATAR
        if (sex.isPresent) fieldMask = fieldMask or FIELD_SEX
        if (phone.isPresent) fieldMask = fieldMask or FIELD_PHONE
        buf.writeVarInt(fieldMask)

        if (name.isPresent) buf.writeString(requireNotNull(name.valueOrNull))
        if (avatar.isPresent) {
            val value = avatar.valueOrNull
            buf.writeBoolean(value != null)
            value?.let(UserAvatarPolicy::requireCanonical)?.writeTo(buf)
        }
        if (sex.isPresent) buf.writeVarInt(requireNotNull(sex.valueOrNull))
        if (phone.isPresent) buf.writeString(phone.valueOrNull)
    }

    companion object : IProtoReader<ProfilePatch> {
        private const val FIELD_NAME = 1 shl 0
        private const val FIELD_AVATAR = 1 shl 1
        private const val FIELD_SEX = 1 shl 2
        private const val FIELD_PHONE = 1 shl 3
        private const val KNOWN_FIELDS = FIELD_NAME or FIELD_AVATAR or FIELD_SEX or FIELD_PHONE

        override fun readFrom(buf: PacketBuffer): ProfilePatch {
            val fieldMask = buf.readVarInt()
            if (fieldMask and KNOWN_FIELDS.inv() != 0) {
                throw ProtocolCorruptionException("Unknown profile patch field mask: $fieldMask")
            }
            return ProfilePatch(
                name = if (fieldMask and FIELD_NAME != 0) {
                    ProfilePatchValue.Set(buf.readRequiredString(fieldName = "profile patch name"))
                } else {
                    ProfilePatchValue.Unchanged
                },
                avatar = if (fieldMask and FIELD_AVATAR != 0) {
                    ProfilePatchValue.Set(
                        if (buf.readBoolean("profile patch avatar presence")) {
                            UserAvatarPolicy.readFrom(buf, "profile patch avatar")
                        } else {
                            null
                        },
                    )
                } else {
                    ProfilePatchValue.Unchanged
                },
                sex = if (fieldMask and FIELD_SEX != 0) {
                    ProfilePatchValue.Set(buf.readVarInt())
                } else {
                    ProfilePatchValue.Unchanged
                },
                phone = if (fieldMask and FIELD_PHONE != 0) {
                    ProfilePatchValue.Set(buf.readString())
                } else {
                    ProfilePatchValue.Unchanged
                },
            )
        }
    }
}
