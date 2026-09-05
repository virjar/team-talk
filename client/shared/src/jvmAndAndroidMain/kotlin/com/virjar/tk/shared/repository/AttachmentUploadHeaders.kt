package com.virjar.tk.shared.repository

import com.virjar.tk.protocol.http.ATTACHMENT_UPLOAD_ID_HEADER
import com.virjar.tk.protocol.http.ATTACHMENT_UPLOAD_ISSUED_AT_HEADER
import com.virjar.tk.protocol.http.AttachmentUploadIdentity
import java.net.HttpURLConnection

/** 共享的 JVM/Android header 应用逻辑，使两个真实 transport 发送相同的上传身份。 */
internal fun HttpURLConnection.applyAttachmentUploadIdentity(identity: AttachmentUploadIdentity) {
    setRequestProperty(ATTACHMENT_UPLOAD_ID_HEADER, identity.uploadId)
    setRequestProperty(ATTACHMENT_UPLOAD_ISSUED_AT_HEADER, identity.issuedAt.toString())
}
