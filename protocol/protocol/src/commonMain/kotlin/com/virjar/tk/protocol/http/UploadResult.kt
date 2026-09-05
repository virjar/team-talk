package com.virjar.tk.protocol.http

import com.virjar.tk.protocol.model.Attachment
import kotlinx.serialization.Serializable

/**
 * 文件上传 HTTP 响应契约。
 *
 * 服务端和所有客户端共享该 DTO；客户端 Repository 只负责执行 HTTP，不拥有响应格式。
 */
@Serializable
data class UploadResult(
    val file: Attachment,
    val thumbnail: Attachment? = null,
    val width: Int = 0,
    val height: Int = 0,
    val durationSec: Int? = null,
)
