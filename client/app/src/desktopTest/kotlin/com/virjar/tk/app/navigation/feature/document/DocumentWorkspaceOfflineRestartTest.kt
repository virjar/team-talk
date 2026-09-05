package com.virjar.tk.app.navigation.feature.document

import com.virjar.tk.app.navigation.feature.GenerationGate

import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.client.ClientSession
import com.virjar.tk.shared.client.DeploymentIdentity
import com.virjar.tk.shared.client.ImClient
import com.virjar.tk.shared.client.PendingDocumentMoveCommand
import com.virjar.tk.shared.client.SessionEndReason
import com.virjar.tk.shared.client.UserSession
import com.virjar.tk.shared.client.createSession
import com.virjar.tk.protocol.model.Document
import com.virjar.tk.protocol.model.DocumentNode
import com.virjar.tk.protocol.model.DocumentPathSpine
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.app.navigation.UiLocalDataBoundary
import com.virjar.tk.protocol.payload.ResponsePayload
import com.virjar.tk.shared.repository.DocumentRepository
import com.virjar.tk.protocol.rpc.RpcInvoker
import com.virjar.tk.protocol.rpc.gen.DocumentRpcContract
import com.virjar.tk.shared.testkit.FakeLocalCache
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentWorkspaceOfflineRestartTest {
    @Test
    fun `cached tree and dirty document survive an offline desktop restart`() = runTest {
        val fixture = createFixture()
        val persistence = MemoryDocumentDraftPersistence()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val featureScope = CoroutineScope(dispatcher + SupervisorJob())
        val errors = mutableListOf<Pair<Throwable, String>>()
        try {
            val space = space()
            val document = document()
            val node = node(document)
            seedLocalProjection(fixture.cache, space, document, node)
            fixture.cache.preparePendingDocumentMoveCommand(
                PendingDocumentMoveCommand.create(
                    operationId = OPERATION_ID,
                    spaceId = SPACE_ID,
                    nodeId = DOCUMENT_ID,
                    oldParentId = null,
                    targetParentId = null,
                    name = "离线改名",
                    expectedRevision = document.revision,
                    issuedAt = 1_800_000_000_000L,
                ),
            )

            val restoredTab = DocumentTabState.from(document, instanceId = 17L).copy(
                draftTitle = "离线改名",
                draftMarkdown = "服务停机后继续编辑的正文",
                dirty = true,
                editGeneration = 2L,
            )
            val ownerKey = DocumentDraftOwnerKey(
                deploymentFingerprint = fixture.deploymentIdentity.fingerprint,
                datasetId = DATASET_ID,
                uid = OWNER_UID,
            )
            val previousProcessStore = DocumentDraftStore(persistence)
            assertTrue(
                previousProcessStore.save(
                    key = ownerKey,
                    tabs = listOf(restoredTab),
                    activeTabId = restoredTab.tabId,
                    selectedSpaceId = SPACE_ID,
                ),
            )
            assertTrue(previousProcessStore.flush())

            // 一个全新的 store 和 feature 代表正常的 Desktop 进程重启边界。
            val feature = DocumentWorkspaceFeature(
                session = fixture.session,
                scope = featureScope,
                reportError = { failure, message -> errors += failure to message },
                draftStore = DocumentDraftStore(persistence),
                localData = UiLocalDataBoundary(dispatcher),
            )
            val opening = featureScope.async { feature.open() }
            advanceUntilIdle()
            opening.await()

            assertEquals(listOf(node), feature.treeChildren[null])
            assertEquals(DocumentWorkspaceProjectionStatus.OFFLINE_CACHED, feature.treeProjectionStatus)
            assertEquals("离线改名", feature.activeTab?.draftTitle)
            assertEquals("服务停机后继续编辑的正文", feature.activeTab?.draftMarkdown)
            assertTrue(feature.moving, "the restored durable rename must remain visibly pending")
            assertEquals(
                listOf(OPERATION_ID),
                fixture.cache.getPendingDocumentMoveCommands().map { it.operationId },
            )
            assertEquals(
                listOf(
                    DocumentRpcContract.M_LIST_SPACES,
                    DocumentRpcContract.M_LIST_NODES,
                    DocumentRpcContract.M_GET_NODE_PATH_SPINE,
                ),
                fixture.rpc.calls.map { it.second },
            )
            assertTrue(errors.isEmpty(), "cached offline recovery must not repeat expected errors")
        } finally {
            featureScope.cancel()
            fixture.close()
        }
    }

    @Test
    fun `cached directory survives offline restart when the document body was never cached`() =
        runTest {
            val fixture = createFixture()
            val persistence = MemoryDocumentDraftPersistence()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val featureScope = CoroutineScope(dispatcher + SupervisorJob())
            val errors = mutableListOf<Pair<Throwable, String>>()
            try {
                val space = space()
                val document = document().copy(
                    parentId = ROOT_ID,
                    ancestorIds = listOf(ROOT_ID),
                )
                val root = rootNode()
                val documentNode = node(document)
                seedDirectoryProjectionWithoutBody(
                    cache = fixture.cache,
                    space = space,
                    root = root,
                    documentNode = documentNode,
                )

                val restoredTab = DocumentTabState.from(document, instanceId = 18L).copy(
                    draftTitle = "仅有目录缓存的离线改名",
                    draftMarkdown = "正文缓存缺失时仍须保留的本机草稿",
                    dirty = true,
                    editGeneration = 3L,
                )
                val ownerKey = DocumentDraftOwnerKey(
                    deploymentFingerprint = fixture.deploymentIdentity.fingerprint,
                    datasetId = DATASET_ID,
                    uid = OWNER_UID,
                )
                val previousProcessStore = DocumentDraftStore(persistence)
                assertTrue(
                    previousProcessStore.save(
                        key = ownerKey,
                        tabs = listOf(restoredTab),
                        activeTabId = restoredTab.tabId,
                        selectedSpaceId = SPACE_ID,
                    ),
                )
                assertTrue(previousProcessStore.flush())

                val feature = DocumentWorkspaceFeature(
                    session = fixture.session,
                    scope = featureScope,
                    reportError = { failure, message -> errors += failure to message },
                    draftStore = DocumentDraftStore(persistence),
                    localData = UiLocalDataBoundary(dispatcher),
                )
                val opening = featureScope.async { feature.open() }
                advanceUntilIdle()
                opening.await()

                assertEquals(listOf(root), feature.treeChildren[null])
                assertEquals(listOf(documentNode), feature.treeChildren[ROOT_ID])
                assertEquals(listOf(ROOT_ID, DOCUMENT_ID), feature.treeRows.map { it.node.nodeId })
                assertTrue(ROOT_ID in feature.expandedNodeIds)
                assertEquals(DocumentWorkspaceProjectionStatus.OFFLINE_CACHED, feature.treeProjectionStatus)
                assertEquals("仅有目录缓存的离线改名", feature.activeTab?.draftTitle)
                assertEquals("正文缓存缺失时仍须保留的本机草稿", feature.activeTab?.draftMarkdown)
                assertFalse(feature.activeTab?.pathResolved ?: true)
                assertEquals(
                    listOf(
                        DocumentRpcContract.M_LIST_SPACES,
                        DocumentRpcContract.M_GET_DOCUMENT,
                        DocumentRpcContract.M_LIST_NODES,
                    ),
                    fixture.rpc.calls.map { it.second },
                )
                assertEquals(1, errors.size)
                assertEquals(AppError.Network, errors.single().first)
                assertEquals(
                    "文档正文暂时无法校验，已恢复本机目录和草稿",
                    errors.single().second,
                )
            } finally {
                featureScope.cancel()
                fixture.close()
            }
        }

    @Test
    fun `business failure during restored directory recovery is reported only once`() = runTest {
        val serverFailure = AppError.Business(500, "文档服务异常")
        val fixture = createFixture(serverFailure)
        val persistence = MemoryDocumentDraftPersistence()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val featureScope = CoroutineScope(dispatcher + SupervisorJob())
        val errors = mutableListOf<Pair<Throwable, String>>()
        try {
            val space = space()
            val document = document()
            val node = node(document)
            seedLocalProjection(fixture.cache, space, document, node)
            val restoredTab = DocumentTabState.from(document, instanceId = 19L).copy(
                draftMarkdown = "500 时仍保留的本机草稿",
                dirty = true,
                editGeneration = 4L,
            )
            val ownerKey = DocumentDraftOwnerKey(
                deploymentFingerprint = fixture.deploymentIdentity.fingerprint,
                datasetId = DATASET_ID,
                uid = OWNER_UID,
            )
            val previousProcessStore = DocumentDraftStore(persistence)
            assertTrue(
                previousProcessStore.save(
                    key = ownerKey,
                    tabs = listOf(restoredTab),
                    activeTabId = restoredTab.tabId,
                    selectedSpaceId = SPACE_ID,
                ),
            )
            assertTrue(previousProcessStore.flush())

            val feature = DocumentWorkspaceFeature(
                session = fixture.session,
                scope = featureScope,
                reportError = { failure, message -> errors += failure to message },
                draftStore = DocumentDraftStore(persistence),
                localData = UiLocalDataBoundary(dispatcher),
            )
            val opening = featureScope.async { feature.open() }
            advanceUntilIdle()
            opening.await()

            assertEquals(listOf(node), feature.treeChildren[null])
            assertEquals("500 时仍保留的本机草稿", feature.activeTab?.draftMarkdown)
            assertEquals(
                listOf(
                    DocumentRpcContract.M_LIST_SPACES,
                    DocumentRpcContract.M_LIST_NODES,
                    DocumentRpcContract.M_GET_NODE_PATH_SPINE,
                ),
                fixture.rpc.calls.map { it.second },
            )
            assertEquals(1, errors.size)
            assertEquals(serverFailure, errors.single().first)
            assertEquals("恢复文档目录失败，草稿已保留", errors.single().second)
        } finally {
            featureScope.cancel()
            fixture.close()
        }
    }

    @Test
    fun `business first page failure is reported when restoration itself has no error`() = runTest {
        val serverFailure = AppError.Business(500, "文档服务异常")
        val fixture = createFixture(serverFailure)
        val persistence = MemoryDocumentDraftPersistence()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val featureScope = CoroutineScope(dispatcher + SupervisorJob())
        val errors = mutableListOf<Pair<Throwable, String>>()
        try {
            val space = space()
            seedSpaceAndRoot(fixture.cache, space, emptyList())
            val restoredTab = newDocumentDraftTab(
                tabId = LOCAL_DOCUMENT_ID,
                instanceId = 20L,
                spaceId = SPACE_ID,
                location = DocumentCreationLocation(parentId = null, ancestorIds = emptyList()),
            ).copy(draftMarkdown = "未保存的新文档")
            val ownerKey = DocumentDraftOwnerKey(
                deploymentFingerprint = fixture.deploymentIdentity.fingerprint,
                datasetId = DATASET_ID,
                uid = OWNER_UID,
            )
            val previousProcessStore = DocumentDraftStore(persistence)
            assertTrue(
                previousProcessStore.save(
                    key = ownerKey,
                    tabs = listOf(restoredTab),
                    activeTabId = restoredTab.tabId,
                    selectedSpaceId = SPACE_ID,
                ),
            )
            assertTrue(previousProcessStore.flush())

            val feature = DocumentWorkspaceFeature(
                session = fixture.session,
                scope = featureScope,
                reportError = { failure, message -> errors += failure to message },
                draftStore = DocumentDraftStore(persistence),
                localData = UiLocalDataBoundary(dispatcher),
            )
            val opening = featureScope.async { feature.open() }
            advanceUntilIdle()
            opening.await()

            assertEquals("未保存的新文档", feature.activeTab?.draftMarkdown)
            assertEquals(
                listOf(
                    DocumentRpcContract.M_LIST_SPACES,
                    DocumentRpcContract.M_LIST_NODES,
                ),
                fixture.rpc.calls.map { it.second },
            )
            assertEquals(1, errors.size)
            assertEquals(serverFailure, errors.single().first)
            assertEquals("文档服务离线，已恢复本机未保存草稿", errors.single().second)
        } finally {
            featureScope.cancel()
            fixture.close()
        }
    }

    @Test
    fun `new navigation wins while a restored cached body read is suspended`() = runTest {
        val navigation = GenerationGate()
        val restorationGeneration = navigation.next()
        val cachedBody = CompletableDeferred<Document?>()
        var cachedReads = 0
        var remoteReads = 0

        val restoration = async(start = CoroutineStart.UNDISPATCHED) {
            loadRestoredDocumentBody(
                ownerIsCurrent = { navigation.isCurrent(restorationGeneration) },
                readCached = {
                    cachedReads += 1
                    cachedBody.await()
                },
                refresh = {
                    remoteReads += 1
                    document()
                },
            )
        }
        assertFalse(restoration.isCompleted)

        navigation.next()
        cachedBody.complete(document())

        assertIs<RestoredDocumentBodyLoad.Superseded>(restoration.await())
        assertEquals(1, cachedReads)
        assertEquals(0, remoteReads)
    }

    private suspend fun createFixture(
        rpcFailure: AppError = AppError.Network,
    ): Fixture {
        val client = ImClient()
        val cache = FakeLocalCache(initialDatasetId = DATASET_ID)
        val deploymentIdentity = DeploymentIdentity.from(
            tcpHost = OFFLINE_HOST,
            tcpPort = 5100,
            serverUrl = "https://offline.test.example",
        )
        val telemetrySpoolRoot = File(
            System.getProperty("java.io.tmpdir"),
            "teamtalk-document-restart-test-${System.nanoTime()}",
        ).apply { mkdirs() }
        var session: ClientSession? = null
        return try {
            val user = UserSession().apply {
                restorePersistedLogin(OWNER_UID, "offline-refresh", DATASET_ID)
            }
            client.prepareAuthentication(
                uid = OWNER_UID,
                token = "offline-refresh",
                deviceId = "document-restart-test-device",
                deviceName = "Document restart test",
                host = OFFLINE_HOST,
                port = 5100,
            )
            withContext(Dispatchers.Default) {
                withTimeout(15_000L) { client.awaitTransportOwnerStart() }
            }
            val createdSession = createSession(
                imClient = client,
                userSession = user,
                deploymentIdentity = deploymentIdentity,
                createCache = { _, _, _ -> cache },
                deviceId = "document-restart-test-device",
                logUploadEnabled = false,
                telemetrySpoolRoot = telemetrySpoolRoot,
            )
            session = createdSession
            val rpc = RecordingUnavailableRpcInvoker(rpcFailure)
            createdSession.installDocumentRepository(
                DocumentRepository(rpc, cache),
            )
            Fixture(
                client = client,
                cache = cache,
                deploymentIdentity = deploymentIdentity,
                telemetrySpoolRoot = telemetrySpoolRoot,
                session = createdSession,
                rpc = rpc,
            )
        } catch (failure: Throwable) {
            runCatching { session?.close(reason = SessionEndReason.SHUTDOWN) }
            client.destroy()
            telemetrySpoolRoot.deleteRecursively()
            throw failure
        }
    }

    private fun ClientSession.installDocumentRepository(repository: DocumentRepository) {
        val field = ClientSession::class.java.getDeclaredField("ownedDocumentRepo")
        check(field.type == DocumentRepository::class.java)
        field.isAccessible = true
        field.set(this, repository)
        check(documentRepo === repository)
    }

    private fun seedLocalProjection(
        cache: FakeLocalCache,
        space: DocumentSpace,
        document: Document,
        node: DocumentNode,
    ) {
        val spaceLease = cache.beginDocumentSpaceSnapshot()
        assertTrue(
            cache.applyDocumentSpaceRefreshPage(
                lease = spaceLease,
                spaces = listOf(space),
                isFirstPage = true,
                isTerminal = true,
            ),
        )
        val bodyLease = cache.beginDocumentBodySnapshot(SPACE_ID, DOCUMENT_ID)
        assertTrue(cache.applyDocumentBodySnapshot(bodyLease, document))
        val branchLease = cache.beginDocumentBranchSnapshot(SPACE_ID, null)
        assertTrue(cache.applyDocumentBranchSnapshot(branchLease, SPACE_ID, null, listOf(node)))
        val pathLease = cache.beginDocumentPathSpineSnapshot(SPACE_ID, DOCUMENT_ID)
        assertTrue(
            cache.applyDocumentPathSpineSnapshot(
                pathLease,
                SPACE_ID,
                DOCUMENT_ID,
                DocumentPathSpine(listOf(node)),
            ),
        )
    }

    private fun seedDirectoryProjectionWithoutBody(
        cache: FakeLocalCache,
        space: DocumentSpace,
        root: DocumentNode,
        documentNode: DocumentNode,
    ) {
        val spaceLease = cache.beginDocumentSpaceSnapshot()
        assertTrue(
            cache.applyDocumentSpaceRefreshPage(
                lease = spaceLease,
                spaces = listOf(space),
                isFirstPage = true,
                isTerminal = true,
            ),
        )
        val branchLease = cache.beginDocumentBranchSnapshot(SPACE_ID, null)
        assertTrue(cache.applyDocumentBranchSnapshot(branchLease, SPACE_ID, null, listOf(root)))
        val pathLease = cache.beginDocumentPathSpineSnapshot(SPACE_ID, DOCUMENT_ID)
        assertTrue(
            cache.applyDocumentPathSpineSnapshot(
                pathLease,
                SPACE_ID,
                DOCUMENT_ID,
                DocumentPathSpine(listOf(root, documentNode)),
            ),
        )
    }

    private fun seedSpaceAndRoot(
        cache: FakeLocalCache,
        space: DocumentSpace,
        nodes: List<DocumentNode>,
    ) {
        val spaceLease = cache.beginDocumentSpaceSnapshot()
        assertTrue(
            cache.applyDocumentSpaceRefreshPage(
                lease = spaceLease,
                spaces = listOf(space),
                isFirstPage = true,
                isTerminal = true,
            ),
        )
        val branchLease = cache.beginDocumentBranchSnapshot(SPACE_ID, null)
        assertTrue(cache.applyDocumentBranchSnapshot(branchLease, SPACE_ID, null, nodes))
    }

    private fun space() = DocumentSpace(
        spaceId = SPACE_ID,
        name = "离线文档空间",
        myRole = DocumentSpace.ROLE_EDITOR,
        createdBy = OWNER_UID,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun document() = Document(
        documentId = DOCUMENT_ID,
        spaceId = SPACE_ID,
        title = "服务器标题",
        markdown = "服务器正文",
        revision = 7L,
        createdBy = OWNER_UID,
        createdAt = 1L,
        updatedBy = OWNER_UID,
        updatedAt = 7L,
    )

    private fun node(document: Document) = DocumentNode(
        nodeId = document.documentId,
        spaceId = document.spaceId,
        parentId = document.parentId,
        hasChildren = false,
        name = document.title,
        revision = document.revision,
        createdBy = document.createdBy,
        createdAt = document.createdAt,
        updatedBy = document.updatedBy,
        updatedAt = document.updatedAt,
    )

    private fun rootNode() = DocumentNode(
        nodeId = ROOT_ID,
        spaceId = SPACE_ID,
        parentId = null,
        hasChildren = true,
        name = "缓存目录",
        revision = 5L,
        createdBy = OWNER_UID,
        createdAt = 1L,
        updatedBy = OWNER_UID,
        updatedAt = 5L,
    )

    private data class Fixture(
        val client: ImClient,
        val cache: FakeLocalCache,
        val deploymentIdentity: DeploymentIdentity,
        val telemetrySpoolRoot: File,
        val session: ClientSession,
        val rpc: RecordingUnavailableRpcInvoker,
    ) {
        fun close() {
            runCatching { session.close(reason = SessionEndReason.SHUTDOWN) }
            client.destroy()
            telemetrySpoolRoot.deleteRecursively()
        }
    }

    private class RecordingUnavailableRpcInvoker(
        private val failure: AppError,
    ) : RpcInvoker {
        val calls = mutableListOf<Pair<String, Int>>()

        override suspend fun invoke(
            service: String,
            methodId: Int,
            payload: ByteArray?,
        ): ResponsePayload {
            calls += service to methodId
            throw failure
        }
    }

    private class MemoryDocumentDraftPersistence : DocumentDraftPersistence {
        private data class Stored(
            val manifest: String,
            val records: Map<String, String>,
        )

        private val stored = mutableMapOf<DocumentDraftOwnerKey, Stored>()
        private val tombstones = mutableMapOf<DocumentDraftOwnerKey, MutableSet<String>>()

        override fun read(
            ownerKey: DocumentDraftOwnerKey,
            consume: (DocumentDraftRecordSource) -> Unit,
        ): DocumentDraftReadStatus {
            val value = stored[ownerKey] ?: return DocumentDraftReadStatus.ABSENT
            consume(object : DocumentDraftRecordSource {
                override val manifest: String = value.manifest
                override val tombstones: Set<String> =
                    this@MemoryDocumentDraftPersistence.tombstones[ownerKey].orEmpty().toSet()

                override fun recordByteCount(key: String): Long? =
                    value.records[key]?.encodeToByteArray()?.size?.toLong()

                override fun readRecord(key: String): String? = value.records[key]
            })
            return DocumentDraftReadStatus.AVAILABLE
        }

        override fun write(
            ownerKey: DocumentDraftOwnerKey,
            payload: () -> DocumentDraftPayload,
        ): Boolean {
            val encoded = payload()
            stored[ownerKey] = Stored(
                encoded.manifest,
                encoded.records.associate { it.key to it.payload() },
            )
            tombstones[ownerKey] = tombstones[ownerKey]
                .orEmpty()
                .intersect(encoded.activeRecoveryKeys)
                .toMutableSet()
            return true
        }

        override fun flush(): Boolean = true

        override fun tombstone(
            ownerKey: DocumentDraftOwnerKey,
            recoveryKeys: Set<String>,
        ): Boolean {
            tombstones.getOrPut(ownerKey, ::linkedSetOf).addAll(recoveryKeys)
            return true
        }

        override fun delete(ownerKey: DocumentDraftOwnerKey): Boolean {
            stored.remove(ownerKey)
            tombstones.remove(ownerKey)
            return true
        }

        override fun clearAll(): Boolean {
            stored.clear()
            tombstones.clear()
            return true
        }
    }

    private companion object {
        const val OWNER_UID = "document-offline-owner"
        const val DATASET_ID = "00000000-0000-4000-8000-000000000301"
        const val SPACE_ID = "00000000-0000-4000-8000-000000000302"
        const val DOCUMENT_ID = "00000000-0000-4000-8000-000000000303"
        const val OPERATION_ID = "00000000-0000-4000-8000-000000000304"
        const val ROOT_ID = "00000000-0000-4000-8000-000000000305"
        const val LOCAL_DOCUMENT_ID = "00000000-0000-4000-8000-000000000306"
        const val OFFLINE_HOST = "203.0.113.1"
    }
}
