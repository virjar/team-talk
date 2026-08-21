package com.virjar.tk.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import io.netty.handler.codec.CorruptedFrameException

/**
 * Presence-aware value used by partial update contracts.
 *
 * [ProfilePatchValue.Unchanged] means that the server must leave the persisted field untouched.
 * [ProfilePatchValue.Set] means that the field is part of this command; nullable profile fields
 * use `Set(null)` for an explicit clear.
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
 * Partial update for the mutable part of [User]. Identity and account-control fields are omitted
 * by construction, so callers cannot attempt to replace uid, username, role or status.
 *
 * Wire format: `fieldMask(VarInt)` followed by each present value in name/avatar/sex/phone order.
 * String values retain their own nullable marker, allowing avatar and phone to distinguish
 * `Unchanged` from `Set(null)`.
 */
data class ProfilePatch(
    val name: ProfilePatchValue<String> = ProfilePatchValue.Unchanged,
    val avatar: ProfilePatchValue<String?> = ProfilePatchValue.Unchanged,
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
        if (avatar.isPresent) buf.writeString(avatar.valueOrNull)
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
                throw CorruptedFrameException("Unknown profile patch field mask: $fieldMask")
            }
            return ProfilePatch(
                name = if (fieldMask and FIELD_NAME != 0) {
                    ProfilePatchValue.Set(buf.readRequiredString(fieldName = "profile patch name"))
                } else {
                    ProfilePatchValue.Unchanged
                },
                avatar = if (fieldMask and FIELD_AVATAR != 0) {
                    ProfilePatchValue.Set(buf.readString())
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
