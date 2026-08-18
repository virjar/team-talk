package com.virjar.tk.media

import com.virjar.tk.client.defaultServerConfig
import com.virjar.tk.repository.FileOps
import com.virjar.tk.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * 桌面媒体缓存体系（doc/05-clients/rich-content.md）。
 *
 * SQLite 管理（media-cache.db，与消息 cache.db 分离）：
 * - 表 media_cache(url PK, local_path, kind, size, downloaded_at)——downloaded_at 支撑
 *   按日期 LRU 清理（磁盘配额超限时删最旧，防磁盘爆炸）
 * - 缩略图默认随消息下载；画廊原图按需下载（进度回调）
 * - 并发去重：同 url 并发下载由 [Mutex] 串行化，后到者直接命中首个结果
 */
object DesktopMediaCache {

    private const val TABLE = "media_cache"
    private const val QUOTA_BYTES = 500L * 1024 * 1024 // 500MB 磁盘配额

    private var conn: Connection? = null
    private var mediaDir: File? = null
    private val urlLocks = mutableMapOf<String, Mutex>()
    private val mapLock = Mutex()

    fun init(dataDir: File) {
        if (conn != null) return
        val dir = File(dataDir, "media-cache").apply { mkdirs() }
        mediaDir = dir
        conn = DriverManager.getConnection("jdbc:sqlite:${File(dir, "cache.db").absolutePath}").apply {
            createStatement().use { st ->
                st.executeUpdate(
                    """CREATE TABLE IF NOT EXISTS $TABLE (
                        url TEXT PRIMARY KEY,
                        local_path TEXT NOT NULL,
                        kind TEXT NOT NULL DEFAULT 'media',
                        size INTEGER NOT NULL DEFAULT 0,
                        downloaded_at INTEGER NOT NULL
                    )"""
                )
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_media_cache_at ON $TABLE(downloaded_at)")
            }
        }
        cleanup(QUOTA_BYTES)
        AppLog.trace("MediaCache", "initialized at ${dir.absolutePath}, quota=${QUOTA_BYTES / 1024 / 1024}MB")
    }

    /** 本地缓存路径（未缓存返回 null）。 */
    fun cachedPath(url: String): String? {
        val c = conn ?: return null
        c.prepareStatement("SELECT local_path FROM $TABLE WHERE url = ?").use { ps ->
            ps.setString(1, url)
            val rs = ps.executeQuery()
            return if (rs.next()) {
                val p = rs.getString(1)
                if (File(p).exists()) p else null // 记录在但文件丢失（外部清理）视为未缓存
            } else null
        }
    }

    /**
     * 确保已下载（命中直接返回；否则下载到本地缓存，进度回调 0f..1f）。
     * 同 url 并发调用去重（等待首个下载完成后共享结果）。
     */
    suspend fun ensureDownloaded(url: String, onProgress: (Float) -> Unit = {}): String =
        withContext(Dispatchers.IO) {
            cachedPath(url)?.let { return@withContext it }

            val lock = mapLock.withLock { urlLocks.getOrPut(url) { Mutex() } }
            lock.withLock {
                // 拿到锁后二次确认（前一个并发下载可能已完成）
                cachedPath(url)?.let { return@withContext it }
                download(url, onProgress)
            }
        }

    private fun download(url: String, onProgress: (Float) -> Unit): String {
        val dir = mediaDir ?: error("MediaCache not initialized")
        val c = conn ?: error("MediaCache not initialized")
        // 消息只保存服务端权威相对路径。即使收到旧版完整 URL，也必须重新绑定
        // 当前部署服务器，禁止客户端跟随消息访问第三方文件主机。
        val resolvedUrl = FileOps.resolveUrl(defaultServerConfig().serverUrl, url)
        val ext = url.substringBefore('?').substringAfterLast('.', "bin").take(8)
        val final = File(dir, "${UUID.randomUUID()}.$ext")

        try {
            val connection = (URL(resolvedUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 60_000
            }
            val code = connection.responseCode
            if (code != 200) throw RuntimeException("download HTTP $code: $resolvedUrl")

            val total = connection.contentLengthLong
            var read = 0L
            connection.inputStream.use { input ->
                final.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        out.write(buf, 0, n)
                        read += n
                        // 进度节流（每 128KB 回调一次，避免重组风暴）
                        if (total > 0 && read - lastReport >= 128 * 1024) {
                            lastReport = read
                            onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            onProgress(1f)

            c.prepareStatement(
                "INSERT OR REPLACE INTO $TABLE(url, local_path, kind, size, downloaded_at) VALUES(?,?,?,?,?)"
            ).use { ps ->
                ps.setString(1, url)
                ps.setString(2, final.absolutePath)
                ps.setString(3, if (read < 100 * 1024) "thumb" else "original")
                ps.setLong(4, final.length())
                ps.setLong(5, System.currentTimeMillis())
                ps.executeUpdate()
            }
            AppLog.trace("MediaCache", "downloaded $resolvedUrl -> ${final.name} (${final.length()}B)")
            return final.absolutePath
        } catch (e: Exception) {
            final.delete()
            throw e
        }
    }

    /**
     * 磁盘配额清理：总量超 [maxBytes] 时按 downloaded_at 从旧到新删除（文件+记录）。
     * 启动时调用（[init]）。
     */
    fun cleanup(maxBytes: Long) {
        val c = conn ?: return
        try {
            var total = c.createStatement().use { st ->
                st.executeQuery("SELECT COALESCE(SUM(size),0) FROM $TABLE").let { rs ->
                    rs.next(); rs.getLong(1)
                }
            }
            if (total <= maxBytes) return

            c.prepareStatement(
                "SELECT url, local_path, size FROM $TABLE ORDER BY downloaded_at ASC"
            ).use { ps ->
                val rs = ps.executeQuery()
                while (total > maxBytes * 8 / 10 && rs.next()) { // 清到 80% 以下（避免频繁触发）
                    val path = rs.getString(2)
                    val size = rs.getLong(3)
                    val f = File(path)
                    val freed = if (f.exists() && f.delete()) size else 0
                    c.prepareStatement("DELETE FROM $TABLE WHERE url = ?").use { del ->
                        del.setString(1, rs.getString(1))
                        del.executeUpdate()
                    }
                    total -= freed
                    AppLog.trace("MediaCache", "evicted $path freed=${freed}B")
                }
            }
        } catch (e: Exception) {
            AppLog.fault("MediaCache", "cleanup failed: ${e.message}")
        }
    }
}
