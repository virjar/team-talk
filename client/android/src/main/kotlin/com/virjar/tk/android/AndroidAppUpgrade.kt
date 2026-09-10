package com.virjar.tk.android

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/** 服务端 /downloads/android.json 的公开发行信息。 */
internal data class AndroidUpgradeInfo(
    val displayName: String,
    val version: String,
    val filename: String,
    val url: String,
)

/**
 * 应用内升级闭环（内测 T024）：检查 android.json → DownloadManager 下载 → 弹出安装。
 * 制品是服务端公开下载面，无需认证；版本比较按数字段比较，非数字段忽略。
 */
internal object AndroidAppUpgrade {

    suspend fun fetchLatest(serverBaseUrl: String): AndroidUpgradeInfo? = withContext(Dispatchers.IO) {
        val connection = URL(serverBaseUrl.trimEnd('/') + "/downloads/android.json")
            .openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = false
            if (connection.responseCode != 200) return@withContext null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parse(body)
        } finally {
            connection.disconnect()
        }
    }

    internal fun parse(body: String): AndroidUpgradeInfo? = runCatching {
        val obj = Json.parseToJsonElement(body).jsonObject
        AndroidUpgradeInfo(
            displayName = obj["displayName"]?.jsonPrimitive?.content ?: "Android",
            version = obj.getValue("version").jsonPrimitive.content,
            filename = obj.getValue("filename").jsonPrimitive.content,
            url = obj.getValue("url").jsonPrimitive.content,
        )
    }.getOrNull()

    /** 段级数字比较：0.0.2 > 0.0.1；忽略 v 前缀与非数字后缀。 */
    fun isNewer(remote: String, current: String): Boolean {
        fun segments(version: String) = version.trim().removePrefix("v").removePrefix("V")
            .split('.').map { segment -> segment.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val remoteSegments = segments(remote)
        val currentSegments = segments(current)
        for (index in 0 until maxOf(remoteSegments.size, currentSegments.size)) {
            val remotePart = remoteSegments.getOrElse(index) { 0 }
            val currentPart = currentSegments.getOrElse(index) { 0 }
            if (remotePart != currentPart) return remotePart > currentPart
        }
        return false
    }

    fun enqueueDownload(context: Context, serverBaseUrl: String, info: AndroidUpgradeInfo): Long {
        val request = DownloadManager.Request(Uri.parse(serverBaseUrl.trimEnd('/') + info.url))
            .setTitle(info.filename)
            .setDescription("${info.displayName} ${info.version}")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, info.filename)
        val manager = context.getSystemService(DownloadManager::class.java)
            ?: throw IllegalStateException("DownloadManager unavailable")
        return manager.enqueue(request)
    }

    /** 下载完成后弹出系统安装器；用户需授予「安装未知应用」权限（系统引导，仅需一次）。 */
    fun installDownload(context: Context, downloadId: Long): Boolean {
        val manager = context.getSystemService(DownloadManager::class.java) ?: return false
        val cursor = manager.query(DownloadManager.Query().setFilterById(downloadId))
        cursor.use {
            if (!it.moveToFirst()) return false
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status != DownloadManager.STATUS_SUCCESSFUL) return false
            val localUri = it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
            val install = Intent(Intent.ACTION_VIEW).setDataAndType(
                Uri.parse(localUri),
                "application/vnd.android.package-archive",
            ).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching { context.startActivity(install) }.isSuccess
        }
    }
}
