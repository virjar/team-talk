package com.virjar.tk.app.navigation.feature.document

import com.virjar.tk.protocol.model.Document

/**
 * 把一次手动服务器刷新合并进同一个打开的文档实例。
 *
 * 干净的 tab 采用完整的远程快照。dirty tab 只跟随远程树位置：
 * 保留它旧的 revision 和基线，确保并发的远程编辑或 move
 * 仍然产生保存冲突，而不是静默覆盖另一台设备。
 */
internal fun mergeDocumentRefresh(
    current: DocumentTabState,
    remote: Document,
): DocumentTabState? {
    if (current.documentId != remote.documentId || current.spaceId != remote.spaceId) return null
    if (!remote.hasValidDocumentPath()) return null
    if (current.revision != null && remote.revision < current.revision) return null
    if (current.dirty || current.creating) {
        return current.copy(
            parentId = remote.parentId,
            ancestorIds = remote.ancestorIds,
            pathResolved = true,
            remoteMissing = false,
        )
    }
    return DocumentTabState.from(
        document = remote,
        instanceId = current.instanceId,
        editGeneration = current.editGeneration,
        recoveryId = current.recoveryId,
    )
}
