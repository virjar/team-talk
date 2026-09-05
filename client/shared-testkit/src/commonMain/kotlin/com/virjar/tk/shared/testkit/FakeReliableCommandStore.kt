package com.virjar.tk.shared.testkit

import com.virjar.tk.shared.client.MAX_PENDING_GROUP_FILE_COMMANDS
import com.virjar.tk.shared.client.MAX_PENDING_DOCUMENT_MOVE_COMMANDS
import com.virjar.tk.shared.client.PendingContactDecision
import com.virjar.tk.shared.client.PendingGroupBotCredentialCommand
import com.virjar.tk.shared.client.PendingGroupBotCredentialCommandConflictException
import com.virjar.tk.shared.client.PendingGroupCreationCommand
import com.virjar.tk.shared.client.PendingDocumentMoveCommand
import com.virjar.tk.shared.client.PendingDocumentMoveCommandConflictException
import com.virjar.tk.shared.client.PendingGroupFileCommand
import com.virjar.tk.shared.client.PendingGroupFileCommandConflictException
import com.virjar.tk.shared.client.PendingGroupFileCommandKind
import com.virjar.tk.shared.client.PendingInviteLinkCreation
import com.virjar.tk.shared.client.PendingReliableCommandConflictException

/** [FakeLocalCache] 使用的可靠本地命令状态，与投影职责相互隔离。 */
internal class FakeReliableCommandStore(
    private val cacheUseGate: FakeCacheUseGate,
) {
    private val groupCreationLock = Any()
    private var groupCreation: PendingGroupCreationCommand? = null
    private val reliableCommandLock = Any()
    private val contactDecisions = linkedMapOf<String, PendingContactDecision>()
    private val inviteCreations = linkedMapOf<String, PendingInviteLinkCreation>()
    private var groupBotCredentialCommand: PendingGroupBotCredentialCommand? = null
    private val groupFileCommands = linkedMapOf<String, PendingGroupFileCommand>()
    private val documentMoveCommands = linkedMapOf<String, PendingDocumentMoveCommand>()

    fun getGroupCreation(): PendingGroupCreationCommand? = cacheUseGate.use {
        synchronized(groupCreationLock) { groupCreation }
    }

    fun replaceGroupCreation(command: PendingGroupCreationCommand) = cacheUseGate.use {
        val canonical = PendingGroupCreationCommand.create(
            operationId = command.operationId,
            creatorUid = command.creatorUid,
            name = command.name,
            avatar = command.avatar,
            memberUids = command.memberUids,
        )
        check(canonical == command) { "Pending group creation command is not canonical" }
        synchronized(groupCreationLock) { groupCreation = canonical }
    }

    fun clearGroupCreation(operationId: String): Boolean = cacheUseGate.use {
        synchronized(groupCreationLock) {
            if (groupCreation?.operationId != operationId) return@synchronized false
            groupCreation = null
            true
        }
    }

    fun prepareContactDecision(candidate: PendingContactDecision): PendingContactDecision = cacheUseGate.use {
        synchronized(reliableCommandLock) {
            val canonical = candidate.requireCanonical()
            contactDecisions[canonical.token]?.let { existing ->
                if (!existing.hasSamePayload(canonical)) {
                    throw PendingReliableCommandConflictException(
                        "该好友申请已有另一项未确认处理操作",
                    )
                }
                return@synchronized existing
            }
            if (contactDecisions.values.any { it.operationId == canonical.operationId }) {
                throw PendingReliableCommandConflictException("好友申请操作标识已用于其他请求")
            }
            check(contactDecisions.size < MAX_PENDING_SOCIAL_COMMANDS) {
                "待确认好友申请操作数量已达上限"
            }
            contactDecisions[canonical.token] = canonical
            canonical
        }
    }

    fun getContactDecisions(): List<PendingContactDecision> = cacheUseGate.use {
        synchronized(reliableCommandLock) { contactDecisions.values.toList() }
    }

    fun clearContactDecision(operationId: String): Boolean = cacheUseGate.use {
        synchronized(reliableCommandLock) {
            val token = contactDecisions.entries.firstOrNull {
                it.value.operationId == operationId
            }?.key ?: return@synchronized false
            contactDecisions.remove(token)
            true
        }
    }

    fun prepareInviteLinkCreation(candidate: PendingInviteLinkCreation): PendingInviteLinkCreation =
        cacheUseGate.use {
            synchronized(reliableCommandLock) {
                val canonical = candidate.requireCanonical()
                inviteCreations[canonical.chatId]?.let { existing ->
                    if (!existing.hasSamePayload(canonical)) {
                        throw PendingReliableCommandConflictException(
                            "该群已有另一项未确认邀请链接创建操作",
                        )
                    }
                    return@synchronized existing
                }
                if (inviteCreations.values.any { it.operationId == canonical.operationId }) {
                    throw PendingReliableCommandConflictException("邀请链接操作标识已用于其他请求")
                }
                check(inviteCreations.size < MAX_PENDING_SOCIAL_COMMANDS) {
                    "待确认邀请链接操作数量已达上限"
                }
                inviteCreations[canonical.chatId] = canonical
                canonical
            }
        }

    fun getInviteLinkCreations(): List<PendingInviteLinkCreation> = cacheUseGate.use {
        synchronized(reliableCommandLock) { inviteCreations.values.toList() }
    }

    fun clearInviteLinkCreation(operationId: String): Boolean = cacheUseGate.use {
        synchronized(reliableCommandLock) {
            val chatId = inviteCreations.entries.firstOrNull {
                it.value.operationId == operationId
            }?.key ?: return@synchronized false
            inviteCreations.remove(chatId)
            true
        }
    }

    fun getGroupBotCredentialCommand(): PendingGroupBotCredentialCommand? = cacheUseGate.use {
        synchronized(reliableCommandLock) { groupBotCredentialCommand }
    }

    fun prepareGroupBotCredentialCommand(
        command: PendingGroupBotCredentialCommand,
    ): PendingGroupBotCredentialCommand = cacheUseGate.use {
        synchronized(reliableCommandLock) {
            val canonical = command.requireCanonical()
            groupBotCredentialCommand?.let { existing ->
                if (!existing.hasSameIntent(canonical)) {
                    throw PendingGroupBotCredentialCommandConflictException()
                }
                return@synchronized existing
            }
            groupBotCredentialCommand = canonical
            canonical
        }
    }

    fun clearGroupBotCredentialCommand(operationId: String): Boolean = cacheUseGate.use {
        synchronized(reliableCommandLock) {
            if (groupBotCredentialCommand?.operationId != operationId) return@synchronized false
            groupBotCredentialCommand = null
            true
        }
    }

    fun prepareGroupFileCommand(candidate: PendingGroupFileCommand): PendingGroupFileCommand = cacheUseGate.use {
        synchronized(reliableCommandLock) {
            val canonical = canonicalGroupFileCommand(candidate)
            groupFileCommands[canonical.intentKey]?.let { existing ->
                if (!existing.hasSameIntentPayload(canonical)) {
                    throw PendingGroupFileCommandConflictException(
                        "该群文件位置已有另一项结果未确认的操作",
                    )
                }
                return@synchronized existing
            }
            if (groupFileCommands.values.any { it.commandId == canonical.commandId }) {
                throw PendingGroupFileCommandConflictException("群文件命令标识已用于其他请求")
            }
            check(groupFileCommands.size < MAX_PENDING_GROUP_FILE_COMMANDS) {
                "待确认群文件操作数量已达上限"
            }
            groupFileCommands[canonical.intentKey] = canonical
            canonical
        }
    }

    fun getGroupFileCommands(): List<PendingGroupFileCommand> = cacheUseGate.use {
        synchronized(reliableCommandLock) { groupFileCommands.values.toList() }
    }

    fun clearGroupFileCommand(commandId: String): Boolean = cacheUseGate.use {
        synchronized(reliableCommandLock) {
            val intentKey = groupFileCommands.entries.firstOrNull {
                it.value.commandId == commandId
            }?.key ?: return@synchronized false
            groupFileCommands.remove(intentKey)
            true
        }
    }

    fun prepareDocumentMoveCommand(
        candidate: PendingDocumentMoveCommand,
    ): PendingDocumentMoveCommand = cacheUseGate.use {
        synchronized(reliableCommandLock) {
            val canonical = candidate.requireCanonical()
            documentMoveCommands[canonical.targetKey]?.let { existing ->
                if (!existing.hasSamePayload(canonical)) {
                    throw PendingDocumentMoveCommandConflictException(
                        "该文档已有一项位置或名称变更等待确认",
                    )
                }
                return@synchronized existing
            }
            if (documentMoveCommands.values.any { it.operationId == canonical.operationId }) {
                throw PendingDocumentMoveCommandConflictException("文档变更操作标识已用于其他请求")
            }
            check(documentMoveCommands.size < MAX_PENDING_DOCUMENT_MOVE_COMMANDS) {
                "待确认文档位置或名称变更数量已达上限"
            }
            documentMoveCommands[canonical.targetKey] = canonical
            canonical
        }
    }

    fun getDocumentMoveCommands(): List<PendingDocumentMoveCommand> = cacheUseGate.use {
        synchronized(reliableCommandLock) { documentMoveCommands.values.toList() }
    }

    fun clearDocumentMoveCommand(operationId: String): Boolean = cacheUseGate.use {
        synchronized(reliableCommandLock) {
            val targetKey = documentMoveCommands.entries.firstOrNull {
                it.value.operationId == operationId
            }?.key ?: return@synchronized false
            documentMoveCommands.remove(targetKey)
            true
        }
    }

    private fun canonicalGroupFileCommand(command: PendingGroupFileCommand): PendingGroupFileCommand {
        val canonical = when (command.kind) {
            PendingGroupFileCommandKind.CREATE_FOLDER -> PendingGroupFileCommand.createFolder(
                command.commandId,
                command.entryId,
                command.chatId,
                command.parentId,
                checkNotNull(command.name),
                command.createdAt,
            )

            PendingGroupFileCommandKind.CREATE_FILE -> PendingGroupFileCommand.createFile(
                command.commandId,
                command.entryId,
                command.chatId,
                command.parentId,
                checkNotNull(command.name),
                checkNotNull(command.attachment),
                command.createdAt,
            )

            PendingGroupFileCommandKind.ADD_VERSION -> PendingGroupFileCommand.addVersion(
                command.commandId,
                command.chatId,
                command.entryId,
                checkNotNull(command.attachment),
                checkNotNull(command.expectedRevision),
                command.createdAt,
            )

            PendingGroupFileCommandKind.RENAME -> PendingGroupFileCommand.rename(
                command.commandId,
                command.chatId,
                command.parentId,
                command.entryId,
                checkNotNull(command.name),
                checkNotNull(command.expectedRevision),
                command.createdAt,
            )

            PendingGroupFileCommandKind.DELETE -> PendingGroupFileCommand.delete(
                command.commandId,
                command.chatId,
                command.parentId,
                command.entryId,
                checkNotNull(command.expectedRevision),
                command.createdAt,
            )
        }
        check(canonical == command) { "Pending group-file command is not canonical" }
        return canonical
    }

    private companion object {
        const val MAX_PENDING_SOCIAL_COMMANDS = 128
    }
}
