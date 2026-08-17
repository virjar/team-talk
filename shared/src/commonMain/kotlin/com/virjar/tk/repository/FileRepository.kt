package com.virjar.tk.repository

import com.virjar.tk.AppError
import com.virjar.tk.Outcome
import com.virjar.tk.outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 文件操作仓库（跨平台 expect）。
 *
 * 封装 HTTP 文件上传/下载。上传走平台各自实现（[upload]），
 * 下载与 URL 拼装两端逻辑相同，由 [FileOps] 共享。
 * 构造函数接受 serverUrl（从 [com.virjar.tk.client.defaultServerConfig] 获取）。
 */
expect class FileRepository(serverUrl: String, accessToken: String? /* HTTP 上传鉴权 Bearer，下载无需 */) {

    /** 上传文件，返回相对 path（形如 "{uid}/{uuid}.ext"）。 */
    suspend fun upload(bytes: ByteArray, fileName: String, contentType: String): Outcome<String>

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
    suspend fun download(path: String): Outcome<ByteArray>

    /** 根据相对 path 拼装完整下载 URL。 */
    fun resolveUrl(path: String): String
}

/**
 * 文件操作共享工具。两端（android/desktop）都有 java.net.HttpURLConnection，
 * 下载、URL 拼装、上传响应解析逻辑完全一致，提取到此 object 消除重复。
 */
object FileOps {
    /** 拼装文件下载完整 URL。 */
    fun resolveUrl(serverUrl: String, path: String): String = "$serverUrl/api/v1/files/$path"

    /** 下载文件字节（HttpURLConnection，两端通用）。 */
    suspend fun download(serverUrl: String, path: String): Outcome<ByteArray> =
        withContext(Dispatchers.IO) {
            outcome {
                val conn = (java.net.URL(resolveUrl(serverUrl, path)).openConnection() as java.net.HttpURLConnection).apply {
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

    /** 解析上传响应 JSON，提取 path 字段。 */
    fun parseUploadPath(body: String): String =
        Json.parseToJsonElement(body).jsonObject["path"]?.jsonPrimitive?.content
            ?: throw AppError.Business(-1, "Invalid upload response: $body")

    /** 解析上传响应全字段（缩略图/宽高/时长，服务端 ThumbnailService 产物）。 */
    fun parseUploadResult(body: String): UploadResult {
        val o = Json.parseToJsonElement(body).jsonObject
        fun str(k: String) = o[k]?.jsonPrimitive?.contentOrNull
        fun int(k: String) = o[k]?.jsonPrimitive?.intOrNull ?: 0
        return UploadResult(
            path = str("path") ?: throw AppError.Business(-1, "Invalid upload response: $body"),
            url = str("url").orEmpty(),
            thumbPath = str("thumbPath"),
            thumbUrl = str("thumbUrl"),
            width = int("width"),
            height = int("height"),
            durationSec = o["durationSec"]?.jsonPrimitive?.intOrNull,
        )
    }
}

/** 上传响应元数据（服务端生成：缩略图路径与媒体宽高/时长）。 */
data class UploadResult(
    val path: String,
    val url: String,
    val thumbPath: String? = null,
    val thumbUrl: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val durationSec: Int? = null,
)
