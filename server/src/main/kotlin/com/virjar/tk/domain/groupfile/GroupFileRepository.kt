package com.virjar.tk.domain.groupfile

import com.virjar.tk.model.GroupFileEntry
import com.virjar.tk.model.GroupFileVersion

/** 群共享文件持久化端口。修改操作必须原子写入审计记录，并在事务内守住目录树与配额约束。 */
interface GroupFileRepository {
    fun list(chatId: String, parentId: String?): List<GroupFileEntry>
    fun find(entryId: String): GroupFileEntry?
    fun create(entry: GroupFileEntry, initialVersion: GroupFileVersion?, quotaBytes: Long): GroupFileEntry
    fun appendVersion(
        entryId: String,
        expectedRevision: Long,
        version: GroupFileVersion,
        actorUid: String,
        quotaBytes: Long,
    ): GroupFileEntry
    fun rename(entryId: String, expectedRevision: Long, name: String, actorUid: String): GroupFileEntry
    fun delete(entryId: String, expectedRevision: Long, actorUid: String)
    fun hasActiveChildren(entryId: String): Boolean
    fun listVersions(entryId: String): List<GroupFileVersion>
    fun totalVersionBytes(chatId: String): Long
    fun getAttachmentChatIds(path: String): Set<String>
}
