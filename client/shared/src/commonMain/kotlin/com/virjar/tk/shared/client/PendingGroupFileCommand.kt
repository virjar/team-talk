package com.virjar.tk.shared.client

import com.virjar.tk.protocol.body.AttachmentPolicy
import com.virjar.tk.protocol.model.Attachment

/** 不可变群文件变更，保留直到其精确服务器结果已知。 */
data class PendingGroupFileCommand(
    val commandId: String,
    val intentKey: String,
    val kind: PendingGroupFileCommandKind,
    val entryId: String,
    val chatId: String,
    val parentId: String?,
    val name: String?,
    val attachment: Attachment?,
    val expectedRevision: Long?,
    val createdAt: Long,
    val payloadBytes: Long,
) {
    /**
     * 新生成的候选有另一个 command id；创建操作还会分配另一个 entry id。一旦语义意图持久化，
     * 生成的身份与时间戳就不是不可变 RPC 载荷比较的一部分；存储的那一代获胜并被精确重放。
     */
    fun hasSameIntentPayload(other: PendingGroupFileCommand): Boolean =
        kind == other.kind &&
            chatId == other.chatId &&
            parentId == other.parentId &&
            name == other.name &&
            attachment == other.attachment &&
            expectedRevision == other.expectedRevision &&
            (kind == PendingGroupFileCommandKind.CREATE_FOLDER ||
                kind == PendingGroupFileCommandKind.CREATE_FILE ||
                entryId == other.entryId)

    internal fun requireCanonical(): PendingGroupFileCommand {
        check(commandId.isCanonicalUuid()) { "Pending group-file command id is invalid" }
        check(entryId.isCanonicalUuid()) { "Pending group-file entry id is invalid" }
        check(chatId.isCanonicalUuid()) { "Pending group-file chat id is invalid" }
        check(parentId == null || parentId.isCanonicalUuid()) { "Pending group-file parent id is invalid" }
        check(createdAt >= 0L) { "Pending group-file timestamp is invalid" }

        val canonicalName = name?.let(::canonicalGroupFileName)
        val canonicalAttachment = attachment?.let(AttachmentPolicy::canonicalizeDescriptor)
        when (kind) {
            PendingGroupFileCommandKind.CREATE_FOLDER -> check(
                canonicalName != null && canonicalAttachment == null && expectedRevision == null,
            ) { "Pending group-folder command shape is invalid" }

            PendingGroupFileCommandKind.CREATE_FILE -> check(
                canonicalName != null && canonicalAttachment != null && expectedRevision == null,
            ) { "Pending group-file creation shape is invalid" }

            PendingGroupFileCommandKind.ADD_VERSION -> check(
                canonicalName == null && canonicalAttachment != null &&
                    expectedRevision != null && expectedRevision > 0L && parentId == null,
            ) { "Pending group-file version shape is invalid" }

            PendingGroupFileCommandKind.RENAME -> check(
                canonicalName != null && canonicalAttachment == null &&
                    expectedRevision != null && expectedRevision > 0L,
            ) { "Pending group-file rename shape is invalid" }

            PendingGroupFileCommandKind.DELETE -> check(
                canonicalName == null && canonicalAttachment == null &&
                    expectedRevision != null && expectedRevision > 0L,
            ) { "Pending group-file deletion shape is invalid" }
        }
        check(name == canonicalName && attachment == canonicalAttachment) {
            "Pending group-file payload is not canonical"
        }
        val expectedIntentKey = groupFileIntentKey(kind, chatId, parentId, entryId, canonicalName)
        check(intentKey == expectedIntentKey && intentKey.length <= MAX_GROUP_FILE_INTENT_KEY_LENGTH) {
            "Pending group-file intent key is invalid"
        }
        val expectedBytes = groupFilePayloadBytes(
            commandId,
            intentKey,
            entryId,
            chatId,
            parentId,
            canonicalName,
            canonicalAttachment,
            expectedRevision,
        )
        check(payloadBytes == expectedBytes && payloadBytes in 1L..MAX_PENDING_GROUP_FILE_COMMAND_BYTES) {
            "Pending group-file payload size is invalid"
        }
        return this
    }

    companion object {
        fun createFolder(
            commandId: String,
            entryId: String,
            chatId: String,
            parentId: String?,
            name: String,
            createdAt: Long,
        ): PendingGroupFileCommand = create(
            commandId = commandId,
            kind = PendingGroupFileCommandKind.CREATE_FOLDER,
            entryId = entryId,
            chatId = chatId,
            parentId = parentId,
            name = canonicalGroupFileName(name),
            attachment = null,
            expectedRevision = null,
            createdAt = createdAt,
        )

        fun createFile(
            commandId: String,
            entryId: String,
            chatId: String,
            parentId: String?,
            name: String,
            attachment: Attachment,
            createdAt: Long,
        ): PendingGroupFileCommand = create(
            commandId = commandId,
            kind = PendingGroupFileCommandKind.CREATE_FILE,
            entryId = entryId,
            chatId = chatId,
            parentId = parentId,
            name = canonicalGroupFileName(name),
            attachment = AttachmentPolicy.canonicalizeDescriptor(attachment),
            expectedRevision = null,
            createdAt = createdAt,
        )

        fun addVersion(
            commandId: String,
            chatId: String,
            entryId: String,
            attachment: Attachment,
            expectedRevision: Long,
            createdAt: Long,
        ): PendingGroupFileCommand = create(
            commandId = commandId,
            kind = PendingGroupFileCommandKind.ADD_VERSION,
            entryId = entryId,
            chatId = chatId,
            parentId = null,
            name = null,
            attachment = AttachmentPolicy.canonicalizeDescriptor(attachment),
            expectedRevision = expectedRevision,
            createdAt = createdAt,
        )

        fun rename(
            commandId: String,
            chatId: String,
            parentId: String?,
            entryId: String,
            name: String,
            expectedRevision: Long,
            createdAt: Long,
        ): PendingGroupFileCommand = create(
            commandId = commandId,
            kind = PendingGroupFileCommandKind.RENAME,
            entryId = entryId,
            chatId = chatId,
            parentId = parentId,
            name = canonicalGroupFileName(name),
            attachment = null,
            expectedRevision = expectedRevision,
            createdAt = createdAt,
        )

        fun delete(
            commandId: String,
            chatId: String,
            parentId: String?,
            entryId: String,
            expectedRevision: Long,
            createdAt: Long,
        ): PendingGroupFileCommand = create(
            commandId = commandId,
            kind = PendingGroupFileCommandKind.DELETE,
            entryId = entryId,
            chatId = chatId,
            parentId = parentId,
            name = null,
            attachment = null,
            expectedRevision = expectedRevision,
            createdAt = createdAt,
        )

        internal fun restore(
            commandId: String,
            intentKey: String,
            kind: PendingGroupFileCommandKind,
            entryId: String,
            chatId: String,
            parentId: String?,
            name: String?,
            attachment: Attachment?,
            expectedRevision: Long?,
            createdAt: Long,
            payloadBytes: Long,
        ): PendingGroupFileCommand = PendingGroupFileCommand(
            commandId,
            intentKey,
            kind,
            entryId,
            chatId,
            parentId,
            name,
            attachment,
            expectedRevision,
            createdAt,
            payloadBytes,
        ).requireCanonical()

        private fun create(
            commandId: String,
            kind: PendingGroupFileCommandKind,
            entryId: String,
            chatId: String,
            parentId: String?,
            name: String?,
            attachment: Attachment?,
            expectedRevision: Long?,
            createdAt: Long,
        ): PendingGroupFileCommand {
            val intentKey = groupFileIntentKey(kind, chatId, parentId, entryId, name)
            return PendingGroupFileCommand(
                commandId = commandId,
                intentKey = intentKey,
                kind = kind,
                entryId = entryId,
                chatId = chatId,
                parentId = parentId,
                name = name,
                attachment = attachment,
                expectedRevision = expectedRevision,
                createdAt = createdAt,
                payloadBytes = groupFilePayloadBytes(
                    commandId,
                    intentKey,
                    entryId,
                    chatId,
                    parentId,
                    name,
                    attachment,
                    expectedRevision,
                ),
            ).requireCanonical()
        }
    }
}

enum class PendingGroupFileCommandKind(val code: Long) {
    CREATE_FOLDER(1),
    CREATE_FILE(2),
    ADD_VERSION(3),
    RENAME(4),
    DELETE(5),
    ;

    companion object {
        fun fromCode(code: Long): PendingGroupFileCommandKind = entries.firstOrNull { it.code == code }
            ?: throw IllegalStateException("Persisted group-file command kind is invalid")
    }
}

class PendingGroupFileCommandConflictException(message: String) : IllegalStateException(message)

private fun canonicalGroupFileName(value: String): String {
    val name = value.trim()
    require(name.isNotEmpty() && name.length <= MAX_GROUP_FILE_NAME_LENGTH) {
        "群文件名称不能为空或超过 $MAX_GROUP_FILE_NAME_LENGTH 个字符"
    }
    require(name != "." && name != ".." && name.none { it == '/' || it == '\\' || it.code < 32 }) {
        "群文件名称包含非法字符"
    }
    return name
}

private fun groupFileIntentKey(
    kind: PendingGroupFileCommandKind,
    chatId: String,
    parentId: String?,
    entryId: String,
    name: String?,
): String = when (kind) {
    // 文件夹与文件创建共享同一个服务器兄弟名称唯一性约束。
    PendingGroupFileCommandKind.CREATE_FOLDER,
    PendingGroupFileCommandKind.CREATE_FILE,
    -> "create|$chatId|${parentId.orEmpty()}|${requireNotNull(name).lowercase()}"

    PendingGroupFileCommandKind.ADD_VERSION,
    PendingGroupFileCommandKind.RENAME,
    PendingGroupFileCommandKind.DELETE,
    -> "mutation|$chatId|$entryId"
}

private fun groupFilePayloadBytes(
    commandId: String,
    intentKey: String,
    entryId: String,
    chatId: String,
    parentId: String?,
    name: String?,
    attachment: Attachment?,
    expectedRevision: Long?,
): Long {
    val strings = listOfNotNull(
        commandId,
        intentKey,
        entryId,
        chatId,
        parentId,
        name,
        attachment?.path,
        attachment?.name,
        attachment?.contentType,
    )
    // 每个字符串包含一个长度前缀，外加 kind、时间戳与可空标量元数据。
    var bytes = strings.sumOf { it.encodeToByteArray().size.toLong() + INT_BYTES }
    bytes += INT_BYTES // kind
    bytes += LONG_BYTES // createdAt
    if (attachment != null) bytes += LONG_BYTES // attachment.size
    if (expectedRevision != null) bytes += LONG_BYTES
    return bytes
}

const val MAX_PENDING_GROUP_FILE_COMMANDS = 256
const val MAX_PENDING_GROUP_FILE_COMMAND_BYTES = 24L * 1_024L
const val MAX_PENDING_GROUP_FILE_COMMAND_STORED_BYTES = 3L * 1_024L * 1_024L
private const val MAX_GROUP_FILE_NAME_LENGTH = 180
private const val MAX_GROUP_FILE_INTENT_KEY_LENGTH = 512
private const val INT_BYTES = 4L
private const val LONG_BYTES = 8L
