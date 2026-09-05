package com.virjar.tk.app.navigation.feature.document

import com.virjar.tk.protocol.model.Document

/** 只用于重新校验一条恢复草稿路径的可选正文读取的结果。 */
internal sealed interface RestoredDocumentBodyLoad {
    data object Superseded : RestoredDocumentBodyLoad

    data class Available(val document: Document) : RestoredDocumentBodyLoad

    data class Failed(val failure: Exception) : RestoredDocumentBodyLoad
}

/**
 * 在网络之前读取本地状态，并在每一次挂起之后重新检查导航所有权。
 * 正文失败是调用方的数据；它绝不能阻止独立的树缓存恢复。
 */
internal suspend fun loadRestoredDocumentBody(
    ownerIsCurrent: () -> Boolean,
    readCached: suspend () -> Document?,
    refresh: suspend () -> Document,
): RestoredDocumentBodyLoad {
    if (!ownerIsCurrent()) return RestoredDocumentBodyLoad.Superseded
    val cached = try {
        readCached()
    } catch (failure: Exception) {
        failure.rethrowIfDocumentWorkspaceCancelled()
        return if (ownerIsCurrent()) {
            RestoredDocumentBodyLoad.Failed(failure)
        } else {
            RestoredDocumentBodyLoad.Superseded
        }
    }
    if (!ownerIsCurrent()) return RestoredDocumentBodyLoad.Superseded
    if (cached != null) return RestoredDocumentBodyLoad.Available(cached)

    val remote = try {
        refresh()
    } catch (failure: Exception) {
        failure.rethrowIfDocumentWorkspaceCancelled()
        return if (ownerIsCurrent()) {
            RestoredDocumentBodyLoad.Failed(failure)
        } else {
            RestoredDocumentBodyLoad.Superseded
        }
    }
    return if (ownerIsCurrent()) {
        RestoredDocumentBodyLoad.Available(remote)
    } else {
        RestoredDocumentBodyLoad.Superseded
    }
}
