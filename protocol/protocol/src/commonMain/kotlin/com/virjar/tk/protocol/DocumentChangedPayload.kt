package com.virjar.tk.protocol

/**
 * 文档当前工作集的持久失效提示。事件和业务变更在同一事务提交，按用户 eventId 顺序重放。
 * 不携带正文、标题、评论或权限快照；读取始终通过当前授权 RPC。
 * [revision] 是节点修订提示，空间与评论事件为零；同 revision 的评论通知仍须处理。
 */
@SinceProtocol(2)
data class DocumentChangedPayload(
    val spaceId: String,
    val nodeId: String?,
    val kind: Int,
    val revision: Long,
    val policyRevision: Long,
) : IProto {
    init {
        require(spaceId.isNotBlank() && spaceId.length <= MAX_ID_LENGTH) { "文档空间标识非法" }
        require(policyRevision > 0L) { "文档权限版本非法" }
        when (kind) {
            NODE_UPSERT, NODE_DELETED -> {
                require(!nodeId.isNullOrBlank() && nodeId.length <= MAX_ID_LENGTH) { "文档节点标识非法" }
                require(revision > 0L) { "文档节点版本非法" }
            }
            COMMENTS_CHANGED -> {
                require(!nodeId.isNullOrBlank() && nodeId.length <= MAX_ID_LENGTH) { "评论文档标识非法" }
                require(revision == 0L) { "评论失效事件不携带节点修订" }
            }
            SPACE_CHANGED, SPACE_REVOKED -> {
                require(nodeId == null && revision == 0L) { "空间事件不能携带文档节点" }
            }
            else -> throw IllegalArgumentException("文档变更类型非法")
        }
    }

    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(spaceId)
        buf.writeString(nodeId)
        buf.writeVarInt(kind)
        buf.writeVarLong(revision)
        buf.writeVarLong(policyRevision)
    }

    companion object : IProtoReader<DocumentChangedPayload> {
        const val NODE_UPSERT = 1
        const val NODE_DELETED = 2
        const val SPACE_CHANGED = 3
        const val SPACE_REVOKED = 4
        const val COMMENTS_CHANGED = 5
        private const val MAX_ID_LENGTH = 36

        override fun readFrom(buf: PacketBuffer): DocumentChangedPayload = try {
            DocumentChangedPayload(
                spaceId = buf.readRequiredString(MAX_ID_LENGTH * 4),
                nodeId = buf.readString(MAX_ID_LENGTH * 4),
                kind = buf.readVarInt(),
                revision = buf.readVarLong(),
                policyRevision = buf.readVarLong(),
            )
        } catch (invalid: IllegalArgumentException) {
            throw ProtocolCorruptionException(invalid.message ?: "Invalid document change")
        }
    }
}
