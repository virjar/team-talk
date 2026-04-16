package com.virjar.tk.db

import com.virjar.tk.protocol.ChannelType
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

data class ChannelRow(
    val id: Long,
    val channelId: String,
    val channelType: ChannelType,
    val name: String,
    val avatar: String,
    val creator: String?,
    val notice: String,
    val status: Int,
    val maxSeq: Long,
    val mutedAll: Boolean = false,
    val updatedAt: Long = 0,
)

data class ChannelMemberRow(
    val id: Long,
    val channelId: String,
    val channelType: ChannelType,
    val uid: String,
    val role: Int,
    val nickname: String,
    val status: Int,
    val joinedAt: Long,
)

object ChannelDao {

    /** 启动时批量加载所有频道（同步事务） */
    fun loadAll(): List<ChannelRow> = transaction {
        Channels.selectAll().map { it.toChannelRow() }
    }

    /** 启动时批量加载所有频道成员（同步事务） */
    fun loadAllMembers(): List<ChannelMemberRow> = transaction {
        ChannelMembers.selectAll().where { ChannelMembers.status eq 1 }.map { it.toChannelMemberRow() }
    }

    fun create(channelId: String, channelType: ChannelType, name: String, avatar: String, creator: String?): ChannelRow = transaction {
        val now = System.currentTimeMillis()
        val id = Channels.insert {
            it[Channels.channelId] = channelId
            it[Channels.channelType] = channelType.code
            it[Channels.name] = name
            it[Channels.avatar] = avatar
            it[Channels.creator] = creator
            it[Channels.createdAt] = now
            it[Channels.updatedAt] = now
        } get Channels.id

        ChannelRow(
            id = id.value,
            channelId = channelId,
            channelType = channelType,
            name = name,
            avatar = avatar,
            creator = creator,
            notice = "",
            status = 1,
            maxSeq = 0,
        )
    }

    fun findByChannelId(channelId: String): ChannelRow? = transaction {
        Channels.selectAll().where { Channels.channelId eq channelId }
            .map { it.toChannelRow() }
            .singleOrNull()
    }

    fun update(channelId: String, name: String?, avatar: String?, notice: String?) = transaction {
        Channels.update({ Channels.channelId eq channelId }) {
            name?.let { v -> it[Channels.name] = v }
            avatar?.let { v -> it[Channels.avatar] = v }
            notice?.let { v -> it[Channels.notice] = v }
            it[Channels.updatedAt] = System.currentTimeMillis()
        }
    }

    fun incrementMaxSeq(channelId: String): Long = transaction {
        val channel = Channels.selectAll().where { Channels.channelId eq channelId }.singleOrNull()
        val newSeq = (channel?.get(Channels.maxSeq) ?: 0L) + 1
        Channels.update({ Channels.channelId eq channelId }) {
            it[maxSeq] = newSeq
            it[updatedAt] = System.currentTimeMillis()
        }
        newSeq
    }

    /** 直接设置 maxSeq（由 ChannelStore 在内存递增后异步调用） */
    fun setMaxSeq(channelId: String, seq: Long) = transaction {
        Channels.update({ Channels.channelId eq channelId }) {
            it[maxSeq] = seq
            it[updatedAt] = System.currentTimeMillis()
        }
    }

    fun addMember(channelId: String, channelType: ChannelType, uid: String, role: Int = 0): ChannelMemberRow = transaction {
        val id = ChannelMembers.insert {
            it[ChannelMembers.channelId] = channelId
            it[ChannelMembers.channelType] = channelType.code
            it[ChannelMembers.uid] = uid
            it[ChannelMembers.role] = role
            it[ChannelMembers.joinedAt] = System.currentTimeMillis()
        } get ChannelMembers.id

        ChannelMemberRow(
            id = id.value,
            channelId = channelId,
            channelType = channelType,
            uid = uid,
            role = role,
            nickname = "",
            status = 1,
            joinedAt = System.currentTimeMillis(),
        )
    }

    fun removeMember(channelId: String, uid: String) = transaction {
        ChannelMembers.deleteWhere {
            with(SqlExpressionBuilder) {
                (ChannelMembers.channelId eq channelId) and (ChannelMembers.uid eq uid)
            }
        }
    }

    fun getMembers(channelId: String, page: Int = 1, pageSize: Int = 50): List<ChannelMemberRow> = transaction {
        ChannelMembers.selectAll().where { ChannelMembers.channelId eq channelId }
            .limit(pageSize).offset(((page - 1) * pageSize).toLong())
            .map { it.toChannelMemberRow() }
    }

    fun getMemberUids(channelId: String): List<String> = transaction {
        ChannelMembers.selectAll().where {
            (ChannelMembers.channelId eq channelId) and (ChannelMembers.status eq 1)
        }.map { it[ChannelMembers.uid] }
    }

    fun isMember(channelId: String, uid: String): Boolean = transaction {
        ChannelMembers.selectAll().where {
            (ChannelMembers.channelId eq channelId) and (ChannelMembers.uid eq uid)
        }.count() > 0
    }

    fun getMemberRole(channelId: String, uid: String): Int? = transaction {
        ChannelMembers.selectAll().where {
            (ChannelMembers.channelId eq channelId) and (ChannelMembers.uid eq uid)
        }.singleOrNull()?.get(ChannelMembers.role)
    }

    fun updateMemberRole(channelId: String, uid: String, role: Int) = transaction {
        ChannelMembers.update({
            with(SqlExpressionBuilder) {
                (ChannelMembers.channelId eq channelId) and (ChannelMembers.uid eq uid)
            }
        }) {
            it[ChannelMembers.role] = role
        }
    }

    fun transferOwner(channelId: String, oldOwnerUid: String, newOwnerUid: String) = transaction {
        ChannelMembers.update({
            with(SqlExpressionBuilder) {
                (ChannelMembers.channelId eq channelId) and (ChannelMembers.uid eq oldOwnerUid)
            }
        }) {
            it[role] = 0
        }
        ChannelMembers.update({
            with(SqlExpressionBuilder) {
                (ChannelMembers.channelId eq channelId) and (ChannelMembers.uid eq newOwnerUid)
            }
        }) {
            it[role] = 2
        }
        Channels.update({ Channels.channelId eq channelId }) {
            it[creator] = newOwnerUid
            it[updatedAt] = System.currentTimeMillis()
        }
    }

    fun findByUser(uid: String, version: Long = 0): List<ChannelRow> = transaction {
        val channelIds = ChannelMembers.selectAll().where {
            (ChannelMembers.uid eq uid) and (ChannelMembers.status eq 1)
        }.map { it[ChannelMembers.channelId] }

        if (channelIds.isEmpty()) return@transaction emptyList()

        Channels.selectAll().where {
            (Channels.channelId inList channelIds) and (Channels.updatedAt greater version)
        }.map { it.toChannelRow() }
    }

    private fun ResultRow.toChannelRow() = ChannelRow(
        id = this[Channels.id].value,
        channelId = this[Channels.channelId],
        channelType = ChannelType.fromCode(this[Channels.channelType]),
        name = this[Channels.name],
        avatar = this[Channels.avatar],
        creator = this[Channels.creator],
        notice = this[Channels.notice],
        status = this[Channels.status],
        maxSeq = this[Channels.maxSeq],
        mutedAll = this[Channels.mutedAll],
        updatedAt = this[Channels.updatedAt],
    )

    private fun ResultRow.toChannelMemberRow() = ChannelMemberRow(
        id = this[ChannelMembers.id].value,
        channelId = this[ChannelMembers.channelId],
        channelType = ChannelType.fromCode(this[ChannelMembers.channelType]),
        uid = this[ChannelMembers.uid],
        role = this[ChannelMembers.role],
        nickname = this[ChannelMembers.nickname],
        status = this[ChannelMembers.status],
        joinedAt = this[ChannelMembers.joinedAt],
    )

    // ================================================================
    // 禁言相关
    // ================================================================

    fun setMutedAll(channelId: String, mutedAll: Boolean) = transaction {
        Channels.update({ Channels.channelId eq channelId }) {
            it[Channels.mutedAll] = mutedAll
            it[updatedAt] = System.currentTimeMillis()
        }
    }

    fun getMutedAll(channelId: String): Boolean = transaction {
        Channels.selectAll().where { Channels.channelId eq channelId }
            .singleOrNull()?.get(Channels.mutedAll) ?: false
    }

    fun muteMember(channelId: String, uid: String, operatorUid: String, expiresAt: Long) = transaction {
        // 先删除旧记录再插入（等效 upsert）
        ChannelMemberMutes.deleteWhere {
            with(SqlExpressionBuilder) {
                (ChannelMemberMutes.channelId eq channelId) and (ChannelMemberMutes.uid eq uid)
            }
        }
        ChannelMemberMutes.insert {
            it[ChannelMemberMutes.channelId] = channelId
            it[ChannelMemberMutes.uid] = uid
            it[ChannelMemberMutes.operatorUid] = operatorUid
            it[ChannelMemberMutes.expiresAt] = expiresAt
            it[ChannelMemberMutes.createdAt] = System.currentTimeMillis()
        }
    }

    fun unmuteMember(channelId: String, uid: String) = transaction {
        ChannelMemberMutes.deleteWhere {
            with(SqlExpressionBuilder) {
                (ChannelMemberMutes.channelId eq channelId) and (ChannelMemberMutes.uid eq uid)
            }
        }
    }

    fun isMemberMuted(channelId: String, uid: String): Boolean = transaction {
        val now = System.currentTimeMillis()
        ChannelMemberMutes.selectAll().where {
            with(SqlExpressionBuilder) {
                (ChannelMemberMutes.channelId eq channelId) and
                (ChannelMemberMutes.uid eq uid) and
                (ChannelMemberMutes.expiresAt eq 0L).or(ChannelMemberMutes.expiresAt greater now)
            }
        }.count() > 0
    }

    /** 启动时批量加载所有有效禁言（同步事务） */
    fun loadAllMutes(): List<Triple<String, String, Long>> = transaction {
        val now = System.currentTimeMillis()
        ChannelMemberMutes.selectAll().where {
            (ChannelMemberMutes.expiresAt eq 0L).or(ChannelMemberMutes.expiresAt greater now)
        }.map { Triple(it[ChannelMemberMutes.channelId], it[ChannelMemberMutes.uid], it[ChannelMemberMutes.expiresAt]) }
    }
}
