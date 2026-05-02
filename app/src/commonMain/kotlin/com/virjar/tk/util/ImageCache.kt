package com.virjar.tk.util

import androidx.compose.ui.graphics.ImageBitmap
import java.io.File
import java.security.MessageDigest

/**
 * Per-user in-memory + disk LRU image cache.
 *
 * Memory: LinkedHashMap with access-order eviction (max 100 entries).
 * Disk: files in cacheDir/images/{sha256(url)}, with 200MB capacity limit.
 */
class ImageCache(cacheDir: File) {
    private val diskCacheDir: File

    init {
        val imagesDir = File(cacheDir, "images")
        imagesDir.mkdirs()
        diskCacheDir = imagesDir
    }

    private val memoryCache = object : LinkedHashMap<String, ImageBitmap>(50, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>): Boolean {
            return size > 100
        }
    }

    fun get(url: String): ImageBitmap? {
        synchronized(memoryCache) {
            memoryCache[url]?.let { return it }
        }
        val file = diskFile(url)
        if (file.exists()) {
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
        try {
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            trimDiskCacheIfNeeded()
        } catch (_: Exception) {
        }
    }

    suspend fun loadOrFetch(url: String): ImageBitmap? {
        get(url)?.let {
            AppLog.i("ImageCache", "Cache HIT: $url")
            return it
        }
        return try {
            AppLog.i("ImageCache", "Fetching: $url")
            val bytes = java.net.URL(url).readBytes()
            AppLog.i("ImageCache", "Fetched ${bytes.size} bytes from: $url")
            val bitmap = bytes.decodeToImageBitmap()
            put(url, bitmap, bytes)
            AppLog.i("ImageCache", "Decoded bitmap OK: ${bitmap.width}x${bitmap.height}")
            bitmap
        } catch (e: Exception) {
            AppLog.e("ImageCache", "Failed to load image: $url", e)
            null
        }
    }

    private fun trimDiskCacheIfNeeded() {
        try {
            val size = diskCacheSize()
            if (size > MAX_DISK_CACHE_BYTES) {
                trimDiskCache()
            }
        } catch (_: Exception) {
        }
    }

    private fun diskCacheSize(): Long {
        if (!diskCacheDir.exists()) return 0L
        return diskCacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun trimDiskCache(maxBytes: Long = MAX_DISK_CACHE_BYTES) {
        if (!diskCacheDir.exists()) return
        val files = diskCacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var totalSize = files.sumOf { it.length() }
        for (file in files) {
            if (totalSize <= maxBytes) break
            totalSize -= file.length()
            file.delete()
        }
    }

    private fun diskFile(url: String): File {
        val hash = sha256(url)
        return File(diskCacheDir, hash)
    }

    private fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MAX_DISK_CACHE_BYTES = 200L * 1024 * 1024 // 200MB
    }
}
