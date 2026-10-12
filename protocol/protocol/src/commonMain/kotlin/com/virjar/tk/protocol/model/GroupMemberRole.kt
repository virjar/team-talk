package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.WireCoded
import kotlinx.serialization.Serializable

/**
 * 群成员角色。wire/DB 上以其 [code] Int 值传输（实现 [WireCoded]：字段在
 * `Int` 与本类型之间切换不构成 wire 变更）；模型字段直接使用本类型，逻辑代码
 * 比较 `member.role == GroupMemberRole.OWNER`——类型层面无法再与账号状态等
 * 其他 Int 值空间混用。
 *
 * [Unknown] 保留未来版本新增的未知取值：读取端不崩溃、往返写回无损，权限裁决按
 * 低于普通成员处理（fail closed）。[rank] 是权限层级，"管理员及以上/不能管理
 * 同级或更高"等裁决用 rank 比较。
 */
@Serializable(with = GroupMemberRole.CodeSerializer::class)
sealed interface GroupMemberRole : WireCoded {
    val rank: Int

    data object MEMBER : GroupMemberRole {
        override val code = 0
        override val rank = 0
    }

    data object ADMIN : GroupMemberRole {
        override val code = 1
        override val rank = 1
    }

    data object OWNER : GroupMemberRole {
        override val code = 2
        override val rank = 2
    }

    data class Unknown(override val code: Int) : GroupMemberRole {
        override val rank = -1
    }

    fun isAtLeast(min: GroupMemberRole): Boolean = rank >= min.rank

    object CodeSerializer : com.virjar.tk.protocol.WireCodedSerializer<GroupMemberRole>("GroupMemberRole") {
        override fun fromCode(code: Int) = GroupMemberRole.fromCode(code)
    }

    companion object {
        fun fromCode(code: Int): GroupMemberRole = when (code) {
            MEMBER.code -> MEMBER
            ADMIN.code -> ADMIN
            OWNER.code -> OWNER
            else -> Unknown(code)
        }
    }
}
