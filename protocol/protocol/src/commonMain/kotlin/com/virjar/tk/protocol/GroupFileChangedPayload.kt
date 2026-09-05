package com.virjar.tk.protocol

import com.virjar.tk.protocol.body.MessageBodyPolicy
import com.virjar.tk.protocol.model.GroupFileEntry

/**
 * GROUP_FILE_CHANGED 通知 payload（CONTENT-01）：群共享文件的一条权威变更 delta。
 *
 * UPSERT 携带变更后的完整 [GroupFileEntry] 快照（entry.revision 是单调乐观锁），DELETE 携带
 * 被删条目 id 与删除时的最终 revision。客户端按 (chatId, entryId) 行级投影：UPSERT 仅在
 * revision 不低于已存值时应用，DELETE 仅在已存 revision 不高于墓碑 revision 时应用——
 * 重复、乱序与离线重放都收敛到同一状态。事件在变更的同一 PostgreSQL 事务内追加，成员流内
 * 天然有序；目录页快照仍由 list RPC 提供，事件不承担全空间预取。
 */
data class GroupFileChangedPayload(
    val chatId: String,
    val operation: Int,
    val entry: GroupFileEntry?,
    val deletedEntryId: String,
    val deletedRevision: Long,
) : IProto {
    init {
        require(chatId.isNotBlank() && chatId.length <= MessageBodyPolicy.MAX_CHAT_ID_LENGTH) {
            "群文件变更 chatId 非法"
        }
        when (operation) {
            OPERATION_UPSERT -> require(entry != null) { "群文件 UPSERT 必须携带条目快照" }
            OPERATION_DELETE -> {
                require(deletedEntryId.isNotBlank()) { "群文件 DELETE 缺少条目 id" }
                require(deletedRevision > 0L) { "群文件 DELETE 缺少墓碑 revision" }
            }
            else -> throw IllegalArgumentException("群文件变更 operation 非法: $operation")
        }
    }

    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(chatId)
        buf.writeVarInt(operation)
        buf.writeBoolean(entry != null)
        entry?.writeTo(buf)
        if (operation == OPERATION_DELETE) {
            buf.writeString(deletedEntryId)
            buf.writeVarLong(deletedRevision)
        }
    }

    companion object : IProtoReader<GroupFileChangedPayload> {
        const val OPERATION_UPSERT = 1
        const val OPERATION_DELETE = 2

        override fun readFrom(buf: PacketBuffer): GroupFileChangedPayload = try {
            val chatId = buf.readRequiredString(
                MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_CHAT_ID_LENGTH),
            )
            val operation = buf.readVarInt()
            val hasEntry = buf.readBoolean("group file change entry presence")
            val entry = if (hasEntry) GroupFileEntry.readFrom(buf) else null
            val deletedEntryId = if (operation == OPERATION_DELETE) {
                buf.readRequiredString(
                    MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_IDENTIFIER_LENGTH),
                )
            } else {
                ""
            }
            val deletedRevision = if (operation == OPERATION_DELETE) buf.readVarLong() else 0L
            GroupFileChangedPayload(chatId, operation, entry, deletedEntryId, deletedRevision)
        } catch (invalid: IllegalArgumentException) {
            throw ProtocolCorruptionException(invalid.message ?: "Invalid group file change")
        }
    }
}
