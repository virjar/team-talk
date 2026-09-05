package com.virjar.tk.app.ui.component.rich

/** 导入到可编辑富内容作用域的单个资源的平台无关生命周期。 */
enum class PendingAssetJobState(val serializedName: String) {
    LOCAL("local"),
    PREPARING("preparing"),
    UPLOADING("uploading"),
    READY("ready"),
    FAILED("failed"),
    CANCELLED("cancelled"),
}

/**
 * Desktop、Android、聊天与文档共享的不可变状态机。
 *
 * [assetId] 在插入 Markdown 之前本地分配，因此预处理/上传只替换该 id 背后的 manifest
 * 描述符时，Markdown URI 保持稳定。
 */
data class PendingAssetJob(
    val jobId: String,
    val assetId: String,
    val state: PendingAssetJobState = PendingAssetJobState.LOCAL,
    val progress: Float = 0f,
    val failureReason: String? = null,
) {
    init {
        require(jobId.isNotBlank()) { "Pending asset job id must not be blank" }
        require(embeddedAssetIdOrNull(embeddedAssetUriCandidate(assetId)) == assetId) {
            "Pending asset id must be a canonical lowercase UUID"
        }
        require(progress in 0f..1f) { "Upload progress must be in 0..1" }
        require(state == PendingAssetJobState.FAILED || failureReason == null) {
            "Only a failed pending asset job may carry a failure reason"
        }
        if (state == PendingAssetJobState.READY) require(progress == 1f) {
            "A ready pending asset job must have complete progress"
        }
    }

    fun beginPreparing(): PendingAssetJob = transition(PendingAssetJobState.PREPARING)

    fun beginUploading(): PendingAssetJob = transition(PendingAssetJobState.UPLOADING)

    fun updateUploadProgress(value: Float): PendingAssetJob {
        require(state == PendingAssetJobState.UPLOADING) { "Progress may only change while uploading" }
        require(value in progress..1f) { "Upload progress must be monotonic and in 0..1" }
        return copy(progress = value)
    }

    fun markReady(): PendingAssetJob = transition(PendingAssetJobState.READY)

    fun markFailed(reason: String): PendingAssetJob {
        require(reason.isNotBlank()) { "A failed pending asset job requires a reason" }
        return transition(PendingAssetJobState.FAILED, reason)
    }

    fun cancel(): PendingAssetJob = transition(PendingAssetJobState.CANCELLED)

    private fun transition(target: PendingAssetJobState, failure: String? = null): PendingAssetJob {
        require(target in allowedTransitions.getValue(state)) {
            "Illegal pending asset transition: ${state.serializedName} -> ${target.serializedName}"
        }
        return copy(
            state = target,
            progress = when (target) {
                PendingAssetJobState.LOCAL,
                PendingAssetJobState.PREPARING,
                PendingAssetJobState.FAILED,
                PendingAssetJobState.CANCELLED,
                -> 0f
                PendingAssetJobState.UPLOADING -> progress
                PendingAssetJobState.READY -> 1f
            },
            failureReason = failure,
        )
    }

    private companion object {
        val allowedTransitions = mapOf(
            PendingAssetJobState.LOCAL to setOf(
                PendingAssetJobState.PREPARING,
                PendingAssetJobState.CANCELLED,
            ),
            PendingAssetJobState.PREPARING to setOf(
                PendingAssetJobState.UPLOADING,
                PendingAssetJobState.FAILED,
                PendingAssetJobState.CANCELLED,
            ),
            PendingAssetJobState.UPLOADING to setOf(
                PendingAssetJobState.READY,
                PendingAssetJobState.FAILED,
                PendingAssetJobState.CANCELLED,
            ),
            PendingAssetJobState.FAILED to setOf(
                PendingAssetJobState.PREPARING,
                PendingAssetJobState.CANCELLED,
            ),
            PendingAssetJobState.READY to emptySet(),
            PendingAssetJobState.CANCELLED to emptySet(),
        )
    }
}

private fun embeddedAssetUriCandidate(assetId: String): String = TEAMTALK_ASSET_URI_PREFIX + assetId

enum class EmbeddedAssetCommitBlocker {
    REFERENCE_POLICY_REJECTED,
    MALFORMED_REFERENCE,
    INVALID_MANIFEST_ASSET_ID,
    DUPLICATE_MANIFEST_ASSET_ID,
    MANIFEST_REFERENCE_MISMATCH,
    DUPLICATE_JOB_ASSET_ID,
    ORPHANED_JOB,
    JOB_NOT_READY,
}

data class EmbeddedAssetCommitAdmission(
    val blockers: Set<EmbeddedAssetCommitBlocker>,
    val referencedAssetIds: Set<String>,
) {
    val canCommit: Boolean get() = blockers.isEmpty()
}

/** 待处理行属于 Markdown 消息体，而不属于网关中的每个历史帧。 */
internal fun referencedPendingAssetJobs(
    markdown: String,
    pendingJobs: List<PendingAssetJob>,
): List<PendingAssetJob> {
    val referencedIds = embeddedAssetMarkdownReferencesForRecovery(markdown)
        .mapNotNull { it.assetId }
        .toSet()
    return pendingJobs.filter { it.assetId in referencedIds }
}

/**
 * 失败关闭（fail-closed）的提交屏障。所提供的 manifest 必须是内部 Markdown 引用的精确、
 * 无重复 projection。既有资源无需待处理任务；任何提供的未取消任务都必须属于该内容且
 * 已就绪。
 */
fun admitEmbeddedAssetCommit(
    markdown: String,
    manifestAssetIds: List<String>,
    pendingJobs: List<PendingAssetJob>,
): EmbeddedAssetCommitAdmission {
    val blockers = linkedSetOf<EmbeddedAssetCommitBlocker>()
    val references = runCatching { embeddedAssetMarkdownReferences(markdown) }
        .getOrElse {
            blockers += EmbeddedAssetCommitBlocker.REFERENCE_POLICY_REJECTED
            emptyList()
        }
    if (references.any { it.assetId == null }) blockers += EmbeddedAssetCommitBlocker.MALFORMED_REFERENCE
    val referencedIds = references.mapNotNull { it.assetId }.toSet()

    val validManifestIds = manifestAssetIds.filter { assetId ->
        val valid = embeddedAssetIdOrNull(embeddedAssetUriCandidate(assetId)) == assetId
        if (!valid) blockers += EmbeddedAssetCommitBlocker.INVALID_MANIFEST_ASSET_ID
        valid
    }
    if (validManifestIds.size != validManifestIds.toSet().size) {
        blockers += EmbeddedAssetCommitBlocker.DUPLICATE_MANIFEST_ASSET_ID
    }
    if (validManifestIds.toSet() != referencedIds) {
        blockers += EmbeddedAssetCommitBlocker.MANIFEST_REFERENCE_MISMATCH
    }

    val activeJobs = pendingJobs.filter { it.state != PendingAssetJobState.CANCELLED }
    if (activeJobs.map(PendingAssetJob::assetId).toSet().size != activeJobs.size) {
        blockers += EmbeddedAssetCommitBlocker.DUPLICATE_JOB_ASSET_ID
    }
    activeJobs.forEach { job ->
        if (job.assetId !in referencedIds) blockers += EmbeddedAssetCommitBlocker.ORPHANED_JOB
        if (job.state != PendingAssetJobState.READY) blockers += EmbeddedAssetCommitBlocker.JOB_NOT_READY
    }
    pendingJobs.filter { it.state == PendingAssetJobState.CANCELLED }.forEach { job ->
        if (job.assetId in referencedIds) blockers += EmbeddedAssetCommitBlocker.JOB_NOT_READY
    }

    return EmbeddedAssetCommitAdmission(blockers = blockers, referencedAssetIds = referencedIds)
}
