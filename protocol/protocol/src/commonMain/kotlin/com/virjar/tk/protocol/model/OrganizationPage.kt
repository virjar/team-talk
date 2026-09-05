package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.ProtocolCorruptionException
import com.virjar.tk.protocol.ProtocolEncodingException
import com.virjar.tk.protocol.payload.MAX_RPC_ENVELOPE_BODY_BYTES
import kotlinx.serialization.Serializable

/**
 * 单组织目录的产品与内存边界。
 *
 * 产品目标是一个组织约一万人。成员关系集合有意更大且仍有界，
 * 因为一个人可能属于多个部门。这些是准入上限，而不是查询上限：
 * 写入方必须在提交前拒绝变更，而不是让读取方静默截断权威数据。
 */
object OrganizationCapacityPolicy {
    const val MAX_ACTIVE_UNITS = 10_000
    /** 根节点深度为 1。一条合法的活跃根到叶路径最多包含这么多节点。 */
    const val MAX_TREE_DEPTH = 64
    /** 活跃与已归档的部门标识在一个数据 epoch 内永久保留。 */
    const val MAX_UNIT_RECORDS = 20_000
    /** 负向的托管群行是持久的对账围栏，不会被静默回收。 */
    const val MAX_MANAGED_CHAT_PROJECTIONS = 20_000
    const val MAX_MEMBERSHIP_RELATIONS = 100_000
    const val MAX_MEMBERS_PER_UNIT = 10_000
    const val MAX_MEMBERSHIPS_PER_USER = 32

    const val UNIT_CAPACITY_REASON = "组织节点数量已达到上限"
    const val TREE_DEPTH_REASON = "组织层级深度已达到上限"
    const val UNIT_RECORD_CAPACITY_REASON = "组织节点历史记录已达到上限"
    const val MANAGED_CHAT_PROJECTION_CAPACITY_REASON = "部门群持久投影记录已达到上限"
    const val MEMBERSHIP_CAPACITY_REASON = "组织成员关系数量已达到上限"
    const val UNIT_MEMBER_CAPACITY_REASON = "部门直属成员数量已达到上限"
    const val USER_MEMBERSHIP_CAPACITY_REASON = "用户所属部门数量已达到上限"
}

/** 完整组织部门快照的一个有界请求。 */
@Serializable
data class OrganizationUnitPageRequest(
    /** 服务端返回的 opaque 排他游标。 */
    val cursor: String? = null,
) : IProto {
    init {
        OrganizationPagePolicy.requireOpaqueCursor(cursor)
    }

    override fun writeTo(buf: PacketBuffer) {
        OrganizationPagePolicy.requireOpaqueCursor(cursor)
        buf.writeString(cursor)
    }

    companion object : IProtoReader<OrganizationUnitPageRequest> {
        override fun readFrom(buf: PacketBuffer): OrganizationUnitPageRequest =
            OrganizationUnitPageRequest(buf.readString(OrganizationPagePolicy.MAX_CURSOR_BYTES))
    }
}

/**
 * 来自 revision 界定的 keyset 遍历的一个定长部门页。
 *
 * [snapshotChanged] 是当 revision N 的游标到达 revision N+1 时的显式重启信号。
 * 这种页面永远不含数据，因此客户端不可能意外发布混合快照。
 */
@Serializable
data class OrganizationUnitPage(
    val revision: Long,
    val items: List<OrganizationUnit>,
    val nextCursor: String?,
    val snapshotChanged: Boolean = false,
) : IProto {
    init {
        validatePageEnvelope(
            kind = "Organization unit",
            revision = revision,
            itemCount = items.size,
            maxItems = MAX_PAGE_SIZE,
            nextCursor = nextCursor,
            snapshotChanged = snapshotChanged,
        )
        if (items.mapTo(hashSetOf(), OrganizationUnit::unitId).size != items.size) {
            throw ProtocolEncodingException("Organization unit page contains duplicate unitId values")
        }
    }

    override fun writeTo(buf: PacketBuffer) {
        val before = buf.readableBytes()
        buf.writeVarLong(revision)
        buf.writeVarInt(items.size)
        items.forEach { it.writeTo(buf) }
        buf.writeString(nextCursor)
        buf.writeBoolean(snapshotChanged)
        requirePageBudget("Organization unit", buf.readableBytes() - before, MAX_ENCODED_BYTES)
    }

    companion object : IProtoReader<OrganizationUnitPage> {
        const val MAX_PAGE_SIZE = 256
        const val MAX_ENCODED_BYTES = 512 * 1024

        init {
            check(MAX_ENCODED_BYTES < MAX_RPC_ENVELOPE_BODY_BYTES)
        }

        override fun readFrom(buf: PacketBuffer): OrganizationUnitPage {
            val before = buf.readableBytes()
            val revision = buf.readVarLong()
            val count = buf.readCollectionSize(
                maximum = MAX_PAGE_SIZE,
                minimumBytesPerEntry = 16,
                fieldName = "organization unit page",
            )
            val page = OrganizationUnitPage(
                revision = revision,
                items = List(count) { OrganizationUnit.readFrom(buf) },
                nextCursor = buf.readString(OrganizationPagePolicy.MAX_CURSOR_BYTES),
                snapshotChanged = buf.readBoolean("organization unit snapshot change"),
            )
            verifyDecodedPageBudget("Organization unit", before - buf.readableBytes(), MAX_ENCODED_BYTES)
            return page
        }
    }
}

/** 直属或递归成员关系的一个有界请求。 */
@Serializable
data class OrganizationMemberPageRequest(
    val unitId: String,
    val recursive: Boolean,
    /** 由服务端绑定到 [unitId]、[recursive] 与单个 revision 的 opaque 游标。 */
    val cursor: String? = null,
) : IProto {
    init {
        OrganizationPagePolicy.requireResourceId(unitId, "Organization member page unitId")
        OrganizationPagePolicy.requireOpaqueCursor(cursor)
    }

    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(unitId)
        buf.writeBoolean(recursive)
        buf.writeString(cursor)
    }

    companion object : IProtoReader<OrganizationMemberPageRequest> {
        override fun readFrom(buf: PacketBuffer): OrganizationMemberPageRequest =
            OrganizationMemberPageRequest(
                unitId = buf.readRequiredString(
                    OrganizationPagePolicy.MAX_ID_CHARACTERS,
                    "organization member page unitId",
                ),
                recursive = buf.readBoolean("organization member page recursive"),
                cursor = buf.readString(OrganizationPagePolicy.MAX_CURSOR_BYTES),
            )
    }
}

/** 按稳定关系键 `(unitId, uid)` 排序的定长成员页。 */
@Serializable
data class OrganizationMemberPage(
    val revision: Long,
    val items: List<OrganizationMember>,
    val nextCursor: String?,
    val snapshotChanged: Boolean = false,
) : IProto {
    init {
        validatePageEnvelope(
            kind = "Organization member",
            revision = revision,
            itemCount = items.size,
            maxItems = MAX_PAGE_SIZE,
            nextCursor = nextCursor,
            snapshotChanged = snapshotChanged,
        )
        if (items.mapTo(hashSetOf()) { it.unitId to it.uid }.size != items.size) {
            throw ProtocolEncodingException("Organization member page contains duplicate relation keys")
        }
    }

    override fun writeTo(buf: PacketBuffer) {
        val before = buf.readableBytes()
        buf.writeVarLong(revision)
        buf.writeVarInt(items.size)
        items.forEach { it.writeTo(buf) }
        buf.writeString(nextCursor)
        buf.writeBoolean(snapshotChanged)
        requirePageBudget("Organization member", buf.readableBytes() - before, MAX_ENCODED_BYTES)
    }

    companion object : IProtoReader<OrganizationMemberPage> {
        const val MAX_PAGE_SIZE = 256
        const val MAX_ENCODED_BYTES = 1024 * 1024

        init {
            check(MAX_ENCODED_BYTES < MAX_RPC_ENVELOPE_BODY_BYTES)
        }

        override fun readFrom(buf: PacketBuffer): OrganizationMemberPage {
            val before = buf.readableBytes()
            val revision = buf.readVarLong()
            val count = buf.readCollectionSize(
                maximum = MAX_PAGE_SIZE,
                minimumBytesPerEntry = 16,
                fieldName = "organization member page",
            )
            val page = OrganizationMemberPage(
                revision = revision,
                items = List(count) { OrganizationMember.readFrom(buf) },
                nextCursor = buf.readString(OrganizationPagePolicy.MAX_CURSOR_BYTES),
                snapshotChanged = buf.readBoolean("organization member snapshot change"),
            )
            verifyDecodedPageBudget("Organization member", before - buf.readableBytes(), MAX_ENCODED_BYTES)
            return page
        }
    }
}

/** 仅共享线格式边界；游标内容与键排序仍属服务端私有。 */
object OrganizationPagePolicy {
    const val MAX_CURSOR_BYTES = 256
    const val MAX_ID_CHARACTERS = 36

    fun requireOpaqueCursor(cursor: String?) {
        if (cursor == null) return
        require(cursor.isNotEmpty() && cursor.length <= MAX_CURSOR_BYTES) {
            "Organization cursor has an invalid length"
        }
        require(cursor.all(::isBase64UrlCharacter)) {
            "Organization cursor is not canonical base64url"
        }
    }

    fun requireResourceId(value: String, label: String) {
        require(value.isNotEmpty() && value.length <= MAX_ID_CHARACTERS) { "$label is invalid" }
        require(value.all { it.code in 0x21..0x7e }) { "$label is not canonical ASCII" }
    }

    private fun isBase64UrlCharacter(character: Char): Boolean =
        character in 'A'..'Z' ||
            character in 'a'..'z' ||
            character in '0'..'9' ||
            character == '-' ||
            character == '_'
}

private fun validatePageEnvelope(
    kind: String,
    revision: Long,
    itemCount: Int,
    maxItems: Int,
    nextCursor: String?,
    snapshotChanged: Boolean,
) {
    if (revision < 0L) throw ProtocolEncodingException("$kind page revision must not be negative")
    if (itemCount > maxItems) {
        throw ProtocolEncodingException("$kind page cannot contain more than $maxItems items")
    }
    try {
        OrganizationPagePolicy.requireOpaqueCursor(nextCursor)
    } catch (invalidCursor: IllegalArgumentException) {
        throw ProtocolEncodingException(invalidCursor.message ?: "$kind cursor is invalid")
    }
    if (snapshotChanged && (itemCount != 0 || nextCursor != null)) {
        throw ProtocolEncodingException("A changed $kind snapshot page must be empty and terminal")
    }
    if (!snapshotChanged && nextCursor != null && itemCount != maxItems) {
        throw ProtocolEncodingException("A non-terminal $kind page must contain exactly $maxItems items")
    }
}

private fun requirePageBudget(kind: String, encodedBytes: Int, maximum: Int) {
    if (encodedBytes > maximum) {
        throw ProtocolEncodingException("$kind page size $encodedBytes exceeds inner budget $maximum")
    }
}

private fun verifyDecodedPageBudget(kind: String, encodedBytes: Int, maximum: Int) {
    if (encodedBytes > maximum) {
        throw ProtocolCorruptionException("$kind page size $encodedBytes exceeds inner budget $maximum")
    }
}
