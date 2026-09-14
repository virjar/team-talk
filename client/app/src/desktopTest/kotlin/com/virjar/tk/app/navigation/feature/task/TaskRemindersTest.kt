package com.virjar.tk.app.navigation.feature.task

import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.ProtocolVersion
import com.virjar.tk.protocol.model.TaskDetails
import com.virjar.tk.protocol.model.TaskOptions
import com.virjar.tk.protocol.model.TaskPolicy
import com.virjar.tk.protocol.model.WorkTask
import com.virjar.tk.protocol.rpc.RpcInvoker
import com.virjar.tk.protocol.rpc.gen.TaskRpcContract
import com.virjar.tk.shared.repository.TaskRepository
import com.virjar.tk.shared.testkit.FakeLocalCache
import com.virjar.tk.shared.testkit.FakeRpcInvoker
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** 两端通知入口共用真实 Repository 与 wire 解码；收据的磁盘恢复由 SDK 集成测试负责。 */
class TaskRemindersTest {
    @Test
    fun `start and due notify once each while newer remote reminder supersedes captured start`() = runTest {
        val local = FakeLocalCache().tasks
        val responses = FakeRpcInvoker()
        val rpc = object : RpcInvoker by responses {
            override val negotiatedProtocolVersion = ProtocolVersion(0, 3)
        }
        val repository = TaskRepository(rpc, local, OWNER)
        val notifications = mutableListOf<Long>()
        val started = TaskDetails(task(), TaskOptions(startsAt = 50), startRemindedAt = 50)
        local.applyDetails(started, local.generation(), OWNER)
        responses.enqueueOk(ProtoCodec.encode(started))
        val notify: suspend (WorkTask, Long) -> Unit = { task, remindedAt ->
            notifications += remindedAt
            local.markReminderNotified(task.taskId, remindedAt)
        }

        forEachTaskReminder(repository, OWNER, { true }, notify)
        assertEquals(listOf(50L), notifications, "开始提醒不能依赖 WorkTask 的截止提醒字段")
        forEachTaskReminder(repository, OWNER, { true }, notify)
        assertEquals(1, responses.calls.size, "已通知的同一收据不再读取或弹出")

        // 已捕获下一次开始提醒，但权威读取时截止提醒已经到达。
        val laterStart = started.copy(task = task().copy(revision = 2), startRemindedAt = 75)
        local.applyDetails(laterStart, local.generation(), OWNER)
        val due = laterStart.copy(task = task().copy(revision = 3, remindedAt = 100))
        responses.enqueueOk(ProtoCodec.encode(due))
        forEachTaskReminder(repository, OWNER, { true }, notify)
        assertEquals(listOf(50L), notifications, "新截止提醒取代旧开始事件，旧事件不能误标新收据")
        assertEquals(100L, local.reminders().single().remindedAt)
        assertFalse(local.reminders().single().notified)

        responses.enqueueOk(ProtoCodec.encode(due))
        forEachTaskReminder(repository, OWNER, { true }, notify)
        assertEquals(listOf(50L, 100L), notifications)
        assertEquals(List(3) { TaskRpcContract.M_DETAILS }, responses.calls.map { it.second })
    }

    @Test
    fun `legacy server still delivers due reminder and foreground return leaves receipt unnotified`() = runTest {
        val local = FakeLocalCache().tasks
        val responses = FakeRpcInvoker()
        val rpc = object : RpcInvoker by responses {
            override val negotiatedProtocolVersion = ProtocolVersion(0, 2)
        }
        val repository = TaskRepository(rpc, local, OWNER)
        val due = task().copy(remindedAt = 100)
        local.applyTask(due, local.generation(), OWNER)
        var notifications = 0
        responses.enqueueOk(ProtoCodec.encode(due))
        forEachTaskReminder(repository, OWNER, { false }) { _, _ -> notifications++ }
        assertEquals(0, notifications)
        assertFalse(local.reminders().single().notified)

        responses.enqueueOk(ProtoCodec.encode(due))
        forEachTaskReminder(repository, OWNER, { true }) { task, remindedAt ->
            assertEquals(due, task)
            assertEquals(100L, remindedAt)
            notifications++
        }
        assertEquals(1, notifications)
        assertEquals(List(2) { TaskRpcContract.M_GET }, responses.calls.map { it.second })
    }

    private fun task() = WorkTask(
        taskId = "00000000-0000-4000-8000-000000000001",
        creatorUid = OWNER,
        assigneeUid = OWNER,
        title = "待办提醒",
        description = "",
        status = TaskPolicy.TODO,
        contextKind = TaskPolicy.CONTEXT_NONE,
        contextId = "",
        dueAt = 100,
        remindedAt = null,
        revision = 1,
        createdAt = 1,
        updatedAt = 1,
    )

    private companion object { const val OWNER = "owner" }
}
