package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.protocol.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
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
    private val json = Json { ignoreUnknownKeys = true }
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
        val start = TaskExtensions.select(TaskExtensions.lastStartReminderAt).where { TaskExtensions.taskId eq taskId }.singleOrNull()?.get(TaskExtensions.lastStartReminderAt)
        val timestamp = maxOf(now, previous?.let { Math.addExact(it, 1L) } ?: now, start?.let { Math.addExact(it, 1L) } ?: now)
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

    override fun extension(transaction: PgReadTransactionContext, taskId: String): TaskExtension {
        transaction.requireExposedReadTransaction()
        return TaskExtensions.selectAll().where { TaskExtensions.taskId eq taskId }.singleOrNull()?.let { row ->
            with(TaskExtensions) { TaskExtension(json.decodeFromString<TaskOptions>(row[options]),
                TaskMetrics(row[originalDueAt], row[deferralCount], row[lastDeferredAt], row[cycleStartedAt], row[completedAt], row[historyKnown]),
                row[startRemindedAt], row[seriesId], row[occurrenceDate]) }
        } ?: TaskExtension()
    }

    override fun saveExtension(transaction: PgWriteTransactionContext, taskId: String, extension: TaskExtension) {
        transaction.requireExposedTransaction()
        fun UpdateBuilder<*>.writeExtension() = with(TaskExtensions) {
            this@writeExtension[options] = json.encodeToString(extension.options)
            this@writeExtension[startsAt] = extension.options.startsAt; this@writeExtension[shareToGroup] = extension.options.shareToGroup
            this@writeExtension[originalDueAt] = extension.metrics.originalDueAt; this@writeExtension[deferralCount] = extension.metrics.deferralCount
            this@writeExtension[lastDeferredAt] = extension.metrics.lastDeferredAt; this@writeExtension[cycleStartedAt] = extension.metrics.cycleStartedAt
            this@writeExtension[completedAt] = extension.metrics.completedAt; this@writeExtension[historyKnown] = extension.metrics.historyKnown
            this@writeExtension[startRemindedAt] = extension.startRemindedAt
            this@writeExtension[seriesId] = extension.seriesId; this@writeExtension[occurrenceDate] = extension.occurrenceDate
        }
        if (TaskExtensions.update({ TaskExtensions.taskId eq taskId }) { it.writeExtension() } == 0) {
            TaskExtensions.insert { it[TaskExtensions.taskId] = taskId; it.writeExtension() }
        }
        TaskAttachmentPaths.deleteWhere { TaskAttachmentPaths.taskId eq taskId }
        extension.options.attachments.forEach { attachment -> TaskAttachmentPaths.insert {
            it[TaskAttachmentPaths.taskId] = taskId; it[path] = attachment.path
        } }
    }

    private fun queryJoin() = WorkTasks.join(TaskExtensions, JoinType.LEFT, WorkTasks.taskId, TaskExtensions.taskId)
    private fun queryCondition(uid: String, query: TaskQuery, now: Long): Op<Boolean> = SqlExpressionBuilder.run {
        val owner = when (query.scope) {
            TaskQuery.ASSIGNED -> WorkTasks.assigneeUid eq uid
            TaskQuery.CREATED -> WorkTasks.creatorUid eq uid
            else -> (WorkTasks.contextKind eq TaskPolicy.CONTEXT_GROUP) and (WorkTasks.contextId eq requireNotNull(query.groupId)) and
                (TaskExtensions.shareToGroup eq true)
        }
        owner and (if (query.openOnly) WorkTasks.status inList listOf(TaskPolicy.TODO, TaskPolicy.IN_PROGRESS) else Op.TRUE) and
            (if (query.startedOnly) (TaskExtensions.startsAt lessEq now) or
                (TaskExtensions.startsAt.isNull() and (WorkTasks.createdAt lessEq now)) else Op.TRUE)
    }

    override fun query(transaction: PgReadTransactionContext, uid: String, query: TaskQuery, now: Long, before: TaskPageAnchor?, limit: Int): List<WorkTask> {
        transaction.requireExposedReadTransaction()
        return queryJoin().selectAll().where { queryCondition(uid, query, now) and
            (before?.let { (WorkTasks.createdAt less it.createdAt) or ((WorkTasks.createdAt eq it.createdAt) and (WorkTasks.taskId greater it.taskId)) } ?: Op.TRUE)
        }.orderBy(WorkTasks.createdAt to SortOrder.DESC, WorkTasks.taskId to SortOrder.ASC).limit(limit).map { it.task() }
    }

    override fun summary(transaction: PgReadTransactionContext, uid: String, query: TaskQuery, now: Long): TaskSummary {
        transaction.requireExposedReadTransaction()
        val base = queryCondition(uid, query, now)
        val open = SqlExpressionBuilder.run { WorkTasks.status inList listOf(TaskPolicy.TODO, TaskPolicy.IN_PROGRESS) }
        fun count(extra: Op<Boolean> = Op.TRUE) = queryJoin().select(WorkTasks.taskId).where { base and extra }.count()
        val minDue = WorkTasks.dueAt.min()
        val completed = SqlExpressionBuilder.run { WorkTasks.status eq TaskPolicy.DONE }
        val average = SqlExpressionBuilder.run {
            val duration = TaskExtensions.completedAt - TaskExtensions.cycleStartedAt
            Case().When(duration less 0L, LiteralOp<Long?>(LongColumnType(), 0L)).Else(duration).avg()
        }
        return TaskSummary(count(), count(open), count(SqlExpressionBuilder.run { open and (WorkTasks.dueAt less now) }),
            queryJoin().select(minDue).where { base and open }.single()[minDue], count(completed),
            queryJoin().select(average).where { base and completed }.single()[average]?.toLong()?.coerceAtLeast(0))
    }

    override fun groupAudience(transaction: PgReadTransactionContext, groupId: String): Set<String> {
        transaction.requireExposedReadTransaction()
        return GroupMembers.join(Chats, JoinType.INNER, GroupMembers.chatId, Chats.chatId)
            .join(GroupChats, JoinType.INNER, Chats.chatId, GroupChats.chatId)
            .join(OrganizationManagedChatProjections, JoinType.LEFT, Chats.chatId, OrganizationManagedChatProjections.chatId)
            .select(GroupMembers.uid).where {
                (Chats.chatId eq groupId) and (Chats.chatType eq 2) and (Chats.status eq 1) and (GroupMembers.status eq 1) and
                    (OrganizationManagedChatProjections.chatId.isNull() or ((OrganizationManagedChatProjections.desiredActive eq true) and
                        (OrganizationManagedChatProjections.desiredRevision eq OrganizationManagedChatProjections.appliedRevision) and
                        OrganizationManagedChatProjections.lastFailure.isNull()))
            }.mapTo(linkedSetOf()) { it[GroupMembers.uid] }
    }

    override fun appendDeferral(transaction: PgWriteTransactionContext, deferral: TaskDeferral) {
        transaction.requireExposedTransaction()
        TaskDeferrals.insert { it[taskId] = deferral.taskId; it[revision] = deferral.revision; it[actorUid] = deferral.actorUid
            it[createdAt] = deferral.createdAt; it[previousDueAt] = deferral.previousDueAt; it[newDueAt] = deferral.newDueAt; it[reason] = deferral.reason }
    }

    override fun deferrals(transaction: PgReadTransactionContext, taskId: String, revisions: List<Long>): Map<Long, TaskDeferral> {
        transaction.requireExposedReadTransaction()
        if (revisions.isEmpty()) return emptyMap()
        return TaskDeferrals.selectAll().where { (TaskDeferrals.taskId eq taskId) and (TaskDeferrals.revision inList revisions) }
            .associate { row -> with(TaskDeferrals) { row[revision] to TaskDeferral(taskId, row[revision], row[actorUid], row[createdAt], row[previousDueAt], row[newDueAt], row[reason]) } }
    }

    override fun startCandidates(transaction: PgReadTransactionContext, now: Long, limit: Int): List<String> {
        transaction.requireExposedReadTransaction()
        return queryJoin().select(WorkTasks.taskId).where { (WorkTasks.status inList listOf(1, 2)) and
            (TaskExtensions.startsAt lessEq now) and TaskExtensions.startRemindedAt.isNull() }
            .orderBy(TaskExtensions.startsAt to SortOrder.ASC, WorkTasks.taskId to SortOrder.ASC).limit(limit).map { it[WorkTasks.taskId] }
    }

    override fun markStarted(transaction: PgWriteTransactionContext, taskId: String, now: Long): Long {
        transaction.requireExposedTransaction()
        val previous = TaskExtensions.select(TaskExtensions.lastStartReminderAt).where { TaskExtensions.taskId eq taskId }.single()[TaskExtensions.lastStartReminderAt]
        val due = WorkTasks.select(WorkTasks.lastReminderAt).where { WorkTasks.taskId eq taskId }.single()[WorkTasks.lastReminderAt]
        val marker = maxOf(now, previous?.let { Math.addExact(it, 1L) } ?: now, due?.let { Math.addExact(it, 1L) } ?: now)
        check(TaskExtensions.update({ TaskExtensions.taskId eq taskId }) { it[startRemindedAt] = marker; it[lastStartReminderAt] = marker } == 1)
        return marker
    }

    override fun series(transaction: PgReadTransactionContext, seriesId: String): TaskSeriesTemplate? {
        transaction.requireExposedReadTransaction()
        return TaskSeriesTemplates.select(TaskSeriesTemplates.payload).where { TaskSeriesTemplates.seriesId eq seriesId }.singleOrNull()
            ?.let { json.decodeFromString<TaskSeriesTemplate>(it[TaskSeriesTemplates.payload]) }
    }

    override fun saveSeries(transaction: PgWriteTransactionContext, series: TaskSeriesTemplate) {
        transaction.requireExposedTransaction()
        fun UpdateBuilder<*>.writeSeries() { this[TaskSeriesTemplates.payload] = json.encodeToString(series)
            this[TaskSeriesTemplates.enabled] = series.info.enabled; this[TaskSeriesTemplates.nextOccurrenceAt] = series.info.nextOccurrenceAt }
        if (TaskSeriesTemplates.update({ TaskSeriesTemplates.seriesId eq series.info.seriesId }) { it.writeSeries() } == 0) {
            TaskSeriesTemplates.insert { it[seriesId] = series.info.seriesId; it.writeSeries() }
            series.options.attachments.forEach { attachment -> TaskSeriesAttachmentPaths.insert { it[seriesId] = series.info.seriesId; it[path] = attachment.path } }
        }
    }

    override fun seriesCandidates(transaction: PgReadTransactionContext, now: Long, limit: Int): List<TaskSeriesTemplate> {
        transaction.requireExposedReadTransaction()
        return TaskSeriesTemplates.select(TaskSeriesTemplates.payload).where { (TaskSeriesTemplates.enabled eq true) and (TaskSeriesTemplates.nextOccurrenceAt lessEq now) }
            .orderBy(TaskSeriesTemplates.nextOccurrenceAt, SortOrder.ASC).limit(limit).map { json.decodeFromString(it[TaskSeriesTemplates.payload]) }
    }

    override fun attachmentTasks(transaction: PgReadTransactionContext, path: String): List<String> {
        transaction.requireExposedReadTransaction()
        return TaskAttachmentPaths.select(TaskAttachmentPaths.taskId).where { TaskAttachmentPaths.path eq path }.map { it[TaskAttachmentPaths.taskId] }
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
