package com.virjar.tk.server.infra.media

import com.virjar.tk.server.env.Environment
import com.virjar.tk.server.infra.storage.ManagedTempFiles
import com.virjar.tk.server.infra.storage.ManagedTempResidueException
import com.virjar.tk.server.infra.storage.THUMBNAIL_TEMP_PREFIX
import com.virjar.tk.server.infra.storage.THUMBNAIL_TEMP_SUFFIX
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.stream.FileImageInputStream
import kotlin.math.roundToInt

/**
 * Helper-JVM 本地的媒体解码器。生产请求线程只能通过
 * [ThumbnailService] 的可杀死进程边界到达此类；把 ImageIO/JavaCV 留在这里使该拥有权
 * 规则显式化，并为解码器预算测试留下确定性缝隙。
 */
internal class LocalThumbnailGenerator(
    private val tempDirectory: File = Environment.fileStoreTmpDir,
    private val maxImageDimension: Int = DEFAULT_MAX_IMAGE_DIMENSION,
    private val maxImagePixels: Long = DEFAULT_MAX_IMAGE_PIXELS,
    private val maxDecodedPixels: Long = DEFAULT_MAX_DECODED_PIXELS,
    private val retireTempFile: (File) -> Unit = { file ->
        ManagedTempFiles.retire(tempDirectory, file)
    },
    private val jpgEncoder: (BufferedImage, File) -> Boolean = { image, file ->
        ImageIO.write(image, "jpg", file)
    },
    private val beforeThumbnailHandoff: (File) -> Unit = {},
    private val thumbnailFileFactory: () -> File = {
        ManagedTempFiles.create(
            tempDirectory,
            THUMBNAIL_TEMP_PREFIX,
            THUMBNAIL_TEMP_SUFFIX,
        )
    },
) {

    init {
        require(maxImageDimension > 0) { "maxImageDimension must be positive" }
        require(maxImagePixels > 0L) { "maxImagePixels must be positive" }
        require(maxDecodedPixels in 1L..maxImagePixels) {
            "maxDecodedPixels must be positive and no larger than maxImagePixels"
        }
    }

    /** 图片：先验证 reader 元信息，再按解码预算 subsample，绝不先全量解码未知尺寸图片。 */
    fun processImage(src: File): ThumbnailService.MediaInfo? {
        val thumbnailOwner = GeneratedThumbnailOwner(retireTempFile)
        return try {
            val result = FileImageInputStream(src).use { input ->
                val readers = ImageIO.getImageReaders(input)
                if (!readers.hasNext()) return@use null
                val reader = readers.next()
                try {
                    reader.setInput(input, true, true)
                    val width = reader.getWidth(0)
                    val height = reader.getHeight(0)
                    if (!isWithinSourceBudget(width, height)) {
                        return@use null
                    }

                    val subsampling = safeSubsampling(width, height)
                    val parameters = reader.defaultReadParam
                    if (subsampling > 1) {
                        parameters.setSourceSubsampling(subsampling, subsampling, 0, 0)
                    }
                    val decoded = reader.read(0, parameters) ?: return@use null
                    try {
                        check(isWithinDecodedBudget(decoded.width, decoded.height)) {
                            "Image reader exceeded the configured decoded-pixel budget"
                        }
                        val scaled = scaleImage(decoded, THUMBNAIL_MAX_EDGE)
                        val thumbnail = try {
                            thumbnailOwner.own(writeJpg(scaled))
                        } finally {
                            if (scaled !== decoded) scaled.flush()
                        }
                        ThumbnailService.MediaInfo(thumbnail, width, height, durationSec = null)
                    } finally {
                        decoded.flush()
                    }
                } finally {
                    reader.dispose()
                }
            }
            result?.thumbFile?.let { thumbnail ->
                beforeThumbnailHandoff(thumbnail)
                thumbnailOwner.handoff(thumbnail)
            }
            result
        } catch (failure: ManagedTempResidueException) {
            throw failure
        } catch (e: Exception) {
            null
        } finally {
            thumbnailOwner.retireOwned()
        }
    }

    /** 视频：FFmpegFrameGrabber 抓首帧（JNI）+ 元数据；帧转 BufferedImage 后与图片共用缩放管线。 */
    fun processVideo(src: File): ThumbnailService.MediaInfo? {
        val thumbnailOwner = GeneratedThumbnailOwner(retireTempFile)
        return try {
            val result = processVideoJni(src, thumbnailOwner)
            result?.thumbFile?.let { thumbnail ->
                beforeThumbnailHandoff(thumbnail)
                thumbnailOwner.handoff(thumbnail)
            }
            result
        } catch (e: LinkageError) {
            null
        } catch (failure: ManagedTempResidueException) {
            throw failure
        } catch (e: Exception) {
            null
        } finally {
            thumbnailOwner.retireOwned()
        }
    }

    private fun processVideoJni(
        src: File,
        thumbnailOwner: GeneratedThumbnailOwner,
    ): ThumbnailService.MediaInfo? {
        org.bytedeco.javacv.FFmpegFrameGrabber(src).use { g ->
            g.start()
            val w = g.imageWidth
            val h = g.imageHeight
            val duration = (g.lengthInTime / 1_000_000L)
                .takeIf { it in 1..MAX_MEDIA_DURATION_SECONDS.toLong() }
                ?.toInt()

            // FFmpeg 元数据在 start 之后、grabImage 分配解码帧之前可用。
            // 与 ImageIO 不同，这里没有可信的 reader 级 subsample 钩子，因此
            // 超过源或解码像素预算的帧会在原生帧解码之前被拒绝。
            if (!isWithinSourceBudget(w, h) || !isWithinDecodedBudget(w, h)) {
                return null
            }

            // 抓首个有图像的帧（个别封装头几帧为纯音频，最多试 10 帧）
            var frame = g.grabImage()
            var tries = 0
            while (frame?.image == null && tries < 10) {
                frame = g.grabImage()
                tries++
            }
            var firstFrame: BufferedImage? = null
            try {
                frame?.let { videoFrame ->
                    org.bytedeco.javacv.Java2DFrameConverter().use { converter ->
                        firstFrame = converter.convert(videoFrame)
                    }
                }
                val decoded = firstFrame
                val thumb = decoded?.let { image ->
                    val scaled = scaleImage(image, THUMBNAIL_MAX_EDGE)
                    try {
                        thumbnailOwner.own(writeJpg(scaled))
                    } finally {
                        if (scaled !== image) scaled.flush()
                    }
                }
                return ThumbnailService.MediaInfo(
                    thumbFile = thumb,
                    width = w,
                    height = h,
                    durationSec = duration,
                )
            } finally {
                firstFrame?.flush()
            }
        }
    }

    // ── Java2D 缩放/编码共用管线 ──

    private fun scaleImage(src: BufferedImage, maxEdge: Int): BufferedImage {
        val w = src.width
        val h = src.height
        if (w <= maxEdge && h <= maxEdge) return src
        val ratio = minOf(maxEdge.toFloat() / w, maxEdge.toFloat() / h)
        val nw = (w * ratio).roundToInt().coerceAtLeast(1)
        val nh = (h * ratio).roundToInt().coerceAtLeast(1)
        val out = BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics() as Graphics2D
        try {
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.drawImage(src, 0, 0, nw, nh, null)
            } finally {
                g.dispose()
            }
        } catch (failure: Throwable) {
            out.flush()
            throw failure
        }
        return out
    }

    private fun writeJpg(img: BufferedImage): File? {
        var output: File? = null
        var published = false
        return try {
            val created = thumbnailFileFactory()
            output = created
            val encoded = jpgEncoder(img, created)
            if (encoded && created.length() > 0L) {
                published = true
                created
            } else {
                null
            }
        } catch (failure: ManagedTempResidueException) {
            throw failure
        } catch (e: Exception) {
            null
        } finally {
            if (!published) {
                output?.let(retireTempFile)
            }
        }
    }

    private fun isWithinSourceBudget(width: Int, height: Int): Boolean =
        width in 1..maxImageDimension &&
            height in 1..maxImageDimension &&
            width.toLong() * height.toLong() <= maxImagePixels

    private fun isWithinDecodedBudget(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && width.toLong() * height.toLong() <= maxDecodedPixels

    private fun safeSubsampling(width: Int, height: Int): Int {
        var sampling = 1
        while (ceilDiv(width, sampling).toLong() * ceilDiv(height, sampling) > maxDecodedPixels) {
            sampling += 1
        }
        return sampling
    }

    private fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor

    /** 拥有生成的缩略图，直到所有解码器/原生资源关闭且交接成功。 */
    private class GeneratedThumbnailOwner(
        private val retireTempFile: (File) -> Unit,
    ) {
        private var owned: File? = null

        fun own(file: File?): File? {
            if (file == null) return null
            check(owned == null) { "Generated thumbnail owner already owns a file" }
            owned = file
            return file
        }

        fun handoff(file: File) {
            check(owned === file) { "Generated thumbnail handoff did not match its owner" }
            owned = null
        }

        fun retireOwned() {
            val file = owned ?: return
            retireTempFile(file)
            owned = null
        }
    }

    private companion object {
        const val THUMBNAIL_MAX_EDGE = 480
        const val DEFAULT_MAX_IMAGE_DIMENSION = 16_384
        const val DEFAULT_MAX_IMAGE_PIXELS = 64L * 1024 * 1024
        const val DEFAULT_MAX_DECODED_PIXELS = 4L * 1024 * 1024
        const val MAX_MEDIA_DURATION_SECONDS = 7 * 24 * 60 * 60
    }
}
