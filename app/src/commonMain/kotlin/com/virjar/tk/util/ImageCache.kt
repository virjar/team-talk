package com.virjar.tk.util

import androidx.compose.ui.graphics.ImageBitmap
import java.io.File
import java.security.MessageDigest

/**
 * Simple in-memory + disk LRU image cache.
 *
 * Memory: LinkedHashMap with access-order eviction (max 100 entries).
 * Disk: files in cacheDir/images/{sha256(url)}, with 200MB capacity limit.
 */
object ImageCache {
    private const val MAX_DISK_CACHE_BYTES = 200L * 1024 * 1024 // 200MB

    private val memoryCache = object : LinkedHashMap<String, ImageBitmap>(50, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>): Boolean {
            return size > 100
        }
    }

    private var cacheDir: File? = null

    fun init(dir: File) {
        val imagesDir = File(dir, "images")
        imagesDir.mkdirs()
        cacheDir = imagesDir
    }

    fun get(url: String): ImageBitmap? {
        synchronized(memoryCache) {
            memoryCache[url]?.let { return it }
        }
        // Try disk cache
        val file = diskFile(url)
        if (file != null && file.exists()) {
            try {
                val bytes = file.readBytes()
                val bitmap = bytes.decodeToImageBitmap()
                synchronized(memoryCache) { memoryCache[url] = bitmap }
                return bitmap
            } catch (_: Exception) {
                file.delete()
            }
        }
        return null
    }

    fun put(url: String, bitmap: ImageBitmap, bytes: ByteArray) {
        synchronized(memoryCache) { memoryCache[url] = bitmap }
        val file = diskFile(url)
        if (file != null) {
            try {
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
                // 写入后检查磁盘容量
                trimDiskCacheIfNeeded()
            } catch (_: Exception) {
                // Disk write failure is non-critical
            }
        }
    }

    suspend fun loadOrFetch(url: String): ImageBitmap? {
        get(url)?.let { return it }
        return try {
            val bytes = java.net.URL(url).readBytes()
            val bitmap = bytes.decodeToImageBitmap()
            put(url, bitmap, bytes)
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 获取磁盘缓存当前总大小（字节）。
     */
    fun getDiskCacheSize(): Long {
        val dir = cacheDir ?: return 0L
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * 清理磁盘缓存，保留最新的文件直到总大小不超过 maxBytes。
     * 按文件的 lastModified 排序，优先删除最旧的文件。
     */
    fun trimDiskCache(maxBytes: Long = MAX_DISK_CACHE_BYTES) {
        val dir = cacheDir ?: return
        if (!dir.exists()) return
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var totalSize = files.sumOf { it.length() }
        for (file in files) {
            if (totalSize <= maxBytes) break
            totalSize -= file.length()
            file.delete()
        }
    }

    private fun trimDiskCacheIfNeeded() {
        try {
            if (getDiskCacheSize() > MAX_DISK_CACHE_BYTES) {
                trimDiskCache()
            }
        } catch (_: Exception) {
            // Trim failure is non-critical
        }
    }

    private fun diskFile(url: String): File? {
        val dir = cacheDir ?: return null
        val hash = sha256(url)
        return File(dir, hash)
    }

    private fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
