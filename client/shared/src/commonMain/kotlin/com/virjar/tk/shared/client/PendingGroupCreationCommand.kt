package com.virjar.tk.shared.client

import com.virjar.tk.protocol.model.ConversationWirePolicy
import com.virjar.tk.protocol.model.GroupPolicy
import java.util.UUID

/**
 * 可能已在服务器上权威化的、冻结的唯一 GUI 建群命令。
 *
 * LocalCache 已经按规范部署与账号 uid 命名空间化。[creatorUid] 重复逻辑 owner，作为内部一致性
 * 检查，并作为服务器规范指纹的一部分。[operationId] 与完整的规范 RPC 载荷保持绑定，直到一次
 * 成功响应条件确认该精确命令，或用户显式用一个不同草稿替换它。
 */
data class PendingGroupCreationCommand(
    val operationId: String,
    val creatorUid: String,
    val name: String,
    val avatar: String?,
    val memberUids: List<String>,
) {
    /** 载荷相等刻意忽略命令身份。 */
    fun hasSamePayload(other: PendingGroupCreationCommand): Boolean =
        creatorUid == other.creatorUid && name == other.name && avatar == other.avatar &&
            memberUids == other.memberUids

    /** 已认证创建者在线格式上是隐式的，绝不能表现为一个被选中的联系人。 */
    val targetMemberUids: List<String>
        get() = memberUids.filterNot { it == creatorUid }

    internal fun requireCanonical(): PendingGroupCreationCommand {
        check(normalizedOrNull() == this) { "Pending group creation command is not canonical" }
        return this
    }

    internal fun encodedMemberUids(): String = memberUids.joinToString(MEMBER_SEPARATOR)

    private fun normalizedOrNull(): PendingGroupCreationCommand? = runCatching {
        val canonicalOperationId = UUID.fromString(operationId).toString()
            .takeIf { operationId.length == UUID_TEXT_LENGTH && it == operationId }
            ?: return null
        require(name.length <= ConversationWirePolicy.MAX_CHAT_NAME_LENGTH)
        val canonicalName = name.trim()
        require(canonicalName.isNotEmpty() && canonicalName.none(Char::isISOControl))
        require(avatar == null || avatar.length <= ConversationWirePolicy.MAX_AVATAR_LENGTH)
        val canonicalAvatar = avatar?.trim()?.takeIf(String::isNotEmpty)
        require(canonicalAvatar == null || canonicalAvatar.none(Char::isISOControl))
        val canonicalMembers = GroupPolicy.canonicalInitialMemberUids(creatorUid, memberUids)
        copy(
            operationId = canonicalOperationId,
            creatorUid = creatorUid,
            name = canonicalName,
            avatar = canonicalAvatar,
            memberUids = canonicalMembers,
        )
    }.getOrNull()

    companion object {
        private const val UUID_TEXT_LENGTH = 36
        private const val MEMBER_SEPARATOR = "\n"

        fun create(
            operationId: String,
            creatorUid: String,
            name: String,
            avatar: String? = null,
            memberUids: List<String>,
        ): PendingGroupCreationCommand = requireNotNull(
            PendingGroupCreationCommand(
                operationId,
                creatorUid,
                name,
                avatar,
                memberUids,
            ).normalizedOrNull(),
        ) { "建群草稿参数无效" }

        internal fun restore(
            operationId: String,
            creatorUid: String,
            name: String,
            avatar: String?,
            encodedMemberUids: String,
        ): PendingGroupCreationCommand {
            val members = if (encodedMemberUids.isEmpty()) {
                emptyList()
            } else {
                encodedMemberUids.split(MEMBER_SEPARATOR)
            }
            val restored = PendingGroupCreationCommand(operationId, creatorUid, name, avatar, members)
            check(restored.normalizedOrNull() == restored) {
                "Persisted group creation command is corrupt or non-canonical"
            }
            return restored
        }
    }
}
