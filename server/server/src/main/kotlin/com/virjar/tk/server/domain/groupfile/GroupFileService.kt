package com.virjar.tk.server.domain.groupfile

import com.virjar.tk.protocol.body.AttachmentPolicy
import com.virjar.tk.protocol.GroupFileChangedPayload
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.server.domain.attachment.AttachmentCatalog
import com.virjar.tk.server.domain.attachment.AttachmentLifecycleGate
import com.virjar.tk.server.domain.chat.ChatAccess
import com.virjar.tk.server.domain.chat.ChatStore
import com.virjar.tk.server.domain.command.reliableCommandFingerprint
import com.virjar.tk.server.domain.transaction.PgUnitOfWork
import com.virjar.tk.server.domain.transaction.PgWriteScope
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.GroupFileEntry
import com.virjar.tk.protocol.model.GroupFileVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

/**
 * 群共享文件领域服务。
 *
 * 群成员资格在读取与新写入时实时判断。创建/追加版本的重放返回当前可读条目；重命名/删除
 * 只确认既有回执，不要求原条目仍然可读。五类命令都只在首次变更时追加事件。
 * 上传者只能把自己刚上传且元数据完全匹配的附件发布到文件空间。历史版本不可变且计入配额，
 * 避免“替换文件”成为绕过容量限制的入口。
 */
class GroupFileService(
    private val repository: GroupFileRepository,
    private val access: ChatAccess,
    private val attachments: AttachmentCatalog,
    private val unitOfWork: PgUnitOfWork,
    private val chatStore: ChatStore,
    private val attachmentLifecycle: AttachmentLifecycleGate = AttachmentLifecycleGate(),
) {
    suspend fun list(actorUid: String, chatId: String, parentId: String?): List<GroupFileEntry> = onIo {
        access.readAsGroupMember(actorUid, chatId, "你不是当前群成员") { _, _ ->
            requireParent(chatId, parentId)
            repository.list(chatId, parentId)
        }
    }

    suspend fun createFolder(
        actorUid: String,
        entryId: String,
        commandId: String,
        chatId: String,
        parentId: String?,
        name: String,
    ): GroupFileEntry = onIo {
        requireMember(actorUid, chatId)
        requireParent(chatId, parentId)
        val canonicalEntryId = validateResourceId(entryId, "群文件条目标识")
        val canonicalCommandId = validateResourceId(commandId, "群文件创建命令标识")
        val normalizedName = validateName(name)
        val now = System.currentTimeMillis()
        val entry = GroupFileEntry(
            entryId = canonicalEntryId,
            chatId = chatId,
            parentId = parentId,
            kind = GroupFileEntry.KIND_FOLDER,
            name = normalizedName,
            createdBy = actorUid,
            createdAt = now,
            updatedBy = actorUid,
            updatedAt = now,
        )
        unitOfWork.write {
            val created = repository.create(
                transaction,
                GroupFileCreateCommand(
                    entry = entry,
                    initialVersion = null,
                    commandId = canonicalCommandId,
                    fingerprint = reliableCommandFingerprint(
                        "CREATE_FOLDER",
                        actorUid,
                        canonicalEntryId,
                        canonicalCommandId,
                        chatId,
                        parentId,
                        normalizedName,
                    ),
                ),
            )
            if (created.changed) broadcastUpsert(created.entry)
            created.entry
        }
    }

    suspend fun createFile(
        actorUid: String,
        entryId: String,
        commandId: String,
        chatId: String,
        parentId: String?,
        name: String,
        declared: Attachment,
    ): GroupFileEntry = onIo {
        requireMember(actorUid, chatId)
        requireParent(chatId, parentId)
        val canonicalEntryId = validateResourceId(entryId, "群文件条目标识")
        val canonicalCommandId = validateResourceId(commandId, "群文件创建命令标识")
        val normalizedName = validateName(name)
        val canonicalAttachmentPath = AttachmentPolicy.canonicalPath(declared.path)
        attachmentLifecycle.withReferenceMutation(listOf(canonicalAttachmentPath)) {
            val attachment = resolveOwnedAttachment(actorUid, declared)
            attachments.markBusinessBound(listOf(attachment.path))
            val now = System.currentTimeMillis()
            val entry = GroupFileEntry(
                entryId = canonicalEntryId,
                chatId = chatId,
                parentId = parentId,
                kind = GroupFileEntry.KIND_FILE,
                name = normalizedName,
                attachment = attachment,
                contentVersion = 1,
                createdBy = actorUid,
                createdAt = now,
                updatedBy = actorUid,
                updatedAt = now,
            )
            unitOfWork.write {
                val created = repository.create(
                    transaction,
                    GroupFileCreateCommand(
                        entry = entry,
                        initialVersion = GroupFileVersion(canonicalEntryId, 1, attachment, actorUid, now),
                        commandId = canonicalCommandId,
                        fingerprint = reliableCommandFingerprint(
                            "CREATE_FILE",
                            actorUid,
                            canonicalEntryId,
                            canonicalCommandId,
                            chatId,
                            parentId,
                            normalizedName,
                            attachment.path,
                            attachment.name,
                            attachment.contentType,
                            attachment.size.toString(),
                        ),
                    ),
                )
                if (created.changed) broadcastUpsert(created.entry)
                created.entry
            }
        }
    }

    suspend fun addVersion(
        actorUid: String,
        commandId: String,
        chatId: String,
        entryId: String,
        declared: Attachment,
        expectedRevision: Long,
    ): GroupFileEntry = onIo {
        requireMember(actorUid, chatId)
        val entry = requireEntry(chatId, entryId)
        require(entry.kind == GroupFileEntry.KIND_FILE) { "目录不能添加文件版本" }
        val canonicalCommandId = validateResourceId(commandId, "群文件版本命令标识")
        val canonicalAttachmentPath = AttachmentPolicy.canonicalPath(declared.path)
        attachmentLifecycle.withReferenceMutation(listOf(canonicalAttachmentPath)) {
            val attachment = resolveOwnedAttachment(actorUid, declared)
            attachments.markBusinessBound(listOf(attachment.path))
            val now = System.currentTimeMillis()
            unitOfWork.write {
                val updated = repository.appendVersion(
                    transaction,
                    GroupFileAppendVersionCommand(
                        entryId = entry.entryId,
                        expectedRevision = expectedRevision,
                        attachment = attachment,
                        actorUid = actorUid,
                        commandId = canonicalCommandId,
                        fingerprint = reliableCommandFingerprint(
                            "ADD_VERSION",
                            actorUid,
                            canonicalCommandId,
                            chatId,
                            entry.entryId,
                            expectedRevision.toString(),
                            attachment.path,
                            attachment.name,
                            attachment.contentType,
                            attachment.size.toString(),
                        ),
                        createdAt = now,
                    ),
                )
                if (updated.changed) broadcastUpsert(updated.entry)
                updated.entry
            }
        }
    }

    /**
     * 按当前群成员身份读取单个条目（类型化引用的打开校验与发送重建共用）。
     * 不是群成员、条目不存在或不属于该群时给出明确失败。
     */
    suspend fun getEntry(actorUid: String, chatId: String, entryId: String): GroupFileEntry = onIo {
        access.readAsGroupMember(actorUid, chatId, "你不是当前群成员") { _, _ ->
            requireEntry(chatId, entryId)
        }
    }

    suspend fun listVersions(actorUid: String, chatId: String, entryId: String): List<GroupFileVersion> = onIo {
        access.readAsGroupMember(actorUid, chatId, "你不是当前群成员") { _, _ ->
            val entry = requireEntry(chatId, entryId)
            require(entry.kind == GroupFileEntry.KIND_FILE) { "目录没有文件版本" }
            repository.listVersions(entryId)
        }
    }

    suspend fun rename(
        actorUid: String,
        commandId: String,
        chatId: String,
        entryId: String,
        name: String,
        expectedRevision: Long,
    ): GroupFileEntry? = onIo {
        val canonicalCommandId = validateResourceId(commandId, "群文件重命名命令标识")
        val canonicalEntryId = validateResourceId(entryId, "群文件条目标识")
        val normalizedName = validateName(name)
        unitOfWork.write {
            val renamed = repository.rename(
                transaction,
                GroupFileRenameCommand(
                    commandId = canonicalCommandId,
                    chatId = chatId,
                    entryId = canonicalEntryId,
                    name = normalizedName,
                    expectedRevision = expectedRevision,
                    actorUid = actorUid,
                    fingerprint = reliableCommandFingerprint(
                        "RENAME",
                        actorUid,
                        canonicalCommandId,
                        chatId,
                        canonicalEntryId,
                        normalizedName,
                        expectedRevision.toString(),
                    ),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            renamed?.let { broadcastUpsert(it) }
            renamed
        }
    }

    suspend fun delete(
        actorUid: String,
        commandId: String,
        chatId: String,
        entryId: String,
        expectedRevision: Long,
    ) = onIo {
        val canonicalCommandId = validateResourceId(commandId, "群文件删除命令标识")
        val canonicalEntryId = validateResourceId(entryId, "群文件条目标识")
        unitOfWork.write {
            val tombstoneRevision = repository.delete(
                transaction,
                GroupFileDeleteCommand(
                    commandId = canonicalCommandId,
                    chatId = chatId,
                    entryId = canonicalEntryId,
                    expectedRevision = expectedRevision,
                    actorUid = actorUid,
                    fingerprint = reliableCommandFingerprint(
                        "DELETE",
                        actorUid,
                        canonicalCommandId,
                        chatId,
                        canonicalEntryId,
                        expectedRevision.toString(),
                    ),
                    deletedAt = System.currentTimeMillis(),
                ),
            )
            tombstoneRevision?.let {
                broadcastDelete(chatId, canonicalEntryId, it)
            }
        }
    }

    /**
     * 变更事件与数据行在同一 PostgreSQL 事务内提交；群行 FOR UPDATE 锁使并发变更的事件顺序
     * 与变更顺序一致。收件人是提交时刻的活动群成员，离线成员经持久事件流补发。
     */
    private fun PgWriteScope.broadcastUpsert(entry: GroupFileEntry) {
        val payload = GroupFileChangedPayload(
            chatId = entry.chatId,
            operation = GroupFileChangedPayload.OPERATION_UPSERT,
            entry = entry,
            deletedEntryId = "",
            deletedRevision = 0L,
        )
        chatStore.getActiveMemberUids(transaction, entry.chatId).forEach { memberUid ->
            appendEvent(memberUid, NotifyType.GROUP_FILE_CHANGED, payload)
        }
    }

    private fun PgWriteScope.broadcastDelete(
        chatId: String,
        entryId: String,
        tombstoneRevision: Long,
    ) {
        val payload = GroupFileChangedPayload(
            chatId = chatId,
            operation = GroupFileChangedPayload.OPERATION_DELETE,
            entry = null,
            deletedEntryId = entryId,
            deletedRevision = tombstoneRevision,
        )
        chatStore.getActiveMemberUids(transaction, chatId).forEach { memberUid ->
            appendEvent(memberUid, NotifyType.GROUP_FILE_CHANGED, payload)
        }
    }

    private suspend fun <T> onIo(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private suspend fun requireMember(uid: String, chatId: String) {
        access.requireGroupMember(uid, chatId, "你不是当前群成员")
    }

    private fun requireParent(chatId: String, parentId: String?) {
        if (parentId == null) return
        val parent = requireEntry(chatId, parentId)
        require(parent.kind == GroupFileEntry.KIND_FOLDER) { "父级不是目录" }
    }

    private fun requireEntry(chatId: String, entryId: String): GroupFileEntry {
        val entry = repository.find(entryId) ?: throw IllegalArgumentException("文件条目不存在")
        require(entry.chatId == chatId) { "文件条目不属于当前群" }
        return entry
    }

    private fun resolveOwnedAttachment(actorUid: String, declared: Attachment): Attachment {
        val canonical = declared.copy(path = AttachmentPolicy.canonicalPath(declared.path))
        require(attachments.getOwnerUid(canonical.path) == actorUid) { "只能发布自己上传的文件" }
        val actual = attachments.getAttachment(canonical.path)
            ?: throw IllegalArgumentException("上传文件不存在或已失效")
        require(actual == canonical) { "上传文件元数据不匹配" }
        return actual
    }

    private fun validateName(value: String): String {
        val name = value.trim()
        require(name.isNotEmpty()) { "名称不能为空" }
        require(name.length <= 180) { "名称不能超过 180 个字符" }
        require(name != "." && name != ".." && name.none { it == '/' || it == '\\' || it.code < 32 }) {
            "名称包含非法字符"
        }
        return name
    }

    private fun validateResourceId(value: String, label: String): String {
        require(value.length == 36 && runCatching { UUID.fromString(value).toString() }.getOrNull() == value) {
            "$label 非法"
        }
        return value
    }

    companion object {
        fun nameKey(name: String): String = name.trim().lowercase(Locale.ROOT)
    }
}
