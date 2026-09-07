package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.SinceProtocol
import kotlinx.serialization.Serializable

/**
 * 用户在组织中的一个归属（T008）。[pathNames] 是从组织根到直属节点的名称路径，
 * 例如 ["公司", "研发", "移动端"]，用于在资料中消除同名部门歧义。
 */
@SinceProtocol(1)
@Serializable
data class UserOrganizationPath(
    val unitId: String,
    val unitName: String,
    val pathNames: List<String>,
    /** 是否为该用户的主部门归属。 */
    val primary: Boolean = false,
    /** 在该部门的职务；未设置时为 null。 */
    val title: String? = null,
) : IProto {
    init {
        require(pathNames.isNotEmpty()) { "UserOrganizationPath.pathNames must contain the direct unit name" }
    }

    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(unitId)
        buf.writeString(unitName)
        buf.writeVarInt(pathNames.size)
        pathNames.forEach { buf.writeString(it) }
        buf.writeBoolean(primary)
        buf.writeString(title)
    }

    companion object : IProtoReader<UserOrganizationPath> {
        /** 组织深度受组织层级策略约束；这里只防御异常输入。 */
        const val MAX_PATH_DEPTH = 32

        override fun readFrom(buf: PacketBuffer): UserOrganizationPath = UserOrganizationPath(
            unitId = buf.readRequiredString(),
            unitName = buf.readRequiredString(),
            pathNames = List(
                buf.readCollectionSize(
                    maximum = MAX_PATH_DEPTH,
                    minimumBytesPerEntry = 2,
                    fieldName = "user organization path names",
                ),
            ) { buf.readRequiredString() },
            primary = buf.readBoolean("user organization path primary"),
            title = buf.readString(),
        )
    }
}

/** 一个账号的全部有效组织归属；查看者必须具备组织目录访问资格（T001 边界）。 */
@SinceProtocol(1)
@Serializable
data class UserOrganizationSummary(
    val uid: String,
    /** 按主部门优先、路径字典序排列；无任何归属时为空列表。 */
    val paths: List<UserOrganizationPath>,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(uid)
        buf.writeVarInt(paths.size)
        paths.forEach { it.writeTo(buf) }
    }

    companion object : IProtoReader<UserOrganizationSummary> {
        /** 单账号多部门归属同样受组织成员关系容量约束；这里只防御异常输入。 */
        const val MAX_PATHS = 256

        override fun readFrom(buf: PacketBuffer): UserOrganizationSummary = UserOrganizationSummary(
            uid = buf.readRequiredString(),
            paths = List(
                buf.readCollectionSize(
                    maximum = MAX_PATHS,
                    minimumBytesPerEntry = 8,
                    fieldName = "user organization paths",
                ),
            ) { UserOrganizationPath.readFrom(buf) },
        )
    }
}
