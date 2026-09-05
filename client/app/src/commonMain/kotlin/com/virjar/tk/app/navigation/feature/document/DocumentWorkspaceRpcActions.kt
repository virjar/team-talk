package com.virjar.tk.app.navigation.feature.document

import com.virjar.tk.app.navigation.feature.LatestRequestGate

import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.Outcome
import com.virjar.tk.protocol.model.DocumentHomeItem
import com.virjar.tk.protocol.model.DocumentPolicy
import com.virjar.tk.protocol.model.DocumentPolicyMutationResult
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import com.virjar.tk.protocol.model.OrganizationUnit
import com.virjar.tk.protocol.model.User
import com.virjar.tk.protocol.model.UserRole
import com.virjar.tk.app.navigation.UiLocalDataBoundary
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 共享的 UI owner 失败边界：取消保留对象身份，绝不变成 UI。 */
internal fun Exception.rethrowIfDocumentWorkspaceCancelled() {
    if (this is CancellationException) throw this
}

internal fun Throwable.isDocumentSpaceAccessDenied(): Boolean =
    this is AppError.Business && code == 403

/** 只用于 404 被定义为目标空间本身不存在的 route。 */
private fun Throwable.isTerminalDocumentSpaceRouteFailure(): Boolean =
    this is AppError.Business && code in setOf(403, 404)

/** 并发加载两个首页投影，保留它们独立的 repository 调用。 */
internal suspend fun loadDocumentWorkspaceHome(
    repository: DocumentRepositoryBoundary,
): Pair<List<DocumentHomeItem>, List<DocumentHomeItem>> = coroutineScope {
    val recent = async {
        repository.call { listRecentDocuments(HOME_LIMIT).getOrThrow() }
    }
    val created = async {
        repository.call { listRecentlyCreatedDocuments(HOME_LIMIT).getOrThrow() }
    }
    recent.await() to created.await()
}

internal data class DocumentSpaceCreateIntent(
    val name: String,
    val description: String?,
)

internal data class DocumentSpaceCreateRequest(
    val intent: DocumentSpaceCreateIntent,
    val spaceId: String,
)

/** 规范载荷相等性必须匹配服务器的幂等指纹规范化。 */
internal fun DocumentSpaceCreateRequest.normalized(): DocumentSpaceCreateRequest? {
    val normalizedName = runCatching { DocumentPolicy.normalizeSpaceName(intent.name) }.getOrNull()
        ?: return null
    val descriptionResult = runCatching {
        DocumentPolicy.normalizeDescription(intent.description)
    }
    if (descriptionResult.isFailure) return null
    val normalizedDescription = descriptionResult.getOrNull()
    val canonicalSpaceId = try {
        UUID.fromString(spaceId).toString().takeIf { it == spaceId }
    } catch (_: IllegalArgumentException) {
        null
    } ?: return null
    return copy(
        intent = DocumentSpaceCreateIntent(normalizedName, normalizedDescription),
        spaceId = canonicalSpaceId,
    )
}

private class DocumentSpaceCreateOperation(
    val request: DocumentSpaceCreateRequest,
    var navigationGeneration: Long?,
) {
    lateinit var job: Job
    var localPersistenceAdmitted: Boolean = false
}

/** 拥有文档空间元数据修改；选择/导航保留在工作区。 */
internal class DocumentWorkspaceSpaceActions(
    private val repository: DocumentRepositoryBoundary,
    private val scope: CoroutineScope,
    private val reportError: (Throwable, String) -> Unit,
    private val selectedSpaceId: () -> String?,
    private val spaces: () -> List<DocumentSpace>,
    private val updateSpaces: (List<DocumentSpace>, String) -> Boolean,
    private val beginNavigation: () -> Long,
    private val isCurrentNavigation: (Long) -> Boolean,
    private val selectSpace: suspend (String, Long) -> Unit,
    private val createOutbox: DocumentDurableCreateOutbox,
    private val persistDrafts: () -> Boolean,
    private val awaitDraftDurability: suspend () -> Boolean,
    private val tombstoneDrafts: suspend (Set<String>) -> Boolean,
    private val onPendingCreatesChanged: () -> Unit,
) {
    private val createOperationLock = Any()
    private val createOperations = mutableMapOf<String, DocumentSpaceCreateOperation>()

    fun create(name: String, description: String?): Job {
        val request = createOutbox.acquireSpace(name, description)
        onPendingCreatesChanged()
        return startCreate(request, beginNavigation())
    }

    /** 在不偷走用户当前导航目标的情况下，重放一条已恢复的持久命令。 */
    fun retryPending(request: DocumentSpaceCreateRequest): Job = startCreate(request, null)

    /** 只有在请求身份拥有持久取消墓碑之后才调用。 */
    fun discardPendingAfterTombstone(request: DocumentSpaceCreateRequest): Boolean {
        synchronized(createOperationLock) {
            createOperations.remove(request.spaceId)?.job?.cancel()
        }
        val discarded = createOutbox.discardSpace(request)
        if (discarded) {
            persistDrafts()
            onPendingCreatesChanged()
        }
        return discarded
    }

    private fun startCreate(request: DocumentSpaceCreateRequest, navigationGeneration: Long?): Job {
        var shouldStart = false
        val operation = synchronized(createOperationLock) {
            createOperations[request.spaceId]?.takeIf { current ->
                !current.job.isCompleted
            }?.also { current ->
                if (navigationGeneration != null) current.navigationGeneration = navigationGeneration
            } ?: DocumentSpaceCreateOperation(
                request = request,
                navigationGeneration = navigationGeneration,
            ).also { created ->
                created.job = scope.launch(start = CoroutineStart.LAZY) {
                    executeCreate(created)
                }
                createOperations[request.spaceId] = created
                // 稳定的资源 ID 在其 RPC 可以启动之前，就被准入到持久本地存储。
                // 因此丢失响应/进程重启会重试同一个服务器 key。
                created.localPersistenceAdmitted = persistDrafts()
                shouldStart = true
            }
        }
        if (shouldStart) operation.job.start()
        return operation.job
    }

    private suspend fun executeCreate(operation: DocumentSpaceCreateOperation) {
        val request = operation.request
        try {
            check(operation.localPersistenceAdmitted && awaitDraftDurability()) {
                "无法持久保存文档空间创建标识，已阻止发送请求"
            }
            val result = repository.call(
                spaceId = request.spaceId,
                notFoundRetiresSpace = true,
            ) {
                createSpace(
                    spaceId = request.spaceId,
                    name = request.intent.name,
                    description = request.intent.description,
                ).getOrThrow()
            }
            check(result.spaceId == request.spaceId) {
                "创建文档空间响应的资源 ID 与请求不一致"
            }
            if (!tombstoneDrafts(setOf(request.draftRecoveryKey()))) {
                reportError(
                    IllegalStateException("Document space create cleanup is not durable"),
                    "空间已在服务器创建，但本机创建命令收尾失败；待办仍保留",
                )
                return
            }
            if (createOutbox.completeSpace(request)) onPendingCreatesChanged()
            // 把移除作为另一个单调快照持久化。如果进程在这次写入落地之前停止，
            // 重放保留的 key 仍然是安全的，并且返回相同的命令确认。
            persistDrafts()
            val created = result.space
            if (created == null) {
                // 稳定的创建身份到达了服务器，但之后的 custody 转移或归档
                // 把它从调用方当前的投影中移除了。
                return
            }
            val published = updateSpaces(
                listOf(created) + spaces().filterNot { it.spaceId == created.spaceId },
                created.spaceId,
            )
            if (!published) return
            operation.navigationGeneration?.let { generation ->
                if (isCurrentNavigation(generation)) selectSpace(created.spaceId, generation)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (!operation.localPersistenceAdmitted && createOutbox.discardSpace(request)) {
                // RPC 被围栏隔离在准入检查之后，因此这个身份从未被发送。
                // 不要把不可持久化的冻结意图滞留在内存 outbox 中。
                persistDrafts()
                onPendingCreatesChanged()
            }
            val generation = operation.navigationGeneration
            if (generation == null || isCurrentNavigation(generation)) {
                reportError(failure, "创建文档空间失败")
            }
        } finally {
            synchronized(createOperationLock) {
                if (createOperations[request.spaceId] === operation) {
                    createOperations.remove(request.spaceId)
                }
            }
        }
    }

    fun update(name: String, description: String?): Job {
        val spaceId = selectedSpaceId()
        return scope.launch {
            try {
                val targetSpaceId = spaceId ?: return@launch
                val result = repository.call(
                    spaceId = targetSpaceId,
                    notFoundRetiresSpace = true,
                ) {
                    updateSpace(targetSpaceId, name, description).getOrThrow()
                }
                // 命令已提交，但没有发布任何本地投影。
                val updated = result.projection ?: return@launch
                check(updated.spaceId == targetSpaceId) {
                    "更新文档空间响应的资源 ID 与请求不一致"
                }
                updateSpaces(
                    listOf(updated) + spaces().filterNot { it.spaceId == targetSpaceId },
                    targetSpaceId,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!failure.isTerminalDocumentSpaceRouteFailure()) {
                    reportError(failure, "更新文档空间失败")
                }
            }
        }
    }
}

/** 在 feature 保持为可观察状态 owner 的同时，协调文档空间 ACL RPC。 */
internal class DocumentWorkspaceGrantActions(
    private val repository: DocumentRepositoryBoundary,
    private val scope: CoroutineScope,
    private val reportError: (Throwable, String) -> Unit,
    private val organization: DocumentGrantOrganizationController,
    private val selectedSpace: () -> DocumentSpace?,
    private val selectedSpaceId: () -> String?,
    private val currentGrants: () -> List<DocumentSpaceGrant>,
    private val updateGrants: (List<DocumentSpaceGrant>) -> Unit,
    private val publishPolicyMutation: ((DocumentPolicyMutationResult) -> Boolean)? = null,
    private val policyOutbox: DocumentPolicyMutationOutbox = DocumentPolicyMutationOutbox(),
) {
    fun refresh(): Job {
        val space = selectedSpace()
        val target = space?.let { DocumentGrantRequestTarget(it.spaceId) }

        return scope.launch {
            val capturedSpace = space ?: return@launch
            val capturedTarget = target ?: return@launch
            if (capturedSpace.myRole < DocumentSpace.ROLE_ADMIN) {
                if (capturedTarget.isSelectedBy(selectedSpaceId())) updateGrants(emptyList())
                return@launch
            }
            try {
                organization.refreshUnits()
                if (!hasCurrentAdminAccess(capturedTarget)) {
                    if (capturedTarget.isSelectedBy(selectedSpaceId())) updateGrants(emptyList())
                    return@launch
                }
                val grants = repository.call(
                    spaceId = capturedTarget.spaceId,
                    notFoundRetiresSpace = true,
                ) {
                    listGrants(capturedTarget.spaceId).getOrThrow()
                }
                if (capturedTarget.isSelectedBy(selectedSpaceId()) &&
                    hasCurrentAdminAccess(capturedTarget)
                ) updateGrants(grants)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (capturedTarget.isSelectedBy(selectedSpaceId()) &&
                    !e.isTerminalDocumentSpaceRouteFailure()
                ) reportError(e, "加载空间权限失败")
            }
        }
    }

    fun searchMembers(query: String): Job? =
        if (selectedSpace()?.myRole?.let { it >= DocumentSpace.ROLE_ADMIN } == true) {
            organization.searchMembers(query)
        } else {
            null
        }

    fun closeMemberSearch() = organization.closeMemberSearch()

    fun upsert(
        principalType: Int,
        principalId: String,
        role: Int,
        includeDescendants: Boolean,
    ): Job {
        val capturedSpace = selectedSpace()
        val target = capturedSpace?.let { DocumentGrantRequestTarget(it.spaceId) }
        return scope.launch {
            val capturedTarget = target ?: return@launch
            if (!hasCurrentAdminAccess(capturedTarget)) return@launch
            var acquiredIntent: DocumentPolicyMutationIntent? = null
            try {
                val intent = policyOutbox.acquireUpsert(
                    spaceId = capturedTarget.spaceId,
                    expectedPolicyRevision = checkNotNull(capturedSpace).policyRevision,
                    principalType = principalType,
                    principalId = principalId,
                    role = role,
                    includeDescendants = includeDescendants,
                ).also { acquiredIntent = it }
                val result = repository.call(
                    spaceId = capturedTarget.spaceId,
                    notFoundRetiresSpace = true,
                ) {
                    upsertGrant(
                        capturedTarget.spaceId,
                        principalType,
                        principalId,
                        role,
                        includeDescendants,
                        intent.expectedPolicyRevision,
                        intent.operationId,
                        intent.issuedAt,
                    ).getOrThrow()
                }
                check(policyOutbox.complete(intent)) { "acknowledged document policy intent was not pending" }
                val selectedAtPublication = capturedTarget.isSelectedBy(selectedSpaceId())
                if (!publishPolicyResult(result)) return@launch
                if (result.effectiveRole < DocumentSpace.ROLE_ADMIN) {
                    if (selectedAtPublication) updateGrants(emptyList())
                    return@launch
                }
                if (!hasCurrentAdminAccess(capturedTarget)) {
                    if (capturedTarget.isSelectedBy(selectedSpaceId())) updateGrants(emptyList())
                    return@launch
                }
                val grants = repository.call(
                    spaceId = capturedTarget.spaceId,
                    notFoundRetiresSpace = true,
                ) {
                    listGrants(capturedTarget.spaceId).getOrThrow()
                }
                if (capturedTarget.isSelectedBy(selectedSpaceId()) &&
                    hasCurrentAdminAccess(capturedTarget)
                ) updateGrants(grants)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (e.isDefinitiveDocumentPolicyFailure()) {
                    acquiredIntent?.let(policyOutbox::complete)
                }
                if (capturedTarget.isSelectedBy(selectedSpaceId()) &&
                    !e.isTerminalDocumentSpaceRouteFailure()
                ) reportError(e, "更新空间权限失败")
            }
        }
    }

    fun remove(grant: DocumentSpaceGrant): Job {
        val capturedSpace = selectedSpace()
        val target = capturedSpace?.let { DocumentGrantRequestTarget(it.spaceId) }
            ?.takeIf { it.spaceId == grant.spaceId }
        return scope.launch {
            val capturedTarget = target ?: return@launch
            if (!hasCurrentAdminAccess(capturedTarget)) return@launch
            var acquiredIntent: DocumentPolicyMutationIntent? = null
            try {
                val intent = policyOutbox.acquireRemove(
                    spaceId = capturedTarget.spaceId,
                    expectedPolicyRevision = checkNotNull(capturedSpace).policyRevision,
                    principalType = grant.principalType,
                    principalId = grant.principalId,
                ).also { acquiredIntent = it }
                val result = repository.call(
                    spaceId = capturedTarget.spaceId,
                    notFoundRetiresSpace = true,
                ) {
                    removeGrant(
                        capturedTarget.spaceId,
                        grant.principalType,
                        grant.principalId,
                        intent.expectedPolicyRevision,
                        intent.operationId,
                        intent.issuedAt,
                    ).getOrThrow()
                }
                check(policyOutbox.complete(intent)) { "acknowledged document policy intent was not pending" }
                val selectedAtPublication = capturedTarget.isSelectedBy(selectedSpaceId())
                if (!publishPolicyResult(result)) return@launch
                if (result.effectiveRole < DocumentSpace.ROLE_ADMIN) {
                    if (selectedAtPublication) updateGrants(emptyList())
                    return@launch
                }
                if (!hasCurrentAdminAccess(capturedTarget)) {
                    if (capturedTarget.isSelectedBy(selectedSpaceId())) updateGrants(emptyList())
                    return@launch
                }
                val grants = repository.call(
                    spaceId = capturedTarget.spaceId,
                    notFoundRetiresSpace = true,
                ) {
                    listGrants(capturedTarget.spaceId).getOrThrow()
                }
                if (capturedTarget.isSelectedBy(selectedSpaceId()) &&
                    hasCurrentAdminAccess(capturedTarget)
                ) updateGrants(grants)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (e.isDefinitiveDocumentPolicyFailure()) {
                    acquiredIntent?.let(policyOutbox::complete)
                }
                if (capturedTarget.isSelectedBy(selectedSpaceId()) &&
                    !e.isTerminalDocumentSpaceRouteFailure()
                ) reportError(e, "移除空间权限失败")
            }
        }
    }

    private fun hasCurrentAdminAccess(target: DocumentGrantRequestTarget): Boolean =
        selectedSpace()?.let { current ->
            current.spaceId == target.spaceId && current.myRole >= DocumentSpace.ROLE_ADMIN
        } == true

    private fun publishPolicyResult(
        result: DocumentPolicyMutationResult,
    ): Boolean = publishPolicyMutation?.invoke(result)
        ?: (result.effectiveRole != DocumentSpace.ROLE_NONE)

}

private fun Throwable.isDefinitiveDocumentPolicyFailure(): Boolean =
    this is AppError.Business && code in 400..499 && code != 408

internal data class DocumentGrantMemberSearchState(
    val query: String = "",
    val candidates: List<User> = emptyList(),
    val loading: Boolean = false,
    val submitted: Boolean = false,
    val failed: Boolean = false,
)

/**
 * 拥有文档 ACL 对话框使用的组织读取。
 *
 * 单元树保持为一个完整的 LocalCache 投影，但用户候选是独立的、
 * 服务器有界的搜索结果。任何 ACL 界面请求都不得仅仅为了显示一个二十行的 typeahead
 * 而重建 10 万成员关系快照。
 */
internal class DocumentGrantOrganizationController(
    private val scope: CoroutineScope,
    private val localData: UiLocalDataBoundary,
    private val loadUnits: suspend () -> Outcome<List<OrganizationUnit>>,
    private val searchUsers: suspend (String) -> Outcome<List<User>>,
    private val reportError: (Throwable, String) -> Unit,
    private val publishUnits: (List<OrganizationUnit>) -> Unit,
    private val publishMemberSearch: (DocumentGrantMemberSearchState) -> Unit,
) {
    private val unitLoadMutex = Mutex()
    private val memberSearchGate = LatestRequestGate<String>()
    private var memberSearchJob: Job? = null

    /** 每一次对话框刷新都重新收敛复制的 Compose 投影；失败不发布任何东西。 */
    suspend fun refreshUnits() {
        unitLoadMutex.withLock {
            val units = localData.run { loadUnits().getOrThrow() }
            publishUnits(units)
        }
    }

    fun searchMembers(query: String): Job? {
        memberSearchJob?.cancel()
        memberSearchJob = null
        val normalized = query.trim()
        if (!isDocumentGrantMemberSearchEligible(normalized)) {
            memberSearchGate.invalidate()
            publishMemberSearch(DocumentGrantMemberSearchState(query = query))
            return null
        }

        val token = memberSearchGate.begin(normalized)
        publishMemberSearch(
            DocumentGrantMemberSearchState(
                query = query,
                loading = true,
                submitted = true,
            ),
        )
        return scope.launch {
            try {
                delay(DOCUMENT_GRANT_MEMBER_SEARCH_DEBOUNCE_MS)
                val candidates = localData.run { searchUsers(normalized).getOrThrow() }
                check(candidates.size <= MAX_DOCUMENT_GRANT_MEMBER_CANDIDATES) {
                    "Document grant member search exceeded its result boundary"
                }
                check(candidates.all { candidate ->
                    candidate.role == UserRole.HUMAN &&
                        candidate.status == DOCUMENT_GRANT_ACTIVE_USER_STATUS
                }) {
                    "Document grant member search returned an inactive or non-human identity"
                }
                check(candidates.mapTo(hashSetOf(), User::uid).size == candidates.size) {
                    "Document grant member search repeated a user identity"
                }
                if (memberSearchGate.isCurrent(token)) {
                    publishMemberSearch(
                        DocumentGrantMemberSearchState(
                            query = query,
                            candidates = candidates,
                            submitted = true,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (memberSearchGate.isCurrent(token)) {
                    publishMemberSearch(
                        DocumentGrantMemberSearchState(
                            query = query,
                            submitted = true,
                            failed = true,
                        ),
                    )
                    reportError(failure, "搜索组织成员失败")
                }
            } finally {
                val completed = currentCoroutineContext()[Job]
                if (memberSearchJob === completed) memberSearchJob = null
            }
        }.also { memberSearchJob = it }
    }

    /** 对话框退役在清除可见候选之前，先使响应能力失效。 */
    fun closeMemberSearch() {
        memberSearchJob?.cancel()
        memberSearchJob = null
        memberSearchGate.invalidate()
        publishMemberSearch(DocumentGrantMemberSearchState())
    }
}

internal fun isDocumentGrantMemberSearchEligible(query: String): Boolean {
    val trimmed = query.trim()
    if (trimmed.length > MAX_DOCUMENT_GRANT_MEMBER_QUERY_CHARS) return false
    val literalCharacters = trimmed.filter(Char::isLetterOrDigit)
    if (literalCharacters.isEmpty()) return false
    val allCjk = literalCharacters.all { it.code in DOCUMENT_GRANT_CJK_RANGE }
    return literalCharacters.length >= if (allCjk) {
        MIN_DOCUMENT_GRANT_CJK_QUERY_CHARS
    } else {
        MIN_DOCUMENT_GRANT_GENERAL_QUERY_CHARS
    }
}

/** 同步捕获，这样排队的协程就不能偷走之后的空间选择。 */
internal data class DocumentGrantRequestTarget(val spaceId: String) {
    fun isSelectedBy(selectedSpaceId: String?): Boolean = selectedSpaceId == spaceId

    companion object {
        fun capture(selectedSpaceId: String?): DocumentGrantRequestTarget? =
            selectedSpaceId?.let(::DocumentGrantRequestTarget)
    }
}

private const val HOME_LIMIT = 12
internal const val MAX_DOCUMENT_GRANT_MEMBER_CANDIDATES = 20
internal const val MAX_DOCUMENT_GRANT_UNIT_CANDIDATES = 30
internal const val DOCUMENT_GRANT_MEMBER_SEARCH_DEBOUNCE_MS = 250L
private const val MIN_DOCUMENT_GRANT_GENERAL_QUERY_CHARS = 3
private const val MIN_DOCUMENT_GRANT_CJK_QUERY_CHARS = 2
private const val MAX_DOCUMENT_GRANT_MEMBER_QUERY_CHARS = 100
private const val DOCUMENT_GRANT_ACTIVE_USER_STATUS = 1
private val DOCUMENT_GRANT_CJK_RANGE = 0x4E00..0x9FFF
