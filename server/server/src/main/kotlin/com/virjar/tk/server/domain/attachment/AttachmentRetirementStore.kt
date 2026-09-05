package com.virjar.tk.server.domain.attachment

/** 由有界元数据扫描返回的、不可变的"比较并退役"令牌。 */
data class AttachmentRetirementCandidate(
    val path: String,
    val uploadedAt: Long,
)

/** 元数据键空间的一个有界切片，包括尚未满足条件（not yet eligible）的行。 */
data class AttachmentRetirementScanPage(
    val candidates: List<AttachmentRetirementCandidate>,
    val lastScannedPath: String?,
    val hasMore: Boolean,
)

/** 附件保留策略使用的存储边界，不暴露其后端的适配器。 */
interface AttachmentRetirementStore {
    fun scanRetirementCandidates(
        uploadedAtOrBefore: Long,
        afterPath: String?,
        limit: Int,
    ): AttachmentRetirementScanPage

    fun retireIfExpiredAndUnchanged(
        candidate: AttachmentRetirementCandidate,
        uploadedAtOrBefore: Long,
    ): Boolean
}
