package com.virjar.tk.server.domain.task

import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.TaskChangedPayload
import com.virjar.tk.protocol.TaskDuePayload
import com.virjar.tk.protocol.model.*
import com.virjar.tk.server.domain.command.ReliableCommandConflictException
import com.virjar.tk.server.domain.command.ReliableCommandPolicy
import com.virjar.tk.server.domain.command.reliableCommandFingerprint
import com.virjar.tk.server.domain.transaction.PgUnitOfWork
import com.virjar.tk.server.domain.transaction.PgWriteScope

/** Tasks belong to their creator and current assignee; group/organization context grants no rights. */
class TaskService(
    private val repository: TaskRepository,
    private val unitOfWork: PgUnitOfWork,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun get(uid: String, taskId: String): WorkTask {
        TaskPolicy.requireId(taskId)
        return unitOfWork.read { readable(uid, repository.find(transaction, taskId)) }
    }

    suspend fun list(uid: String, view: Int, cursor: String?, limit: Int): TaskPage {
        require(view == TaskPolicy.VIEW_ASSIGNED || view == TaskPolicy.VIEW_CREATED) { "任务列表类型非法" }
        requireLimit(limit)
        val anchor = TaskCursor.list(uid, view, cursor)
        return unitOfWork.read {
            val rows = repository.list(transaction, uid, view, anchor, limit + 1)
            val items = rows.take(limit)
            TaskPage(items, if (rows.size > limit) TaskCursor.list(uid, view, items.last()) else null)
        }
    }

    suspend fun audit(uid: String, taskId: String, cursor: String?, limit: Int): TaskAuditPage {
        TaskPolicy.requireId(taskId)
        requireLimit(limit)
        val before = TaskCursor.audit(taskId, cursor)
        return unitOfWork.read {
            readable(uid, repository.find(transaction, taskId))
            val rows = repository.audits(transaction, taskId, before, limit + 1)
            val items = rows.take(limit)
            TaskAuditPage(items, if (rows.size > limit) "a1:$taskId:${items.last().revision}" else null)
        }
    }

    suspend fun mutate(uid: String, command: TaskCommand): TaskCommandResult {
        val fingerprint = fingerprint(uid, command)
        return unitOfWork.write {
            val activeUsers = repository.lockUsers(transaction, setOfNotNull(uid, command.draft?.assigneeUid))
            if (uid !in activeUsers) throw TaskAccessDeniedException()
            ReliableCommandPolicy.requireActiveIssuedAt(command.issuedAt, clock(), "任务操作")
            val receipt = repository.findReceipt(transaction, uid, command.operationId)
            if (receipt != null) {
                if (receipt.fingerprint != fingerprint || receipt.taskId != command.taskId) {
                    throw ReliableCommandConflictException("任务操作标识已用于不同请求")
                }
                // A committed ACK stays valid after reassignment; never return the old private body.
                return@write TaskCommandResult(repository.find(transaction, command.taskId)?.takeIf { it.readableBy(uid) })
            }
            repository.requireReceiptCapacity(transaction, uid, clock())
            val before = repository.lockTask(transaction, command.taskId)
            val now = maxOf(clock(), before?.updatedAt ?: 0L)
            ReliableCommandPolicy.requireActiveIssuedAt(command.issuedAt, now, "任务操作")
            val after = when (command.kind) {
                TaskCommand.CREATE -> {
                    if (before != null) throw TaskRevisionConflictException()
                    val draft = requireNotNull(command.draft)
                    requireAssignee(activeUsers, draft.assigneeUid)
                    requireContext(this, uid, draft)
                    WorkTask(command.taskId, uid, draft.assigneeUid, draft.title, draft.description,
                        TaskPolicy.TODO, draft.contextKind, draft.contextId, draft.dueAt, null, 1, now, now)
                }
                TaskCommand.EDIT -> {
                    val current = readable(uid, before)
                    requireRevision(current, command.expectedRevision)
                    if (uid != current.creatorUid) throw TaskAccessDeniedException()
                    val draft = requireNotNull(command.draft)
                    if (draft.assigneeUid != current.assigneeUid) requireAssignee(activeUsers, draft.assigneeUid)
                    if (draft.contextKind != current.contextKind || draft.contextId != current.contextId) requireContext(this, uid, draft)
                    current.copy(title = draft.title, description = draft.description, assigneeUid = draft.assigneeUid,
                        contextKind = draft.contextKind, contextId = draft.contextId, dueAt = draft.dueAt,
                        remindedAt = if (draft.dueAt != current.dueAt || draft.assigneeUid != current.assigneeUid) null else current.remindedAt,
                        revision = current.revision + 1, updatedAt = now)
                }
                TaskCommand.STATUS -> {
                    val current = readable(uid, before)
                    requireRevision(current, command.expectedRevision)
                    val status = requireNotNull(command.status)
                    // The assignee cannot undo the creator's cancellation decision.
                    if ((status == TaskPolicy.CANCELLED || current.status == TaskPolicy.CANCELLED) && uid != current.creatorUid) {
                        throw TaskAccessDeniedException()
                    }
                    val reopened = current.status in listOf(TaskPolicy.DONE, TaskPolicy.CANCELLED) &&
                        status in listOf(TaskPolicy.TODO, TaskPolicy.IN_PROGRESS)
                    current.copy(status = status,
                        remindedAt = if (reopened || status in listOf(TaskPolicy.DONE, TaskPolicy.CANCELLED)) null else current.remindedAt,
                        revision = current.revision + 1, updatedAt = now)
                }
                else -> error("Validated task command kind is unknown")
            }
            if (before == null) {
                if (!repository.insert(transaction, after)) throw TaskRevisionConflictException()
            } else repository.update(transaction, after)
            repository.appendAudit(transaction, TaskAudit(after.taskId, after.revision, uid,
                when (command.kind) { TaskCommand.CREATE -> TaskAudit.CREATED; TaskCommand.EDIT -> TaskAudit.EDITED; else -> TaskAudit.STATUS_CHANGED },
                now, before?.status, after.status, before?.assigneeUid, after.assigneeUid))
            repository.appendReceipt(transaction, TaskCommandReceipt(uid, command.operationId, command.taskId,
                fingerprint, command.issuedAt, ReliableCommandPolicy.expiresAt(command.issuedAt)))
            val recipients = setOfNotNull(after.creatorUid, after.assigneeUid, before?.assigneeUid)
            recipients.forEach { recipient -> appendEvent(recipient, NotifyType.TASK_CHANGED,
                TaskChangedPayload(after.taskId, after.revision,
                    if (after.readableBy(recipient)) TaskChangedPayload.UPDATED else TaskChangedPayload.REVOKED)) }
            TaskCommandResult(after)
        }
    }

    /** A restart needs no volatile timer state. The task lock orders completion/reassignment against delivery. */
    suspend fun remindDue(limit: Int = 100): Int {
        require(limit in 1..100)
        val candidates = unitOfWork.read { repository.dueCandidates(transaction, clock(), limit) }
        var delivered = 0
        for (taskId in candidates) {
            val sent = unitOfWork.write {
                val task = repository.lockTask(transaction, taskId) ?: return@write false
                val now = clock()
                val dueAt = task.dueAt
                if (task.status !in listOf(TaskPolicy.TODO, TaskPolicy.IN_PROGRESS) || dueAt == null ||
                    dueAt > now || task.remindedAt != null) return@write false
                val remindedAt = repository.markReminded(transaction, task.taskId, now)
                appendEvent(task.assigneeUid, NotifyType.TASK_DUE, TaskDuePayload(task.taskId, task.revision, remindedAt))
                true
            }
            if (sent) delivered++
        }
        return delivered
    }

    suspend fun cleanupReceipts(limit: Int = 1_000): Int {
        require(limit in 1..1_000)
        return unitOfWork.write { repository.cleanupReceipts(transaction, clock(), limit) }
    }

    private fun requireContext(scope: PgWriteScope, uid: String, draft: TaskDraft) {
        if (!repository.contextVisible(scope.transaction, uid, draft.contextKind, draft.contextId)) {
            throw TaskAccessDeniedException()
        }
    }
    private fun requireAssignee(activeUsers: Set<String>, assigneeUid: String) {
        require(assigneeUid in activeUsers) { "执行人必须是可用的用户账号" }
    }
    private fun requireRevision(task: WorkTask, expected: Long) {
        if (task.revision != expected) throw TaskRevisionConflictException()
    }
    private fun readable(uid: String, task: WorkTask?): WorkTask {
        val present = task ?: throw TaskNotFoundException()
        if (!present.readableBy(uid)) throw TaskAccessDeniedException()
        return present
    }
    private fun WorkTask.readableBy(uid: String) = uid == creatorUid || uid == assigneeUid
    private fun requireLimit(limit: Int) { require(limit in 1..TaskPolicy.MAX_PAGE_SIZE) { "任务分页大小非法" } }
    private fun fingerprint(uid: String, command: TaskCommand) = with(command) {
        reliableCommandFingerprint("task-command-v1", uid, operationId, issuedAt.toString(), taskId,
            expectedRevision.toString(), kind.toString(), status?.toString(), draft?.title, draft?.description,
            draft?.assigneeUid, draft?.contextKind?.toString(), draft?.contextId, draft?.dueAt?.toString())
    }
}

/** Cursors carry only immutable ordering coordinates; every page still applies current participants. */
private object TaskCursor {
    fun list(uid: String, view: Int, task: WorkTask) = "t1:$uid:$view:${task.createdAt}:${task.taskId}"
    fun list(uid: String, view: Int, cursor: String?): TaskPageAnchor? {
        TaskPolicy.requireCursor(cursor)
        if (cursor == null) return null
        val parts = cursor.split(':')
        require(parts.size == 5 && parts[0] == "t1" && parts[1] == uid && parts[2] == view.toString()) { "任务分页游标非法" }
        val createdAt = parts[3].toLongOrNull()
        require(createdAt != null && createdAt >= 0) { "任务分页游标非法" }
        TaskPolicy.requireId(parts[4])
        return TaskPageAnchor(createdAt, parts[4])
    }
    fun audit(taskId: String, cursor: String?): Long? {
        TaskPolicy.requireCursor(cursor)
        if (cursor == null) return null
        val parts = cursor.split(':')
        require(parts.size == 3 && parts[0] == "a1" && parts[1] == taskId) { "任务审计游标非法" }
        val revision = parts[2].toLongOrNull()
        require(revision != null && revision > 0) { "任务审计游标非法" }
        return revision
    }
}
