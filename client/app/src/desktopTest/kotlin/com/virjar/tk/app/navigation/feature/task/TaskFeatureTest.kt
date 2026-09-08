package com.virjar.tk.app.navigation.feature.task

import com.virjar.tk.app.navigation.UiLocalDataBoundary
import com.virjar.tk.app.viewmodel.ConversationViewModel
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.TaskChangedPayload
import com.virjar.tk.protocol.model.*
import com.virjar.tk.protocol.payload.ResponsePayload
import com.virjar.tk.protocol.rpc.RpcInvoker
import com.virjar.tk.protocol.rpc.gen.TaskRpcContract
import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.client.*
import com.virjar.tk.shared.repository.TaskRepository
import com.virjar.tk.shared.testkit.FakeLocalCache
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/** App 交互回归使用真实 TaskRepository；磁盘恢复由 SDK 的 SQLite 集成测试负责。 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskFeatureTest {
    @Test
    fun `cold external reference opens independently of first list page and survives workspace initialization`() = runTest {
        withFixture { f ->
            f.rpc.respond = { method -> response(method, task()) }
            f.feature.openTask(ID)
            f.scope.async { f.feature.open() }.also { advanceUntilIdle(); it.await() }
            assertEquals(ID, f.feature.selectedTaskId)
            assertEquals("任务正文", f.feature.task?.description)
            assertTrue(f.feature.items.isEmpty())
            assertNull(f.feature.detailError)
            assertEquals(1, f.rpc.calls.count { it == TaskRpcContract.M_GET })
        }
    }

    @Test
    fun `create persists pending without querying nonexistent task then acknowledgment publishes confirmed detail`() = runTest {
        withFixture { f ->
            f.feature.editor = TaskEditorState.create(OWNER).copy(title = "离线创建", description = "本机原意图")
            f.feature.saveEditor()
            advanceUntilIdle()
            val command = f.cache.tasks.pending().single().command
            assertEquals(command.taskId, f.feature.selectedTaskId)
            assertEquals("本机原意图", f.feature.pending.single().command.draft?.description)
            assertNull(f.feature.task)
            assertNull(f.feature.detailError)
            assertFalse(f.rpc.calls.contains(TaskRpcContract.M_GET))
            assertEquals("任务操作已保存，等待同步", f.feature.notice)
            val confirmed = task().copy(taskId = command.taskId, title = "离线创建", description = "本机原意图")
            f.rpc.respond = { method -> response(method, confirmed) }
            f.cache.tasks.acknowledge(command, TaskCommandResult(confirmed), f.cache.tasks.generation(), OWNER)
            advanceUntilIdle()
            assertTrue(f.feature.pending.isEmpty())
            assertEquals(confirmed, f.feature.task)
            assertNull(f.feature.detailError)
            assertNull(f.feature.notice, "确认完成后不再提示等待同步")
        }
    }

    @Test
    fun `revoked cached detail clears once without an endless generation refresh loop`() = runTest {
        withFixture { f ->
            f.cache.tasks.applyTask(task(), f.cache.tasks.generation(), OWNER)
            f.rpc.respond = { method ->
                if (method == TaskRpcContract.M_GET) {
                    check(f.rpc.calls.count { it == method } <= 3) { "repeated rejected read" }
                    ResponsePayload(1, 403, "forbidden".encodeToByteArray())
                } else response(method, task())
            }
            f.feature.openTask(ID)
            advanceUntilIdle()
            assertNull(f.feature.task)
            assertTrue(f.feature.audits.isEmpty())
            assertEquals("任务不可访问或已删除", f.feature.detailError)
            assertTrue(f.rpc.calls.count { it == TaskRpcContract.M_GET } in 1..2)
            val count = f.rpc.calls.size
            advanceUntilIdle()
            assertEquals(count, f.rpc.calls.size)
        }
    }

    @Test
    fun `remote revision preserves editor intent and rejected operation remains until explicit retry or discard`() = runTest {
        withFixture { f ->
            var current = task()
            f.rpc.respond = { method -> response(method, current) }
            f.feature.openTask(ID)
            advanceUntilIdle()
            f.feature.beginEdit()
            f.feature.editor = assertNotNull(f.feature.editor).copy(description = "尚未提交的输入")
            current = current.copy(description = "远端修改", revision = 2, updatedAt = 2)
            f.cache.tasks.invalidate(TaskChangedPayload(ID, 2, TaskChangedPayload.UPDATED))
            advanceUntilIdle()
            assertEquals(2L, f.feature.task?.revision)
            assertEquals("尚未提交的输入", f.feature.editor?.description)
            assertEquals(1L, f.feature.editor?.original?.revision)
            f.feature.saveEditor()
            advanceUntilIdle()
            val command = f.cache.tasks.pending().single().command
            assertEquals(1L, command.expectedRevision)
            assertEquals("尚未提交的输入", command.draft?.description)
            f.cache.tasks.fail(ID, "任务已变化，请查看当前内容后重新操作")
            advanceUntilIdle()
            assertNotNull(f.feature.pending.single().failure)
            assertNull(f.feature.notice, "明确拒绝后由待发送行展示失败原因")
            f.feature.retryPending(ID)
            advanceUntilIdle()
            assertEquals(command, f.cache.tasks.pending().single().command)
            assertNull(f.feature.pending.single().failure)
            f.cache.tasks.fail(ID, "任务已变化，请查看当前内容后重新操作")
            advanceUntilIdle()
            f.feature.discardPending(ID)
            advanceUntilIdle()
            assertTrue(f.feature.pending.isEmpty())
            assertEquals(current, f.feature.task)
            assertNull(f.feature.notice, "放弃操作不能被报告为成功")
        }
    }

    @Test
    fun `pagination retries failed continuation and keeps loading beyond a bounded resident window`() = runTest {
        withFixture { f ->
            var pageNumber = 0
            var failOnce = false
            f.rpc.respond = { method ->
                assertEquals(TaskRpcContract.M_LIST, method)
                if (failOnce) { failOnce = false; throw AppError.Network }
                val index = pageNumber++
                val items = (1..20).map { offset -> task().copy(taskId = id(index * 20 + offset)) }
                ResponsePayload(1, 0, ProtoCodec.encode(TaskPage(items, "page-${index + 1}")))
            }
            f.scope.async { f.feature.open() }.also { advanceUntilIdle(); it.await() }
            assertEquals(20, f.feature.items.size)
            failOnce = true
            f.feature.loadMore()
            advanceUntilIdle()
            assertEquals(20, f.feature.items.size)
            assertEquals("page-1", f.feature.nextCursor)
            assertNotNull(f.feature.listError)
            repeat(11) { f.feature.loadMore(); advanceUntilIdle() }
            assertEquals(12, pageNumber)
            assertEquals(200, f.feature.items.size)
            assertEquals(id(41), f.feature.items.first().taskId)
            assertEquals(id(240), f.feature.items.last().taskId)
            assertEquals("page-12", f.feature.nextCursor)
            assertNull(f.feature.listError)
        }
    }

    @Test
    fun `late task read cannot reopen detail after returning to list`() = runTest {
        withFixture { f ->
            val late = CompletableDeferred<ResponsePayload>()
            f.rpc.respond = { method ->
                if (method == TaskRpcContract.M_GET) withContext(NonCancellable) { late.await() }
                else response(method, task())
            }
            f.feature.openTask(ID)
            advanceUntilIdle()
            f.feature.showList()
            late.complete(response(TaskRpcContract.M_GET, task()))
            advanceUntilIdle()
            assertNull(f.feature.selectedTaskId)
            assertNull(f.feature.task)
            assertFalse(f.feature.loadingTask)
            assertTrue(f.feature.audits.isEmpty())
        }
    }

    private suspend fun TestScope.withFixture(block: suspend (Fixture) -> Unit) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        val client = ImClient()
        val cache = FakeLocalCache(initialDatasetId = DATASET)
        cache.upsertUser(User(OWNER, "task-owner", "任务成员"))
        val spool = Files.createTempDirectory("teamtalk-task-feature-test-").toFile()
        var session: ClientSession? = null
        var conversations: ConversationViewModel? = null
        try {
            val user = UserSession().apply { restorePersistedLogin(OWNER, "offline-refresh", DATASET) }
            client.prepareAuthentication(OWNER, "offline-refresh", "task-feature-device", "Task feature test", "offline.test.example", 5100)
            withContext(Dispatchers.Default) { withTimeout(15_000) { client.awaitTransportOwnerStart() } }
            val active = createSession(client, user,
                DeploymentIdentity.from("offline.test.example", 5100, "https://offline.test.example"),
                createCache = { _, _, _ -> cache }, deviceId = "task-feature-device", logUploadEnabled = false,
                telemetrySpoolRoot = spool)
            session = active
            val rpc = RecordingRpc()
            val repository = TaskRepository(rpc, cache.tasks, OWNER)
            ClientSession::class.java.getDeclaredField("ownedTaskRepo").apply { isAccessible = true }.set(active, repository)
            val boundary = UiLocalDataBoundary(dispatcher)
            val conversationOwner = ConversationViewModel(cache, active.conversationRepo, active.connectionState, dispatcher,
                localData = boundary)
            conversations = conversationOwner
            val feature = TaskFeature(active, scope, boundary, conversationOwner)
            advanceUntilIdle()
            block(Fixture(cache, rpc, feature, scope))
        } finally {
            scope.cancel()
            conversations?.destroy()
            runCatching { session?.close(reason = SessionEndReason.SHUTDOWN) }
            client.destroy()
            spool.deleteRecursively()
        }
    }

    private class RecordingRpc : RpcInvoker {
        val calls = mutableListOf<Int>()
        var respond: suspend (Int) -> ResponsePayload = { throw AppError.Network }
        override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?): ResponsePayload {
            calls += methodId
            return respond(methodId)
        }
    }
    private data class Fixture(val cache: FakeLocalCache, val rpc: RecordingRpc, val feature: TaskFeature, val scope: CoroutineScope)
    private fun response(method: Int, task: WorkTask): ResponsePayload = ResponsePayload(1, 0, when (method) {
        TaskRpcContract.M_LIST -> ProtoCodec.encode(TaskPage(emptyList(), null))
        TaskRpcContract.M_GET -> ProtoCodec.encode(task)
        TaskRpcContract.M_AUDIT -> ProtoCodec.encode(TaskAuditPage(emptyList(), null))
        else -> error("Unexpected task RPC $method")
    })
    private fun task() = WorkTask(ID, OWNER, OWNER, "测试任务", "任务正文", TaskPolicy.TODO,
        TaskPolicy.CONTEXT_NONE, "", null, null, 1, 1, 1)
    private fun id(value: Int) = "00000000-0000-4000-8000-${value.toString().padStart(12, '0')}"
    private companion object {
        const val OWNER = "task-owner"
        const val ID = "00000000-0000-4000-8000-000000000001"
        const val DATASET = "00000000-0000-4000-8000-000000000009"
    }
}
