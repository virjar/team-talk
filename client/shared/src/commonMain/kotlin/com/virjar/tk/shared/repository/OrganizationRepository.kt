package com.virjar.tk.shared.repository

import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.client.LocalCache
import com.virjar.tk.shared.client.OrganizationMemberProjection
import com.virjar.tk.shared.client.OrganizationUnitProjection
import com.virjar.tk.protocol.model.OrganizationCapacityPolicy
import com.virjar.tk.protocol.model.OrganizationMember
import com.virjar.tk.protocol.model.OrganizationMemberPage
import com.virjar.tk.protocol.model.OrganizationMemberPageRequest
import com.virjar.tk.protocol.model.OrganizationUnit
import com.virjar.tk.protocol.model.OrganizationUnitPage
import com.virjar.tk.protocol.model.OrganizationUnitPageRequest
import com.virjar.tk.shared.outcome
import com.virjar.tk.protocol.rpc.RpcInvoker
import com.virjar.tk.protocol.rpc.gen.OrganizationRpcProxy
import kotlinx.coroutines.flow.Flow

/** 终端用户只读的组织目录 SDK。写操作只允许管理端执行。 */
class OrganizationRepository(
    rpcClient: RpcInvoker,
    private val localCache: LocalCache,
) {
    private val rpc = OrganizationRpcProxy(rpcClient)

    fun cachedUnitProjection(): OrganizationUnitProjection =
        localCache.getOrganizationUnitProjection()

    fun observeUnitProjection(): Flow<OrganizationUnitProjection> =
        localCache.observeOrganizationUnitProjection()

    fun cachedMemberProjection(unitId: String): OrganizationMemberProjection =
        localCache.getOrganizationMemberProjection(unitId)

    fun observeMemberProjection(unitId: String): Flow<OrganizationMemberProjection> =
        localCache.observeOrganizationMemberProjection(unitId)

    /** 供交互式刷新/重连路径使用的严格在线刷新。 */
    suspend fun refreshUnits(): Outcome<List<OrganizationUnit>> = outcome {
        val lease = localCache.beginOrganizationUnitSnapshot()
        try {
            val remote = collectUnitSnapshot()
            localCache.applyOrganizationUnitSnapshot(lease, remote.items, remote.revision)
            // 一次更新的请求或本地变更可能已把这笔响应挡在栅栏外。
            val current = localCache.getOrganizationUnitProjection()
            check(current.snapshotKnown) {
                "Organization unit refresh was fenced before a current-revision projection"
            }
            current.units
        } finally {
            localCache.abandonProjectionSnapshot(lease)
        }
    }

    /**
     * 严格在线刷新。非递归结果原子地替换恰好一个直接成员
     * 投影。递归结果刻意保持为独立的响应，并且绝不清除
     * 直接行：RPC 期间的组织层级变化使"缺失"作为按单位的墓碑是不安全的。
     */
    suspend fun refreshMembers(
        unitId: String,
        recursive: Boolean = false,
    ): Outcome<List<OrganizationMember>> = if (recursive) {
        outcome {
            val remote = collectMemberSnapshot(unitId, recursive = true)
            val required = localCache.advanceOrganizationRequiredRevision(remote.revision)
            check(remote.revision >= required) {
                "Recursive organization member snapshot predates required revision"
            }
            remote.items
        }
    } else {
        refreshMemberProjection(unitId).map(OrganizationMemberProjection::members)
    }

    /**
     * 在其结果中保留缓存完整性位的严格直接成员刷新。
     * 被栅栏挡住的响应只有在较新的本地投影已经完整时才可以返回该投影。
     * 否则刷新会失败关闭，而不是把未知状态翻译成成功。
     */
    suspend fun refreshMemberProjection(
        unitId: String,
    ): Outcome<OrganizationMemberProjection> = outcome {
        val lease = localCache.beginOrganizationMemberSnapshot(unitId)
        try {
            val remote = collectMemberSnapshot(unitId, recursive = false)
            if (!localCache.applyOrganizationMemberSnapshot(lease, remote.items, remote.revision)) {
                // 一次更新的请求或精确的本地变更把这份租约挡在了栅栏外。它的投影
                // 是我们唯一可以上报的缓存状态；部分/未知状态不能变成成功。
                val current = localCache.getOrganizationMemberProjection(unitId)
                check(current.snapshotKnown) {
                    "Direct organization member refresh was fenced before a complete projection"
                }
                return@outcome current
            }
            localCache.getOrganizationMemberProjection(unitId)
        } finally {
            localCache.abandonProjectionSnapshot(lease)
        }
    }

    /**
     * 供文档 ACL 对话框等只读消费者使用的兼容/查询 API。
     * 优先在线权威；只有网络/超时失败才回退到同一份持久化
     * 目录。认证、编解码与业务失败对调用方保持可见。
     */
    suspend fun listUnits(): Outcome<List<OrganizationUnit>> =
        refreshUnits().offlineFallback {
            localCache.getOrganizationUnitProjection()
                .takeIf(OrganizationUnitProjection::snapshotKnown)
                ?.units
        }

    suspend fun listMembers(
        unitId: String,
        recursive: Boolean = false,
    ): Outcome<List<OrganizationMember>> {
        val refreshed = refreshMembers(unitId, recursive)
        if (refreshed is Outcome.Success) return refreshed
        val failure = refreshed as Outcome.Failure
        if (failure.error != AppError.Network && failure.error != AppError.Timeout) return failure

        val cached = if (recursive) {
            cachedCompleteRecursiveMembers(unitId)
        } else {
            localCache.getOrganizationMemberProjection(unitId)
                .takeIf(OrganizationMemberProjection::snapshotKnown)
                ?.members
        }
        return cached?.let { Outcome.Success(it) } ?: failure
    }

    private fun cachedCompleteRecursiveMembers(rootUnitId: String): List<OrganizationMember>? {
        val unitProjection = localCache.getOrganizationUnitProjection()
        if (!unitProjection.snapshotKnown) return null
        val units = unitProjection.units
        if (units.none { it.unitId == rootUnitId }) return null
        val children = units.groupBy(OrganizationUnit::parentId)
        val subtree = linkedSetOf<String>()
        val pending = ArrayDeque<String>()
        pending.addLast(rootUnitId)
        while (pending.isNotEmpty()) {
            val unitId = pending.removeFirst()
            if (!subtree.add(unitId)) continue
            children[unitId].orEmpty().forEach { pending.addLast(it.unitId) }
        }
        if (subtree.any {
                val member = localCache.getOrganizationMemberProjection(it)
                !member.snapshotKnown || member.revision != unitProjection.revision
            }
        ) {
            return null
        }
        return localCache.getOrganizationMembersForUnits(subtree)
    }

    private suspend fun collectUnitSnapshot(): StableSnapshot<OrganizationUnit> = collectStableSnapshot(
        maximumItems = OrganizationCapacityPolicy.MAX_ACTIVE_UNITS,
        maximumPages = pagesFor(
            OrganizationCapacityPolicy.MAX_ACTIVE_UNITS,
            OrganizationUnitPage.MAX_PAGE_SIZE,
        ),
        identity = OrganizationUnit::unitId,
        kind = "organization unit",
    ) { cursor ->
        val page = rpc.listUnitPage(OrganizationUnitPageRequest(cursor))
        SnapshotPage(page.revision, page.items, page.nextCursor, page.snapshotChanged)
    }

    private suspend fun collectMemberSnapshot(
        unitId: String,
        recursive: Boolean,
    ): StableSnapshot<OrganizationMember> {
        val maximumItems = if (recursive) {
            OrganizationCapacityPolicy.MAX_MEMBERSHIP_RELATIONS
        } else {
            OrganizationCapacityPolicy.MAX_MEMBERS_PER_UNIT
        }
        return collectStableSnapshot(
            maximumItems = maximumItems,
            maximumPages = pagesFor(maximumItems, OrganizationMemberPage.MAX_PAGE_SIZE),
            identity = { member: OrganizationMember -> member.unitId to member.uid },
            kind = "organization member",
        ) { cursor ->
            val page = rpc.listMemberPage(OrganizationMemberPageRequest(unitId, recursive, cursor))
            if (!recursive && page.items.any { it.unitId != unitId }) {
                throw IllegalStateException("Direct organization member page escaped its unit")
            }
            SnapshotPage(page.revision, page.items, page.nextCursor, page.snapshotChanged)
        }
    }

    /** 收集一份权威快照，且不把任何部分页面发布到 LocalCache。 */
    private suspend fun <T, K> collectStableSnapshot(
        maximumItems: Int,
        maximumPages: Int,
        identity: (T) -> K,
        kind: String,
        load: suspend (String?) -> SnapshotPage<T>,
    ): StableSnapshot<T> {
        repeat(MAX_SNAPSHOT_ATTEMPTS) {
            val collected = ArrayList<T>()
            val seenIdentities = hashSetOf<K>()
            val seenCursors = hashSetOf<String>()
            var expectedRevision: Long? = null
            var cursor: String? = null
            var pageCount = 0

            while (true) {
                if (pageCount >= maximumPages) {
                    throw IllegalStateException("$kind snapshot exceeded $maximumItems items")
                }
                pageCount += 1
                val page = load(cursor)
                if (page.snapshotChanged ||
                    (expectedRevision != null && expectedRevision != page.revision)
                ) {
                    break
                }
                if (expectedRevision == null) expectedRevision = page.revision
                if (page.items.size > maximumItems - collected.size) {
                    throw IllegalStateException("$kind snapshot exceeded $maximumItems items")
                }
                page.items.forEach { item ->
                    if (!seenIdentities.add(identity(item))) {
                        throw IllegalStateException("$kind snapshot repeated an item identity")
                    }
                    collected += item
                }

                val next = page.nextCursor ?: return StableSnapshot(
                    revision = checkNotNull(expectedRevision),
                    items = collected,
                )
                if (collected.size == maximumItems) {
                    throw IllegalStateException("$kind snapshot exceeded $maximumItems items")
                }
                if (next == cursor || !seenCursors.add(next)) {
                    throw IllegalStateException("$kind snapshot cursor did not advance")
                }
                cursor = next
            }
        }
        throw IllegalStateException(
            "Organization snapshot stayed unstable after $MAX_SNAPSHOT_ATTEMPTS attempts",
        )
    }

    private data class SnapshotPage<T>(
        val revision: Long,
        val items: List<T>,
        val nextCursor: String?,
        val snapshotChanged: Boolean,
    )

    private data class StableSnapshot<T>(
        val revision: Long,
        val items: List<T>,
    )

    private inline fun <T> Outcome<T>.offlineFallback(cached: () -> T?): Outcome<T> = when (this) {
        is Outcome.Success -> this
        is Outcome.Failure -> if (error == AppError.Network || error == AppError.Timeout) {
            cached()?.let { Outcome.Success(it) } ?: this
        } else {
            this
        }
    }

    private companion object {
        const val MAX_SNAPSHOT_ATTEMPTS = 3

        fun pagesFor(maximumItems: Int, pageSize: Int): Int =
            (maximumItems + pageSize - 1) / pageSize
    }
}
