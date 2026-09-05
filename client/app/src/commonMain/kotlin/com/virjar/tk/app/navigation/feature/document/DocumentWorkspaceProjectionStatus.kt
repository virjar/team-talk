package com.virjar.tk.app.navigation.feature.document

import com.virjar.tk.shared.AppError

/** 一个独立缓存文档投影的发布状态。 */
enum class DocumentWorkspaceProjectionStatus {
    NOT_LOADED,
    LOADING,
    CACHED,
    CURRENT,
    OFFLINE_CACHED,
    OFFLINE_MISSING,
    /** 一个终止性不可访问的空间，只保留未保存的本地身份。 */
    LOCAL_ORPHAN,
}

internal fun documentProjectionStatusAfterFailure(
    hadCachedSnapshot: Boolean,
    failure: Exception,
): DocumentWorkspaceProjectionStatus = when {
    failure === AppError.Network || failure === AppError.Timeout -> {
        if (hadCachedSnapshot) {
            DocumentWorkspaceProjectionStatus.OFFLINE_CACHED
        } else {
            DocumentWorkspaceProjectionStatus.OFFLINE_MISSING
        }
    }
    hadCachedSnapshot -> DocumentWorkspaceProjectionStatus.CACHED
    else -> DocumentWorkspaceProjectionStatus.NOT_LOADED
}

internal fun DocumentWorkspaceProjectionStatus.isOffline(): Boolean =
    this == DocumentWorkspaceProjectionStatus.OFFLINE_CACHED ||
        this == DocumentWorkspaceProjectionStatus.OFFLINE_MISSING

internal fun DocumentWorkspaceProjectionStatus.hasPublishedSnapshot(): Boolean = when (this) {
    DocumentWorkspaceProjectionStatus.CACHED,
    DocumentWorkspaceProjectionStatus.CURRENT,
    DocumentWorkspaceProjectionStatus.OFFLINE_CACHED,
    -> true
    DocumentWorkspaceProjectionStatus.NOT_LOADED,
    DocumentWorkspaceProjectionStatus.LOADING,
    DocumentWorkspaceProjectionStatus.OFFLINE_MISSING,
    DocumentWorkspaceProjectionStatus.LOCAL_ORPHAN,
    -> false
}
