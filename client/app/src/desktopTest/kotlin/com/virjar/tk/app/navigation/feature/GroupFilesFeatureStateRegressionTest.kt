package com.virjar.tk.app.navigation.feature

import com.virjar.tk.shared.client.ClientSession
import com.virjar.tk.shared.client.DeploymentIdentity
import com.virjar.tk.shared.client.ImClient
import com.virjar.tk.shared.client.LocalCache
import com.virjar.tk.shared.client.PendingGroupFileCommandKind
import com.virjar.tk.shared.client.SessionEndReason
import com.virjar.tk.shared.client.UserSession
import com.virjar.tk.shared.client.createSession
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.GroupFileEntry
import com.virjar.tk.protocol.model.GroupFileVersion
import com.virjar.tk.app.navigation.UiLocalDataBoundary
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.payload.ResponsePayload
import com.virjar.tk.shared.repository.GroupFileRepository
import com.virjar.tk.shared.repository.GroupFileCommandCompletion
import com.virjar.tk.shared.repository.GroupFileCommandCompletionStatus
import com.virjar.tk.protocol.rpc.RpcInvoker
import com.virjar.tk.protocol.rpc.gen.GroupFileRpcContract
import com.virjar.tk.shared.testkit.FakeLocalCache
import com.virjar.tk.app.telemetry.UserFeedbackCode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GroupFilesFeatureStateRegressionTest {
    @Test
    fun `uploads retain the selected directory and version after navigating elsewhere`() = runTest {
        val existing = file(CHILD_FILE_ID, FOLDER_ID, "原版本").copy(revision = 7)
        val rpc = ControlledRpcInvoker().apply { enqueueOk(entriesPayload(folder())) }
        val harness = createHarness(rpc)
        try {
            harness.feature.open(CHAT_ID)
            rpc.enqueueOk(entriesPayload(existing))
            harness.feature.enter(folder())
            advanceUntilIdle()
            val create = requireNotNull(harness.feature.captureUploadTarget(CHAT_ID))
            val version = requireNotNull(harness.feature.captureUploadTarget(CHAT_ID, existing))

            rpc.enqueueOk(entriesPayload())
            harness.feature.open("another-chat")
            assertNull(harness.feature.captureUploadTarget(CHAT_ID))
            repeat(2) { rpc.enqueueDeferred(CompletableDeferred(rpcError(503, "稍后重试"))) }
            val attachment = requireNotNull(existing.attachment)
            assertTrue(harness.feature.completeUpload(create, "新文件", attachment))
            assertTrue(harness.feature.completeUpload(version, "新版本", attachment))

            val commands = harness.session.localCache.getPendingGroupFileCommands()
            val newFile = commands.single { it.kind == PendingGroupFileCommandKind.CREATE_FILE }
            assertEquals(CHAT_ID, newFile.chatId)
            assertEquals(FOLDER_ID, newFile.parentId)
            val newVersion = commands.single { it.kind == PendingGroupFileCommandKind.ADD_VERSION }
            assertEquals(CHAT_ID, newVersion.chatId)
            assertEquals(CHILD_FILE_ID, newVersion.entryId)
            assertEquals(7L, newVersion.expectedRevision)
            assertEquals("another-chat", harness.feature.chatId)
            assertTrue(harness.feature.entries.isEmpty())
        } finally { harness.close() }
    }

    @Test
    fun `accepted list returning after a newer observed delta cannot overwrite the page`() = runTest {
        val original = folder()
        val updated = original.copy(name = "较新的事件", revision = 2)
        val captured = CompletableDeferred<Unit>()
        val releaseReturn = CountDownLatch(1)
        val backing = FakeLocalCache(initialDatasetId = DATASET_ID)
        val cache = object : LocalCache by backing {
            override fun activeGroupFileEntries(chatId: String, parentId: String?): List<GroupFileEntry> {
                val result = backing.activeGroupFileEntries(chatId, parentId)
                captured.complete(Unit)
                check(releaseReturn.await(10, TimeUnit.SECONDS)) { "list return was not released" }
                return result
            }
        }
        val rpc = ControlledRpcInvoker().apply { enqueueOk(entriesPayload(original)) }
        val harness = createHarness(rpc, cache, Dispatchers.IO)
        try {
            val opening = async { harness.feature.open(CHAT_ID) }
            withContext(Dispatchers.Default) { withTimeout(10_000) { captured.await() } }
            backing.applyGroupFileUpsert(updated)
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { while (harness.feature.entries != listOf(updated)) delay(5) }
            }
            releaseReturn.countDown()
            opening.await()
            runCurrent()
            assertEquals(listOf(updated), harness.feature.entries)
            assertFalse(harness.feature.stale)
        } finally {
            releaseReturn.countDown()
            harness.close()
        }
    }

    @Test
    fun `first directory keeps newer deltas stale when both full snapshots are invalidated`() = runTest {
        val first = CompletableDeferred<ResponsePayload>()
        val retry = CompletableDeferred<ResponsePayload>()
        val rpc = ControlledRpcInvoker().apply {
            enqueueDeferred(first)
            enqueueDeferred(retry)
        }
        val harness = createHarness(rpc)
        try {
            val opening = async { harness.feature.open(CHAT_ID) }
            runCurrent()
            val updated = folder(name = "实时目录", revision = 2)
            harness.session.localCache.applyGroupFileUpsert(updated)
            runCurrent()
            assertEquals(listOf(updated), harness.feature.entries)

            first.complete(ok(entriesPayload(folder())))
            runCurrent()
            val newest = updated.copy(name = "再次更新", revision = 3)
            harness.session.localCache.applyGroupFileUpsert(newest)
            runCurrent()
            retry.complete(ok(entriesPayload(folder())))
            advanceUntilIdle()
            opening.await()

            assertEquals(listOf(newest), harness.feature.entries)
            assertTrue(harness.feature.stale, "一条 delta 不能证明首次目录已完整加载")
            assertFalse(harness.feature.loading)
            assertEquals(2, rpc.listParentIds().size)
            assertTrue(harness.errors.isEmpty())
        } finally {
            harness.close()
        }
    }

    @Test
    fun `enter clears previous entries when child refresh fails`() = runTest {
        val rpc = ControlledRpcInvoker().apply {
            enqueueOk(entriesPayload(folder(), file(ROOT_FILE_ID, null, "根文件")))
        }
        val harness = createHarness(rpc)
        try {
            harness.feature.open(CHAT_ID)
            runCurrent()
            assertEquals(listOf(FOLDER_ID, ROOT_FILE_ID), harness.feature.entries.map { it.entryId })

            val childResponse = CompletableDeferred<ResponsePayload>()
            rpc.enqueueDeferred(childResponse)
            harness.feature.enter(folder())
            runCurrent()

            assertEquals(listOf(FOLDER_ID), harness.feature.path.map { it.entryId })
            assertTrue(harness.feature.entries.isEmpty())
            assertTrue(harness.feature.loading)
            assertEquals(listOf(null, FOLDER_ID), rpc.listParentIds())

            childResponse.complete(rpcError(503, "offline"))
            advanceUntilIdle()

            assertTrue(harness.feature.entries.isEmpty())
            assertFalse(harness.feature.loading)
            assertTrue(harness.errors.contains("加载群文件失败"))
        } finally {
            harness.close()
        }
    }

    @Test
    fun `up restores only parent cache and keeps it stale when refresh fails`() = runTest {
        val child = file(CHILD_FILE_ID, FOLDER_ID, "目录文件")
        val rpc = ControlledRpcInvoker().apply {
            enqueueOk(entriesPayload(folder()))
            enqueueOk(entriesPayload(child))
        }
        val harness = createHarness(rpc)
        try {
            harness.feature.open(CHAT_ID)
            runCurrent()
            harness.feature.enter(folder())
            advanceUntilIdle()
            assertEquals(listOf(CHILD_FILE_ID), harness.feature.entries.map { it.entryId })

            val parentResponse = CompletableDeferred<ResponsePayload>()
            rpc.enqueueDeferred(parentResponse)
            harness.feature.up()
            runCurrent()

            assertTrue(harness.feature.path.isEmpty())
            assertEquals(listOf(folder()), harness.feature.entries)
            assertTrue(harness.feature.stale)
            assertTrue(harness.feature.loading)
            assertEquals(listOf(null, FOLDER_ID, null), rpc.listParentIds())

            parentResponse.complete(rpcError(503, "offline"))
            advanceUntilIdle()

            assertEquals(listOf(folder()), harness.feature.entries)
            assertTrue(harness.feature.stale)
            assertFalse(harness.feature.loading)
            assertTrue(harness.errors.isEmpty())
        } finally {
            harness.close()
        }
    }

    @Test
    fun `recovered ancestor rename updates breadcrumb and refreshes current folder`() = runTest {
        val originalChild = file(CHILD_FILE_ID, FOLDER_ID, "旧目录文件")
        val refreshedChild = originalChild.copy(name = "刷新后的目录文件", revision = 2L)
        val rpc = ControlledRpcInvoker().apply {
            enqueueOk(entriesPayload(folder(name = "旧目录名")))
            enqueueOk(entriesPayload(originalChild))
        }
        val harness = createHarness(rpc)
        try {
            harness.feature.open(CHAT_ID)
            runCurrent()
            harness.feature.enter(folder(name = "旧目录名"))
            advanceUntilIdle()

            val currentFolderResponse = CompletableDeferred<ResponsePayload>()
            rpc.enqueueOk(entriesPayload(folder(name = "新目录名", revision = 2L)))
            rpc.enqueueDeferred(currentFolderResponse)
            assertTrue(
                harness.emitRecovery(
                    GroupFileCommandCompletion(
                        chatId = CHAT_ID,
                        entryId = FOLDER_ID,
                        parentId = null,
                        kind = PendingGroupFileCommandKind.RENAME,
                    ),
                ),
            )
            runCurrent()

            assertEquals("新目录名", harness.feature.path.single().name)
            assertEquals(listOf(CHILD_FILE_ID), harness.feature.entries.map { it.entryId })
            assertTrue(harness.feature.loading)
            assertEquals(listOf(null, FOLDER_ID, null, FOLDER_ID), rpc.listParentIds())

            currentFolderResponse.complete(ok(entriesPayload(refreshedChild)))
            advanceUntilIdle()

            assertEquals(listOf(refreshedChild), harness.feature.entries)
            assertFalse(harness.feature.loading)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `recovered top-level delete returns to root and clears details before root refresh`() = runTest {
        val child = file(CHILD_FILE_ID, FOLDER_ID, "即将失效的文件")
        val version = GroupFileVersion(
            entryId = CHILD_FILE_ID,
            version = 1L,
            attachment = requireNotNull(child.attachment),
            createdBy = OWNER_UID,
            createdAt = 1L,
        )
        val refreshedRoot = file(ROOT_FILE_ID, null, "根目录刷新结果")
        val rpc = ControlledRpcInvoker().apply {
            enqueueOk(entriesPayload(folder()))
            enqueueOk(entriesPayload(child))
            enqueueOk(ProtoCodec.encodeList(listOf(version)))
        }
        val harness = createHarness(rpc)
        try {
            harness.feature.open(CHAT_ID)
            runCurrent()
            harness.feature.enter(folder())
            advanceUntilIdle()
            harness.feature.showVersions(child)
            advanceUntilIdle()
            assertEquals(child, harness.feature.selectedFile)
            assertEquals(listOf(version), harness.feature.versions)

            val rootResponse = CompletableDeferred<ResponsePayload>()
            rpc.enqueueDeferred(rootResponse)
            assertTrue(
                harness.emitRecovery(
                    GroupFileCommandCompletion(
                        chatId = CHAT_ID,
                        entryId = FOLDER_ID,
                        parentId = null,
                        kind = PendingGroupFileCommandKind.DELETE,
                    ),
                ),
            )
            runCurrent()

            assertTrue(harness.feature.path.isEmpty())
            assertEquals(listOf(folder()), harness.feature.entries)
            assertTrue(harness.feature.stale)
            assertNull(harness.feature.selectedFile)
            assertTrue(harness.feature.versions.isEmpty())
            assertTrue(harness.feature.loading)
            assertEquals(listOf(null, FOLDER_ID, null), rpc.listParentIds())

            rootResponse.complete(ok(entriesPayload(refreshedRoot)))
            advanceUntilIdle()

            assertEquals(listOf(refreshedRoot), harness.feature.entries)
            assertFalse(harness.feature.loading)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `rejected background command reports feedback and refreshes current folder`() = runTest {
        val original = file(ROOT_FILE_ID, null, "旧的根目录文件")
        val refreshed = original.copy(name = "拒绝后的服务端结果", revision = 2L)
        val rpc = ControlledRpcInvoker().apply {
            enqueueOk(entriesPayload(original))
        }
        val harness = createHarness(rpc)
        try {
            harness.feature.open(CHAT_ID)
            runCurrent()
            assertEquals(listOf(original), harness.feature.entries)

            val refreshResponse = CompletableDeferred<ResponsePayload>()
            rpc.enqueueDeferred(refreshResponse)
            assertTrue(
                harness.emitRecovery(
                    GroupFileCommandCompletion(
                        chatId = CHAT_ID,
                        entryId = ROOT_FILE_ID,
                        parentId = null,
                        kind = PendingGroupFileCommandKind.RENAME,
                        status = GroupFileCommandCompletionStatus.REJECTED,
                    ),
                ),
            )
            runCurrent()

            assertEquals(
                listOf(UserFeedbackCode.RELIABLE_COMMAND_REJECTED),
                harness.feedback,
            )
            assertEquals(listOf(original), harness.feature.entries)
            assertTrue(harness.feature.loading)
            assertEquals(listOf(null, null), rpc.listParentIds())

            refreshResponse.complete(ok(entriesPayload(refreshed)))
            advanceUntilIdle()

            assertEquals(listOf(refreshed), harness.feature.entries)
            assertFalse(harness.feature.loading)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `recovered rename whose folder disappeared leaves stale branch and refreshes valid parent`() = runTest {
        val parentFolder = folder(name = "上级目录")
        val vanishedFolder = nestedFolder(name = "已被删除的目录")
        val staleFile = file(NESTED_FILE_ID, NESTED_FOLDER_ID, "失效详情")
        val version = GroupFileVersion(
            entryId = NESTED_FILE_ID,
            version = 1L,
            attachment = requireNotNull(staleFile.attachment),
            createdBy = OWNER_UID,
            createdAt = 1L,
        )
        val validParentEntry = file(PARENT_FILE_ID, FOLDER_ID, "有效上级内容")
        val rpc = ControlledRpcInvoker().apply {
            enqueueOk(entriesPayload(parentFolder))
            enqueueOk(entriesPayload(vanishedFolder))
            enqueueOk(entriesPayload(staleFile))
            enqueueOk(ProtoCodec.encodeList(listOf(version)))
        }
        val harness = createHarness(rpc)
        try {
            harness.feature.open(CHAT_ID)
            runCurrent()
            harness.feature.enter(parentFolder)
            advanceUntilIdle()
            harness.feature.enter(vanishedFolder)
            advanceUntilIdle()
            harness.feature.showVersions(staleFile)
            advanceUntilIdle()
            assertEquals(listOf(FOLDER_ID, NESTED_FOLDER_ID), harness.feature.path.map { it.entryId })
            assertEquals(staleFile, harness.feature.selectedFile)
            assertEquals(listOf(version), harness.feature.versions)

            val parentRefresh = CompletableDeferred<ResponsePayload>()
            // 第一次响应对照其兄弟节点校验重命名后的文件夹。它的缺失意味着
            // 另一个参与者在这个客户端观察到重命名确认之前删除了它。
            rpc.enqueueOk(entriesPayload(validParentEntry))
            rpc.enqueueDeferred(parentRefresh)
            assertTrue(
                harness.emitRecovery(
                    GroupFileCommandCompletion(
                        chatId = CHAT_ID,
                        entryId = NESTED_FOLDER_ID,
                        parentId = FOLDER_ID,
                        kind = PendingGroupFileCommandKind.RENAME,
                    ),
                ),
            )
            runCurrent()

            assertEquals(listOf(FOLDER_ID), harness.feature.path.map { it.entryId })
            assertEquals(listOf(validParentEntry), harness.feature.entries)
            assertNull(harness.feature.selectedFile)
            assertTrue(harness.feature.versions.isEmpty())
            assertTrue(harness.feature.loading)
            assertEquals(
                listOf(null, FOLDER_ID, NESTED_FOLDER_ID, FOLDER_ID, FOLDER_ID),
                rpc.listParentIds(),
            )

            parentRefresh.complete(ok(entriesPayload(validParentEntry)))
            advanceUntilIdle()

            assertEquals(listOf(validParentEntry), harness.feature.entries)
            assertFalse(harness.feature.loading)
            assertTrue(harness.errors.isEmpty())
        } finally {
            harness.close()
        }
    }

    private suspend fun TestScope.createHarness(
        rpc: ControlledRpcInvoker,
        cache: LocalCache = FakeLocalCache(initialDatasetId = DATASET_ID),
        storageDispatcher: CoroutineDispatcher? = null,
    ): Harness {
        val client = ImClient()
        val telemetrySpoolRoot = File(
            System.getProperty("java.io.tmpdir"),
            "teamtalk-group-files-state-test-${System.nanoTime()}",
        ).apply { mkdirs() }
        val user = UserSession().apply {
            restorePersistedLogin(OWNER_UID, "offline-refresh", DATASET_ID)
        }
        var session: ClientSession? = null
        try {
            client.prepareAuthentication(
                uid = OWNER_UID,
                token = "offline-refresh",
                deviceId = "group-files-state-test-device",
                deviceName = "Group files state test",
                host = OFFLINE_HOST,
                port = 5100,
            )
            withContext(Dispatchers.Default) {
                withTimeout(15_000L) { client.awaitTransportOwnerStart() }
            }
            val createdSession = createSession(
                imClient = client,
                userSession = user,
                deploymentIdentity = DeploymentIdentity.from(
                    tcpHost = OFFLINE_HOST,
                    tcpPort = 5100,
                    serverUrl = "https://offline.test.example",
                ),
                createCache = { _, _, _ -> cache },
                deviceId = "group-files-state-test-device",
                logUploadEnabled = false,
                telemetrySpoolRoot = telemetrySpoolRoot,
            )
            session = createdSession
            createdSession.installGroupFileRepository(GroupFileRepository(rpc, cache))
            val dispatcher = StandardTestDispatcher(testScheduler)
            val featureScope = CoroutineScope(dispatcher + SupervisorJob())
            val errors = mutableListOf<String>()
            val feedback = mutableListOf<UserFeedbackCode>()
            val feature = GroupFilesFeature(
                session = createdSession,
                scope = featureScope,
                reportError = { _, fallback -> errors += fallback },
                localData = UiLocalDataBoundary(storageDispatcher ?: dispatcher),
                reportFeedback = feedback::add,
            )
            @Suppress("UNCHECKED_CAST")
            val recovery = createdSession.groupFileRecoveryCompletions as MutableSharedFlow<GroupFileCommandCompletion>
            testScheduler.runCurrent()
            return Harness(
                client = client,
                session = createdSession,
                featureScope = featureScope,
                telemetrySpoolRoot = telemetrySpoolRoot,
                feature = feature,
                emitRecovery = recovery::tryEmit,
                errors = errors,
                feedback = feedback,
            )
        } catch (failure: Throwable) {
            runCatching { session?.close(reason = SessionEndReason.SHUTDOWN) }
            client.destroy()
            telemetrySpoolRoot.deleteRecursively()
            throw failure
        }
    }

    private fun ClientSession.installGroupFileRepository(repository: GroupFileRepository) {
        // ClientSession 刻意把它的 repository 暴露为只读。把这个不可避免的测试接缝
        // 约束到这个确切拥有的资源上，而不是按类型选择任何字段。
        val field = ClientSession::class.java.getDeclaredField("ownedGroupFileRepo")
        check(field.type == GroupFileRepository::class.java) {
            "ClientSession.ownedGroupFileRepo no longer stores GroupFileRepository"
        }
        field.isAccessible = true
        field.set(this, repository)
        check(groupFileRepo === repository) { "Test group-file repository was not installed" }
    }

    private data class Harness(
        val client: ImClient,
        val session: ClientSession,
        val featureScope: CoroutineScope,
        val telemetrySpoolRoot: File,
        val feature: GroupFilesFeature,
        val emitRecovery: (GroupFileCommandCompletion) -> Boolean,
        val errors: MutableList<String>,
        val feedback: MutableList<UserFeedbackCode>,
    ) {
        fun close() {
            featureScope.cancel()
            runCatching { session.close(reason = SessionEndReason.SHUTDOWN) }
            client.destroy()
            telemetrySpoolRoot.deleteRecursively()
        }
    }

    private class ControlledRpcInvoker : RpcInvoker {
        private val replies = ArrayDeque<suspend () -> ResponsePayload>()
        val calls = mutableListOf<Triple<String, Int, ByteArray?>>()

        fun enqueueOk(payload: ByteArray? = null) {
            replies.addLast { ok(payload) }
        }

        fun enqueueDeferred(response: CompletableDeferred<ResponsePayload>) {
            replies.addLast { response.await() }
        }

        override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?): ResponsePayload {
            calls += Triple(service, methodId, payload)
            return replies.removeFirstOrNull()?.invoke()
                ?: error("No scripted RPC response for $service/$methodId")
        }

        fun listParentIds(): List<String?> = calls
            .filter { it.second == GroupFileRpcContract.M_LIST }
            .map { call ->
                ProtoCodec.withPayload(call.third) {
                    readRequiredString(fieldName = "chatId")
                    readString()
                }
            }
    }

    private fun entriesPayload(vararg entries: GroupFileEntry): ByteArray =
        ProtoCodec.encodeList(entries.toList())

    private fun folder(
        name: String = "目录",
        revision: Long = 1L,
    ) = GroupFileEntry(
        entryId = FOLDER_ID,
        chatId = CHAT_ID,
        parentId = null,
        kind = GroupFileEntry.KIND_FOLDER,
        name = name,
        revision = revision,
        createdBy = OWNER_UID,
        createdAt = 1L,
        updatedBy = OWNER_UID,
        updatedAt = revision,
    )

    private fun nestedFolder(name: String) = GroupFileEntry(
        entryId = NESTED_FOLDER_ID,
        chatId = CHAT_ID,
        parentId = FOLDER_ID,
        kind = GroupFileEntry.KIND_FOLDER,
        name = name,
        revision = 1L,
        createdBy = OWNER_UID,
        createdAt = 1L,
        updatedBy = OWNER_UID,
        updatedAt = 1L,
    )

    private fun file(entryId: String, parentId: String?, name: String) = GroupFileEntry(
        entryId = entryId,
        chatId = CHAT_ID,
        parentId = parentId,
        kind = GroupFileEntry.KIND_FILE,
        name = name,
        attachment = Attachment(
            path = "owner/$entryId.bin",
            name = "$name.bin",
            contentType = "application/octet-stream",
            size = 138L,
        ),
        revision = 1L,
        contentVersion = 1L,
        createdBy = OWNER_UID,
        createdAt = 1L,
        updatedBy = OWNER_UID,
        updatedAt = 1L,
    )

    private companion object {
        const val OWNER_UID = "group-files-state-owner"
        const val DATASET_ID = "00000000-0000-4000-8000-000000000201"
        const val CHAT_ID = "00000000-0000-4000-8000-000000000202"
        const val FOLDER_ID = "00000000-0000-4000-8000-000000000203"
        const val ROOT_FILE_ID = "00000000-0000-4000-8000-000000000204"
        const val CHILD_FILE_ID = "00000000-0000-4000-8000-000000000205"
        const val NESTED_FOLDER_ID = "00000000-0000-4000-8000-000000000206"
        const val NESTED_FILE_ID = "00000000-0000-4000-8000-000000000207"
        const val PARENT_FILE_ID = "00000000-0000-4000-8000-000000000208"
        const val OFFLINE_HOST = "203.0.113.1"

        fun ok(payload: ByteArray?): ResponsePayload = ResponsePayload(1, 0, payload)

        fun rpcError(status: Int, message: String): ResponsePayload =
            ResponsePayload(1, status, message.encodeToByteArray())
    }
}
