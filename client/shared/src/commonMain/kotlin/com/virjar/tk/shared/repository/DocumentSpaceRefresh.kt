package com.virjar.tk.shared.repository

import com.virjar.tk.shared.client.ProjectionSnapshotLease
import com.virjar.tk.protocol.model.DocumentDirectorySnapshotVersion
import com.virjar.tk.protocol.model.DocumentSpacePage

/** 一次已在远程提交的文档变更，附带一个可选的当前本地投影。 */
data class DocumentMutationResult<out T>(
    val projection: T?,
)

sealed interface DocumentSpaceRefreshPageResult {
    data class Page(val value: DocumentSpacePage) : DocumentSpaceRefreshPageResult
    data object RestartRequired : DocumentSpaceRefreshPageResult
}

/** 一条绑定到 repository 的游标链，用于证明当前可见的完整空间集合。 */
class DocumentSpaceRefreshCycle internal constructor(
    internal val owner: Any,
    private val abandonProjection: (ProjectionSnapshotLease) -> Boolean,
) {
    internal val lock = Any()
    internal var projectionLease: ProjectionSnapshotLease? = null
    internal var expectedCursor: String? = null
    internal var inFlight = false
    internal var completed = false
    internal var cancelled = false
    internal var snapshotVersion: DocumentDirectorySnapshotVersion? = null

    fun cancel(): Boolean = synchronized(lock) {
        if (cancelled || completed) return@synchronized false
        cancelled = true
        projectionLease?.let(abandonProjection)
        projectionLease = null
        true
    }
}
