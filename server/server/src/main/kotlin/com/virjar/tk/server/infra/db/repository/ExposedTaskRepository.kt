package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.protocol.model.TaskAudit
import com.virjar.tk.protocol.model.UserRole
import com.virjar.tk.protocol.model.WorkTask
import com.virjar.tk.server.domain.command.ReliableCommandCapacityException
import com.virjar.tk.server.domain.command.ReliableCommandPolicy
import com.virjar.tk.server.domain.task.*
import com.virjar.tk.server.domain.transaction.PgReadTransactionContext
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.infra.db.*
import com.virjar.tk.server.infra.db.ReliableCommandReceiptWindows
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.statements.UpdateBuilder

class ExposedTaskRepository : TaskRepository {
    override fun lockUsers(transaction: PgWriteTransactionContext, uids: Set<String>): Set<String> {
        transaction.requireExposedTransaction()
        return Users.selectAll().where { Users.uid inList uids.sorted() }
            .orderBy(Users.uid, SortOrder.ASC).forUpdate().filter {
                it[Users.status] == 1 && it[Users.role] == UserRole.HUMAN
            }.mapTo(mutableSetOf()) { it[Users.uid] }
    }

    override fun find(transaction: PgReadTransactionContext, taskId: String): WorkTask? {
        transaction.requireExposedReadTransaction()
        return WorkTasks.selectAll().where { WorkTasks.taskId eq taskId }.singleOrNull()?.task()
    }

    override fun lockTask(transaction: PgWriteTransactionContext, taskId: String): WorkTask? {
        transaction.requireExposedTransaction()
        return WorkTasks.selectAll().where { WorkTasks.taskId eq taskId }.forUpdate().singleOrNull()?.task()
    }

    override fun contextVisible(transaction: PgReadTransactionContext, actorUid: String, kind: Int, contextId: String): Boolean {
        transaction.requireExposedReadTransaction()
        // Context is metadata, never task authorization. Each check uses one statement's current
        // snapshot; leaving a group after this decision cannot revoke an independently owned task.
        return when (kind) {
            0 -> contextId.isEmpty()
            1 -> {
                val membership = GroupMembers.join(Chats, JoinType.INNER, GroupMembers.chatId, Chats.chatId)
                    .join(GroupChats, JoinType.INNER, Chats.chatId, GroupChats.chatId)
                    .join(OrganizationManagedChatProjections, JoinType.LEFT, Chats.chatId, OrganizationManagedChatProjections.chatId)
                membership.select(GroupMembers.uid).where {
                    (Chats.chatId eq contextId) and (Chats.chatType eq 2) and (Chats.status eq 1) and
                        (GroupMembers.uid eq actorUid) and (GroupMembers.status eq 1) and
                        (OrganizationManagedChatProjections.chatId.isNull() or (
                            (OrganizationManagedChatProjections.desiredActive eq true) and
                                (OrganizationManagedChatProjections.desiredRevision eq OrganizationManagedChatProjections.appliedRevision) and
                                OrganizationManagedChatProjections.lastFailure.isNull()))
                }.limit(1).any()
            }
            2 -> {
                val target = OrganizationUnits.alias("task_context_unit")
                OrganizationMemberships.join(OrganizationUnits, JoinType.INNER, OrganizationMemberships.unitId, OrganizationUnits.unitId)
                    .join(target, JoinType.INNER, additionalConstraint = { target[OrganizationUnits.unitId] eq contextId })
                    .select(OrganizationMemberships.uid).where {
                        (OrganizationMemberships.uid eq actorUid) and (OrganizationUnits.status eq 1) and
                            (target[OrganizationUnits.status] eq 1)
                    }.limit(1).any()
            }
            else -> false
        }
    }

    override fun insert(transaction: PgWriteTransactionContext, task: WorkTask): Boolean {
        transaction.requireExposedTransaction()
        return WorkTasks.insertIgnore { it[taskId] = task.taskId; it[creatorUid] = task.creatorUid; it[createdAt] = task.createdAt; it.write(task) }.insertedCount == 1
    }

    override fun update(transaction: PgWriteTransactionContext, task: WorkTask) {
        transaction.requireExposedTransaction()
        check(WorkTasks.update({ WorkTasks.taskId eq task.taskId }) { it.write(task) } == 1)
    }

    override fun list(transaction: PgReadTransactionContext, uid: String, view: Int, before: TaskPageAnchor?, limit: Int): List<WorkTask> {
        transaction.requireExposedReadTransaction()
        return WorkTasks.selectAll().where {
            (if (view == 1) WorkTasks.assigneeUid eq uid else WorkTasks.creatorUid eq uid) and
                (before?.let { (WorkTasks.createdAt less it.createdAt) or
                    ((WorkTasks.createdAt eq it.createdAt) and (WorkTasks.taskId greater it.taskId)) } ?: Op.TRUE)
        }.orderBy(WorkTasks.createdAt to SortOrder.DESC, WorkTasks.taskId to SortOrder.ASC).limit(limit).map { it.task() }
    }

    override fun appendAudit(transaction: PgWriteTransactionContext, audit: TaskAudit) {
        transaction.requireExposedTransaction()
        TaskAudits.insert {
            it[taskId] = audit.taskId; it[revision] = audit.revision; it[actorUid] = audit.actorUid
            it[action] = audit.action; it[createdAt] = audit.createdAt; it[fromStatus] = audit.fromStatus
            it[toStatus] = audit.toStatus; it[previousAssigneeUid] = audit.previousAssigneeUid; it[assigneeUid] = audit.assigneeUid
        }
    }

    override fun audits(transaction: PgReadTransactionContext, taskId: String, beforeRevision: Long?, limit: Int): List<TaskAudit> {
        transaction.requireExposedReadTransaction()
        return TaskAudits.selectAll().where { (TaskAudits.taskId eq taskId) and
            (beforeRevision?.let { TaskAudits.revision less it } ?: Op.TRUE) }
            .orderBy(TaskAudits.revision, SortOrder.DESC).limit(limit).map { with(TaskAudits) {
                TaskAudit(it[TaskAudits.taskId], it[revision], it[actorUid], it[action], it[createdAt],
                    it[fromStatus], it[toStatus], it[previousAssigneeUid], it[assigneeUid])
            } }
    }

    override fun findReceipt(transaction: PgReadTransactionContext, actorUid: String, operationId: String): TaskCommandReceipt? {
        transaction.requireExposedReadTransaction()
        return TaskCommands.selectAll().where { (TaskCommands.actorUid eq actorUid) and (TaskCommands.operationId eq operationId) }
            .singleOrNull()?.let { row -> with(TaskCommands) {
                check(row[expiresAt] == ReliableCommandPolicy.expiresAt(row[issuedAt]))
                TaskCommandReceipt(actorUid, operationId, row[taskId], row[fingerprint], row[issuedAt], row[expiresAt])
            } }
    }

    override fun requireReceiptCapacity(transaction: PgWriteTransactionContext, actorUid: String, now: Long) {
        transaction.requireExposedTransaction()
        ReliableCommandReceiptWindows.require(
            TaskCommands, TaskCommands.actorUid, TaskCommands.expiresAt, actorUid, now,
            failureMessage = "任务可靠重试窗口已满",
        )
    }

    override fun appendReceipt(transaction: PgWriteTransactionContext, receipt: TaskCommandReceipt) {
        transaction.requireExposedTransaction()
        TaskCommands.insert {
            it[actorUid] = receipt.actorUid; it[operationId] = receipt.operationId; it[taskId] = receipt.taskId
            it[fingerprint] = receipt.fingerprint; it[issuedAt] = receipt.issuedAt; it[expiresAt] = receipt.expiresAt
        }
    }

    override fun dueCandidates(transaction: PgReadTransactionContext, now: Long, limit: Int): List<String> {
        transaction.requireExposedReadTransaction()
        return WorkTasks.select(WorkTasks.taskId).where { (WorkTasks.status inList listOf(1, 2)) and
            WorkTasks.remindedAt.isNull() and (WorkTasks.dueAt lessEq now) }
            .orderBy(WorkTasks.dueAt to SortOrder.ASC, WorkTasks.taskId to SortOrder.ASC).limit(limit).map { it[WorkTasks.taskId] }
    }

    override fun markReminded(transaction: PgWriteTransactionContext, taskId: String, now: Long): Long {
        transaction.requireExposedTransaction()
        val previous = WorkTasks.select(WorkTasks.lastReminderAt).where { WorkTasks.taskId eq taskId }.single()[WorkTasks.lastReminderAt]
        val timestamp = maxOf(now, previous?.let { Math.addExact(it, 1L) } ?: now)
        check(WorkTasks.update({ WorkTasks.taskId eq taskId }) {
            it[remindedAt] = timestamp
            it[lastReminderAt] = timestamp
        } == 1)
        return timestamp
    }

    override fun cleanupReceipts(transaction: PgWriteTransactionContext, now: Long, limit: Int): Int {
        transaction.requireExposedTransaction()
        val expired = TaskCommands.select(TaskCommands.actorUid, TaskCommands.operationId).where { TaskCommands.expiresAt less now }
            .orderBy(TaskCommands.expiresAt, SortOrder.ASC).limit(limit).toList()
        return expired.sumOf { row -> TaskCommands.deleteWhere {
            (actorUid eq row[actorUid]) and (operationId eq row[operationId]) and (expiresAt less now)
        } }
    }

    private fun UpdateBuilder<*>.write(task: WorkTask) = with(WorkTasks) {
        this@write[assigneeUid] = task.assigneeUid; this@write[title] = task.title; this@write[description] = task.description
        this@write[status] = task.status; this@write[contextKind] = task.contextKind; this@write[contextId] = task.contextId
        this@write[dueAt] = task.dueAt; this@write[remindedAt] = task.remindedAt
        this@write[revision] = task.revision; this@write[updatedAt] = task.updatedAt
    }

    private fun ResultRow.task() = with(WorkTasks) {
        WorkTask(this@task[taskId], this@task[creatorUid], this@task[assigneeUid], this@task[title], this@task[description],
            this@task[status], this@task[contextKind], this@task[contextId], this@task[dueAt], this@task[remindedAt],
            this@task[revision], this@task[createdAt], this@task[updatedAt])
    }
}
