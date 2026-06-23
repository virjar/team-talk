package com.virjar.tk.repository

import com.virjar.tk.Outcome

/**
 * 文件操作仓库（跨平台 expect）。
 *
 * 封装 HTTP 文件上传/下载，平台各自实现 HTTP 客户端。
 * 构造函数接受 serverUrl（从 [com.virjar.tk.client.defaultServerConfig] 获取）。
 */
expect class FileRepository(serverUrl: String) {

    /** 上传文件，返回相对 path（形如 "{uid}/{uuid}.ext"）。 */
    suspend fun upload(bytes: ByteArray, fileName: String, contentType: String): Outcome<String>

    /** 下载文件，返回原始字节。 */
    suspend fun download(path: String): Outcome<ByteArray>

    /** 根据相对 path 拼装完整下载 URL。 */
    fun resolveUrl(path: String): String
}
