package com.virjar.tk.protocol.http

import com.virjar.tk.protocol.ReliableCommandContract

/** 一次附件上传尝试及其全部精确重放的、客户端拥有的稳定标识。 */
data class AttachmentUploadIdentity(
    val uploadId: String,
    val issuedAt: Long,
) {
    init {
        require(CANONICAL_UUID.matches(uploadId)) {
            "$ATTACHMENT_UPLOAD_ID_HEADER must be a canonical UUID"
        }
        require(issuedAt >= 0L) {
            "$ATTACHMENT_UPLOAD_ISSUED_AT_HEADER must be a non-negative epoch millisecond"
        }
    }

    /**
     * 应用共享的有限重放寿命，不引入上传专属的时限。
     * 最老与最新的边界值仍然被接受。
     */
    fun requireActiveAt(nowEpochMs: Long): AttachmentUploadIdentity {
        require(nowEpochMs >= 0L) { "Current epoch millisecond must be non-negative" }
        require(
            issuedAt <= nowEpochMs ||
                issuedAt - nowEpochMs <= ReliableCommandContract.MAX_FUTURE_CLOCK_SKEW_MILLIS,
        ) {
            "$ATTACHMENT_UPLOAD_ISSUED_AT_HEADER exceeds the allowed clock skew"
        }
        require(
            issuedAt >= nowEpochMs ||
                nowEpochMs - issuedAt <= ReliableCommandContract.RETRY_HORIZON_MILLIS,
        ) {
            "$ATTACHMENT_UPLOAD_ID_HEADER has exceeded the reliable retry horizon"
        }
        return this
    }

    private companion object {
        val CANONICAL_UUID = Regex(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
        )
    }
}

/** 携带 [AttachmentUploadIdentity.uploadId] 的 HTTP 头。 */
const val ATTACHMENT_UPLOAD_ID_HEADER = "Idempotency-Key"

/** 携带 [AttachmentUploadIdentity.issuedAt]（十进制 epoch 毫秒）的 HTTP 头。 */
const val ATTACHMENT_UPLOAD_ISSUED_AT_HEADER = "X-TeamTalk-Command-Issued-At"

/** 解析两个必填的上传标识头，不依赖任何 HTTP 实现。 */
fun parseAttachmentUploadIdentityHeaders(
    uploadIdHeader: String?,
    issuedAtHeader: String?,
): AttachmentUploadIdentity {
    require(uploadIdHeader != null) { "Missing $ATTACHMENT_UPLOAD_ID_HEADER header" }
    require(issuedAtHeader != null) { "Missing $ATTACHMENT_UPLOAD_ISSUED_AT_HEADER header" }
    require(issuedAtHeader == "0" || DECIMAL_EPOCH_MILLIS.matches(issuedAtHeader)) {
        "$ATTACHMENT_UPLOAD_ISSUED_AT_HEADER must be a canonical non-negative epoch millisecond"
    }
    val issuedAt = issuedAtHeader.toLongOrNull()
    require(issuedAt != null) {
        "$ATTACHMENT_UPLOAD_ISSUED_AT_HEADER must fit in a signed 64-bit epoch millisecond"
    }
    return AttachmentUploadIdentity(uploadIdHeader, issuedAt)
}

private val DECIMAL_EPOCH_MILLIS = Regex("[1-9][0-9]*")
