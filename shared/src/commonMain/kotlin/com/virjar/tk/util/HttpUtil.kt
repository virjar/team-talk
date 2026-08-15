package com.virjar.tk.util

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream

/**
 * 跨平台 HTTP 工具（android/desktop 共享，基于 java.net.HttpURLConnection）。
 *
 * 消除 HttpLogUploader / CrashDumper / FileOps 等多处重复的 HTTP 样板。
 */
object HttpUtil {

    /** gzip 压缩字符串。 */
    fun gzip(text: String): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(text.encodeToByteArray()) }
        return bos.toByteArray()
    }

    /**
     * POST gzip 压缩数据到指定 URL。
     * @param url 目标地址
     * @param gzipData 已 gzip 压缩的字节
     * @param headers 额外请求头
     * @return HTTP 响应码
     * @throws RuntimeException 响应码非预期时（由调用方判断）
     */
    fun postGzip(url: String, gzipData: ByteArray, headers: Map<String, String> = emptyMap()): Int {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Content-Type", "application/gzip")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return try {
            conn.outputStream.use { it.write(gzipData) }
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }
}
