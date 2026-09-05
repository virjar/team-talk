package com.virjar.tk.server.e2e

import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.Outcome
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpacePage
import com.virjar.tk.shared.repository.DocumentRepository
import com.virjar.tk.shared.repository.DocumentSpaceRefreshPageResult
import com.virjar.tk.shared.testkit.FakeLocalCache

internal suspend fun seedDocumentFixture(
    invocation: DocumentFixtureInvocation,
    manifest: DocumentFixtureManifest,
    plan: DocumentFixturePlan,
): DocumentFixtureManifest {
    require(manifest.status != DocumentFixtureStatus.ARCHIVED) {
        "Archived fixture cannot be seeded again; create a new private state directory"
    }
    require(manifest.status != DocumentFixtureStatus.OBSOLETE_DATASET) {
        "Obsolete fixture cannot cross a server dataset; create a new private state directory"
    }
    return withRemoteDocumentRepository(invocation.credentials) { session, repository ->
        var current = reconcileDocumentFixtureAuthority(
            manifest,
            session.uid,
            session.userSession.datasetId,
        )
        if (current.status == DocumentFixtureStatus.OBSOLETE_DATASET) {
            invocation.files.writeManifest(current)
            error("Fixture belongs to an obsolete server dataset; create a new private state directory")
        }
        current = current.copy(status = DocumentFixtureStatus.SEEDING)
        invocation.files.writeManifest(current)

        val space = requireNotNull(
            repository.createSpace(plan.spaceId, plan.spaceName, plan.spaceDescription).getOrThrow().space,
        ) { "Document fixture create acknowledgement unexpectedly lacked its current projection" }
        require(
            space.spaceId == plan.spaceId &&
                space.createdBy == session.uid &&
                space.myRole == DocumentSpace.ROLE_OWNER
        ) { "Document fixture space escaped its requested owner identity" }

        plan.nodes.forEachIndexed { index, spec ->
            val createResult = repository.createDocument(
                documentId = spec.documentId,
                spaceId = plan.spaceId,
                parentId = spec.parentId,
                title = spec.title,
                markdown = spec.markdown,
            ).getOrThrow()
            // 恢复执行的确定性 seed 可能命中一条已提交的 create 回执，其可靠确认
            // 有意省略了当前投影。夹具仍然拥有这个存活空间，因此通过普通授权读取来收敛该情形。
            val created = createResult.projection
                ?: repository.getDocument(plan.spaceId, spec.documentId).getOrThrow()
            require(
                created.documentId == spec.documentId &&
                    created.spaceId == plan.spaceId &&
                    created.parentId == spec.parentId &&
                    created.title == spec.title &&
                    created.markdown == spec.markdown
            ) { "Document fixture command escaped its deterministic identity" }
            val progress = index + 1
            if (progress % 25 == 0 || progress == plan.nodes.size) {
                current = current.copy(createdDocuments = maxOf(current.createdDocuments, progress))
                invocation.files.writeManifest(current)
                println("[DocumentFixture] progress=$progress/${plan.nodes.size}")
            }
        }

        verifyDocumentFixture(repository, plan)
        current.copy(
            status = DocumentFixtureStatus.READY,
            createdDocuments = plan.nodes.size,
            completedAtEpochMs = current.completedAtEpochMs ?: System.currentTimeMillis(),
            archivedAtEpochMs = null,
        ).also(invocation.files::writeManifest)
    }
}

internal suspend fun archiveDocumentFixture(
    invocation: DocumentFixtureInvocation,
    manifest: DocumentFixtureManifest,
    plan: DocumentFixturePlan,
): DocumentFixtureManifest {
    if (manifest.status == DocumentFixtureStatus.ARCHIVED) return manifest
    require(manifest.status != DocumentFixtureStatus.OBSOLETE_DATASET) {
        "Obsolete fixture cannot cross a server dataset; create a new private state directory"
    }
    if (manifest.ownerUid == null && manifest.datasetId == null) {
        return manifest.copy(
            status = DocumentFixtureStatus.ARCHIVED,
            archivedAtEpochMs = System.currentTimeMillis(),
        ).also(invocation.files::writeManifest)
    }
    return withRemoteDocumentRepository(invocation.credentials) { session, repository ->
        val current = reconcileDocumentFixtureAuthority(
            manifest,
            session.uid,
            session.userSession.datasetId,
        )
        if (current.status == DocumentFixtureStatus.OBSOLETE_DATASET) {
            invocation.files.writeManifest(current)
            error("Fixture belongs to an obsolete server dataset; create a new private state directory")
        }

        // 服务器持有这个精确 operationId 的持久完成回执。因此即使首次响应丢失，
        // 直接重发 archive 也是重试安全的。在这里创建 space 会破坏该重试：
        // 已归档的聚合会在 archiveSpace 有机会观察到其完成回执之前，正确拒绝 createSpace。
        when (val archived = repository.archiveSpace(plan.spaceId, current.archiveOperationId)) {
            is Outcome.Success -> Unit
            is Outcome.Failure -> {
                val wasNeverMaterialized = (archived.error as? AppError.Business)?.code == 404
                if (!wasNeverMaterialized) archived.getOrThrow()
            }
        }
        requireFixtureSpaceAbsent(repository, plan.spaceId)
        current.copy(
            status = DocumentFixtureStatus.ARCHIVED,
            archivedAtEpochMs = current.archivedAtEpochMs ?: System.currentTimeMillis(),
        ).also(invocation.files::writeManifest)
    }
}

internal fun reconcileDocumentFixtureAuthority(
    manifest: DocumentFixtureManifest,
    ownerUid: String,
    datasetId: String,
): DocumentFixtureManifest {
    require(ownerUid.isNotBlank() && datasetId.isNotBlank()) {
        "Authenticated fixture authority is incomplete"
    }
    if (manifest.datasetId != null && manifest.datasetId != datasetId) {
        return manifest.copy(status = DocumentFixtureStatus.OBSOLETE_DATASET)
    }
    manifest.ownerUid?.let { require(it == ownerUid) { "Fixture belongs to a different account" } }
    return manifest.copy(ownerUid = ownerUid, datasetId = datasetId)
}

private suspend fun verifyDocumentFixture(repository: DocumentRepository, plan: DocumentFixturePlan) {
    val childrenByParent = plan.nodes.groupBy(DocumentFixtureNodeSpec::parentId)
    val expectedHasChildren = plan.nodes.mapTo(hashSetOf()) { it.parentId }
    val branchParents = listOf<String?>(null) + plan.nodes.filter { it.level < 3 }.map { it.documentId }
    branchParents.forEach { parentId ->
        val expected = childrenByParent[parentId].orEmpty()
        val actual = repository.listNodes(plan.spaceId, parentId).getOrThrow()
        require(actual.mapTo(hashSetOf()) { it.nodeId } == expected.mapTo(hashSetOf()) { it.documentId }) {
            "Document fixture branch does not match its deterministic plan"
        }
        val actualById = actual.associateBy { it.nodeId }
        expected.forEach { spec ->
            val node = checkNotNull(actualById[spec.documentId])
            require(
                node.name == spec.title &&
                    node.parentId == spec.parentId &&
                    node.hasChildren == (spec.documentId in expectedHasChildren)
            ) { "Document fixture node projection is inconsistent" }
        }
    }

    val byId = plan.nodes.associateBy(DocumentFixtureNodeSpec::documentId)
    val bodyIds = listOf(
        plan.representatives.middleWithChildrenId,
        plan.representatives.rootWithChildrenId,
        plan.representatives.leafId,
    )
    bodyIds.forEach { documentId ->
        val expected = byId.getValue(documentId)
        val actual = repository.getDocument(plan.spaceId, documentId).getOrThrow()
        require(actual.title == expected.title && actual.markdown == expected.markdown) {
            "Document fixture body projection is inconsistent"
        }
    }
    val recentIds = repository.listRecentDocuments(10).getOrThrow().mapTo(hashSetOf()) { it.documentId }
    require(
        plan.representatives.rootWithChildrenId in recentIds &&
            plan.representatives.leafId in recentIds
    ) { "Document fixture recent projection must contain both an inner and a leaf document" }
}

private suspend fun requireFixtureSpaceAbsent(repository: DocumentRepository, spaceId: String) {
    require(repository.refreshAllDocumentSpacesForAcceptance().none { it.spaceId == spaceId }) {
        "Archived document fixture remains visible in the authority space list"
    }
}

/** 仅测试使用的完整刷新；生产调用方必须显式掌握自己的刷新周期。 */
internal suspend fun DocumentRepository.refreshAllDocumentSpacesForAcceptance(): List<DocumentSpace> {
    repeat(MAX_DOCUMENT_SPACE_REFRESH_ATTEMPTS) {
        val cycle = beginSpaceRefreshCycle()
        val spaces = mutableListOf<DocumentSpace>()
        var cursor: String? = null
        try {
            while (true) {
                when (
                    val result = refreshSpacesPage(
                        cycle = cycle,
                        cursor = cursor,
                        limit = DocumentSpacePage.MAX_PAGE_SIZE,
                    ).getOrThrow()
                ) {
                    is DocumentSpaceRefreshPageResult.Page -> {
                        spaces += result.value.items
                        cursor = result.value.nextCursor
                        if (cursor == null) return spaces
                    }
                    DocumentSpaceRefreshPageResult.RestartRequired -> break
                }
            }
        } catch (failure: Throwable) {
            cycle.cancel()
            throw failure
        }
    }
    error("Document space directory kept changing during acceptance refresh")
}

private const val MAX_DOCUMENT_SPACE_REFRESH_ATTEMPTS = 3

private suspend fun <T> withRemoteDocumentRepository(
    credentials: DocumentFixtureCredentials,
    block: suspend (RemoteAcceptanceSupport.Session, DocumentRepository) -> T,
): T {
    var session: RemoteAcceptanceSupport.Session? = null
    var cache: FakeLocalCache? = null
    try {
        session = RemoteAcceptanceSupport.loginUser(credentials.username, credentials.password)
        cache = FakeLocalCache(initialDatasetId = session.userSession.datasetId)
        return block(session, DocumentRepository(session.rpc, cache))
    } finally {
        try {
            cache?.close()
        } finally {
            try {
                session?.close()
            } finally {
                RemoteAcceptanceSupport.shutdown()
            }
        }
    }
}
