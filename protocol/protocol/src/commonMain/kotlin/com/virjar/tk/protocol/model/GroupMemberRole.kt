package com.virjar.tk.protocol.model

import kotlinx.serialization.Serializable

/**
 * 群成员角色枚举。wire/DB 列仍存 Int code（[Member.role] 字段类型受已发行契约锁定），
 * 逻辑代码通过 [Member.roleValue] 取本枚举比较——类型层面无法再与账号状态等其他
 * Int 值空间混用。
 *
 * 未知 code（未来版本新增角色）映射为 null，权限裁决按低于普通成员处理（fail closed）。
 * [rank] 是权限层级，"管理员及以上/不能管理同级或更高"等裁决用 rank 比较。
 *
 * code 值与既有 [Member.role] Int 字段自 0.0 起的取值一致；枚举包装自 protocol 0.7 起登记。
 */
@com.virjar.tk.protocol.SinceProtocol(7)
@Serializable
enum class GroupMemberRole(val code: Int) {
    MEMBER(0),
    ADMIN(1),
    OWNER(2);

    /** 权限层级，与 code 同序：越大越高。 */
    val rank: Int get() = code

    fun isAtLeast(min: GroupMemberRole): Boolean = rank >= min.rank

    companion object {
        fun fromCode(code: Int): GroupMemberRole? = entries.find { it.code == code }
    }
}

/** 成员角色的类型化视图；null = 未知取值（未来版本），权限裁决应按无特权处理。 */
val Member.roleValue: GroupMemberRole?
    get() = GroupMemberRole.fromCode(role)

/** 未知角色按低于普通成员的层级参与裁决（fail closed）。 */
val Member.roleRank: Int
    get() = roleValue?.rank ?: -1

/** 可空角色（非成员/未知值）不满足任何最低权限要求。 */
fun GroupMemberRole?.isAtLeast(min: GroupMemberRole): Boolean = this != null && rank >= min.rank
