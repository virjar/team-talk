package com.virjar.tk.server.infra.db

import org.jetbrains.exposed.sql.Table

/** Optional extension rows leave all released task facts and wire records intact. */
object TaskExtensions : Table("task_extensions") {
    val taskId = varchar("task_id", 36).references(WorkTasks.taskId)
    val options = text("options")
    val startsAt = long("starts_at").nullable().index("idx_task_starts")
    val shareToGroup = bool("share_to_group")
    val originalDueAt = long("original_due_at").nullable()
    val deferralCount = integer("deferral_count")
    val lastDeferredAt = long("last_deferred_at").nullable()
    val cycleStartedAt = long("cycle_started_at").nullable()
    val completedAt = long("completed_at").nullable()
    val historyKnown = bool("history_known")
    val startRemindedAt = long("start_reminded_at").nullable()
    val lastStartReminderAt = long("last_start_reminder_at").nullable()
    val seriesId = varchar("series_id", 36).nullable()
    val occurrenceDate = varchar("occurrence_date", 10).nullable()
    override val primaryKey = PrimaryKey(taskId)
    init { uniqueIndex("idx_task_series_occurrence", seriesId, occurrenceDate) }
}

object TaskAttachmentPaths : Table("task_attachment_paths") {
    val taskId = varchar("task_id", 36).references(WorkTasks.taskId)
    val path = varchar("path", 500)
    override val primaryKey = PrimaryKey(taskId, path)
    init { index("idx_task_attachment_path", false, path) }
}

object TaskDeferrals : Table("task_deferrals") {
    val taskId = varchar("task_id", 36).references(WorkTasks.taskId)
    val revision = long("revision")
    val actorUid = varchar("actor_uid", 36)
    val createdAt = long("created_at")
    val previousDueAt = long("previous_due_at").nullable()
    val newDueAt = long("new_due_at")
    val reason = text("reason")
    override val primaryKey = PrimaryKey(taskId, revision)
}

/** The first task is also the stable receipt/locking anchor for the series. */
object TaskSeriesTemplates : Table("task_series") {
    val seriesId = varchar("series_id", 36).references(WorkTasks.taskId)
    val payload = text("payload")
    val enabled = bool("enabled")
    val nextOccurrenceAt = long("next_occurrence_at").index("idx_task_series_next")
    override val primaryKey = PrimaryKey(seriesId)
}

object TaskSeriesAttachmentPaths : Table("task_series_attachment_paths") {
    val seriesId = varchar("series_id", 36).references(TaskSeriesTemplates.seriesId)
    val path = varchar("path", 500)
    override val primaryKey = PrimaryKey(seriesId, path)
    init { index("idx_task_series_attachment_path", false, path) }
}
