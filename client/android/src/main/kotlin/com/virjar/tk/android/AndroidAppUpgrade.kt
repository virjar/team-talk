package com.virjar.tk.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import com.virjar.tk.protocol.http.AndroidReleaseManifest
import java.net.URL

/** 服务端 /downloads/android.json 的公开发行信息；契约由共享的 AndroidReleaseManifest 定义。 */
internal typealias AndroidUpgradeInfo = AndroidReleaseManifest

/**
 * 应用内升级闭环（内测 T024 重做）：检查 android.json → 应用内流式下载（进度可见、可取消）
 * → 经 FileProvider 拉起系统安装器 → 升级安装完成后的首次启动清理暂存包。
 * 不再使用系统 DownloadManager：下载完成事件无法可靠回到应用，且后台拉起安装器
 * 受系统限制，用户感知不到下载已结束（公测反馈）。
 *
 * 制品是服务端公开下载面，无需认证；版本比较按数字段比较，非数字段忽略。
 */
internal object AndroidAppUpgrade {

    private const val UPGRADE_DIRECTORY = "teamtalk-upgrade"
    private const val PART_SUFFIX = ".part"
    private const val CONNECT_TIMEOUT_MILLIS = 8_000
    private const val READ_TIMEOUT_MILLIS = 30_000

    suspend fun fetchLatest(serverBaseUrl: String): AndroidUpgradeInfo? = withContext(Dispatchers.IO) {
        val connection = URL(serverBaseUrl.trimEnd('/') + "/downloads/android.json")
            .openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.instanceFollowRedirects = false
            if (connection.responseCode != 200) return@withContext null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parse(body)
        } finally {
            connection.disconnect()
        }
    }

    internal fun parse(body: String): AndroidUpgradeInfo? = AndroidReleaseManifest.decode(body)

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

    /** 升级暂存目录：cacheDir 下独立窄前缀，清理只动这里，不触碰媒体缓存。 */
    internal fun upgradePackageDirectory(cacheRoot: File): File = File(cacheRoot, UPGRADE_DIRECTORY)

    /**
     * 清理暂存包（完整包与 .part 半成品）。调用点：每次开始新下载前、升级安装完成后
     * 新进程首次启动（[TeamTalkApp.onCreate]）——即「安装后删除」的落点；安装会重启
     * 进程，首次启动清理与系统 MY_PACKAGE_REPLACED 时机等价，且不依赖清单接收器。
     */
    fun clearStagedUpgradePackages(context: Context) {
        clearStagedUpgradePackages(context.cacheDir)
    }

    internal fun clearStagedUpgradePackages(cacheRoot: File) {
        upgradePackageDirectory(cacheRoot).listFiles()?.forEach { it.deleteRecursively() }
    }

    /**
     * 应用内流式下载升级包到暂存目录，写入 .part 半成品、完成后原子改名。
     * [onProgress] 收到 0f..1f；服务端未返回 Content-Length 时收到的都是 -1f（不定进度）。
     * 返回暂存包文件；失败时半成品已删除，调用方拿到的是异常。
     */
    suspend fun downloadUpgradePackage(
        context: Context,
        serverBaseUrl: String,
        info: AndroidUpgradeInfo,
        onProgress: (Float) -> Unit,
    ): File {
        val directory = upgradePackageDirectory(context.cacheDir)
        return downloadToDirectory(directory, serverBaseUrl.trimEnd('/') + info.url, info.filename, onProgress)
    }

    internal suspend fun downloadToDirectory(
        directory: File,
        url: String,
        fileName: String,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        ensureActive()
        directory.mkdirs()
        val leaf = File(fileName).name
        check(leaf.isNotBlank() && leaf != "." && leaf != "..") { "无效的升级包文件名" }
        val target = File(directory, leaf)
        val part = File(directory, "$leaf$PART_SUFFIX")
        part.delete()
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            if (connection.responseCode != 200) {
                throw IllegalStateException("升级包下载失败：HTTP ${connection.responseCode}")
            }
            val totalBytes = connection.contentLengthLong
            var downloadedBytes = 0L
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        onProgress(
                            when {
                                totalBytes <= 0L -> -1f
                                else -> (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                            },
                        )
                    }
                }
            }
            check(!target.exists() || target.delete()) { "旧升级包清理失败" }
            check(part.renameTo(target)) { "升级包落位失败" }
            target
        } catch (failure: Throwable) {
            part.delete()
            throw failure
        } finally {
            connection.disconnect()
        }
    }
}
