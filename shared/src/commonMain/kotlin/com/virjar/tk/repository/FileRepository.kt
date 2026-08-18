package com.virjar.tk.repository

import com.virjar.tk.AppError
import com.virjar.tk.Outcome
import com.virjar.tk.body.AttachmentPolicy
import com.virjar.tk.http.UploadResult
import com.virjar.tk.model.Attachment
import com.virjar.tk.outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * 文件操作仓库（跨平台 expect）。
 *
 * 封装 HTTP 文件上传/下载。上传走平台各自实现（[upload]），
 * 下载与 URL 拼装两端逻辑相同，由 [FileOps] 共享。
 * 构造函数接受 serverUrl（从 [com.virjar.tk.client.defaultServerConfig] 获取）。
 */
expect class FileRepository(serverUrl: String, accessToken: String? /* HTTP 上传鉴权 Bearer，下载无需 */) {

    /** 上传文件，返回服务端权威附件描述符。 */
    suspend fun upload(bytes: ByteArray, fileName: String, contentType: String): Outcome<Attachment>

    /** 上传并返回完整元数据（含服务端生成的缩略图/宽高/时长）。 */
    suspend fun uploadWithMeta(bytes: ByteArray, fileName: String, contentType: String): Outcome<UploadResult>

    /** 带进度回调的上传（大文件上传动画）。 */
    suspend fun uploadWithMeta(
        bytes: ByteArray,
        fileName: String,
        contentType: String,
        onProgress: (Float) -> Unit,
    ): Outcome<UploadResult>

    /** 下载文件，返回原始字节。 */
    suspend fun download(attachment: Attachment): Outcome<ByteArray>

    /** 根据附件相对 path 拼装完整下载 URL。 */
    fun resolveUrl(attachment: Attachment): String
}

/**
 * 文件操作共享工具。两端（android/desktop）都有 java.net.HttpURLConnection，
 * 下载、URL 拼装、上传响应解析逻辑完全一致，提取到此 object 消除重复。
 */
object FileOps {
    private val uploadJson = Json { ignoreUnknownKeys = false }

    /**
     * 拼装文件下载完整 URL。完整文件端点 URL 也会先提取相对 path，再绑定
     * 当前会话服务器，客户端不会跟随消息访问第三方文件主机。
     */
    fun resolveUrl(serverUrl: String, path: String): String =
        "${serverUrl.trimEnd('/')}/api/v1/files/${AttachmentPolicy.canonicalPath(path)}"

    fun resolveUrl(serverUrl: String, attachment: Attachment): String =
        resolveUrl(serverUrl, attachment.path)

    /** 下载文件字节（HttpURLConnection，两端通用）。 */
    suspend fun download(serverUrl: String, attachment: Attachment): Outcome<ByteArray> =
        withContext(Dispatchers.IO) {
            outcome {
                val conn = (java.net.URL(resolveUrl(serverUrl, attachment)).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 120_000
                }
                val code = conn.responseCode
                if (code != 200) {
                    throw AppError.Business(code, "Download failed HTTP $code")
                }
                conn.inputStream.readBytes()
            }
        }

    /** HTTP 上传响应和 SDK 使用同一强类型契约，不再手工读取平行字段。 */
    fun parseUploadResult(body: String): UploadResult = runCatching {
        uploadJson.decodeFromString<UploadResult>(body)
    }.getOrElse { throw AppError.Business(-1, "Invalid upload response: $body") }
}
