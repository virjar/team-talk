package com.virjar.tk.infra.media

import org.slf4j.LoggerFactory
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.stream.FileImageInputStream
import kotlin.math.roundToInt

/**
 * 媒体缩略图与元数据服务。
 *
 * 技术路线（用户确认）：图片纯 Java2D（零依赖零进程）；视频 bytedeco javacv JNI
 * （FFmpegFrameGrabber——首帧/时长/宽高全走 API 返回值，native 内嵌 jar 自动加载，
 * 版本锁定；对比 ProcessBuilder：无部署耦合、无版本漂移、无进程输出解析暗坑）。
 * 平台裁剪：linux-x86_64（服务器）+ macosx-x86_64/arm64（开发）。
 * native 缺失时 catch NoClassDefFoundError 优雅降级（返回 null，客户端回退）。
 */
class ThumbnailService {

    private val logger = LoggerFactory.getLogger(ThumbnailService::class.java)

    /** 处理结果：缩略图文件（调用方负责存储与删除）+ 媒体元数据 */
    data class MediaInfo(
        val thumbFile: File?,
        val width: Int,
        val height: Int,
        val durationSec: Int?,
    )

    /** 图片：ImageIO 读头尺寸（不解码像素）+ Java2D 等比缩放（max 边 480）输出 jpg。 */
    fun processImage(src: File): MediaInfo? {
        val size = readImageSize(src) ?: return null
        val thumb = scaleToJpg(src, size.first, size.second, maxEdge = 480)
        return MediaInfo(thumbFile = thumb, width = size.first, height = size.second, durationSec = null)
    }

    /** 视频：FFmpegFrameGrabber 抓首帧（JNI）+ 元数据；帧转 BufferedImage 后与图片共用缩放管线。 */
    fun processVideo(src: File): MediaInfo? = try {
        processVideoJni(src)
    } catch (e: NoClassDefFoundError) {
        logger.warn("javacv natives missing, video thumbnails disabled: ${e.message}")
        null
    } catch (e: Throwable) {
        logger.warn("video thumbnail failed: ${e.message}")
        null
    }

    private fun processVideoJni(src: File): MediaInfo? {
        org.bytedeco.javacv.FFmpegFrameGrabber(src).use { g ->
            g.start()
            val w = g.imageWidth
            val h = g.imageHeight
            val duration = if (g.lengthInTime > 0) (g.lengthInTime / 1_000_000L).toInt() else null

            // 抓首个有图像的帧（个别封装头几帧为纯音频，最多试 10 帧）
            var frame = g.grabImage()
            var tries = 0
            while (frame?.image == null && tries < 10) {
                frame = g.grabImage()
                tries++
            }
            val thumb = frame?.let { f ->
                org.bytedeco.javacv.Java2DFrameConverter().use { converter ->
                    converter.convert(f)
                }
            }?.let { firstFrame ->
                writeJpg(scaleImage(firstFrame, 480))
            }
            return MediaInfo(thumbFile = thumb, width = w, height = h, durationSec = duration)
        }
    }

    // ── Java2D 缩放/编码共用管线 ──

    private fun scaleToJpg(src: File, w: Int, h: Int, maxEdge: Int): File? {
        if (w <= 0 || h <= 0) return null
        val img = ImageIO.read(src) ?: return null
        return writeJpg(scaleImage(img, maxEdge))
    }

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
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.drawImage(src, 0, 0, nw, nh, null)
        } finally {
            g.dispose()
        }
        return out
    }

    private fun writeJpg(img: BufferedImage): File? = try {
        val out = File.createTempFile("thumb", ".jpg")
        out.deleteOnExit()
        ImageIO.write(img, "jpg", out)
        if (out.length() > 0) out else { out.delete(); null }
    } catch (e: Exception) {
        logger.warn("jpg encode failed: ${e.message}")
        null
    }

    /** ImageReader 只读图片头尺寸（不解码全图，防大图 OOM）。 */
    private fun readImageSize(src: File): Pair<Int, Int>? = try {
        // 诊断：服务器上曾返回 null 且异常被吞（排查中）
        val readers = ImageIO.getImageReaders(FileImageInputStream(src))
        if (readers.hasNext()) {
            val reader = readers.next()
            reader.input = FileImageInputStream(src)
            val w = reader.getWidth(0)
            val h = reader.getHeight(0)
            reader.dispose()
            w to h
        } else null
    } catch (e: Throwable) {
        logger.warn("readImageSize failed: ${e::class.simpleName}: ${e.message}")
        null
    }
}
