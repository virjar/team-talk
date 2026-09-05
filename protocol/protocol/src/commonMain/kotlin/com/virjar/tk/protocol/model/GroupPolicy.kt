package com.virjar.tk.protocol.model

/** 客户端与所有服务端写入适配器共享的 canonical 群成员信封。 */
object GroupPolicy {
    /** 人类用户、群主与服务身份都占用同一个活跃成员预算。 */
    const val MAX_MEMBERS = 1_000
    const val MAX_MEMBER_UIDS_PER_COMMAND = MAX_MEMBERS
    const val MAX_MEMBER_UID_LENGTH = 36

    const val CAPACITY_LIMIT_REASON = "群成员数量已达上限"
    const val INPUT_LIMIT_REASON = "单次群成员请求过大"
    const val INVALID_MEMBER_REASON = "群成员参数无效"

    /** 创建者总是恰好被包含一次，并占用一个成员名额。 */
    fun canonicalInitialMemberUids(creatorUid: String, memberUids: List<String>): List<String> {
        requireInputEnvelope(memberUids, allowEmpty = true)
        requireValidMemberUid(creatorUid)
        val result = linkedSetOf(creatorUid)
        memberUids.forEach { uid ->
            requireValidMemberUid(uid)
            result += uid
        }
        requireFinalMemberCount(result.size)
        return result.sorted()
    }

    /** 在去重之前先约束原始分配工作，并返回确定性的目标集合。 */
    fun canonicalTargetMemberUids(memberUids: List<String>): List<String> {
        requireInputEnvelope(memberUids, allowEmpty = false)
        val result = linkedSetOf<String>()
        memberUids.forEach { uid ->
            requireValidMemberUid(uid)
            result += uid
        }
        return result.sorted()
    }

    fun requireValidMemberUid(uid: String) {
        if (
            uid.isEmpty() ||
            uid.length > MAX_MEMBER_UID_LENGTH ||
            uid.any { character ->
                character.isISOControl() || character.isWhitespace()
            }
        ) {
            throw IllegalArgumentException(INVALID_MEMBER_REASON)
        }
    }

    /** 校验最终替换结果，例如全新或组织托管的群投影。 */
    fun requireFinalMemberCount(memberCount: Int) {
        check(memberCount >= 0) { "Group member count cannot be negative" }
        if (memberCount > MAX_MEMBERS) throw IllegalArgumentException(CAPACITY_LIMIT_REASON)
    }

    /** 在不发生整数溢出的前提下校验 活跃数 + 互不相同的当前非活跃目标。 */
    fun requireAdditionalCapacity(activeMemberCount: Int, newMemberCount: Int) {
        check(activeMemberCount >= 0) { "Active group member count cannot be negative" }
        check(newMemberCount >= 0) { "New group member count cannot be negative" }
        if (
            activeMemberCount > MAX_MEMBERS ||
            newMemberCount > MAX_MEMBERS - activeMemberCount
        ) {
            throw IllegalArgumentException(CAPACITY_LIMIT_REASON)
        }
    }

    private fun requireInputEnvelope(memberUids: List<String>, allowEmpty: Boolean) {
        if (!allowEmpty && memberUids.isEmpty()) throw IllegalArgumentException(INVALID_MEMBER_REASON)
        if (memberUids.size > MAX_MEMBER_UIDS_PER_COMMAND) {
            throw IllegalArgumentException(INPUT_LIMIT_REASON)
        }
    }
}
