package com.virjar.tk.app.navigation.feature.document

/** 必须在一个投影 replace/close 命令中存续下来的确切活动编辑器身份。 */
internal data class ActiveDocumentDraftCaptureTarget(
    val tabId: String,
    val instanceId: Long,
    val recoveryId: String,
    val spaceId: String,
    val documentId: String?,
    val revision: Long?,
) {
    fun resolves(tab: DocumentTabState): Boolean =
        tab.tabId == tabId && tab.instanceId == instanceId && tab.recoveryId == recoveryId &&
            tab.spaceId == spaceId && tab.documentId == documentId && tab.revision == revision

    fun owns(owner: DocumentDraftCaptureOwner): Boolean =
        owner.tabId == tabId && owner.instanceId == instanceId &&
            owner.recoveryId == recoveryId && owner.revision == revision

    companion object {
        fun capture(tab: DocumentTabState) = ActiveDocumentDraftCaptureTarget(
            tabId = tab.tabId,
            instanceId = tab.instanceId,
            recoveryId = tab.recoveryId,
            spaceId = tab.spaceId,
            documentId = tab.documentId,
            revision = tab.revision,
        )
    }
}

/** 同步 UI 状态捕获与不可变状态重新解析的 fail-closed 结果。 */
internal sealed interface ActiveDocumentDraftCaptureResult {
    data class Captured(val tab: DocumentTabState) : ActiveDocumentDraftCaptureResult
    data object TargetChanged : ActiveDocumentDraftCaptureResult
    data object CaptureFailed : ActiveDocumentDraftCaptureResult
    data class StaleEditor(
        val expected: ActiveDocumentDraftCaptureTarget,
        val actual: DocumentDraftCaptureOwner?,
    ) : ActiveDocumentDraftCaptureResult
}

/**
 * Compose 拥有的编辑器状态与投影修改之间的 Main dispatcher 屏障。
 *
 * 调用方刻意同步调用 [captureLatest]：如果把它派发出去，就会留下一个窗口，
 * 远程结果可以先替换 feature 快照。然后 tab 会再次从不可变 feature 状态解析。
 * 盖有相同 instance 但不同 revision/recovery 纪元的编辑器会被拒绝，
 * 因为那个 tab 不可能接受它的发布。
 */
internal fun captureLatestActiveDocumentDraft(
    target: ActiveDocumentDraftCaptureTarget,
    activeTabId: () -> String?,
    tabs: () -> List<DocumentTabState>,
    captureLatest: () -> DocumentDraftCaptureOutcome,
): ActiveDocumentDraftCaptureResult {
    fun resolve(): DocumentTabState? = tabs().firstOrNull(target::resolves)
        ?.takeIf { activeTabId() == it.tabId }

    resolve() ?: return ActiveDocumentDraftCaptureResult.TargetChanged
    when (val capture = captureLatest()) {
        DocumentDraftCaptureOutcome.NoEditor -> Unit
        is DocumentDraftCaptureOutcome.Failed -> {
            return ActiveDocumentDraftCaptureResult.CaptureFailed
        }
        is DocumentDraftCaptureOutcome.Captured -> {
            val owner = capture.owner
                ?: return ActiveDocumentDraftCaptureResult.StaleEditor(target, actual = null)
            if (owner.instanceId == target.instanceId && !target.owns(owner)) {
                return ActiveDocumentDraftCaptureResult.StaleEditor(target, owner)
            }
        }
    }
    return resolve()?.let(ActiveDocumentDraftCaptureResult::Captured)
        ?: ActiveDocumentDraftCaptureResult.TargetChanged
}

/**
 * 只有当 [expected] 仍然是活动编辑器时才捕获；非活动的后台 tab 没有存活的
 * 视觉状态，会直接通过它们确切的 instance/recovery 纪元重新解析。
 */
internal fun DocumentWorkspaceFeature.captureLatestActiveDraftResult(
    expected: DocumentTabState,
): ActiveDocumentDraftCaptureResult {
    val current = tabs.firstOrNull {
        it.instanceId == expected.instanceId && it.recoveryId == expected.recoveryId &&
            it.tabId == expected.tabId && it.spaceId == expected.spaceId &&
            it.documentId == expected.documentId
    } ?: return ActiveDocumentDraftCaptureResult.TargetChanged
    if (activeTab?.instanceId != current.instanceId) {
        return ActiveDocumentDraftCaptureResult.Captured(current)
    }

    return captureLatestActiveDocumentDraft(
        target = ActiveDocumentDraftCaptureTarget.capture(current),
        activeTabId = { activeTabId },
        tabs = { tabs },
        captureLatest = draftLifecycleBridge::captureLatestOutcome,
    )
}

internal fun DocumentWorkspaceFeature.captureLatestActiveDraft(
    expected: DocumentTabState,
): DocumentTabState? = when (val result = captureLatestActiveDraftResult(expected)) {
        is ActiveDocumentDraftCaptureResult.Captured -> result.tab
        ActiveDocumentDraftCaptureResult.TargetChanged -> null
        ActiveDocumentDraftCaptureResult.CaptureFailed -> {
            reportError(
                IllegalStateException("Active document editor capture failed"),
                "无法读取编辑器最新内容，已取消可能覆盖本地内容的操作",
            )
            null
        }
        is ActiveDocumentDraftCaptureResult.StaleEditor -> {
            reportError(
                IllegalStateException(
                    "Active editor epoch does not match feature tab: " +
                        "instance=${result.expected.instanceId}, revision=${result.expected.revision}",
                ),
                "编辑器版本已变化，已取消可能覆盖本地内容的操作，请重试",
            )
            null
        }
    }

/**
 * 服务器拒绝访问之后，投影移除就不能再取消。如果同步编辑器捕获未确认，
 * 即使捕获回调切换了活动 tab，也要把最初的目标 tab 实例保留为一个 dirty、未解析的
 * 孤儿。这样重新组合/最新的编辑器帧仍然有一个不可变状态的落脚点。
 */
internal fun protectUnconfirmedActiveDraftForProjectionRemoval(
    tabs: List<DocumentTabState>,
    spaceId: String,
    expectedInstanceId: Long,
    captureResult: ActiveDocumentDraftCaptureResult,
): List<DocumentTabState> {
    if (captureResult is ActiveDocumentDraftCaptureResult.Captured) return tabs
    val index = tabs.indexOfFirst { tab ->
        tab.instanceId == expectedInstanceId && tab.spaceId == spaceId
    }
    if (index < 0) return tabs
    val current = tabs[index]
    val protected = current.copy(dirty = true, pathResolved = false)
    if (protected == current) return tabs
    return tabs.toMutableList().also { it[index] = protected }
}
