package com.virjar.tk.server.domain.task

import com.virjar.tk.protocol.model.TaskAudit
import com.virjar.tk.protocol.model.WorkTask
import com.virjar.tk.server.domain.transaction.PgReadTransactionContext
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext

/** PostgreSQL owns task participants, command receipts, audit history and reminder delivery markers. */
interface TaskRepository {
    /** Sorted user locks precede task locks; this also serializes command identities per actor. */
    fun lockUsers(transaction: PgWriteTransactionContext, uids: Set<String>): Set<String>
    fun find(transaction: PgReadTransactionContext, taskId: String): WorkTask?
    fun lockTask(transaction: PgWriteTransactionContext, taskId: String): WorkTask?
    fun contextVisible(transaction: PgReadTransactionContext, actorUid: String, kind: Int, contextId: String): Boolean
    fun insert(transaction: PgWriteTransactionContext, task: WorkTask): Boolean
    fun update(transaction: PgWriteTransactionContext, task: WorkTask)
    fun list(transaction: PgReadTransactionContext, uid: String, view: Int, before: TaskPageAnchor?, limit: Int): List<WorkTask>
    fun appendAudit(transaction: PgWriteTransactionContext, audit: TaskAudit)
    fun audits(transaction: PgReadTransactionContext, taskId: String, beforeRevision: Long?, limit: Int): List<TaskAudit>
    fun findReceipt(transaction: PgReadTransactionContext, actorUid: String, operationId: String): TaskCommandReceipt?
    fun requireReceiptCapacity(transaction: PgWriteTransactionContext, actorUid: String, now: Long)
    fun appendReceipt(transaction: PgWriteTransactionContext, receipt: TaskCommandReceipt)
    fun dueCandidates(transaction: PgReadTransactionContext, now: Long, limit: Int): List<String>
    /** Caller holds the task lock. The returned delivery timestamp is unique across reminder resets. */
    fun markReminded(transaction: PgWriteTransactionContext, taskId: String, now: Long): Long
    fun cleanupReceipts(transaction: PgWriteTransactionContext, now: Long, limit: Int): Int
}

data class TaskPageAnchor(val createdAt: Long, val taskId: String)
data class TaskCommandReceipt(val actorUid: String, val operationId: String, val taskId: String,
    val fingerprint: String, val issuedAt: Long, val expiresAt: Long)

class TaskAccessDeniedException : IllegalArgumentException("没有任务访问权限")
class TaskNotFoundException : IllegalArgumentException("任务不存在")
class TaskRevisionConflictException : IllegalArgumentException("任务已变更，请查看最新内容后重试")
