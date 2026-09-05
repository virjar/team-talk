package com.virjar.tk.app.navigation.feature.document

/**
 * 服务器提交了一次稳定的文档创建，而没有可发布的响应投影。
 * 退役 creating 恢复身份，并把保留的 dirty 正文绑定到已提交的远程身份，
 * 而不接受任何响应正文、路径或干净基线投影。
 *
 * 收尾顺序：持久化替换身份 → 封存旧身份/完成创建待办 → 发布身份并持久化迟到编辑。
 */
internal suspend fun DocumentWorkspaceFeature.completeCommittedDocumentCreateWithoutPublication(
    command: PendingDocumentCreateCommand,
): Boolean {
    if (!createOutbox.containsDocument(command)) return true
    // 编辑器可能在协调器更早的响应捕获之后已经前进，因此在旋转 revision/recovery 身份之前
    // 同步最后一帧。一旦下面的 recovery key 被守护，updateDraft 会把更新的帧
    // 排入 deferredDraftUpdates 供绑定使用。
    val liveCreatingTab = tabs.firstOrNull(command::matches)
    if (liveCreatingTab != null && captureLatestActiveDraft(liveCreatingTab) == null) {
        return false
    }
    val rawInitialBinding = bindCommittedDocumentCreateIdentity(tabs, command)
    val commandRecoveryKey = command.draftRecoveryKey()
    val recoveryKeys = buildSet {
        add(commandRecoveryKey)
        rawInitialBinding?.let {
            add(it.retiredRecoveryKey)
            add(it.tab.draftRecoveryKey())
        }
    }
    if (recoveryKeys.any(transitioningDraftRecoveryKeys::contains)) return false
    transitioningDraftRecoveryKeys += recoveryKeys
    val capturedDeferredFrame = rawInitialBinding?.let { binding ->
        removeMatchingCommittedCreateDeferredFrame(deferredDraftUpdates, binding)
    }
    val initialBinding = if (rawInitialBinding != null && capturedDeferredFrame != null) {
        rebaseCommittedDocumentCreateBinding(rawInitialBinding, capturedDeferredFrame)
    } else {
        rawInitialBinding
    }
    var identityPublished = false
    return try {
        if (!stageCommittedCreateIdentity(initialBinding, command)) return false
        if (!sealRetiredCreateIdentities(command, commandRecoveryKey, initialBinding)) return false

        if (initialBinding != null) {
            var boundTabs = initialBinding.tabs
            deferredDraftUpdates.remove(initialBinding.retiredRecoveryKey)?.let { deferred ->
                boundTabs = updateDocumentDraftTabs(
                    boundTabs,
                    deferred.copy(revision = DOCUMENT_INITIAL_REVISION),
                ).map { tab ->
                    if (tab.instanceId == initialBinding.tab.instanceId) tab.copy(dirty = true)
                    else tab
                }
            }
            tabs = boundTabs
            identityPublished = true
            if (activeTabId == initialBinding.tab.tabId) {
                selectedParentNodeId = null
                closeHistory()
            }
        }

        if (!persistCommittedCreateAndDeferredFrames(initialBinding)) {
            reportError(
                IllegalStateException("Committed document create manifest cleanup is not durable"),
                "文档已在服务器创建并绑定远端标识；本地草稿持久化仍需重试",
            )
        }
        true
    } finally {
        if (!identityPublished && initialBinding != null && capturedDeferredFrame != null) {
            // 失败的替换转换仍然拥有原始的 creating 身份。保留最新捕获的帧，
            // 让这个守卫在释放后重放；绝不覆盖持久化挂起期间到达的、更新的帧。
            deferredDraftUpdates.getOrPut(initialBinding.retiredRecoveryKey) {
                capturedDeferredFrame
            }
        }
        transitioningDraftRecoveryKeys -= recoveryKeys
        replayDeferredDraftUpdates(recoveryKeys)
    }
}

/**
 * 阶段一：先让替换身份持久。从这一刻起崩溃也会恢复一个 dirty、可更新的 tab
 * 且没有创建命令，即使旧 key 尚未封存。无绑定时无需暂存。
 */
private suspend fun DocumentWorkspaceFeature.stageCommittedCreateIdentity(
    initialBinding: CommittedDocumentCreateBinding?,
    command: PendingDocumentCreateCommand,
): Boolean {
    if (initialBinding == null) return true
    val staged = draftCollaboration.save(
        tabs = initialBinding.tabs,
        activeTabId = activeTabId,
        selectedSpaceId = selectedSpaceId,
        pendingSpaceCreates = createOutbox.pendingSpaces(),
        pendingDocumentCreates = createOutbox.pendingDocuments().filterNot { it == command },
        pendingDestructiveIntents = destructiveOutbox.pending(),
    )
    if (!staged || !draftCollaboration.flush()) {
        reportError(
            IllegalStateException("Committed document identity staging is not durable"),
            "文档已在服务器创建，但本机远端身份绑定失败；本地草稿仍保留",
        )
        return false
    }
    return true
}

/** 阶段二：墓碑封存被取代的恢复身份并完成创建待办；任一失败都保留本地草稿。 */
private suspend fun DocumentWorkspaceFeature.sealRetiredCreateIdentities(
    command: PendingDocumentCreateCommand,
    commandRecoveryKey: String,
    initialBinding: CommittedDocumentCreateBinding?,
): Boolean {
    val retiredKeys = buildSet {
        add(commandRecoveryKey)
        initialBinding?.let { add(it.retiredRecoveryKey) }
    }
    if (!draftCollaboration.tombstone(retiredKeys)) {
        reportError(
            IllegalStateException("Committed document create cleanup is not durable"),
            "文档已在服务器创建，但本机创建待办收尾失败；本地草稿仍保留",
        )
        return false
    }
    check(createOutbox.completeDocument(command)) {
        "Committed document create disappeared during guarded cleanup"
    }
    return true
}

/**
 * 阶段三：持久化已经发布的替换身份，再持续排空针对它的迟到编辑器帧。
 * 每帧重新置脏并持久化；全部写入成功才返回 true。
 */
private suspend fun DocumentWorkspaceFeature.persistCommittedCreateAndDeferredFrames(
    initialBinding: CommittedDocumentCreateBinding?,
): Boolean {
    var durable = persistDraftSnapshot() && draftCollaboration.flush()
    val replacementRecoveryKey = initialBinding?.tab?.draftRecoveryKey()
    while (initialBinding != null && durable && replacementRecoveryKey != null) {
        val deferred = deferredDraftUpdates.remove(replacementRecoveryKey) ?: break
        val current = tabs.firstOrNull { it.instanceId == initialBinding.tab.instanceId }
            ?: break
        tabs = updateDocumentDraftTabs(
            tabs,
            deferred.copy(revision = current.revision),
        ).map { tab ->
            if (tab.instanceId == current.instanceId) tab.copy(dirty = true) else tab
        }
        durable = persistDraftSnapshot() && draftCollaboration.flush()
    }
    return durable
}
