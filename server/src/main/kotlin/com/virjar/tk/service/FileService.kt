package com.virjar.tk.service

import com.virjar.tk.s3.S3Client
import com.virjar.tk.s3.S3ObjectMeta
import com.virjar.tk.s3.S3StreamConsumer
import io.netty.buffer.ByteBuf
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpResponse
import org.slf4j.LoggerFactory
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

data class FileStream(
    val inputStream: InputStream,
    val contentType: String,
    val contentLength: Long,
)

data class FileStreamWithRange(
    val inputStream: InputStream,
    val contentType: String,
    val contentLength: Long,
    val totalSize: Long,
    val rangeStart: Long,
    val rangeEnd: Long,
    val isRange: Boolean,
)

class FileService(
    endpoint: String,
    accessKey: String,
    secretKey: String,
    private val bucketName: String = "teamtalk",
) {
    private val logger = LoggerFactory.getLogger(FileService::class.java)
    private val eventLoopGroup = NioEventLoopGroup(2)
    private val s3Client = S3Client(endpoint, accessKey, secretKey, bucketName, eventLoopGroup)

    init {
        ensureBucket()
    }

    private fun ensureBucket() {
        try {
            val exists = s3Client.bucketExists().get(10, TimeUnit.SECONDS)
            if (!exists) {
                s3Client.makeBucket().get(10, TimeUnit.SECONDS)
                logger.info("Created MinIO bucket: {}", bucketName)
            }
        } catch (e: Exception) {
            logger.error("Failed to ensure bucket exists: {}", e.message)
        }
    }

    fun upload(uid: String, fileName: String, contentType: String, data: ByteArray): String {
        val ext = fileName.substringAfterLast('.', "")
        val path = "${uid}/${UUID.randomUUID().toString().replace("-", "")}.${ext}"

        s3Client.putObject(path, data, contentType).get(30, TimeUnit.SECONDS)

        logger.debug("File uploaded: {} by user {}", path, uid)
        return path
    }

    fun headObject(path: String): S3ObjectMeta? {
        return try {
            s3Client.headObject(path).get(10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            logger.warn("Failed to head object {}: {}", path, e.message)
            null
        }
    }

    fun streamFile(path: String): FileStream? {
        return try {
            val meta = s3Client.headObject(path).get(10, TimeUnit.SECONDS) ?: return null
            val response = s3Client.getObject(path).get(30, TimeUnit.SECONDS)
            FileStream(response.body.inputStream(), meta.contentType, meta.contentLength)
        } catch (e: Exception) {
            logger.warn("Failed to stream file {}: {}", path, e.message)
            null
        }
    }

    /**
     * 流式下载文件，支持 HTTP Range 请求。
     *
     * Range 下载流程：S3Client.getObjectStreaming(key, rangeHeader) → chunk 回调写入
     * PipedOutputStream → PipedInputStream 返回给 Ktor → Ktor respondOutputStream 写给客户端。
     */
    fun streamFileWithRange(path: String, rangeHeader: String? = null): FileStreamWithRange? {
        return try {
            val meta = s3Client.headObject(path).get(10, TimeUnit.SECONDS) ?: return null
            val totalSize = meta.contentLength

            val resolvedRange = resolveRange(rangeHeader, totalSize)

            val pipedOut = PipedOutputStream()
            val pipedIn = PipedInputStream(pipedOut, 64 * 1024)

            val consumer = object : S3StreamConsumer {
                override fun onHeaders(response: HttpResponse): Boolean {
                    val status = response.status().code()
                    // 206 (Partial Content) 或 200 (OK, 无 Range)
                    return status == 206 || status == 200
                }

                override fun onContent(content: ByteBuf) {
                    try {
                        if (content.hasArray()) {
                            pipedOut.write(content.array(), content.arrayOffset() + content.readerIndex(), content.readableBytes())
                        } else {
                            val bytes = ByteArray(content.readableBytes())
                            content.getBytes(content.readerIndex(), bytes)
                            pipedOut.write(bytes)
                        }
                    } catch (e: java.io.IOException) {
                        // PipedInputStream 已关闭（客户端断开），属于正常行为
                        logger.debug("Pipe write aborted (client likely disconnected): {}", e.message)
                    } finally {
                        content.release()
                    }
                }

                override fun onComplete() {
                    try {
                        pipedOut.close()
                    } catch (e: Exception) {
                        logger.debug("Pipe close error: {}", e.message)
                    }
                }

                override fun onError(throwable: Throwable) {
                    logger.error("S3 streaming error for {}", path, throwable)
                    try {
                        pipedOut.close()
                    } catch (_: Exception) {
                    }
                }
            }

            val effectiveRange = if (resolvedRange != null) {
                "bytes=${resolvedRange.first}-${resolvedRange.second}"
            } else null

            s3Client.getObjectStreaming(path, consumer, effectiveRange)

            if (resolvedRange != null) {
                val contentLength = resolvedRange.second - resolvedRange.first + 1
                FileStreamWithRange(
                    inputStream = pipedIn,
                    contentType = meta.contentType,
                    contentLength = contentLength,
                    totalSize = totalSize,
                    rangeStart = resolvedRange.first,
                    rangeEnd = resolvedRange.second,
                    isRange = true,
                )
            } else {
                FileStreamWithRange(
                    inputStream = pipedIn,
                    contentType = meta.contentType,
                    contentLength = totalSize,
                    totalSize = totalSize,
                    rangeStart = 0,
                    rangeEnd = totalSize - 1,
                    isRange = false,
                )
            }
        } catch (e: Exception) {
            logger.warn("Failed to stream file with range {}: {}", path, e.message)
            null
        }
    }

    /**
     * 解析 HTTP Range header，返回 [start, end]（inclusive），无效时返回 null。
     * 支持格式：`bytes=start-end`, `bytes=start-`, `bytes=-suffix`
     */
    private fun resolveRange(rangeHeader: String?, totalSize: Long): Pair<Long, Long>? {
        if (rangeHeader.isNullOrBlank()) return null
        val prefix = "bytes="
        if (!rangeHeader.startsWith(prefix, ignoreCase = true)) return null

        val rangeSpec = rangeHeader.substring(prefix.length).trim()
        if (rangeSpec.contains(',')) {
            // 仅处理第一个 range（multipart range 不支持）
            return resolveSingleRange(rangeSpec.substringBefore(','), totalSize)
        }
        return resolveSingleRange(rangeSpec, totalSize)
    }

    private fun resolveSingleRange(spec: String, totalSize: Long): Pair<Long, Long>? {
        val parts = spec.split('-', limit = 2)
        if (parts.size != 2) return null

        val startStr = parts[0].trim()
        val endStr = parts[1].trim()

        return when {
            startStr.isEmpty() && endStr.isNotEmpty() -> {
                // bytes=-suffix: 最后 suffix 字节
                val suffix = endStr.toLongOrNull() ?: return null
                if (suffix <= 0 || totalSize == 0L) return null
                val start = (totalSize - suffix).coerceAtLeast(0)
                Pair(start, totalSize - 1)
            }
            startStr.isNotEmpty() && endStr.isEmpty() -> {
                // bytes=start-: 从 start 到文件末尾
                val start = startStr.toLongOrNull() ?: return null
                if (start >= totalSize) return null
                Pair(start, totalSize - 1)
            }
            startStr.isNotEmpty() && endStr.isNotEmpty() -> {
                // bytes=start-end
                val start = startStr.toLongOrNull() ?: return null
                var end = endStr.toLongOrNull() ?: return null
                if (start > end || start >= totalSize) return null
                end = end.coerceAtMost(totalSize - 1)
                Pair(start, end)
            }
            else -> null
        }
    }

    fun generateAndUploadThumbnail(originalPath: String, originalBytes: ByteArray): String? {
        return try {
            val image: BufferedImage = ImageIO.read(originalBytes.inputStream()) ?: return null
            val thumbSize = 200
            val srcW = image.width
            val srcH = image.height
            if (srcW <= 0 || srcH <= 0) return null

            // Center-crop to square
            val cropSize = minOf(srcW, srcH)
            val x = (srcW - cropSize) / 2
            val y = (srcH - cropSize) / 2
            val cropped = image.getSubimage(x, y, cropSize, cropSize)

            val thumb = BufferedImage(thumbSize, thumbSize, BufferedImage.TYPE_INT_RGB)
            val g = thumb.graphics as Graphics2D
            g.drawImage(cropped, 0, 0, thumbSize, thumbSize, null)
            g.dispose()

            val baos = ByteArrayOutputStream()
            ImageIO.write(thumb, "jpg", baos)
            val thumbBytes = baos.toByteArray()

            // Derive thumbnail path: uid/abc123.jpg → uid/thumb_abc123.jpg
            val dir = originalPath.substringBeforeLast('/', "")
            val fileName = originalPath.substringAfterLast('/')
            val thumbFileName = "thumb_$fileName"
            val thumbPath = if (dir.isNotEmpty()) "$dir/$thumbFileName" else thumbFileName

            s3Client.putObject(thumbPath, thumbBytes, "image/jpeg").get(30, TimeUnit.SECONDS)

            logger.debug("Thumbnail generated: {} -> {}", originalPath, thumbPath)
            thumbPath
        } catch (e: Exception) {
            logger.warn("Failed to generate thumbnail for {}: {}", originalPath, e.message)
            null
        }
    }

    fun delete(path: String) {
        try {
            s3Client.deleteObject(path).get(10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            logger.error("Failed to delete file {}: {}", path, e.message)
        }
    }

    fun close() {
        s3Client.shutdown()
        eventLoopGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS)
        logger.info("FileService S3 client shut down")
    }
}
