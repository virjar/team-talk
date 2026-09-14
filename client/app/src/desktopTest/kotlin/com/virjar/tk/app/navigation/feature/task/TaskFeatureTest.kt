package com.virjar.tk.app.navigation.feature.task

import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import com.virjar.tk.app.navigation.UiLocalDataBoundary
import com.virjar.tk.app.viewmodel.ConversationViewModel
import com.virjar.tk.protocol.ProtocolVersion
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
            val command = assertNotNull(f.cache.tasks.pending().single().command)
            assertEquals(command.taskId, f.feature.selectedTaskId)
            assertEquals("本机原意图", f.feature.pending.single().draft?.description)
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
            val command = assertNotNull(f.cache.tasks.pending().single().command)
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

    @Test
    fun `group filter failure clears previous query and summaries use complete server counts`() = runTest {
        withFixture(minor = 3) { f ->
            val groupTask = TaskDetails(task().copy(contextKind = TaskPolicy.CONTEXT_GROUP, contextId = id(10)),
                TaskOptions(shareToGroup = true))
            f.rpc.respond = { method ->
                assertEquals(TaskRpcContract.M_QUERY, method)
                ResponsePayload(1, 0, ProtoCodec.encode(TaskQueryPage(listOf(groupTask), null,
                    TaskSummary(35, 35, 4, null, 0, null))))
            }
            f.feature.openGroupTodos(id(10))
            advanceUntilIdle()
            assertEquals(1, f.feature.items.size)
            assertEquals(35L, f.feature.summary?.openCount)
            assertFalse(f.feature.queryKeys.single().startedOnly, "群卡片包括未来开始的未完成项")
            f.rpc.respond = { throw AppError.Network }
            f.feature.openGroupTodos(id(11))
            assertTrue(f.feature.items.isEmpty(), "切换后立即清除另一群的内容")
            assertNull(f.feature.summary)
            advanceUntilIdle()
            assertTrue(f.feature.items.isEmpty())
            assertNull(f.feature.summary)
            assertNotNull(f.feature.listError)
            f.feature.openAssignedTodos()
            advanceUntilIdle()
            assertTrue(f.feature.queryKeys.single().startedOnly)
            f.feature.selectView(TaskQuery.ASSIGNED)
            advanceUntilIdle()
            assertFalse(f.feature.onlyOpen)
            assertFalse(f.feature.onlyStarted)
        }
    }

    @Test
    fun `extended editor keeps materials and captured revision until confirmed acknowledgment`() = runTest {
        withFixture(minor = 3) { f ->
            var current = TaskDetails(task(), TaskOptions(descriptionFormat = TaskOptions.MARKDOWN,
                documentRefs = listOf(com.virjar.tk.protocol.body.OfficeRefBody(1, id(20), id(21), "运维说明"))))
            f.rpc.respond = { method -> detailsResponse(method, current) }
            f.feature.openTask(ID)
            advanceUntilIdle()
            f.feature.beginEdit()
            f.feature.editor = assertNotNull(f.feature.editor).copy(description = "**本机修改**")
            current = current.copy(task = current.task.copy(revision = 2, updatedAt = 2, description = "远端修改"))
            f.cache.tasks.invalidate(TaskChangedPayload(ID, 2, TaskChangedPayload.UPDATED))
            advanceUntilIdle()
            assertEquals(2L, f.feature.task?.revision)
            f.feature.saveEditor()
            advanceUntilIdle()
            val command = assertNotNull(f.cache.tasks.pending().single().detailsCommand)
            assertEquals(1L, command.expectedRevision)
            assertEquals("**本机修改**", command.draft?.description)
            assertEquals(current.options.documentRefs, command.options?.documentRefs)
            assertNull(f.feature.editor)
            f.cache.tasks.fail(ID, "任务已变化")
            advanceUntilIdle()
            assertEquals(command, f.feature.pending.single().detailsCommand)
        }
    }

    @Test
    fun `deferral preserves the reviewed deadline reason and revision across a remote update`() = runTest {
        withFixture(minor = 3) { f ->
            val now = System.currentTimeMillis()
            val original = TaskDetails(task().copy(dueAt = now + 60_000), metrics = TaskMetrics(historyKnown = true))
            var current = original
            f.rpc.respond = { method -> detailsResponse(method, current) }
            f.feature.openTask(ID)
            advanceUntilIdle()
            current = current.copy(task = current.task.copy(revision = 2, updatedAt = 2))
            f.cache.tasks.invalidate(TaskChangedPayload(ID, 2, TaskChangedPayload.UPDATED))
            advanceUntilIdle()
            f.feature.deferTask(original, now + 120_000, "等待统计数据")
            advanceUntilIdle()
            val command = assertNotNull(f.feature.pending.single().detailsCommand)
            assertEquals(TaskDetailsCommand.DEFER, command.kind)
            assertEquals(1L, command.expectedRevision)
            assertEquals(now + 120_000, command.deferDueAt)
            assertEquals("等待统计数据", command.reason)
        }
    }

    @Test
    fun `saved editor restores raw input original revision and completed materials only for its exact owner`() = runTest {
        withFixture(minor = 3) { f ->
            val original = TaskDetails(task(), TaskOptions(descriptionFormat = TaskOptions.MARKDOWN,
                documentRefs = listOf(com.virjar.tk.protocol.body.OfficeRefBody(1, id(20), id(21), "运维说明")),
                attachments = listOf(Attachment("tasks/material.txt", "材料.txt", "text/plain", 5))))
            val captured = TaskEditorState.from(original).copy(
                title = "  未完成标题  ", description = "**尚未保存**\n- 最后输入", assigneeUid = "另一执行人",
                contextKind = TaskPolicy.CONTEXT_GROUP, contextId = id(10),
                deadline = TaskDeadlineInput("2026-09-", "18:"), start = TaskDeadlineInput("2026-", "09"),
                recurrenceInput = TaskRecurrenceInput(true, TaskRecurrenceRule.MONTHLY, "", "2026-09-", "09:", "1", "Asia/"), uploading = true,
            )
            f.feature.editor = captured
            val payload = assertNotNull(f.feature.saveEditorSnapshot())
            val saved = Json.decodeFromString<SavedTaskEditor>(payload)
            val restored = f.newFeature()
            suspend fun restore(value: String) {
                f.scope.async { restored.restoreEditorSnapshot(value) }.also { advanceUntilIdle(); it.await() }
            }
            for (wrongOwner in listOf(
                saved.copy(deploymentFingerprint = "another-deployment"),
                saved.copy(datasetId = id(30)), saved.copy(ownerUid = "another-account"),
            )) {
                restore(Json.encodeToString(wrongOwner))
                assertNull(restored.editor)
            }
            val current = original.copy(task = original.task.copy(revision = 2, updatedAt = 2, description = "远端修改"))
            f.cache.tasks.applyDetails(current, f.cache.tasks.generation(), OWNER)
            restore(payload)
            assertEquals(captured.copy(uploading = false), restored.editor)
            assertEquals(1L, restored.editor?.original?.revision)
            assertEquals(2L, restored.task?.revision)
            assertFalse(restored.posting)
            assertTrue(f.cache.tasks.pending().isEmpty(), "恢复输入不得自动创建命令")

            restored.editor = assertNotNull(restored.editor).copy(deadline = TaskDeadlineInput(), start = TaskDeadlineInput())
            restored.saveEditor()
            advanceUntilIdle()
            val command = assertNotNull(f.cache.tasks.pending().single().detailsCommand)
            assertEquals(1L, command.expectedRevision, "恢复后显式保存仍用用户开始编辑时的修订号")
            assertEquals(captured.description, command.draft?.description)
            assertEquals(original.options.attachments, command.options?.attachments)
            assertEquals(original.options.documentRefs, command.options?.documentRefs)
        }
    }

    @Test
    fun `restored editor observes negotiated capabilities without changing its input`() = runTest {
        withFixture(minor = 3, protocolAvailable = false) { f ->
            val captured = TaskEditorState.create(OWNER).copy(
                title = "选择文件前的任务", description = "**尚未保存**",
                options = TaskOptions(descriptionFormat = TaskOptions.MARKDOWN,
                    attachments = listOf(Attachment("tasks/material.txt", "材料.txt", "text/plain", 5))),
                recurrenceInput = TaskRecurrenceInput(true, TaskRecurrenceRule.WEEKLY, "2", "2026-09-14",
                    "09:00", "18:00", "Asia/Shanghai"),
            )
            f.feature.editor = captured
            val restored = f.newFeature()
            f.scope.async { restored.restoreEditorSnapshot(assertNotNull(f.feature.saveEditorSnapshot())) }
                .also { advanceUntilIdle(); it.await() }
            val observed = mutableListOf<Boolean>()
            val observer = f.scope.launch { snapshotFlow { restored.supportsTaskDetails }.collect(observed::add) }
            advanceUntilIdle()
            assertEquals(listOf(false), observed)

            f.rpc.protocolAvailable = true
            f.connectionState.value = ConnectionState.AUTHENTICATED
            advanceUntilIdle()
            Snapshot.sendApplyNotifications()
            advanceUntilIdle()
            assertEquals(listOf(false, true), observed, "协商完成要主动刷新已恢复的 Compose 表单")
            assertEquals(captured, restored.editor, "刷新能力不能重置输入、已上传材料或周期锚点")
            assertTrue(f.cache.tasks.pending().isEmpty(), "恢复和认证均不得代替用户保存")

            f.rpc.protocolAvailable = false
            f.connectionState.value = ConnectionState.DISCONNECTED
            advanceUntilIdle()
            assertTrue(restored.supportsTaskDetails, "短暂断线沿用本会话已确认能力")
            observer.cancel()
        }
    }

    @Test
    fun `old saved create opens its pending or confirmed task without replaying the form`() = runTest {
        withFixture(minor = 3) { f ->
            val captured = TaskEditorState.create(OWNER).copy(title = "选择文件前的任务", description = "原始输入")
            f.feature.editor = captured
            val payload = assertNotNull(f.feature.saveEditorSnapshot())
            f.feature.saveEditor()
            assertTrue(f.feature.posting)
            assertNotNull(f.feature.saveEditorSnapshot(), "尚未写入 SDK 的提交窗口仍保留原输入")
            advanceUntilIdle()
            val command = assertNotNull(f.cache.tasks.pending().single().detailsCommand)
            assertEquals(captured.editorKey, command.taskId)
            assertNull(f.feature.saveEditorSnapshot())

            val pendingRestore = f.newFeature()
            f.scope.async { pendingRestore.restoreEditorSnapshot(payload) }.also { advanceUntilIdle(); it.await() }
            assertNull(pendingRestore.editor)
            assertEquals(command.taskId, pendingRestore.selectedTaskId)
            assertEquals(command, f.cache.tasks.pending().single().detailsCommand)
            assertTrue(assertNotNull(pendingRestore.notice).contains("待发送"))

            val confirmed = TaskDetails(task().copy(taskId = command.taskId, title = captured.title,
                description = captured.description), captured.options)
            f.rpc.respond = { method -> detailsResponse(method, confirmed) }
            f.cache.tasks.acknowledge(command, TaskDetailsCommandResult(confirmed), f.cache.tasks.generation(), OWNER)
            advanceUntilIdle()
            val acknowledgedRestore = f.newFeature()
            f.scope.async { acknowledgedRestore.restoreEditorSnapshot(payload) }.also { advanceUntilIdle(); it.await() }
            assertNull(acknowledgedRestore.editor)
            assertEquals(confirmed.task, acknowledgedRestore.task)
            assertEquals(command.taskId, acknowledgedRestore.selectedTaskId)
            assertTrue(f.cache.tasks.pending().isEmpty())
            assertTrue(assertNotNull(acknowledgedRestore.notice).contains("已提交"))
        }
    }

    private fun Fixture.newFeature() = TaskFeature(feature.session, scope, feature.localData, feature.conversations)

    private fun detailsResponse(method: Int, details: TaskDetails) = ResponsePayload(1, 0, when (method) {
        TaskRpcContract.M_QUERY -> ProtoCodec.encode(TaskQueryPage(emptyList(), null, TaskSummary(0, 0, 0, null, 0, null)))
        TaskRpcContract.M_DETAILS -> ProtoCodec.encode(details)
        TaskRpcContract.M_HISTORY -> ProtoCodec.encode(TaskHistoryPage(emptyList(), null))
        else -> error("Unexpected task RPC $method")
    })

    private suspend fun TestScope.withFixture(minor: Int = 2, protocolAvailable: Boolean = true, block: suspend (Fixture) -> Unit) {
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
            val offlineRpc = ClientSession::class.java.getDeclaredField("ownedRpcClient")
                .apply { isAccessible = true }.get(active) as RpcInvoker
            val rpc = RecordingRpc(minor, offlineRpc).apply { this.protocolAvailable = protocolAvailable }
            val repository = TaskRepository(rpc, cache.tasks, OWNER)
            ClientSession::class.java.getDeclaredField("ownedTaskRepo").apply { isAccessible = true }.set(active, repository)
            val boundary = UiLocalDataBoundary(dispatcher)
            val conversationOwner = ConversationViewModel(cache, active.conversationRepo, active.connectionState, dispatcher,
                localData = boundary)
            conversations = conversationOwner
            val feature = TaskFeature(active, scope, boundary, conversationOwner)
            advanceUntilIdle()
            val transport = ImClient::class.java.getDeclaredField("transport").apply { isAccessible = true }.get(client)
            @Suppress("UNCHECKED_CAST")
            val connectionState = transport.javaClass.getDeclaredField("_state").apply { isAccessible = true }
                .get(transport) as MutableStateFlow<ConnectionState>
            block(Fixture(cache, rpc, feature, scope, connectionState))
        } finally {
            scope.cancel()
            conversations?.destroy()
            runCatching { session?.close(reason = SessionEndReason.SHUTDOWN) }
            client.destroy()
            spool.deleteRecursively()
        }
    }

    private class RecordingRpc(minor: Int, private val offlineRpc: RpcInvoker) : RpcInvoker {
        private val version = ProtocolVersion(0, minor)
        var protocolAvailable = true
        override val negotiatedProtocolVersion: ProtocolVersion
            get() = if (protocolAvailable) version else offlineRpc.negotiatedProtocolVersion
        val calls = mutableListOf<Int>()
        var respond: suspend (Int) -> ResponsePayload = { throw AppError.Network }
        override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?): ResponsePayload {
            calls += methodId
            return respond(methodId)
        }
    }
    private data class Fixture(val cache: FakeLocalCache, val rpc: RecordingRpc, val feature: TaskFeature, val scope: CoroutineScope,
        val connectionState: MutableStateFlow<ConnectionState>)
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
