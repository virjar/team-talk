package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.domain.attachment.TaskAttachmentReferences
import com.virjar.tk.server.domain.task.TaskService
import com.virjar.tk.server.infra.db.TaskAttachmentPaths
import com.virjar.tk.server.infra.db.TaskSeriesAttachmentPaths
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

class ExposedTaskAttachmentReferences(private val database: Database, private val tasks: TaskService) : TaskAttachmentReferences {
    override fun getReferencedPaths(paths: Set<String>): Set<String> {
        if (paths.isEmpty()) return emptySet()
        return transaction(database) {
            TaskAttachmentPaths.selectAll().where { TaskAttachmentPaths.path inList paths }.mapTo(linkedSetOf()) { it[TaskAttachmentPaths.path] } +
                TaskSeriesAttachmentPaths.selectAll().where { TaskSeriesAttachmentPaths.path inList paths }.map { it[TaskSeriesAttachmentPaths.path] }
        }
    }
    override suspend fun canRead(uid: String, path: String): Boolean = tasks.canReadAttachment(uid, path)
}
