package com.virjar.tk.server.infra.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or

object WorkTasks : Table("work_tasks") {
    val taskId = varchar("task_id", 36)
    val creatorUid = varchar("creator_uid", 36).references(Users.uid)
    val assigneeUid = varchar("assignee_uid", 36).references(Users.uid)
    val title = varchar("title", com.virjar.tk.protocol.model.TaskPolicy.MAX_TITLE_LENGTH)
    val description = text("description")
    val status = integer("status")
    val contextKind = integer("context_kind")
    val contextId = varchar("context_id", 36)
    val dueAt = long("due_at").nullable()
    val remindedAt = long("reminded_at").nullable()
    /** Retained across reschedule/reopen so an equal or rolled-back wall clock cannot reuse an alert identity. */
    val lastReminderAt = long("last_reminder_at").nullable()
    val revision = long("revision")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(taskId)
    init {
        index("idx_tasks_created", false, creatorUid, createdAt, taskId)
        index("idx_tasks_assigned", false, assigneeUid, createdAt, taskId)
        index("idx_tasks_due", false, dueAt, taskId,
            filterCondition = { remindedAt.isNull() and dueAt.isNotNull() and (status inList listOf(1, 2)) })
        check("ck_tasks_revision") { revision greater 0L }
        check("ck_tasks_status") { status inList listOf(1, 2, 3, 4) }
        check("ck_tasks_context") { contextKind inList listOf(0, 1, 2) }
        check("ck_tasks_time") { (createdAt greaterEq 0L) and (updatedAt greaterEq createdAt) and
            (dueAt.isNull() or (dueAt greaterEq 0L)) and (remindedAt.isNull() or (remindedAt greaterEq 0L)) }
    }
}

object TaskAudits : Table("task_audits") {
    val taskId = varchar("task_id", 36).references(WorkTasks.taskId)
    val revision = long("revision")
    val actorUid = varchar("actor_uid", 36)
    val action = integer("action")
    val createdAt = long("created_at")
    val fromStatus = integer("from_status").nullable()
    val toStatus = integer("to_status").nullable()
    val previousAssigneeUid = varchar("previous_assignee_uid", 36).nullable()
    val assigneeUid = varchar("assignee_uid", 36).nullable()
    override val primaryKey = PrimaryKey(taskId, revision)
}

object TaskCommands : Table("task_commands") {
    val actorUid = varchar("actor_uid", 36).references(Users.uid)
    val operationId = varchar("operation_id", 36)
    val taskId = varchar("task_id", 36).references(WorkTasks.taskId)
    val fingerprint = varchar("fingerprint", 64)
    val issuedAt = long("issued_at")
    val expiresAt = long("expires_at").index("idx_task_command_expiry")
    override val primaryKey = PrimaryKey(actorUid, operationId)
}
