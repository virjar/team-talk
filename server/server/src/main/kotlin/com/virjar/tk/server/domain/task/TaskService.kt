package com.virjar.tk.server.domain.task

import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.TaskChangedPayload
import com.virjar.tk.protocol.TaskDuePayload
import com.virjar.tk.protocol.TaskStartedPayload
import com.virjar.tk.protocol.body.AttachmentPolicy
import com.virjar.tk.protocol.model.*
import com.virjar.tk.server.domain.attachment.AttachmentCatalog
import com.virjar.tk.server.domain.attachment.AttachmentLifecycleGate
import com.virjar.tk.server.domain.message.OfficeRefResolver
import com.virjar.tk.server.domain.command.ReliableCommandConflictException
import com.virjar.tk.server.domain.command.ReliableCommandPolicy
import com.virjar.tk.server.domain.command.reliableCommandFingerprint
import com.virjar.tk.server.domain.transaction.PgUnitOfWork
import com.virjar.tk.server.domain.transaction.PgWriteScope
import com.virjar.tk.server.domain.transaction.PgReadTransactionContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.util.UUID

/** Creator/assignee own mutations. Only explicitly shared group tasks grant current members read access. */
class TaskService(
    private val repository: TaskRepository,
    private val unitOfWork: PgUnitOfWork,
    private val clock: () -> Long = System::currentTimeMillis,
    private val attachments: AttachmentCatalog? = null,
    private val attachmentLifecycle: AttachmentLifecycleGate? = null,
    private val officeRefs: OfficeRefResolver? = null,
) {
    suspend fun get(uid: String, taskId: String): WorkTask {
        TaskPolicy.requireId(taskId)
        return unitOfWork.read { readableDetails(transaction, uid, taskId).task }
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
            readableDetails(transaction, uid, taskId)
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
            val extension = repository.extension(transaction, command.taskId)
            requireOptions(after, extension.options)
            recordChange(this, uid, before, after, extension,
                when (command.kind) { TaskCommand.CREATE -> TaskAudit.CREATED; TaskCommand.EDIT -> TaskAudit.EDITED; else -> TaskAudit.STATUS_CHANGED })
            repository.appendReceipt(transaction, TaskCommandReceipt(uid, command.operationId, command.taskId,
                fingerprint, command.issuedAt, ReliableCommandPolicy.expiresAt(command.issuedAt)))
            TaskCommandResult(after)
        }
    }

    suspend fun details(uid: String, taskId: String): TaskDetails {
        TaskPolicy.requireId(taskId)
        return unitOfWork.read { readableDetails(transaction, uid, taskId) }
    }

    suspend fun query(uid: String, query: TaskQuery, cursor: String?, limit: Int): TaskQueryPage {
        requireLimit(limit)
        val cursorPrefix = "q1:$uid:${query.scope}:${query.groupId.orEmpty()}:${query.openOnly}:${query.startedOnly}:"
        TaskPolicy.requireCursor(cursor)
        val anchor = cursor?.let {
            require(it.startsWith(cursorPrefix)) { "任务分页游标非法" }
            val parts = it.removePrefix(cursorPrefix).split(':')
            require(parts.size == 2)
            val created = requireNotNull(parts[0].toLongOrNull()).also { value -> require(value >= 0) }
            TaskPolicy.requireId(parts[1]); TaskPageAnchor(created, parts[1])
        }
        return unitOfWork.read {
            if (query.scope == TaskQuery.GROUP && !repository.contextVisible(transaction, uid, TaskPolicy.CONTEXT_GROUP, requireNotNull(query.groupId))) throw TaskAccessDeniedException()
            val now = clock()
            val rows = repository.query(transaction, uid, query, now, anchor, limit + 1)
            val items = rows.take(limit).map { taskDetails(transaction, it) }
            TaskQueryPage(items, if (rows.size > limit) items.last().task.let { "$cursorPrefix${it.createdAt}:${it.taskId}" } else null,
                repository.summary(transaction, uid, query, now))
        }
    }

    suspend fun history(uid: String, taskId: String, cursor: String?, limit: Int): TaskHistoryPage {
        TaskPolicy.requireId(taskId); requireLimit(limit)
        val before = TaskCursor.audit(taskId, cursor)
        return unitOfWork.read {
            readableDetails(transaction, uid, taskId)
            val rows = repository.audits(transaction, taskId, before, limit + 1)
            val audits = rows.take(limit)
            val deferrals = repository.deferrals(transaction, taskId, audits.map { it.revision })
            TaskHistoryPage(audits.map { TaskHistoryEntry(it, deferrals[it.revision]) },
                if (rows.size > limit) "a1:$taskId:${audits.last().revision}" else null)
        }
    }

    suspend fun modify(uid: String, command: TaskDetailsCommand): TaskDetailsCommandResult {
        val fingerprint = reliableCommandFingerprint("task-details-v1", uid, Json.encodeToString(command))
        // Committed acknowledgements are resolved before re-reading newly referenced documents.
        // Their permission may have changed since the first attempt.
        val replay = unitOfWork.write {
            if (uid !in repository.lockUsers(transaction, setOf(uid))) throw TaskAccessDeniedException()
            ReliableCommandPolicy.requireActiveIssuedAt(command.issuedAt, clock(), "任务操作")
            repository.findReceipt(transaction, uid, command.operationId)?.let {
                requireReceipt(it, fingerprint, command.taskId)
                TaskDetailsCommandResult(repository.find(transaction, command.taskId)?.takeIf { task -> canRead(transaction, uid, task) }?.let { task -> taskDetails(transaction, task) })
            }
        }
        if (replay != null) return replay
        val known = unitOfWork.read {
            repository.find(transaction, command.taskId)?.let {
                if (!it.readableBy(uid)) throw TaskAccessDeniedException()
                repository.extension(transaction, command.taskId).options
            } ?: TaskOptions()
        }
        val resolvedOptions = command.options?.let { options -> options.copy(documentRefs = options.documentRefs.map { declared ->
            known.documentRefs.find { it.spaceId == declared.spaceId && it.targetId == declared.targetId }
                ?: requireNotNull(officeRefs) { "任务文档引用不可用" }.resolve(uid, declared)
        }) }
        val paths = resolvedOptions?.attachments.orEmpty().map { AttachmentPolicy.canonicalPath(it.path) }
        suspend fun execute(): TaskDetailsCommandResult = unitOfWork.write {
            val activeUsers = repository.lockUsers(transaction, setOfNotNull(uid, command.draft?.assigneeUid))
            if (uid !in activeUsers) throw TaskAccessDeniedException()
            ReliableCommandPolicy.requireActiveIssuedAt(command.issuedAt, clock(), "任务操作")
            repository.findReceipt(transaction, uid, command.operationId)?.let {
                requireReceipt(it, fingerprint, command.taskId)
                return@write TaskDetailsCommandResult(repository.find(transaction, command.taskId)?.takeIf { task -> canRead(transaction, uid, task) }?.let { task -> taskDetails(transaction, task) })
            }
            repository.requireReceiptCapacity(transaction, uid, clock())
            val before = repository.lockTask(transaction, command.taskId)
            val previous = repository.extension(transaction, command.taskId)
            val now = maxOf(clock(), before?.updatedAt ?: 0L)
            ReliableCommandPolicy.requireActiveIssuedAt(command.issuedAt, now, "任务操作")
            val schedule = command.weeklyRule?.let(::TaskWeeklySchedule)
            val occurrence = schedule?.next(now)
            val options = resolvedOptions?.let { if (occurrence != null) it.copy(startsAt = occurrence.startsAt) else it } ?: previous.options
            val after = when (command.kind) {
                TaskDetailsCommand.CREATE -> {
                    if (before != null) throw TaskRevisionConflictException()
                    val draft = requireNotNull(command.draft)
                    requireAssignee(activeUsers, draft.assigneeUid); requireContext(this, uid, draft)
                    WorkTask(command.taskId, uid, draft.assigneeUid, draft.title, draft.description, TaskPolicy.TODO,
                        draft.contextKind, draft.contextId, occurrence?.dueAt ?: draft.dueAt, null, 1, now, now)
                }
                TaskDetailsCommand.EDIT -> {
                    val current = readable(uid, before); requireRevision(current, command.expectedRevision)
                    if (uid != current.creatorUid) throw TaskAccessDeniedException()
                    val draft = requireNotNull(command.draft)
                    if (draft.assigneeUid != current.assigneeUid) requireAssignee(activeUsers, draft.assigneeUid)
                    if (draft.contextKind != current.contextKind || draft.contextId != current.contextId || (!previous.options.shareToGroup && options.shareToGroup)) requireContext(this, uid, draft)
                    current.copy(title = draft.title, description = draft.description, assigneeUid = draft.assigneeUid,
                        contextKind = draft.contextKind, contextId = draft.contextId, dueAt = draft.dueAt,
                        remindedAt = if (draft.dueAt != current.dueAt || draft.assigneeUid != current.assigneeUid) null else current.remindedAt,
                        revision = current.revision + 1, updatedAt = now)
                }
                else -> {
                    val current = readable(uid, before); requireRevision(current, command.expectedRevision)
                    if (uid != current.assigneeUid) throw TaskAccessDeniedException()
                    require(current.isOpen()) { "已结束任务不能延期" }
                    val due = requireNotNull(command.deferDueAt)
                    val previousDue = current.dueAt
                    require(previousDue != null && due > previousDue && due > now) { "新截止时间必须晚于原截止时间与当前时间" }
                    current.copy(dueAt = due, remindedAt = null, revision = current.revision + 1, updatedAt = now)
                }
            }
            requireOptions(after, options)
            validateAttachments(uid, options, previous.options)
            var extension = previous.copy(options = options,
                seriesId = if (occurrence != null) after.taskId else previous.seriesId,
                occurrenceDate = occurrence?.date ?: previous.occurrenceDate)
            if (command.kind == TaskDetailsCommand.DEFER) {
                extension = extension.copy(metrics = extension.metrics.copy(deferralCount = Math.addExact(extension.metrics.deferralCount, 1), lastDeferredAt = now))
            }
            // Match the existing document/file binding boundary: once bound, an aborted write cannot
            // turn a published path back into an uploader-owned staging object.
            if (paths.isNotEmpty()) requireNotNull(attachments).markBusinessBound(paths)
            recordChange(this, uid, before, after, extension, if (before == null) TaskAudit.CREATED else TaskAudit.EDITED)
            if (command.kind == TaskDetailsCommand.DEFER) repository.appendDeferral(transaction,
                TaskDeferral(after.taskId, after.revision, uid, now, before?.dueAt, requireNotNull(after.dueAt), requireNotNull(command.reason)))
            if (schedule != null && occurrence != null) repository.saveSeries(transaction,
                TaskSeriesTemplate(TaskSeries(after.taskId, uid, 1, true, requireNotNull(command.weeklyRule), schedule.following(occurrence).startsAt),
                    requireNotNull(command.draft).copy(dueAt = null), options.copy(startsAt = null)))
            repository.appendReceipt(transaction, TaskCommandReceipt(uid, command.operationId, command.taskId, fingerprint,
                command.issuedAt, ReliableCommandPolicy.expiresAt(command.issuedAt)))
            TaskDetailsCommandResult(taskDetails(transaction, after))
        }
        return if (paths.isEmpty()) execute() else requireNotNull(attachmentLifecycle) { "任务附件不可用" }.withReferenceMutation(paths) { execute() }
    }

    suspend fun modifySeries(uid: String, command: TaskSeriesCommand): TaskSeries = unitOfWork.write {
        if (uid !in repository.lockUsers(transaction, setOf(uid))) throw TaskAccessDeniedException()
        ReliableCommandPolicy.requireActiveIssuedAt(command.issuedAt, clock(), "周期任务操作")
        val fingerprint = reliableCommandFingerprint("task-series-v1", uid, Json.encodeToString(command))
        repository.findReceipt(transaction, uid, command.operationId)?.let {
            requireReceipt(it, fingerprint, command.seriesId)
            return@write requireNotNull(repository.series(transaction, command.seriesId)).info
        }
        repository.requireReceiptCapacity(transaction, uid, clock())
        val anchor = repository.lockTask(transaction, command.seriesId) ?: throw TaskNotFoundException()
        val before = repository.series(transaction, command.seriesId) ?: throw TaskNotFoundException()
        if (uid != before.info.creatorUid) throw TaskAccessDeniedException()
        if (before.info.revision != command.expectedRevision) throw TaskRevisionConflictException()
        // Resuming starts with the next occurrence; disabled weeks are intentionally never backfilled.
        val after = before.copy(info = before.info.copy(enabled = command.enabled, revision = before.info.revision + 1,
            nextOccurrenceAt = if (command.enabled && !before.info.enabled)
                maxOf(before.info.nextOccurrenceAt, TaskWeeklySchedule(before.info.weeklyRule).next(clock()).startsAt)
                else before.info.nextOccurrenceAt))
        repository.saveSeries(transaction, after)
        repository.appendReceipt(transaction, TaskCommandReceipt(uid, command.operationId, command.seriesId, fingerprint,
            command.issuedAt, ReliableCommandPolicy.expiresAt(command.issuedAt)))
        notifyChange(this, anchor, anchor, repository.extension(transaction, anchor.taskId), repository.extension(transaction, anchor.taskId))
        after.info
    }

    /** The anchor lock and atomic next-date advance create at most one latest missed occurrence. */
    suspend fun generateWeekly(limit: Int = 100): Int {
        require(limit in 1..100)
        val candidates = unitOfWork.read { repository.seriesCandidates(transaction, clock(), limit) }
        var generated = 0
        for (candidate in candidates) {
            val created = unitOfWork.write {
                val active = repository.lockUsers(transaction, setOf(candidate.info.creatorUid, candidate.draft.assigneeUid))
                val anchor = repository.lockTask(transaction, candidate.info.seriesId) ?: return@write false
                val series = repository.series(transaction, candidate.info.seriesId) ?: return@write false
                val now = clock()
                if (!series.info.enabled || series.info.nextOccurrenceAt > now) return@write false
                if (series.info.creatorUid !in active || series.draft.assigneeUid !in active ||
                    !repository.contextVisible(transaction, series.info.creatorUid, series.draft.contextKind, series.draft.contextId)) {
                    repository.saveSeries(transaction, series.copy(info = series.info.copy(enabled = false, revision = series.info.revision + 1)))
                    notifyChange(this, anchor, anchor, repository.extension(transaction, anchor.taskId), repository.extension(transaction, anchor.taskId))
                    return@write false
                }
                val schedule = TaskWeeklySchedule(series.info.weeklyRule)
                val occurrence = schedule.latest(now)
                if (occurrence.startsAt < series.info.nextOccurrenceAt) return@write false
                val id = UUID.randomUUID().toString()
                val draft = series.draft
                val task = WorkTask(id, series.info.creatorUid, draft.assigneeUid, draft.title, draft.description, TaskPolicy.TODO,
                    draft.contextKind, draft.contextId, occurrence.dueAt, null, 1, now, now)
                recordChange(this, series.info.creatorUid, null, task,
                    TaskExtension(options = series.options.copy(startsAt = occurrence.startsAt), seriesId = series.info.seriesId, occurrenceDate = occurrence.date), TaskAudit.CREATED)
                repository.saveSeries(transaction, series.copy(info = series.info.copy(nextOccurrenceAt = schedule.following(occurrence).startsAt)))
                true
            }
            if (created) generated++
        }
        return generated
    }

    suspend fun remindStarted(limit: Int = 100): Int {
        require(limit in 1..100)
        val candidates = unitOfWork.read { repository.startCandidates(transaction, clock(), limit) }
        var delivered = 0
        for (id in candidates) {
            val sent = unitOfWork.write {
                val task = repository.lockTask(transaction, id) ?: return@write false
                val extension = repository.extension(transaction, id)
                val now = clock()
                val startsAt = extension.options.startsAt
                if (!task.isOpen() || startsAt == null || startsAt > now || extension.startRemindedAt != null) return@write false
                val marker = repository.markStarted(transaction, id, now)
                appendEvent(task.assigneeUid, NotifyType.TASK_STARTED, TaskStartedPayload(id, task.revision, marker))
                true
            }
            if (sent) delivered++
        }
        return delivered
    }

    suspend fun canReadAttachment(uid: String, path: String): Boolean = unitOfWork.read {
        repository.attachmentTasks(transaction, path).any { id -> repository.find(transaction, id)?.let { canRead(transaction, uid, it) } == true }
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

    private fun recordChange(scope: PgWriteScope, uid: String, before: WorkTask?, after: WorkTask, proposed: TaskExtension, action: Int) {
        val previous = repository.extension(scope.transaction, after.taskId)
        val reopened = before != null && !before.isOpen() && after.isOpen()
        val assigneeChanged = before != null && before.assigneeUid != after.assigneeUid
        var metrics = proposed.metrics
        if (before == null) metrics = TaskMetrics(after.dueAt, cycleStartedAt = proposed.options.startsAt ?: after.createdAt, historyKnown = true)
        else if (reopened) metrics = metrics.copy(cycleStartedAt = after.updatedAt, completedAt = null)
        else if (before.status != TaskPolicy.IN_PROGRESS && after.status == TaskPolicy.IN_PROGRESS && metrics.cycleStartedAt == null) metrics = metrics.copy(cycleStartedAt = after.updatedAt)
        if (after.status == TaskPolicy.DONE && before?.status != TaskPolicy.DONE) metrics = metrics.copy(completedAt = after.updatedAt)
        else if (after.status != TaskPolicy.DONE) metrics = metrics.copy(completedAt = null)
        val extension = proposed.copy(metrics = metrics,
            startRemindedAt = if (reopened || assigneeChanged || previous.options.startsAt != proposed.options.startsAt || !after.isOpen()) null else proposed.startRemindedAt)
        if (before == null) { if (!repository.insert(scope.transaction, after)) throw TaskRevisionConflictException() }
        else repository.update(scope.transaction, after)
        repository.saveExtension(scope.transaction, after.taskId, extension)
        repository.appendAudit(scope.transaction, TaskAudit(after.taskId, after.revision, uid, action, after.updatedAt,
            before?.status, after.status, before?.assigneeUid, after.assigneeUid))
        notifyChange(scope, before, after, previous, extension)
    }

    private fun notifyChange(scope: PgWriteScope, before: WorkTask?, after: WorkTask, previous: TaskExtension, extension: TaskExtension) {
        fun audience(task: WorkTask?, metadata: TaskExtension): Set<String> = if (task == null) emptySet() else
            setOf(task.creatorUid, task.assigneeUid) + if (metadata.options.shareToGroup && task.contextKind == TaskPolicy.CONTEXT_GROUP)
                repository.groupAudience(scope.transaction, task.contextId) else emptySet()
        val current = audience(after, extension)
        (current + audience(before, previous)).forEach { recipient -> scope.appendEvent(recipient, NotifyType.TASK_CHANGED,
            TaskChangedPayload(after.taskId, after.revision, if (recipient in current) TaskChangedPayload.UPDATED else TaskChangedPayload.REVOKED)) }
    }

    private fun taskDetails(transaction: PgReadTransactionContext, task: WorkTask): TaskDetails {
        val extension = repository.extension(transaction, task.taskId)
        return TaskDetails(task, extension.options, extension.metrics, extension.startRemindedAt,
            extension.seriesId?.let { repository.series(transaction, it)?.info }, extension.occurrenceDate)
    }
    private fun readableDetails(transaction: PgReadTransactionContext, uid: String, taskId: String): TaskDetails {
        val task = repository.find(transaction, taskId) ?: throw TaskNotFoundException()
        if (!canRead(transaction, uid, task)) throw TaskAccessDeniedException()
        return taskDetails(transaction, task)
    }
    private fun canRead(transaction: PgReadTransactionContext, uid: String, task: WorkTask): Boolean = task.readableBy(uid) ||
        (task.contextKind == TaskPolicy.CONTEXT_GROUP && repository.extension(transaction, task.taskId).options.shareToGroup &&
            repository.contextVisible(transaction, uid, TaskPolicy.CONTEXT_GROUP, task.contextId))
    private fun requireReceipt(receipt: TaskCommandReceipt, fingerprint: String, taskId: String) {
        if (receipt.fingerprint != fingerprint || receipt.taskId != taskId) throw ReliableCommandConflictException("任务操作标识已用于不同请求")
    }
    private fun requireOptions(task: WorkTask, options: TaskOptions) {
        require(!options.shareToGroup || task.contextKind == TaskPolicy.CONTEXT_GROUP) { "只有群任务可以共享给群成员" }
        val startsAt = options.startsAt
        val dueAt = task.dueAt
        require(startsAt == null || dueAt == null || startsAt <= dueAt) { "开始时间不能晚于截止时间" }
    }
    private fun validateAttachments(uid: String, options: TaskOptions, known: TaskOptions) {
        if (options.attachments.isEmpty()) return
        val catalog = requireNotNull(attachments) { "任务附件不可用" }
        options.attachments.forEach { declared ->
            val path = AttachmentPolicy.canonicalPath(declared.path)
            require(path == declared.path && catalog.getAttachment(path) == declared) { "任务附件描述与服务器不一致" }
            if (known.attachments.none { it == declared }) {
                require(catalog.getOwnerUid(path) == uid && catalog.isStaging(path)) { "只能绑定本人刚上传的附件" }
            }
        }
    }
    private fun WorkTask.isOpen() = status == TaskPolicy.TODO || status == TaskPolicy.IN_PROGRESS

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
