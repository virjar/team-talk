package com.virjar.tk.infra.db.repository

import com.virjar.tk.domain.groupfile.GroupFileRepository
import com.virjar.tk.domain.groupfile.GroupFileService
import com.virjar.tk.infra.db.GroupFileAudits
import com.virjar.tk.infra.db.GroupFileEntries
import com.virjar.tk.infra.db.GroupFileVersions
import com.virjar.tk.infra.db.Chats
import com.virjar.tk.model.Attachment
import com.virjar.tk.model.GroupFileEntry
import com.virjar.tk.model.GroupFileVersion
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

class ExposedGroupFileRepository : GroupFileRepository {
    override fun list(chatId: String, parentId: String?): List<GroupFileEntry> = transaction {
        GroupFileEntries.selectAll().where {
            (GroupFileEntries.chatId eq chatId) and
                (GroupFileEntries.parentKey eq parentId.orEmpty()) and
                (GroupFileEntries.status eq STATUS_ACTIVE)
        }.orderBy(
            GroupFileEntries.kind to SortOrder.ASC,
            GroupFileEntries.nameKey to SortOrder.ASC,
        ).map(ResultRow::toGroupFileEntry)
    }

    override fun find(entryId: String): GroupFileEntry? = transaction {
        GroupFileEntries.selectAll().where {
            (GroupFileEntries.entryId eq entryId) and (GroupFileEntries.status eq STATUS_ACTIVE)
        }.singleOrNull()?.toGroupFileEntry()
    }

    override fun create(entry: GroupFileEntry, initialVersion: GroupFileVersion?, quotaBytes: Long): GroupFileEntry = transaction {
        lockChat(entry.chatId)
        requireActiveParent(entry.chatId, entry.parentId)
        if (initialVersion != null) {
            requireQuota(entry.chatId, initialVersion.attachment.size, quotaBytes)
        }
        requireAvailableName(entry.chatId, entry.parentId, entry.name)
        GroupFileEntries.insert {
            it[entryId] = entry.entryId
            it[chatId] = entry.chatId
            it[parentId] = entry.parentId
            it[parentKey] = entry.parentId.orEmpty()
            it[kind] = entry.kind
            it[name] = entry.name
            it[nameKey] = GroupFileService.nameKey(entry.name)
            it[attachmentPath] = entry.attachment?.path
            it[attachmentName] = entry.attachment?.name
            it[attachmentContentType] = entry.attachment?.contentType
            it[attachmentSize] = entry.attachment?.size
            it[revision] = entry.revision
            it[contentVersion] = entry.contentVersion
            it[status] = STATUS_ACTIVE
            it[createdBy] = entry.createdBy
            it[createdAt] = entry.createdAt
            it[updatedBy] = entry.updatedBy
            it[updatedAt] = entry.updatedAt
        }
        initialVersion?.let(::insertVersion)
        audit(entry.chatId, entry.entryId, entry.createdBy, if (initialVersion == null) "CREATE_FOLDER" else "CREATE_FILE", entry.name)
        entry
    }

    override fun appendVersion(
        entryId: String,
        expectedRevision: Long,
        version: GroupFileVersion,
        actorUid: String,
        quotaBytes: Long,
    ): GroupFileEntry = transaction {
        val snapshot = requireActiveEntry(entryId)
        lockChat(snapshot.chatId)
        val current = requireActiveEntry(entryId)
        requireQuota(current.chatId, version.attachment.size, quotaBytes)
        require(current.revision == expectedRevision) { "文件已被其他成员修改，请刷新后重试" }
        require(current.kind == GroupFileEntry.KIND_FILE) { "目录不能添加文件版本" }
        require(version.version == current.contentVersion + 1) { "文件版本号不连续" }
        val now = version.createdAt
        val updated = GroupFileEntries.update({
            (GroupFileEntries.entryId eq entryId) and
                (GroupFileEntries.status eq STATUS_ACTIVE) and
                (GroupFileEntries.revision eq expectedRevision)
        }) {
            it[attachmentPath] = version.attachment.path
            it[attachmentName] = version.attachment.name
            it[attachmentContentType] = version.attachment.contentType
            it[attachmentSize] = version.attachment.size
            it[revision] = expectedRevision + 1
            it[contentVersion] = version.version
            it[updatedBy] = actorUid
            it[updatedAt] = now
        }
        require(updated == 1) { "文件已被其他成员修改，请刷新后重试" }
        insertVersion(version)
        audit(current.chatId, entryId, actorUid, "ADD_VERSION", "v${version.version}")
        requireActiveEntry(entryId)
    }

    override fun rename(entryId: String, expectedRevision: Long, name: String, actorUid: String): GroupFileEntry = transaction {
        val snapshot = requireActiveEntry(entryId)
        lockChat(snapshot.chatId)
        val current = requireActiveEntry(entryId)
        require(current.revision == expectedRevision) { "文件已被其他成员修改，请刷新后重试" }
        requireAvailableName(current.chatId, current.parentId, name, excludingEntryId = entryId)
        val updated = GroupFileEntries.update({
            (GroupFileEntries.entryId eq entryId) and
                (GroupFileEntries.status eq STATUS_ACTIVE) and
                (GroupFileEntries.revision eq expectedRevision)
        }) {
            it[GroupFileEntries.name] = name
            it[nameKey] = GroupFileService.nameKey(name)
            it[revision] = expectedRevision + 1
            it[updatedBy] = actorUid
            it[updatedAt] = System.currentTimeMillis()
        }
        require(updated == 1) { "文件已被其他成员修改，请刷新后重试" }
        audit(current.chatId, entryId, actorUid, "RENAME", "${current.name} -> $name")
        requireActiveEntry(entryId)
    }

    override fun delete(entryId: String, expectedRevision: Long, actorUid: String) {
        transaction {
            val snapshot = requireActiveEntry(entryId)
            lockChat(snapshot.chatId)
            val current = requireActiveEntry(entryId)
            require(current.revision == expectedRevision) { "文件已被其他成员修改，请刷新后重试" }
            require(current.kind != GroupFileEntry.KIND_FOLDER || !hasActiveChildrenInternal(entryId)) {
                "目录非空，请先删除其中的内容"
            }
            val updated = GroupFileEntries.update({
                (GroupFileEntries.entryId eq entryId) and
                    (GroupFileEntries.status eq STATUS_ACTIVE) and
                    (GroupFileEntries.revision eq expectedRevision)
            }) {
                it[status] = STATUS_DELETED
                it[nameKey] = "${GroupFileService.nameKey(current.name)}#deleted#$entryId"
                it[revision] = expectedRevision + 1
                it[updatedBy] = actorUid
                it[updatedAt] = System.currentTimeMillis()
            }
            require(updated == 1) { "文件已被其他成员修改，请刷新后重试" }
            audit(current.chatId, entryId, actorUid, "DELETE", current.name)
        }
    }

    override fun hasActiveChildren(entryId: String): Boolean = transaction {
        hasActiveChildrenInternal(entryId)
    }

    private fun hasActiveChildrenInternal(entryId: String): Boolean =
        GroupFileEntries.selectAll().where {
            (GroupFileEntries.parentId eq entryId) and (GroupFileEntries.status eq STATUS_ACTIVE)
        }.limit(1).any()

    override fun listVersions(entryId: String): List<GroupFileVersion> = transaction {
        GroupFileVersions.selectAll().where { GroupFileVersions.entryId eq entryId }
            .orderBy(GroupFileVersions.version to SortOrder.DESC)
            .map(ResultRow::toGroupFileVersion)
    }

    override fun totalVersionBytes(chatId: String): Long = transaction { totalVersionBytesInternal(chatId) }

    private fun totalVersionBytesInternal(chatId: String): Long {
        val sizeSum = GroupFileVersions.attachmentSize.sum()
        return versionEntryJoin()
            .select(sizeSum)
            .where {
                (GroupFileEntries.chatId eq chatId) and
                    (GroupFileEntries.status eq STATUS_ACTIVE)
            }
            .singleOrNull()
            ?.get(sizeSum) ?: 0L
    }

    override fun getAttachmentChatIds(path: String): Set<String> = transaction {
        versionEntryJoin().select(GroupFileEntries.chatId).where {
            (GroupFileVersions.attachmentPath eq path) and
                (GroupFileEntries.status eq STATUS_ACTIVE)
        }.mapTo(linkedSetOf()) { it[GroupFileEntries.chatId] }
    }

    private fun requireAvailableName(chatId: String, parentId: String?, name: String, excludingEntryId: String? = null) {
        val found = GroupFileEntries.selectAll().where {
            (GroupFileEntries.chatId eq chatId) and
                (GroupFileEntries.parentKey eq parentId.orEmpty()) and
                (GroupFileEntries.nameKey eq GroupFileService.nameKey(name)) and
                (GroupFileEntries.status eq STATUS_ACTIVE)
        }.singleOrNull()
        require(found == null || found[GroupFileEntries.entryId] == excludingEntryId) { "同一目录下已存在同名条目" }
    }

    private fun requireActiveParent(chatId: String, parentId: String?) {
        if (parentId == null) return
        val parent = requireActiveEntry(parentId)
        require(parent.chatId == chatId && parent.kind == GroupFileEntry.KIND_FOLDER) { "父级目录不存在" }
    }

    private fun versionEntryJoin() = GroupFileVersions.join(
        otherTable = GroupFileEntries,
        joinType = JoinType.INNER,
        onColumn = GroupFileVersions.entryId,
        otherColumn = GroupFileEntries.entryId,
    )

    /** 复用 chat 行作为每个群文件空间的互斥锁，统一保护目录树、名称、版本和配额。 */
    private fun lockChat(chatId: String) {
        require(Chats.selectAll().where { Chats.chatId eq chatId }.forUpdate().singleOrNull() != null) { "群聊不存在" }
    }

    private fun requireQuota(chatId: String, incomingBytes: Long, quotaBytes: Long) {
        val used = totalVersionBytesInternal(chatId)
        require(used <= quotaBytes && incomingBytes <= quotaBytes - used) {
            "群文件空间已超出配额（${quotaBytes / 1024 / 1024} MiB）"
        }
    }

    private fun requireActiveEntry(entryId: String): GroupFileEntry =
        GroupFileEntries.selectAll().where {
            (GroupFileEntries.entryId eq entryId) and (GroupFileEntries.status eq STATUS_ACTIVE)
        }.singleOrNull()?.toGroupFileEntry() ?: throw IllegalArgumentException("文件条目不存在")

    private fun insertVersion(version: GroupFileVersion) {
        GroupFileVersions.insert {
            it[entryId] = version.entryId
            it[GroupFileVersions.version] = version.version
            it[attachmentPath] = version.attachment.path
            it[attachmentName] = version.attachment.name
            it[attachmentContentType] = version.attachment.contentType
            it[attachmentSize] = version.attachment.size
            it[createdBy] = version.createdBy
            it[createdAt] = version.createdAt
        }
    }

    private fun audit(chatId: String, entryId: String?, actorUid: String, action: String, detail: String?) {
        GroupFileAudits.insert {
            it[GroupFileAudits.chatId] = chatId
            it[GroupFileAudits.entryId] = entryId
            it[GroupFileAudits.actorUid] = actorUid
            it[GroupFileAudits.action] = action
            it[GroupFileAudits.detail] = detail?.take(500)
            it[createdAt] = System.currentTimeMillis()
        }
    }

    companion object {
        private const val STATUS_DELETED = 0
        private const val STATUS_ACTIVE = 1
    }
}

private fun ResultRow.toGroupFileEntry(): GroupFileEntry {
    val path = this[GroupFileEntries.attachmentPath]
    val attachment = path?.let {
        Attachment(
            path = it,
            name = this[GroupFileEntries.attachmentName]!!,
            contentType = this[GroupFileEntries.attachmentContentType]!!,
            size = this[GroupFileEntries.attachmentSize]!!,
        )
    }
    return GroupFileEntry(
        entryId = this[GroupFileEntries.entryId],
        chatId = this[GroupFileEntries.chatId],
        parentId = this[GroupFileEntries.parentId],
        kind = this[GroupFileEntries.kind],
        name = this[GroupFileEntries.name],
        attachment = attachment,
        revision = this[GroupFileEntries.revision],
        contentVersion = this[GroupFileEntries.contentVersion],
        createdBy = this[GroupFileEntries.createdBy],
        createdAt = this[GroupFileEntries.createdAt],
        updatedBy = this[GroupFileEntries.updatedBy],
        updatedAt = this[GroupFileEntries.updatedAt],
    )
}

private fun ResultRow.toGroupFileVersion() = GroupFileVersion(
    entryId = this[GroupFileVersions.entryId],
    version = this[GroupFileVersions.version],
    attachment = Attachment(
        path = this[GroupFileVersions.attachmentPath],
        name = this[GroupFileVersions.attachmentName],
        contentType = this[GroupFileVersions.attachmentContentType],
        size = this[GroupFileVersions.attachmentSize],
    ),
    createdBy = this[GroupFileVersions.createdBy],
    createdAt = this[GroupFileVersions.createdAt],
)
