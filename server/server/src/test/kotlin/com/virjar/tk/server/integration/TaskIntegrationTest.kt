package com.virjar.tk.server.integration

import com.virjar.tk.protocol.*
import com.virjar.tk.protocol.body.TaskRefBody
import com.virjar.tk.protocol.body.plainTextContentOrNull
import com.virjar.tk.protocol.model.*
import com.virjar.tk.protocol.payload.InvokePayload
import com.virjar.tk.protocol.rpc.gen.TaskRpcContract
import com.virjar.tk.server.domain.command.*
import com.virjar.tk.server.domain.task.*
import com.virjar.tk.server.domain.transaction.PgUnitOfWork
import com.virjar.tk.server.infra.db.*
import com.virjar.tk.server.infra.db.repository.ExposedTaskRepository
import com.virjar.tk.server.protocol.dispatcher.RpcDispatcher
import com.virjar.tk.server.protocol.rpc.MessageRpcImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

/** Real isolated PostgreSQL, durable user streams and Rocks/Lucene message adapters. */
class TaskIntegrationTest {
    companion object { @JvmField @RegisterExtension val ext = IntegrationTestExtension() }
    private val ctx get() = ext.env
    private val repository = ExposedTaskRepository()
    private val now = AtomicLong(System.currentTimeMillis())
    private fun service(uow: PgUnitOfWork = ctx.pgUnitOfWork, repo: TaskRepository = repository) = TaskService(repo, uow, now::get)
    private fun id() = UUID.randomUUID().toString()
    private suspend fun user() = ctx.registerUser(uniqueUsername("task"))
    private fun create(assignee: String, title: String = "任务", dueAt: Long? = null,
        contextKind: Int = TaskPolicy.CONTEXT_NONE, contextId: String = "") =
        TaskCommand(id(), now.get(), id(), 0, TaskCommand.CREATE, TaskDraft(title, "任务正文", assignee, contextKind, contextId, dueAt))
    private fun status(task: WorkTask, value: Int) = TaskCommand(id(), now.get(), task.taskId, task.revision, TaskCommand.STATUS, status = value)
    private fun edit(task: WorkTask, assignee: String = task.assigneeUid, dueAt: Long? = task.dueAt, title: String = task.title) =
        TaskCommand(id(), now.get(), task.taskId, task.revision, TaskCommand.EDIT,
            TaskDraft(title, task.description, assignee, task.contextKind, task.contextId, dueAt))
    private suspend fun apply(uid: String, command: TaskCommand) = assertNotNull(service().mutate(uid, command).task)
    private fun events(uid: String, type: NotifyType) = transaction(ctx.database) {
        SyncEvents.selectAll().where { (SyncEvents.uid eq uid) and (SyncEvents.eventType eq type.code) }
            .orderBy(SyncEvents.streamSeq).map { it[SyncEvents.payload] }
    }

    @Test fun `exact command replay survives restart while conflicting and expired identities never execute`() = runTest {
        val owner = user(); val peer = user(); val command = create(peer)
        val task = apply(owner, command)
        val beforeEvents = events(owner, NotifyType.TASK_CHANGED).size
        val restarted = service(ExposedPgUnitOfWork(ctx.database, onEventsCommitted = {}))
        assertEquals(task, restarted.mutate(owner, command).task)
        assertEquals(beforeEvents, events(owner, NotifyType.TASK_CHANGED).size)
        assertEquals(1, restarted.audit(owner, task.taskId, null, 20).items.size)
        assertFailsWith<ReliableCommandConflictException> { restarted.mutate(owner, command.copy(draft = command.draft!!.copy(title = "另一意图"))) }
        now.addAndGet(ReliableCommandPolicy.RETRY_HORIZON_MILLIS + 1)
        assertTrue(restarted.cleanupReceipts() >= 1)
        assertFailsWith<ReliableCommandExpiredException> { restarted.mutate(owner, command) }
        assertEquals(task, restarted.get(owner, task.taskId))
    }

    @Test fun `participants have explicit edit and status permissions and CAS rejects competing commands`() = runTest {
        val owner = user(); val assignee = user(); val outsider = user()
        val initial = apply(owner, create(assignee))
        assertFailsWith<TaskAccessDeniedException> { service().get(outsider, initial.taskId) }
        assertFailsWith<TaskAccessDeniedException> { service().audit(outsider, initial.taskId, null, 20) }
        assertFailsWith<TaskAccessDeniedException> { service().mutate(assignee, edit(initial, title = "越权")) }
        assertFailsWith<TaskAccessDeniedException> { service().mutate(assignee, status(initial, TaskPolicy.CANCELLED)) }
        val started = apply(assignee, status(initial, TaskPolicy.IN_PROGRESS))
        assertFailsWith<TaskRevisionConflictException> { service().mutate(owner, status(initial, TaskPolicy.DONE)) }
        val done = apply(assignee, status(started, TaskPolicy.DONE))
        val reopened = apply(assignee, status(done, TaskPolicy.TODO))
        val cancelled = apply(owner, status(reopened, TaskPolicy.CANCELLED))
        assertFailsWith<TaskAccessDeniedException> { service().mutate(assignee, status(cancelled, TaskPolicy.TODO)) }
        assertEquals(TaskPolicy.TODO, apply(owner, status(cancelled, TaskPolicy.TODO)).status)
    }

    @Test fun `concurrent reliable commands commit once and concurrent distinct revisions have one winner`() = runTest {
        val owner = user(); val peer = user(); val create = create(peer)
        val first = async(Dispatchers.IO) { service().mutate(owner, create) }
        val duplicate = async(Dispatchers.IO) { service().mutate(owner, create) }
        val task = assertNotNull(first.await().task)
        assertEquals(task, duplicate.await().task)
        val start = status(task, TaskPolicy.IN_PROGRESS); val finish = status(task, TaskPolicy.DONE)
        val a = async(Dispatchers.IO) { runCatching { service().mutate(owner, start) } }
        val b = async(Dispatchers.IO) { runCatching { service().mutate(peer, finish) } }
        val outcomes = listOf(a.await(), b.await())
        assertEquals(1, outcomes.count { it.isSuccess })
        assertTrue(outcomes.single { it.isFailure }.exceptionOrNull() is TaskRevisionConflictException)
        assertEquals(2, service().audit(owner, task.taskId, null, 20).items.size)
        assertEquals(2, events(owner, NotifyType.TASK_CHANGED).size)
    }

    @Test fun `reassignment revokes old assignee but acknowledges their committed retry without private content`() = runTest {
        val owner = user(); val old = user(); val next = user()
        val initial = apply(owner, create(old))
        val oldCommand = status(initial, TaskPolicy.IN_PROGRESS)
        val started = apply(old, oldCommand)
        val reassigned = apply(owner, edit(started, assignee = next))
        assertNull(service().mutate(old, oldCommand).task)
        assertFailsWith<TaskAccessDeniedException> { service().get(old, initial.taskId) }
        assertTrue(service().list(old, TaskPolicy.VIEW_ASSIGNED, null, 20).items.isEmpty())
        assertEquals(reassigned, service().get(next, initial.taskId))
        val revoked = ProtoCodec.decode(TaskChangedPayload, events(old, NotifyType.TASK_CHANGED).last())
        assertEquals(TaskChangedPayload.REVOKED, revoked.kind)
        assertEquals(reassigned.revision, revoked.revision)
        assertEquals(3, service().audit(owner, initial.taskId, null, 20).items.size)
    }

    @Test fun `group and organization are visible association metadata and never task ownership`() = runTest {
        val owner = user(); val creator = user(); val assignee = user(); val visitor = user()
        val group = ctx.chatService.createGroup(id(), "任务上下文", null, owner, listOf(creator, assignee))
        val task = apply(creator, create(assignee, contextKind = TaskPolicy.CONTEXT_GROUP, contextId = group.chatId))
        assertFailsWith<TaskAccessDeniedException> { service().get(owner, task.taskId) }
        assertFailsWith<TaskAccessDeniedException> { service().mutate(visitor, create(visitor, contextKind = TaskPolicy.CONTEXT_GROUP, contextId = group.chatId)) }
        ctx.chatService.leaveGroup(creator, group.chatId)
        assertEquals(task, service().get(creator, task.taskId))
        assertEquals("离群后仍可编辑", apply(creator, edit(task, title = "离群后仍可编辑")).title)
        assertFailsWith<TaskAccessDeniedException> { service().mutate(creator, create(creator, contextKind = TaskPolicy.CONTEXT_GROUP, contextId = group.chatId)) }
        val unit = OrganizationUnit(id(), name = "任务关联部门")
        ctx.seedOrganizationUnit(unit)
        ctx.seedOrganizationMember(OrganizationMember(unit.unitId, creator))
        val orgTask = apply(creator, create(assignee, contextKind = TaskPolicy.CONTEXT_ORGANIZATION, contextId = unit.unitId))
        assertEquals(orgTask, service().get(assignee, orgTask.taskId))
        assertFailsWith<TaskAccessDeniedException> { service().mutate(visitor, create(visitor, contextKind = TaskPolicy.CONTEXT_ORGANIZATION, contextId = unit.unitId)) }
    }

    @Test fun `task pages keep immutable ordering across edits and audit pages stay participant protected`() = runTest {
        val owner = user(); val peer = user()
        val tasks = (1..5).map { now.incrementAndGet(); apply(owner, create(peer, "任务 $it")) }
        val first = service().list(peer, TaskPolicy.VIEW_ASSIGNED, null, 2)
        assertEquals(tasks.takeLast(2).reversed(), first.items)
        val edited = apply(owner, edit(tasks.first(), title = "旧任务新标题"))
        now.incrementAndGet(); apply(owner, create(peer, "后来插入"))
        val second = service().list(peer, TaskPolicy.VIEW_ASSIGNED, first.nextCursor, 2)
        val third = service().list(peer, TaskPolicy.VIEW_ASSIGNED, second.nextCursor, 2)
        assertEquals(tasks.reversed().map { it.taskId }, (first.items + second.items + third.items).map { it.taskId })
        assertEquals(edited, third.items.single()); assertNull(third.nextCursor)
        assertFailsWith<IllegalArgumentException> { service().list(owner, TaskPolicy.VIEW_CREATED, first.nextCursor, 2) }
        val audit = service().audit(owner, edited.taskId, null, 1)
        assertEquals(2L, audit.items.single().revision)
        assertEquals(1L, service().audit(peer, edited.taskId, audit.nextCursor, 1).items.single().revision)
    }

    @Test fun `task audit receipt and events roll back together and due markers recover across restart`() = runTest {
        val owner = user(); val peer = user(); val command = create(peer, dueAt = now.get())
        val failing = service(ExposedPgUnitOfWork(ctx.database, onEventsCommitted = {},
            hooks = PgUnitOfWorkHooks { if (it == PgUnitOfWorkStage.AFTER_EVENT_FLUSH_BEFORE_COMMIT) error("task rollback") }))
        assertFailsWith<IllegalStateException> { failing.mutate(owner, command) }
        assertFailsWith<TaskNotFoundException> { service().get(owner, command.taskId) }
        assertTrue(events(owner, NotifyType.TASK_CHANGED).isEmpty())
        assertNull(ctx.pgUnitOfWork.read { repository.findReceipt(transaction, owner, command.operationId) })
        val task = apply(owner, command)
        assertFailsWith<IllegalStateException> { failing.remindDue() }
        assertNull(service().get(peer, task.taskId).remindedAt)
        assertTrue(events(peer, NotifyType.TASK_DUE).isEmpty())
        val restarted = service(ExposedPgUnitOfWork(ctx.database, onEventsCommitted = {}))
        assertTrue(restarted.remindDue() >= 1)
        val reminded = assertNotNull(restarted.get(peer, task.taskId).remindedAt)
        restarted.remindDue()
        assertEquals(1, events(peer, NotifyType.TASK_DUE).size)
        assertEquals(reminded, ProtoCodec.decode(TaskDuePayload, events(peer, NotifyType.TASK_DUE).single()).remindedAt)
        assertTrue(events(owner, NotifyType.TASK_DUE).isEmpty())
    }

    @Test fun `rename preserves reminder while reassignment due change and reopen rearm exactly once`() = runTest {
        val owner = user(); val peer = user(); val next = user()
        val initial = apply(owner, create(peer, dueAt = now.get()))
        service().remindDue()
        val reminded = service().get(owner, initial.taskId)
        val renamed = apply(owner, edit(reminded, title = "只改标题"))
        assertEquals(reminded.remindedAt, renamed.remindedAt)
        service().remindDue(); assertEquals(1, events(peer, NotifyType.TASK_DUE).size)
        val assigned = apply(owner, edit(renamed, assignee = next))
        assertNull(assigned.remindedAt)
        service().remindDue(); assertEquals(1, events(next, NotifyType.TASK_DUE).size)
        val done = apply(next, status(service().get(next, initial.taskId), TaskPolicy.DONE))
        service().remindDue(); assertEquals(1, events(next, NotifyType.TASK_DUE).size)
        val open = apply(next, status(done, TaskPolicy.TODO))
        service().remindDue(); assertEquals(2, events(next, NotifyType.TASK_DUE).size)
        // The wall clock has not advanced. A new reminder still needs a distinct persistent identity.
        val markers = events(next, NotifyType.TASK_DUE).map { ProtoCodec.decode(TaskDuePayload, it).remindedAt }
        assertTrue(markers[1] > markers[0])
        val postponed = apply(owner, edit(open, dueAt = now.get() + 1_000))
        assertNull(postponed.remindedAt)
        service().remindDue(); assertEquals(2, events(next, NotifyType.TASK_DUE).size)
        now.addAndGet(1_000)
        service().remindDue(); assertEquals(3, events(next, NotifyType.TASK_DUE).size)
    }

    @Test fun `completion racing a selected due candidate suppresses delivery under the task lock`() = runTest {
        val owner = user(); val peer = user()
        val task = apply(owner, create(peer, dueAt = now.get()))
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val selected = CountDownLatch(1)
        val completing = service(ExposedPgUnitOfWork(ctx.database, onEventsCommitted = {}, hooks = PgUnitOfWorkHooks {
            if (it == PgUnitOfWorkStage.BEFORE_EVENT_FLUSH) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        }))
        val observing = service(repo = object : TaskRepository by repository {
            override fun dueCandidates(transaction: com.virjar.tk.server.domain.transaction.PgReadTransactionContext, now: Long, limit: Int): List<String> =
                repository.dueCandidates(transaction, now, limit).also { if (task.taskId in it) selected.countDown() }
        })
        val completion = async(Dispatchers.IO) { completing.mutate(owner, status(task, TaskPolicy.DONE)) }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
            val reminder = async(Dispatchers.IO) { observing.remindDue() }
            assertTrue(withContext(Dispatchers.IO) { selected.await(10, TimeUnit.SECONDS) })
            release.countDown(); completion.await(); reminder.await()
        } finally { release.countDown() }
        assertEquals(TaskPolicy.DONE, service().get(peer, task.taskId).status)
        assertTrue(events(peer, NotifyType.TASK_DUE).isEmpty())
    }

    @Test fun `task RPC dispatch exposes conflict expiry permissions and negotiated minor gating`() = runTest {
        val owner = user(); val peer = user(); val outsider = user()
        val task = apply(owner, create(peer)); val dispatcher = RpcDispatcher(ctx.rpcStubRegistry)
        suspend fun invoke(uid: String, method: Int, payload: ByteArray, version: ProtocolVersion = ProtocolVersion(0, 2)) =
            dispatcher.dispatch(uid, "task-test-device", 1, "task-test-session", InvokePayload(1, TaskRpcContract.SERVICE, method, payload), version)
        assertEquals(0, invoke(peer, TaskRpcContract.M_GET, TaskRpcContract.encodeGet(task.taskId)).status)
        assertEquals(403, invoke(outsider, TaskRpcContract.M_GET, TaskRpcContract.encodeGet(task.taskId)).status)
        val stale = status(task, TaskPolicy.IN_PROGRESS)
        apply(peer, stale)
        assertEquals(409, invoke(owner, TaskRpcContract.M_MUTATE, TaskRpcContract.encodeMutate(status(task, TaskPolicy.DONE))).status)
        // The real DI/RPC service uses wall time, not this fixture's injected clock. Commit an
        // older command using the fixture clock, then replay its actual receipt through real RPC.
        now.set(System.currentTimeMillis() - ReliableCommandPolicy.RETRY_HORIZON_MILLIS - 60_000L)
        val historical = apply(owner, create(peer))
        val expired = status(historical, TaskPolicy.IN_PROGRESS)
        val committed = apply(peer, expired)
        assertNotNull(ctx.pgUnitOfWork.read { repository.findReceipt(transaction, peer, expired.operationId) })
        assertEquals(410, invoke(peer, TaskRpcContract.M_MUTATE, TaskRpcContract.encodeMutate(expired)).status)
        assertEquals(committed, service().get(peer, historical.taskId))
        assertNotEquals(0, invoke(peer, TaskRpcContract.M_GET, TaskRpcContract.encodeGet(task.taskId), ProtocolVersion(0, 1)).status)
    }

    @Test fun `task references freeze authoritative preview and old message RPC paths preserve coordinates`() = runTest {
        val owner = user(); val peer = user(); val assignee = user()
        val task = apply(owner, create(assignee, title = "TaskProbe 权威标题"))
        val chat = ctx.chatService.createPersonalChat(owner, peer)
        fun reference() = Message(chat.chatId, id(), senderUid = "", messageType = MessageType.TASK_REF.code,
            timestamp = now.get(), body = TaskRefBody(task.taskId, "伪造标题", "不应保留的正文"))
        assertFailsWith<TaskAccessDeniedException> { ctx.messageService.sendMessage(peer, reference()) }
        val seq = ctx.messageService.sendMessage(owner, reference())
        val stored = ctx.messageService.getHistory(owner, chat.chatId, 0, 10).first { it.serverSeq == seq }
        assertEquals(TaskRefBody(task.taskId, task.title, "任务 · 待处理"), stored.body)
        assertFailsWith<TaskAccessDeniedException> { service().get(peer, task.taskId) }
        fun rpc(version: ProtocolVersion) = MessageRpcImpl(peer, ctx.messageService, ctx.conversationService, ctx.reactionService, version)
        val old = rpc(ProtocolVersion(0, 1)); val current = rpc(ProtocolVersion(0, 2))
        assertEquals(stored, current.getHistory(chat.chatId, 0, 10).first { it.serverSeq == seq })
        val placeholder = old.getHistory(chat.chatId, 0, 10).first { it.serverSeq == seq }
        assertEquals(MessageType.RICH_TEXT.code, placeholder.messageType)
        assertEquals(stored.clientMsgId, placeholder.clientMsgId)
        assertEquals("此消息需要升级客户端查看", placeholder.body?.plainTextContentOrNull())
        assertEquals(MessageType.RICH_TEXT.code, old.search(chat.chatId, "TaskProbe", 10).single().messageType)
        assertEquals(MessageType.RICH_TEXT.code, old.saveMessage(chat.chatId, seq, id()).messageType)
        val target = ctx.chatService.createPersonalChat(peer, assignee)
        val forwarded = old.forward(chat.chatId, seq, target.chatId)
        assertEquals(MessageType.RICH_TEXT.code, forwarded.messageType)
        assertEquals(task.title, (ctx.messageService.getHistory(peer, target.chatId, 0, 10).single().body as TaskRefBody).title)
    }
}
