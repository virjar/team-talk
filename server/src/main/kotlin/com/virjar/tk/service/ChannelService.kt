package com.virjar.tk.service

import com.virjar.tk.api.BusinessException
import com.virjar.tk.db.ChannelMemberRow
import com.virjar.tk.db.ChannelRow
import com.virjar.tk.db.InviteLinkRow
import com.virjar.tk.dto.*
import com.virjar.tk.protocol.ChannelErrorCode
import com.virjar.tk.protocol.ChannelType
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.payload.MemberAddedPayload
import com.virjar.tk.protocol.payload.MemberMutedPayload
import com.virjar.tk.protocol.payload.MemberRoleChangedPayload
import com.virjar.tk.protocol.payload.MemberUnmutedPayload
import com.virjar.tk.store.ChannelStore
import com.virjar.tk.store.ConversationStore
import com.virjar.tk.store.InviteLinkStore
import com.virjar.tk.tcp.ClientRegistry
import io.ktor.http.*
import org.slf4j.LoggerFactory
import java.security.SecureRandom

class ChannelService(
    private val channelStore: ChannelStore,
    private val conversationStore: ConversationStore,
    private val inviteLinkStore: InviteLinkStore,
) {
    private val logger = LoggerFactory.getLogger(ChannelService::class.java)

    // ================================================================
    // 权限辅助
    // ================================================================

    /**
     * 获取操作者在频道中的角色，未找到时抛 NOT_MEMBER 异常。
     */
    private fun requireMember(channelId: String, uid: String): Int {
        return channelStore.getMemberRole(channelId, uid)
            ?: throw BusinessException(ChannelErrorCode.NOT_MEMBER, "not a member", HttpStatusCode.Forbidden)
    }

    /**
     * 要求操作者角色 >= [minRole]，否则抛 INSUFFICIENT_PERMISSION。
     */
    private fun requireRole(channelId: String, uid: String, minRole: Int): Int {
        val role = requireMember(channelId, uid)
        if (role < minRole) {
            throw BusinessException(ChannelErrorCode.INSUFFICIENT_PERMISSION, "insufficient permission", HttpStatusCode.Forbidden)
        }
        return role
    }

    // ================================================================
    // 频道 CRUD
    // ================================================================

    suspend fun createPersonalChannel(uid1: String, uid2: String): ChannelRow {
        val channelId = buildPersonalChannelId(uid1, uid2)
        val existing = channelStore.findByChannelId(channelId)
        if (existing != null) return existing

        val channel = channelStore.create(
            channelId = channelId,
            channelType = ChannelType.PERSONAL,
            name = "",
            avatar = "",
            creator = null,
        )

        channelStore.addMember(channelId, ChannelType.PERSONAL, uid1)
        channelStore.addMember(channelId, ChannelType.PERSONAL, uid2)

        conversationStore.createOrUpdate(uid1, channelId, ChannelType.PERSONAL)
        conversationStore.createOrUpdate(uid2, channelId, ChannelType.PERSONAL)

        logger.info("Personal channel created: {} for users {} and {}", channelId, uid1, uid2)
        return channel
    }

    suspend fun createGroup(ownerUid: String, req: CreateGroupRequest): ChannelRow {
        val channelId = "group:${System.currentTimeMillis()}:${ownerUid.take(8)}"
        val channel = channelStore.create(
            channelId = channelId,
            channelType = ChannelType.GROUP,
            name = req.name,
            avatar = req.avatar,
            creator = ownerUid,
        )

        channelStore.addMember(channelId, ChannelType.GROUP, ownerUid, role = 2)
        for (memberUid in req.members) {
            if (memberUid != ownerUid) {
                channelStore.addMember(channelId, ChannelType.GROUP, memberUid, role = 0)
                conversationStore.createOrUpdate(memberUid, channelId, ChannelType.GROUP)
            }
        }
        conversationStore.createOrUpdate(ownerUid, channelId, ChannelType.GROUP)

        logger.info("Group created: {} by {}", channelId, ownerUid)
        return channel
    }

    suspend fun getChannel(channelId: String): ChannelDto {
        val channel = channelStore.findByChannelId(channelId) ?: throw BusinessException(ChannelErrorCode.CHANNEL_NOT_FOUND, "channel not found", HttpStatusCode.NotFound)
        val memberCount = channelStore.getMemberUids(channelId).size
        return channel.toResponse(memberCount)
    }

    /**
     * 更新频道信息 — owner(2) + admin(1) 可操作。
     */
    suspend fun updateChannel(channelId: String, uid: String, req: UpdateChannelRequest): ChannelDto {
        channelStore.findByChannelId(channelId) ?: throw BusinessException(ChannelErrorCode.CHANNEL_NOT_FOUND, "channel not found", HttpStatusCode.NotFound)
        requireRole(channelId, uid, 1) // admin+
        channelStore.update(channelId, req.name, req.avatar, req.notice)
        return getChannel(channelId)
    }

    suspend fun syncChannels(uid: String, version: Long): List<ChannelDto> {
        return channelStore.findByUser(uid, version).map { ch ->
            val memberCount = channelStore.getMemberUids(ch.channelId).size
            ch.toResponse(memberCount)
        }
    }

    // ================================================================
    // 成员管理
    // ================================================================

    suspend fun addMembers(channelId: String, operatorUid: String, uids: List<String>) {
        channelStore.findByChannelId(channelId) ?: throw BusinessException(ChannelErrorCode.CHANNEL_NOT_FOUND, "channel not found", HttpStatusCode.NotFound)
        val channel = channelStore.findByChannelId(channelId)!!
        if (!channelStore.isMember(channelId, operatorUid)) {
            throw BusinessException(ChannelErrorCode.NOT_MEMBER, "operator is not a member", HttpStatusCode.Forbidden)
        }
        for (uid in uids) {
            if (!channelStore.isMember(channelId, uid)) {
                channelStore.addMember(channelId, channel.channelType, uid)
                conversationStore.createOrUpdate(uid, channelId, channel.channelType)
            }
        }
        logger.info("Members added to {}: {}", channelId, uids)
    }

    /**
     * 踢出成员 — admin+ 可操作，admin 不能踢 admin/owner。
     */
    suspend fun removeMembers(channelId: String, operatorUid: String, uids: List<String>) {
        val channel = channelStore.findByChannelId(channelId) ?: throw BusinessException(ChannelErrorCode.CHANNEL_NOT_FOUND, "channel not found", HttpStatusCode.NotFound)
        if (channel.channelType == ChannelType.PERSONAL) error("cannot remove members from personal channel")

        val operatorRole = requireRole(channelId, operatorUid, 1) // admin+

        for (uid in uids) {
            val targetRole = channelStore.getMemberRole(channelId, uid) ?: continue
            // admin 不能踢 admin 或 owner
            if (operatorRole < 2 && targetRole >= 1) {
                throw BusinessException(ChannelErrorCode.CANNOT_KICK_ADMIN, "cannot kick admin or owner", HttpStatusCode.Forbidden)
            }
            channelStore.removeMember(channelId, uid)
        }
        logger.info("Members removed from {}: {}", channelId, uids)
    }

    suspend fun getMembers(channelId: String, page: Int): List<ChannelMemberDto> {
        return channelStore.getMembers(channelId, page).map { it.toResponse() }
    }

    /**
     * 解散群组 — 仅 owner。
     */
    suspend fun deleteChannel(channelId: String, uid: String) {
        val channel = channelStore.findByChannelId(channelId) ?: throw BusinessException(ChannelErrorCode.CHANNEL_NOT_FOUND, "channel not found", HttpStatusCode.NotFound)
        if (channel.channelType == ChannelType.PERSONAL) error("cannot delete personal channel")
        if (channel.creator != uid) throw BusinessException(ChannelErrorCode.NOT_OWNER, "only creator can delete channel", HttpStatusCode.Forbidden)
        channelStore.update(channelId, null, null, null)
        val members = channelStore.getMemberUids(channelId)
        for (memberUid in members) {
            channelStore.removeMember(channelId, memberUid)
        }
        logger.info("Channel deleted: {} by {}", channelId, uid)
    }

    /**
     * 转让群组 — 仅 owner。
     */
    suspend fun transferOwner(channelId: String, currentOwnerUid: String, newOwnerUid: String) {
        val channel = channelStore.findByChannelId(channelId) ?: throw BusinessException(ChannelErrorCode.CHANNEL_NOT_FOUND, "channel not found", HttpStatusCode.NotFound)
        if (channel.channelType == ChannelType.PERSONAL) error("cannot transfer personal channel")
        if (channel.creator != currentOwnerUid) throw BusinessException(ChannelErrorCode.NOT_OWNER, "only owner can transfer", HttpStatusCode.Forbidden)
        if (!channelStore.isMember(channelId, newOwnerUid)) throw BusinessException(ChannelErrorCode.NOT_MEMBER, "new owner must be a member", HttpStatusCode.Forbidden)

        val oldRole = channelStore.getMemberRole(channelId, newOwnerUid) ?: 0
        channelStore.transferOwner(channelId, currentOwnerUid, newOwnerUid)

        // 广播角色变更系统消息
        broadcastSystemPacket(channelId, MemberRoleChangedPayload(channelId, currentOwnerUid, currentOwnerUid, 2, 0))
        broadcastSystemPacket(channelId, MemberRoleChangedPayload(channelId, newOwnerUid, currentOwnerUid, oldRole.toByte(), 2))

        logger.info("Channel {} ownership transferred from {} to {}", channelId, currentOwnerUid, newOwnerUid)
    }

    /**
     * 设置成员角色 — 仅 owner。
     * 广播 MEMBER_ROLE_CHANGED 系统消息。
     */
    suspend fun setMemberRole(channelId: String, operatorUid: String, targetUid: String, role: Int) {
        val channel = channelStore.findByChannelId(channelId) ?: throw BusinessException(ChannelErrorCode.CHANNEL_NOT_FOUND, "channel not found", HttpStatusCode.NotFound)
        if (channel.channelType == ChannelType.PERSONAL) error("cannot set role in personal channel")
        requireRole(channelId, operatorUid, 2) // owner only
        if (!channelStore.isMember(channelId, targetUid)) throw BusinessException(ChannelErrorCode.NOT_MEMBER, "target is not a member", HttpStatusCode.Forbidden)

        val oldRole = channelStore.getMemberRole(channelId, targetUid) ?: 0
        channelStore.updateMemberRole(channelId, targetUid, role)

        // 广播角色变更
        broadcastSystemPacket(channelId, MemberRoleChangedPayload(channelId, targetUid, operatorUid, oldRole.toByte(), role.toByte()))

        logger.info("Member {} role set to {} in channel {} by {}", targetUid, role, channelId, operatorUid)
    }

    // ================================================================
    // 禁言管理
    // ================================================================

    /**
     * 禁言成员 — admin+ 可操作，不能禁言 admin/owner。
     */
    suspend fun muteMember(channelId: String, operatorUid: String, targetUid: String, durationSeconds: Long) {
        channelStore.findByChannelId(channelId) ?: throw BusinessException(ChannelErrorCode.CHANNEL_NOT_FOUND, "channel not found", HttpStatusCode.NotFound)
        val operatorRole = requireRole(channelId, operatorUid, 1) // admin+
        val targetRole = requireMember(channelId, targetUid)

        // 不能禁言 admin 或 owner
        if (targetRole >= 1) {
            throw BusinessException(ChannelErrorCode.CANNOT_MUTE_ADMIN, "cannot mute admin or owner", HttpStatusCode.Forbidden)
        }
        // admin 不能禁言 admin（双重检查）
        if (operatorRole < 2 && targetRole >= 1) {
            throw BusinessException(ChannelErrorCode.INSUFFICIENT_PERMISSION, "insufficient permission", HttpStatusCode.Forbidden)
        }

        val expiresAt = if (durationSeconds <= 0) 0L else System.currentTimeMillis() + durationSeconds * 1000
        channelStore.muteMember(channelId, targetUid, operatorUid, expiresAt)

        // 广播禁言系统消息
        broadcastSystemPacket(channelId, MemberMutedPayload(channelId, targetUid, operatorUid, durationSeconds))

        logger.info("Member {} muted in channel {} by {} for {}s", targetUid, channelId, operatorUid, durationSeconds)
    }

    /**
     * 解除禁言 — admin+ 可操作。
     */
    suspend fun unmuteMember(channelId: String, operatorUid: String, targetUid: String) {
        channelStore.findByChannelId(channelId) ?: throw BusinessException(ChannelErrorCode.CHANNEL_NOT_FOUND, "channel not found", HttpStatusCode.NotFound)
        requireRole(channelId, operatorUid, 1) // admin+

        channelStore.unmuteMember(channelId, targetUid)

        // 广播解禁系统消息
        broadcastSystemPacket(channelId, MemberUnmutedPayload(channelId, targetUid, operatorUid))

        logger.info("Member {} unmuted in channel {} by {}", targetUid, channelId, operatorUid)
    }

    /**
     * 全员禁言开关 — admin+ 可操作。
     */
    suspend fun setMutedAll(channelId: String, operatorUid: String, mutedAll: Boolean) {
        channelStore.findByChannelId(channelId) ?: throw BusinessException(ChannelErrorCode.CHANNEL_NOT_FOUND, "channel not found", HttpStatusCode.NotFound)
        requireRole(channelId, operatorUid, 1) // admin+

        channelStore.setMutedAll(channelId, mutedAll)

        logger.info("Channel {} muted_all set to {} by {}", channelId, mutedAll, operatorUid)
    }

    // ================================================================
    // 系统消息广播
    // ================================================================

    /**
     * 向频道所有在线成员广播 TCP 系统包。
     */
    private suspend fun broadcastSystemPacket(
        channelId: String,
        proto: IProto,
    ) {
        val memberUidList = channelStore.getMemberUids(channelId)

        for (uid in memberUidList) {
            for (agent in ClientRegistry.getAgentsByUid(uid)) {
                if (!agent.isActive) continue
                agent.send(proto)
            }
        }
    }

    // ================================================================
    // 响应转换
    // ================================================================

    private fun ChannelRow.toResponse(memberCount: Int) = ChannelDto(
        channelId = channelId,
        channelType = channelType.code,
        name = name,
        avatar = avatar,
        creator = creator,
        notice = notice,
        maxSeq = maxSeq,
        memberCount = memberCount,
        mutedAll = mutedAll,
    )

    private fun ChannelMemberRow.toResponse() = ChannelMemberDto(
        uid = uid,
        role = role,
        nickname = nickname,
    )

    // ================================================================
    // 邀请链接
    // ================================================================

    private val BASE62_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private val secureRandom = SecureRandom()

    private fun generateToken(): String {
        val bytes = ByteArray(8)
        secureRandom.nextBytes(bytes)
        return bytes.map { b -> BASE62_CHARS[Math.abs(b.toInt()) % BASE62_CHARS.length] }.joinToString("")
    }

    suspend fun createInviteLink(
        channelId: String,
        creatorUid: String,
        name: String?,
        maxUses: Int?,
        expiresIn: Long?,
    ): InviteLinkRow {
        val channel = channelStore.findByChannelId(channelId)
            ?: throw BusinessException(ChannelErrorCode.CHANNEL_NOT_FOUND, "channel not found", HttpStatusCode.NotFound)
        if (channel.channelType != ChannelType.GROUP) {
            throw BusinessException(ChannelErrorCode.INSUFFICIENT_PERMISSION, "only group channels support invite links", HttpStatusCode.BadRequest)
        }
        requireRole(channelId, creatorUid, 1) // admin+

        val activeCount = inviteLinkStore.countActiveByChannel(channelId)
        if (activeCount >= 5) {
            throw BusinessException(ChannelErrorCode.INVITE_LINK_LIMIT, "max 5 active invite links per group", HttpStatusCode.BadRequest)
        }

        val token = generateToken()
        val expiresAt = expiresIn?.let { System.currentTimeMillis() + it }
        val link = inviteLinkStore.create(channelId, creatorUid, token, name, maxUses, expiresAt)

        logger.info("Invite link created: {} for channel {} by {}", token, channelId, creatorUid)
        return link
    }

    suspend fun getInviteLinks(channelId: String, operatorUid: String): List<InviteLinkRow> {
        channelStore.findByChannelId(channelId)
            ?: throw BusinessException(ChannelErrorCode.CHANNEL_NOT_FOUND, "channel not found", HttpStatusCode.NotFound)
        requireRole(channelId, operatorUid, 1) // admin+
        return inviteLinkStore.findActiveByChannel(channelId)
    }

    suspend fun revokeInviteLink(channelId: String, token: String, operatorUid: String) {
        channelStore.findByChannelId(channelId)
            ?: throw BusinessException(ChannelErrorCode.CHANNEL_NOT_FOUND, "channel not found", HttpStatusCode.NotFound)

        val link = inviteLinkStore.findByToken(token)
            ?: throw BusinessException(ChannelErrorCode.INVITE_LINK_NOT_FOUND, "invite link not found", HttpStatusCode.NotFound)

        if (link.channelId != channelId) {
            throw BusinessException(ChannelErrorCode.INVITE_LINK_NOT_FOUND, "invite link does not belong to this channel", HttpStatusCode.BadRequest)
        }

        // admin+ can revoke, or the creator can revoke their own
        val role = channelStore.getMemberRole(channelId, operatorUid)
        if (role == null || (role < 1 && link.creatorUid != operatorUid)) {
            throw BusinessException(ChannelErrorCode.INSUFFICIENT_PERMISSION, "insufficient permission", HttpStatusCode.Forbidden)
        }

        inviteLinkStore.revoke(token)
        logger.info("Invite link revoked: {} in channel {} by {}", token, channelId, operatorUid)
    }

    suspend fun joinByInviteLink(token: String, uid: String): Pair<ChannelRow, Boolean> {
        val link = inviteLinkStore.findByToken(token)
            ?: throw BusinessException(ChannelErrorCode.INVITE_LINK_NOT_FOUND, "invite link not found", HttpStatusCode.NotFound)

        // Check revoked
        if (link.revokedAt != null) {
            throw BusinessException(ChannelErrorCode.INVITE_LINK_NOT_FOUND, "invite link has been revoked", HttpStatusCode.BadRequest)
        }

        // Check expired
        if (link.expiresAt != null && link.expiresAt <= System.currentTimeMillis()) {
            throw BusinessException(ChannelErrorCode.INVITE_LINK_EXPIRED, "invite link has expired", HttpStatusCode.BadRequest)
        }

        // Check max uses
        if (link.maxUses != null && link.useCount >= link.maxUses) {
            throw BusinessException(ChannelErrorCode.INVITE_LINK_FULL, "invite link has reached max uses", HttpStatusCode.BadRequest)
        }

        val channel = channelStore.findByChannelId(link.channelId)
            ?: throw BusinessException(ChannelErrorCode.CHANNEL_NOT_FOUND, "channel not found", HttpStatusCode.NotFound)

        // Check already member
        if (channelStore.isMember(link.channelId, uid)) {
            throw BusinessException(ChannelErrorCode.ALREADY_MEMBER, "already a member", HttpStatusCode.BadRequest)
        }

        // Add member
        channelStore.addMember(link.channelId, channel.channelType, uid, role = 0)
        conversationStore.createOrUpdate(uid, link.channelId, channel.channelType)

        // Increment use count
        inviteLinkStore.incrementUseCount(token)

        // Broadcast MEMBER_ADDED
        broadcastSystemPacket(link.channelId, MemberAddedPayload(link.channelId, channel.channelType, uid, null, link.creatorUid))

        logger.info("User {} joined channel {} via invite link {}", uid, link.channelId, token)
        return Pair(channel, true)
    }

    suspend fun getInviteLinkInfo(token: String): Triple<String, String, Int> {
        val link = inviteLinkStore.findByToken(token)
            ?: throw BusinessException(ChannelErrorCode.INVITE_LINK_NOT_FOUND, "invite link not found", HttpStatusCode.NotFound)

        if (link.revokedAt != null) {
            throw BusinessException(ChannelErrorCode.INVITE_LINK_NOT_FOUND, "invite link has been revoked", HttpStatusCode.BadRequest)
        }

        if (link.expiresAt != null && link.expiresAt <= System.currentTimeMillis()) {
            throw BusinessException(ChannelErrorCode.INVITE_LINK_EXPIRED, "invite link has expired", HttpStatusCode.BadRequest)
        }

        val channel = channelStore.findByChannelId(link.channelId)
            ?: throw BusinessException(ChannelErrorCode.CHANNEL_NOT_FOUND, "channel not found", HttpStatusCode.NotFound)

        val memberCount = channelStore.getMemberUids(link.channelId).size
        return Triple(channel.name, link.channelId, memberCount)
    }

    companion object {
        fun buildPersonalChannelId(uid1: String, uid2: String): String {
            val sorted = listOf(uid1, uid2).sorted()
            return "p:${sorted[0]}:${sorted[1]}"
        }
    }
}
