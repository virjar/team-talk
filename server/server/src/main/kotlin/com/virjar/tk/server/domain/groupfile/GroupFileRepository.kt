package com.virjar.tk.server.domain.groupfile

import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.GroupFileEntry
import com.virjar.tk.protocol.model.GroupFileVersion
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext

/**
 * 创建/追加版本的确认同时携带当前条目和本次事务是否发生了变更。
 *
 * 精确重放仍返回当前条目供 RPC 响应使用，但不能再次产生变更事件；条目本身的 revision
 * 无法区分“刚刚提交”和“此前已提交”，因此该事实由持有命令回执的仓储一并返回。
 */
data class GroupFileEntryWriteResult(
    val entry: GroupFileEntry,
    val changed: Boolean,
)

data class GroupFileCreateCommand(
    val entry: GroupFileEntry,
    val initialVersion: GroupFileVersion?,
    val commandId: String,
    val fingerprint: String,
)

/** 版本号由已锁定聚合分配，绝不由事务前的读取分配。 */
data class GroupFileAppendVersionCommand(
    val entryId: String,
    val expectedRevision: Long,
    val attachment: Attachment,
    val actorUid: String,
    val commandId: String,
    val fingerprint: String,
    val createdAt: Long,
)

data class GroupFileRenameCommand(
    val commandId: String,
    val chatId: String,
    val entryId: String,
    val name: String,
    val expectedRevision: Long,
    val actorUid: String,
    val fingerprint: String,
    val updatedAt: Long,
)

data class GroupFileDeleteCommand(
    val commandId: String,
    val chatId: String,
    val entryId: String,
    val expectedRevision: Long,
    val actorUid: String,
    val fingerprint: String,
    val deletedAt: Long,
)

/**
 * 群共享文件持久化端口。
 *
 * 修改操作必须原子写入审计记录，并在 Chat 聚合锁保护的同一事务内守住目录树、活动条目、
 * 同级条目、活动版本和字节配额约束。精确资源重试不得重复占用容量槽。
 */
interface GroupFileRepository {
    fun list(chatId: String, parentId: String?): List<GroupFileEntry>
    fun find(entryId: String): GroupFileEntry?
    fun create(
        transaction: PgWriteTransactionContext,
        command: GroupFileCreateCommand,
    ): GroupFileEntryWriteResult

    fun appendVersion(
        transaction: PgWriteTransactionContext,
        command: GroupFileAppendVersionCommand,
    ): GroupFileEntryWriteResult

    /**
     * @return 变更后的条目快照；精确回执命中（无状态变化的重放）返回 null，调用方不广播事件。
     */
    fun rename(
        transaction: PgWriteTransactionContext,
        command: GroupFileRenameCommand,
    ): GroupFileEntry?

    /**
     * @return 墓穴 revision（删除后的最终 revision）；精确回执命中返回 null，调用方不广播事件。
     */
    fun delete(
        transaction: PgWriteTransactionContext,
        command: GroupFileDeleteCommand,
    ): Long?
    fun hasActiveChildren(entryId: String): Boolean
    fun listVersions(entryId: String): List<GroupFileVersion>
    /** 所属条目在 [chatId] 中仍活跃的不可变版本字节之和。 */
    fun totalVersionBytes(chatId: String): Long
    fun getAttachmentChatIds(path: String): Set<String>

    fun isAttachmentReferencedByAny(path: String, chatIds: Set<String>): Boolean =
        chatIds.isNotEmpty() && getAttachmentChatIds(path).any(chatIds::contains)

    fun getReferencedAttachmentPaths(paths: Set<String>): Set<String> =
        paths.filterTo(linkedSetOf()) { path -> getAttachmentChatIds(path).isNotEmpty() }
}
