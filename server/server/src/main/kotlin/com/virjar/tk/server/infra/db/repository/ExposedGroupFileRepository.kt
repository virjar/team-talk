package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.infra.db.requireExposedTransaction
import com.virjar.tk.server.domain.groupfile.GroupFileCapacityPolicy
import com.virjar.tk.server.domain.groupfile.GroupFileAppendVersionCommand
import com.virjar.tk.server.domain.groupfile.GroupFileCreateCommand
import com.virjar.tk.server.domain.groupfile.GroupFileDeleteCommand
import com.virjar.tk.server.domain.groupfile.GroupFileEntryWriteResult
import com.virjar.tk.server.domain.groupfile.GroupFileRepository
import com.virjar.tk.server.domain.groupfile.GroupFileRenameCommand
import com.virjar.tk.server.domain.groupfile.GroupFileService
import com.virjar.tk.server.domain.chat.ManagedChatPolicy
import com.virjar.tk.server.domain.chat.UnmanagedChatPolicy
import com.virjar.tk.server.domain.command.ReliableCommandConflictException
import com.virjar.tk.server.infra.db.ExposedPgWriteTransactionContext
import com.virjar.tk.server.infra.db.GroupFileAudits
import com.virjar.tk.server.infra.db.GroupFileChatUsages
import com.virjar.tk.server.infra.db.GroupFileCommands
import com.virjar.tk.server.infra.db.GroupFileEntries
import com.virjar.tk.server.infra.db.GroupFileVersions
import com.virjar.tk.server.infra.db.Chats
import com.virjar.tk.server.infra.db.GroupMembers
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.GroupFileEntry
import com.virjar.tk.protocol.model.GroupFileVersion
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.update
import java.util.UUID

class ExposedGroupFileRepository(
    private val database: Database,
    private val managedChats: ManagedChatPolicy = UnmanagedChatPolicy,
    private val capacityPolicy: GroupFileCapacityPolicy = GroupFileCapacityPolicy(),
) : GroupFileRepository {

    private inline fun <T> inWriteTransaction(
        context: PgWriteTransactionContext,
        block: () -> T,
    ): T {
        context.requireExposedTransaction()
        return block()
    }

    override fun list(chatId: String, parentId: String?): List<GroupFileEntry> = transaction(database) {
        val entries = GroupFileEntries.selectAll().where {
            (GroupFileEntries.chatId eq chatId) and
                (GroupFileEntries.parentKey eq parentId.orEmpty()) and
                (GroupFileEntries.status eq STATUS_ACTIVE)
        }.orderBy(
            GroupFileEntries.kind to SortOrder.ASC,
            GroupFileEntries.nameKey to SortOrder.ASC,
        ).limit(capacityPolicy.directChildrenOverflowProbeLimit)
            .map(ResultRow::toGroupFileEntry)
        capacityPolicy.requireDirectChildrenProjection(entries.size)
        entries
    }

    override fun find(entryId: String): GroupFileEntry? = transaction(database) {
        GroupFileEntries.selectAll().where {
            (GroupFileEntries.entryId eq entryId) and (GroupFileEntries.status eq STATUS_ACTIVE)
        }.singleOrNull()?.toGroupFileEntry()
    }

    override fun create(
        transaction: PgWriteTransactionContext,
        command: GroupFileCreateCommand,
    ): GroupFileEntryWriteResult = inWriteTransaction(transaction) {
        val entry = command.entry
        val initialVersion = command.initialVersion
        requireWritableGroup(entry.chatId, entry.createdBy)
        requireValidCommandIdentity(command.commandId, command.fingerprint)
        requireValidCreateShape(entry, initialVersion)
        requireActiveParent(entry.chatId, entry.parentId)
        val usage = lockOrCreateUsage(entry.chatId)
        findExactCreateRetry(command)?.let { existing ->
            val activeEntryBytes = requireNotNull(findEntryRow(existing.entryId))[GroupFileEntries.activeVersionBytes]
            require(
                usage.activeEntries > 0 &&
                    activeEntryBytes >= 0 &&
                    activeEntryBytes <= usage.activeVersionBytes,
            ) { "群文件容量台账与创建收据不一致" }
            return@inWriteTransaction GroupFileEntryWriteResult(existing, changed = false)
        }
        capacityPolicy.requireEntrySlot(usage.activeEntries)
        capacityPolicy.requireDirectChildSlot(activeDirectChildCount(entry.chatId, entry.parentId))
        val incomingBytes = initialVersion?.attachment?.size ?: 0L
        if (initialVersion != null) {
            capacityPolicy.requireVersionSlot(0)
            capacityPolicy.requireByteSlot(usage.activeVersionBytes, incomingBytes)
        }
        requireAvailableName(entry.chatId, entry.parentId, entry.name)
        GroupFileEntries.insert {
            it[entryId] = entry.entryId
            it[chatId] = entry.chatId
            it[creationCommandId] = command.commandId
            it[creationFingerprint] = command.fingerprint
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
            it[activeVersionBytes] = incomingBytes
            it[status] = STATUS_ACTIVE
            it[createdBy] = entry.createdBy
            it[createdAt] = entry.createdAt
            it[updatedBy] = entry.updatedBy
            it[updatedAt] = entry.updatedAt
        }
        initialVersion?.let { version ->
            insertVersion(version, command.commandId, command.fingerprint)
        }
        insertCommandReceipt(
            commandId = command.commandId,
            chatId = entry.chatId,
            entryId = entry.entryId,
            actorUid = entry.createdBy,
            kind = if (initialVersion == null) COMMAND_CREATE_FOLDER else COMMAND_CREATE_FILE,
            fingerprint = command.fingerprint,
            resultVersion = initialVersion?.version,
            createdAt = entry.createdAt,
        )
        updateUsage(
            entry.chatId,
            usage,
            activeEntryDelta = 1,
            activeVersionBytesDelta = incomingBytes,
        )
        audit(
            entry.chatId,
            entry.entryId,
            entry.createdBy,
            if (initialVersion == null) "CREATE_FOLDER" else "CREATE_FILE",
            entry.name,
        )
        GroupFileEntryWriteResult(entry, changed = true)
    }

    override fun appendVersion(
        transaction: PgWriteTransactionContext,
        command: GroupFileAppendVersionCommand,
    ): GroupFileEntryWriteResult = inWriteTransaction(transaction) {
        requireValidCommandIdentity(command.commandId, command.fingerprint)
        requireCanonicalUuid(command.entryId, "群文件条目标识")
        val snapshot = requireActiveEntry(command.entryId)
        requireWritableGroup(snapshot.chatId, command.actorUid)
        val currentRow = requireActiveEntryRow(command.entryId)
        val current = currentRow.toGroupFileEntry()
        val usage = lockOrCreateUsage(current.chatId)
        val currentEntryBytes = currentRow[GroupFileEntries.activeVersionBytes]
        require(
            usage.activeEntries > 0 &&
                currentEntryBytes >= 0 &&
                currentEntryBytes <= usage.activeVersionBytes,
        ) { "群文件容量台账与活动条目不一致" }
        findExactVersionRetry(current, command)?.let {
            return@inWriteTransaction GroupFileEntryWriteResult(it, changed = false)
        }
        require(current.revision == command.expectedRevision) { "文件已被其他成员修改，请刷新后重试" }
        require(current.kind == GroupFileEntry.KIND_FILE) { "目录不能添加文件版本" }
        require(current.contentVersion > 0) { "群文件当前版本计数异常" }
        capacityPolicy.requireVersionSlot(current.contentVersion)
        capacityPolicy.requireByteSlot(usage.activeVersionBytes, command.attachment.size)
        val nextVersion = Math.addExact(current.contentVersion, 1L)
        val nextEntryBytes = Math.addExact(currentEntryBytes, command.attachment.size)
        val updated = GroupFileEntries.update({
            (GroupFileEntries.entryId eq command.entryId) and
                (GroupFileEntries.status eq STATUS_ACTIVE) and
                (GroupFileEntries.revision eq command.expectedRevision)
        }) {
            it[attachmentPath] = command.attachment.path
            it[attachmentName] = command.attachment.name
            it[attachmentContentType] = command.attachment.contentType
            it[attachmentSize] = command.attachment.size
            it[revision] = Math.addExact(command.expectedRevision, 1L)
            it[contentVersion] = nextVersion
            it[activeVersionBytes] = nextEntryBytes
            it[updatedBy] = command.actorUid
            it[updatedAt] = command.createdAt
        }
        require(updated == 1) { "文件已被其他成员修改，请刷新后重试" }
        insertVersion(
            GroupFileVersion(
                entryId = command.entryId,
                version = nextVersion,
                attachment = command.attachment,
                createdBy = command.actorUid,
                createdAt = command.createdAt,
            ),
            command.commandId,
            command.fingerprint,
        )
        insertCommandReceipt(
            commandId = command.commandId,
            chatId = current.chatId,
            entryId = command.entryId,
            actorUid = command.actorUid,
            kind = COMMAND_ADD_VERSION,
            fingerprint = command.fingerprint,
            resultVersion = nextVersion,
            createdAt = command.createdAt,
        )
        updateUsage(
            current.chatId,
            usage,
            activeEntryDelta = 0,
            activeVersionBytesDelta = command.attachment.size,
        )
        audit(current.chatId, command.entryId, command.actorUid, "ADD_VERSION", "v$nextVersion")
        GroupFileEntryWriteResult(requireActiveEntry(command.entryId), changed = true)
    }

    override fun rename(
        transaction: PgWriteTransactionContext,
        command: GroupFileRenameCommand,
    ): GroupFileEntry? = inWriteTransaction(transaction) {
        requireValidCommandIdentity(command.commandId, command.fingerprint)
        requireCanonicalUuid(command.entryId, "群文件条目标识")
        if (
            findExactMutationRetryAroundWriteAdmission(
                commandId = command.commandId,
                chatId = command.chatId,
                entryId = command.entryId,
                actorUid = command.actorUid,
                kind = COMMAND_RENAME,
                fingerprint = command.fingerprint,
            )
        ) return@inWriteTransaction null
        val current = requireActiveEntry(command.entryId)
        require(current.chatId == command.chatId) { "文件条目不属于当前群" }
        require(current.revision == command.expectedRevision) { "文件已被其他成员修改，请刷新后重试" }
        requireAvailableName(current.chatId, current.parentId, command.name, excludingEntryId = command.entryId)
        val updated = GroupFileEntries.update({
            (GroupFileEntries.entryId eq command.entryId) and
                (GroupFileEntries.status eq STATUS_ACTIVE) and
                (GroupFileEntries.revision eq command.expectedRevision)
        }) {
            it[GroupFileEntries.name] = command.name
            it[nameKey] = GroupFileService.nameKey(command.name)
            it[revision] = Math.addExact(command.expectedRevision, 1L)
            it[updatedBy] = command.actorUid
            it[updatedAt] = command.updatedAt
        }
        require(updated == 1) { "文件已被其他成员修改，请刷新后重试" }
        insertCommandReceipt(
            commandId = command.commandId,
            chatId = command.chatId,
            entryId = command.entryId,
            actorUid = command.actorUid,
            kind = COMMAND_RENAME,
            fingerprint = command.fingerprint,
            resultVersion = null,
            createdAt = command.updatedAt,
        )
        audit(current.chatId, command.entryId, command.actorUid, "RENAME", "${current.name} -> ${command.name}")
        requireActiveEntry(command.entryId)
    }

    override fun delete(
        transaction: PgWriteTransactionContext,
        command: GroupFileDeleteCommand,
    ): Long? = inWriteTransaction(transaction) {
        requireValidCommandIdentity(command.commandId, command.fingerprint)
        requireCanonicalUuid(command.entryId, "群文件条目标识")
        if (
            findExactMutationRetryAroundWriteAdmission(
                commandId = command.commandId,
                chatId = command.chatId,
                entryId = command.entryId,
                actorUid = command.actorUid,
                kind = COMMAND_DELETE,
                fingerprint = command.fingerprint,
            )
        ) return@inWriteTransaction null
        val currentRow = requireActiveEntryRow(command.entryId)
        val current = currentRow.toGroupFileEntry()
        require(current.chatId == command.chatId) { "文件条目不属于当前群" }
        val usage = lockOrCreateUsage(current.chatId)
        require(current.revision == command.expectedRevision) { "文件已被其他成员修改，请刷新后重试" }
        require(current.kind != GroupFileEntry.KIND_FOLDER || !hasActiveChildrenInternal(command.entryId)) {
            "目录非空，请先删除其中的内容"
        }
        val releasedBytes = currentRow[GroupFileEntries.activeVersionBytes]
        require(
            usage.activeEntries > 0 &&
                releasedBytes >= 0 &&
                releasedBytes <= usage.activeVersionBytes,
        ) { "群文件容量台账与条目不一致" }
        val updated = GroupFileEntries.update({
            (GroupFileEntries.entryId eq command.entryId) and
                (GroupFileEntries.status eq STATUS_ACTIVE) and
                (GroupFileEntries.revision eq command.expectedRevision)
        }) {
            it[status] = STATUS_DELETED
            it[nameKey] = "${GroupFileService.nameKey(current.name)}#deleted#${command.entryId}"
            it[revision] = Math.addExact(command.expectedRevision, 1L)
            it[updatedBy] = command.actorUid
            it[updatedAt] = command.deletedAt
        }
        require(updated == 1) { "文件已被其他成员修改，请刷新后重试" }
        insertCommandReceipt(
            commandId = command.commandId,
            chatId = command.chatId,
            entryId = command.entryId,
            actorUid = command.actorUid,
            kind = COMMAND_DELETE,
            fingerprint = command.fingerprint,
            resultVersion = null,
            createdAt = command.deletedAt,
        )
        updateUsage(
            current.chatId,
            usage,
            activeEntryDelta = -1,
            activeVersionBytesDelta = -releasedBytes,
        )
        audit(current.chatId, command.entryId, command.actorUid, "DELETE", current.name)
        Math.addExact(command.expectedRevision, 1L)
    }

    override fun hasActiveChildren(entryId: String): Boolean = transaction(database) {
        hasActiveChildrenInternal(entryId)
    }

    private fun hasActiveChildrenInternal(entryId: String): Boolean =
        GroupFileEntries.selectAll().where {
            (GroupFileEntries.parentId eq entryId) and (GroupFileEntries.status eq STATUS_ACTIVE)
        }.limit(1).any()

    override fun listVersions(entryId: String): List<GroupFileVersion> = transaction(database) {
        val versions = GroupFileVersions.selectAll().where { GroupFileVersions.entryId eq entryId }
            .orderBy(GroupFileVersions.version to SortOrder.DESC)
            .limit(capacityPolicy.activeVersionsOverflowProbeLimit)
            .map(ResultRow::toGroupFileVersion)
        capacityPolicy.requireVersionsProjection(versions.size)
        versions
    }

    override fun totalVersionBytes(chatId: String): Long = transaction(database) {
        GroupFileChatUsages.selectAll().where {
            GroupFileChatUsages.chatId eq chatId
        }.singleOrNull()?.let { usage ->
            val bytes = usage[GroupFileChatUsages.activeVersionBytes]
            require(bytes >= 0) { "群文件容量台账非法" }
            bytes
        } ?: run {
            require(!hasAnyEntry(chatId)) { "群文件容量台账缺失，请重建当前测试数据" }
            0L
        }
    }

    override fun getAttachmentChatIds(path: String): Set<String> = transaction(database) {
        versionEntryJoin().select(GroupFileEntries.chatId).where {
            (GroupFileVersions.attachmentPath eq path) and
                (GroupFileEntries.status eq STATUS_ACTIVE)
        }.mapTo(linkedSetOf()) { it[GroupFileEntries.chatId] }
    }

    override fun isAttachmentReferencedByAny(path: String, chatIds: Set<String>): Boolean {
        if (chatIds.isEmpty()) return false
        return transaction(database) {
            versionEntryJoin().select(GroupFileEntries.chatId).where {
                (GroupFileVersions.attachmentPath eq path) and
                    (GroupFileEntries.chatId inList chatIds.sorted()) and
                    (GroupFileEntries.status eq STATUS_ACTIVE)
            }.limit(1).any()
        }
    }

    override fun getReferencedAttachmentPaths(paths: Set<String>): Set<String> {
        if (paths.isEmpty()) return emptySet()
        return transaction(database) {
            versionEntryJoin().select(GroupFileVersions.attachmentPath).where {
                (GroupFileVersions.attachmentPath inList paths.sorted()) and
                    (GroupFileEntries.status eq STATUS_ACTIVE)
            }.groupBy(GroupFileVersions.attachmentPath)
                .mapTo(linkedSetOf()) { it[GroupFileVersions.attachmentPath] }
        }
    }

    private fun activeDirectChildCount(chatId: String, parentId: String?): Long =
        GroupFileEntries.selectAll().where {
            (GroupFileEntries.chatId eq chatId) and
                (GroupFileEntries.parentKey eq parentId.orEmpty()) and
                (GroupFileEntries.status eq STATUS_ACTIVE)
        }.count()

    /** 精确回执在容量准入之前解析，因此绝不会两次消耗名额。 */
    private fun findExactCreateRetry(command: GroupFileCreateCommand): GroupFileEntry? {
        val requested = command.entry
        val receipt = findCommand(command.commandId)
        val existingRow = findEntryRow(requested.entryId)
        if (receipt == null && existingRow == null) return null
        require(receipt != null && existingRow != null) {
            "群文件资源标识或命令标识已用于不同的创建请求"
        }
        val expectedKind = if (command.initialVersion == null) COMMAND_CREATE_FOLDER else COMMAND_CREATE_FILE
        requireCommandReceipt(
            receipt = receipt,
            commandId = command.commandId,
            chatId = requested.chatId,
            entryId = requested.entryId,
            actorUid = requested.createdBy,
            kind = expectedKind,
            fingerprint = command.fingerprint,
        )
        require(
            existingRow[GroupFileEntries.chatId] == requested.chatId &&
                existingRow[GroupFileEntries.creationCommandId] == command.commandId &&
                existingRow[GroupFileEntries.creationFingerprint] == command.fingerprint &&
                existingRow[GroupFileEntries.createdBy] == requested.createdBy,
        ) { "群文件资源标识已用于不同的创建请求" }
        require(existingRow[GroupFileEntries.status] == STATUS_ACTIVE) { "群文件条目已删除" }

        val versionReceipt = findVersionByCommand(command.commandId)
        if (command.initialVersion == null) {
            require(receipt[GroupFileCommands.resultVersion] == null && versionReceipt == null) {
                "群文件目录创建收据包含异常版本"
            }
        } else {
            require(
                receipt[GroupFileCommands.resultVersion] == 1L &&
                    versionReceipt != null &&
                    versionReceipt[GroupFileVersions.entryId] == requested.entryId &&
                    versionReceipt[GroupFileVersions.version] == 1L &&
                    versionReceipt[GroupFileVersions.commandFingerprint] == command.fingerprint,
            ) { "群文件初始版本收据不一致" }
        }
        return existingRow.toGroupFileEntry()
    }

    /** 重放已提交的版本命令时，原样返回当前条目而不做变更。 */
    private fun findExactVersionRetry(
        current: GroupFileEntry,
        command: GroupFileAppendVersionCommand,
    ): GroupFileEntry? {
        val receipt = findCommand(command.commandId) ?: return null
        requireCommandReceipt(
            receipt = receipt,
            commandId = command.commandId,
            chatId = current.chatId,
            entryId = current.entryId,
            actorUid = command.actorUid,
            kind = COMMAND_ADD_VERSION,
            fingerprint = command.fingerprint,
        )
        val resultVersion = requireNotNull(receipt[GroupFileCommands.resultVersion]) {
            "群文件版本命令收据缺少结果版本"
        }
        val version = findVersionByCommand(command.commandId)
        require(
            version != null &&
                version[GroupFileVersions.entryId] == current.entryId &&
                version[GroupFileVersions.version] == resultVersion &&
                version[GroupFileVersions.commandFingerprint] == command.fingerprint &&
                current.kind == GroupFileEntry.KIND_FILE &&
                current.contentVersion >= resultVersion,
        ) { "群文件版本命令收据与当前条目不一致" }
        return current
    }

    private fun isExactMutationRetry(
        commandId: String,
        chatId: String,
        entryId: String,
        actorUid: String,
        kind: Int,
        fingerprint: String,
    ): Boolean {
        val receipt = findCommand(commandId) ?: return false
        requireCommandReceipt(receipt, commandId, chatId, entryId, actorUid, kind, fingerprint)
        require(receipt[GroupFileCommands.resultVersion] == null) {
            "群文件变更命令收据包含异常结果版本"
        }
        return true
    }

    /**
     * 精确回执是一次 ACK，而不是新写入，因此它在之后的成员资格或群状态
     * 变化后仍然存续。第二次查找至关重要：并发首次投递可能在此
     * 事务等待聊天行锁时提交。若准入本身观察到更新的成员资格
     * 状态，最后一次查找能区分该竞态与真正未授权的新命令。
     */
    private fun findExactMutationRetryAroundWriteAdmission(
        commandId: String,
        chatId: String,
        entryId: String,
        actorUid: String,
        kind: Int,
        fingerprint: String,
    ): Boolean {
        if (isExactMutationRetry(commandId, chatId, entryId, actorUid, kind, fingerprint)) return true
        try {
            requireWritableGroup(chatId, actorUid)
        } catch (failure: IllegalArgumentException) {
            if (isExactMutationRetry(commandId, chatId, entryId, actorUid, kind, fingerprint)) return true
            throw failure
        }
        return isExactMutationRetry(commandId, chatId, entryId, actorUid, kind, fingerprint)
    }

    private fun findEntryRow(entryId: String): ResultRow? =
        GroupFileEntries.selectAll().where { GroupFileEntries.entryId eq entryId }.singleOrNull()

    private fun findCommand(commandId: String): ResultRow? =
        GroupFileCommands.selectAll().where { GroupFileCommands.commandId eq commandId }.singleOrNull()

    private fun findVersionByCommand(commandId: String): ResultRow? =
        GroupFileVersions.selectAll().where { GroupFileVersions.commandId eq commandId }.singleOrNull()

    private fun requireCommandReceipt(
        receipt: ResultRow,
        commandId: String,
        chatId: String,
        entryId: String,
        actorUid: String,
        kind: Int,
        fingerprint: String,
    ) {
        if (
            receipt[GroupFileCommands.commandId] != commandId ||
            receipt[GroupFileCommands.chatId] != chatId ||
            receipt[GroupFileCommands.entryId] != entryId ||
            receipt[GroupFileCommands.actorUid] != actorUid ||
            receipt[GroupFileCommands.kind] != kind ||
            receipt[GroupFileCommands.fingerprint] != fingerprint
        ) {
            throw ReliableCommandConflictException("群文件命令标识已用于不同的请求")
        }
    }

    private fun lockOrCreateUsage(chatId: String): GroupFileUsage {
        GroupFileChatUsages.selectAll().where {
            GroupFileChatUsages.chatId eq chatId
        }.forUpdate().singleOrNull()?.let { row ->
            return row.toGroupFileUsage()
        }
        require(!hasAnyEntry(chatId)) {
            "群文件容量台账缺失，请重建当前测试数据"
        }
        GroupFileChatUsages.insert {
            it[GroupFileChatUsages.chatId] = chatId
            it[GroupFileChatUsages.activeEntries] = 0L
            it[GroupFileChatUsages.activeVersionBytes] = 0L
        }
        return GroupFileUsage(activeEntries = 0L, activeVersionBytes = 0L)
    }

    private fun hasAnyEntry(chatId: String): Boolean =
        GroupFileEntries.selectAll().where { GroupFileEntries.chatId eq chatId }.limit(1).any()

    private fun updateUsage(
        chatId: String,
        current: GroupFileUsage,
        activeEntryDelta: Long,
        activeVersionBytesDelta: Long,
    ): GroupFileUsage {
        val nextEntries = Math.addExact(current.activeEntries, activeEntryDelta)
        val nextBytes = Math.addExact(current.activeVersionBytes, activeVersionBytesDelta)
        require(nextEntries >= 0 && nextBytes >= 0) { "群文件容量台账不能为负数" }
        val updated = GroupFileChatUsages.update({
            (GroupFileChatUsages.chatId eq chatId) and
                (GroupFileChatUsages.activeEntries eq current.activeEntries) and
                (GroupFileChatUsages.activeVersionBytes eq current.activeVersionBytes)
        }) {
            it[GroupFileChatUsages.activeEntries] = nextEntries
            it[GroupFileChatUsages.activeVersionBytes] = nextBytes
        }
        require(updated == 1) { "群文件容量台账并发冲突" }
        return GroupFileUsage(nextEntries, nextBytes)
    }

    private fun requireValidCommandIdentity(commandId: String, fingerprint: String) {
        requireCanonicalUuid(commandId, "群文件命令标识")
        require(fingerprint.length == 64 && fingerprint.all { it in '0'..'9' || it in 'a'..'f' }) {
            "群文件命令指纹非法"
        }
    }

    private fun requireCanonicalUuid(value: String, label: String) {
        require(value.length == 36 && runCatching { UUID.fromString(value).toString() }.getOrNull() == value) {
            "$label 非法"
        }
    }

    private fun requireValidCreateShape(entry: GroupFileEntry, initialVersion: GroupFileVersion?) {
        requireCanonicalUuid(entry.entryId, "群文件条目标识")
        require(entry.revision == 1L) { "群文件初始 revision 必须为 1" }
        require(entry.createdBy == entry.updatedBy && entry.createdAt == entry.updatedAt) {
            "群文件初始审计身份不一致"
        }
        when (entry.kind) {
            GroupFileEntry.KIND_FOLDER -> {
                require(initialVersion == null && entry.attachment == null && entry.contentVersion == 0L) {
                    "群文件目录不能携带文件版本"
                }
            }

            GroupFileEntry.KIND_FILE -> {
                require(initialVersion != null) { "群文件必须携带初始版本" }
                require(
                    initialVersion.entryId == entry.entryId &&
                        initialVersion.version == 1L &&
                        initialVersion.attachment == entry.attachment &&
                        initialVersion.createdBy == entry.createdBy &&
                        initialVersion.createdAt == entry.createdAt &&
                        entry.contentVersion == 1L,
                ) { "群文件初始版本与条目不一致" }
            }

            else -> throw IllegalArgumentException("未知的群文件条目类型")
        }
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

    /**
     * 复用 chat 行作为每个群文件空间的互斥锁，并在同一事务内复验授权。
     *
     * 服务层的预检只负责尽早返回友好错误；真正的安全边界必须位于写事务里。这样即使用户
     * 在上传准备完成后被踢出，或群在请求进入仓储前解散，也不能再提交文件树、版本或审计行。
     */
    private fun requireWritableGroup(chatId: String, actorUid: String) {
        val authority = managedChats.lockAuthority(
            ExposedPgWriteTransactionContext(TransactionManager.current()),
            listOf(chatId),
        ).getValue(chatId)
        require(authority.ready) { "受管群投影尚未收敛" }
        val activeGroup = Chats.selectAll().where {
            (Chats.chatId eq chatId) and
                (Chats.chatType eq 2) and
                (Chats.status eq 1)
        }.forUpdate().singleOrNull()
        require(activeGroup != null) { "群聊不存在或已解散" }

        val activeMember = GroupMembers.selectAll().where {
            (GroupMembers.chatId eq chatId) and
                (GroupMembers.uid eq actorUid) and
                (GroupMembers.status eq 1)
        }.limit(1).any()
        require(activeMember) { "你不是当前群成员" }
    }

    private fun requireActiveEntry(entryId: String): GroupFileEntry =
        requireActiveEntryRow(entryId).toGroupFileEntry()

    private fun requireActiveEntryRow(entryId: String): ResultRow =
        GroupFileEntries.selectAll().where {
            (GroupFileEntries.entryId eq entryId) and (GroupFileEntries.status eq STATUS_ACTIVE)
        }.singleOrNull() ?: throw IllegalArgumentException("文件条目不存在")

    private fun insertVersion(version: GroupFileVersion, commandId: String, commandFingerprint: String) {
        GroupFileVersions.insert {
            it[entryId] = version.entryId
            it[GroupFileVersions.version] = version.version
            it[GroupFileVersions.commandId] = commandId
            it[GroupFileVersions.commandFingerprint] = commandFingerprint
            it[attachmentPath] = version.attachment.path
            it[attachmentName] = version.attachment.name
            it[attachmentContentType] = version.attachment.contentType
            it[attachmentSize] = version.attachment.size
            it[createdBy] = version.createdBy
            it[createdAt] = version.createdAt
        }
    }

    private fun insertCommandReceipt(
        commandId: String,
        chatId: String,
        entryId: String,
        actorUid: String,
        kind: Int,
        fingerprint: String,
        resultVersion: Long?,
        createdAt: Long,
    ) {
        GroupFileCommands.insert {
            it[GroupFileCommands.commandId] = commandId
            it[GroupFileCommands.chatId] = chatId
            it[GroupFileCommands.entryId] = entryId
            it[GroupFileCommands.actorUid] = actorUid
            it[GroupFileCommands.kind] = kind
            it[GroupFileCommands.fingerprint] = fingerprint
            it[GroupFileCommands.resultVersion] = resultVersion
            it[GroupFileCommands.createdAt] = createdAt
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
        private const val COMMAND_CREATE_FOLDER = 1
        private const val COMMAND_CREATE_FILE = 2
        private const val COMMAND_ADD_VERSION = 3
        private const val COMMAND_RENAME = 4
        private const val COMMAND_DELETE = 5
    }
}

private data class GroupFileUsage(
    val activeEntries: Long,
    val activeVersionBytes: Long,
)

private fun ResultRow.toGroupFileUsage() = GroupFileUsage(
    activeEntries = this[GroupFileChatUsages.activeEntries],
    activeVersionBytes = this[GroupFileChatUsages.activeVersionBytes],
)

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
