package com.virjar.tk.desktop.call

import com.virjar.tk.shared.log.TkLogger
import dev.onvoid.webrtc.media.video.I420Buffer
import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.VideoTrackSink
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 桌面视频帧泵：webrtc-java I420 帧 → 旋转感知转换 → Skia 位图 → 发布回调。
 *
 * 职责边界（引擎只做组装）：
 * - 帧方向：[VideoFrame.rotation]（RTP CVO，接收端顺时针旋转角）烤进 YUV→BGRA
 *   像素映射，90/270 输出宽高换位，单趟零额外分配；方向翻转记日志。
 * - native 内存：像素数组与位图跨帧复用，分辨率（或换向后尺寸）变化才重建——
 *   每帧 new Bitmap 的 native 分配在 JVM GC 压力不足时永不回收（实测 1 小时
 *   堆积 200GB+）。
 * - 限频：本地/远端共用引擎级 [throttleNanos]（~15fps），发布线程为 webrtc 回调线程。
 *
 * [localFirstFrame] 由引擎的摄像头首帧看门狗消费；[publish] 收到的位图归泵所有
 * 并会在后续帧被复写，消费方不得缓存像素内容。
 */
internal class DesktopVideoFramePump(
    private val isLocal: Boolean,
    private val logger: TkLogger,
    private val throttleNanos: AtomicLong,
    private val localFirstFrame: AtomicBoolean,
    private val publish: (Bitmap) -> Unit,
) : VideoTrackSink {

    private var firstFrameLogged = false
    private var lastRotation = -1

    private var reuseBitmap: Bitmap? = null
    private var reusePixels: ByteArray? = null
    private var reuseWidth = 0
    private var reuseHeight = 0

    override fun onVideoFrame(frame: VideoFrame) {
        val wasFirst = !firstFrameLogged
        // 归一化角度；方向翻转（用户旋转手机）单独记一条，便于排查"画面跟着转"
        val rotation = ((frame.rotation % 360) + 360) % 360
        if (wasFirst) {
            firstFrameLogged = true
            logger.fault(
                "[ice] 首帧到达 isLocal=$isLocal ${frame.buffer.getWidth()}x${frame.buffer.getHeight()} rotation=$rotation",
            )
            if (isLocal) localFirstFrame.set(true)
        } else if (rotation != lastRotation) {
            logger.fault("[ice] 帧方向变化 isLocal=$isLocal rotation=$rotation")
        }
        lastRotation = rotation
        val now = System.nanoTime()
        if (now - throttleNanos.get() < FRAME_INTERVAL_NANOS) return
        throttleNanos.set(now)
        try {
            val buffer = frame.buffer
            val width = buffer.getWidth()
            val height = buffer.getHeight()
            val outWidth = if (rotation == 90 || rotation == 270) height else width
            val outHeight = if (rotation == 90 || rotation == 270) width else height
            var bitmap = reuseBitmap
            var pixels = reusePixels
            if (bitmap == null || pixels == null || outWidth != reuseWidth || outHeight != reuseHeight) {
                pixels = ByteArray(outWidth * outHeight * 4)
                bitmap = Bitmap()
                bitmap.installPixels(
                    ImageInfo.makeN32(outWidth, outHeight, ColorAlphaType.OPAQUE),
                    pixels,
                    outWidth * 4,
                )
                reuseBitmap = bitmap
                reusePixels = pixels
                reuseWidth = outWidth
                reuseHeight = outHeight
            }
            convertI420ToBgra(buffer.toI420(), width, height, rotation, pixels)
            // installPixels 复用同一数组重绑，确保 Skia 侧指针刷新
            bitmap.installPixels(
                ImageInfo.makeN32(outWidth, outHeight, ColorAlphaType.OPAQUE),
                pixels,
                outWidth * 4,
            )
            publish(bitmap)
        } catch (failure: Exception) {
            if (!firstFrameLogged) logger.fault("[ice] 帧转换失败 isLocal=$isLocal: ${failure.message}")
            // 单帧转换失败只丢帧，不断媒体
        }
    }

    companion object {
        private val FRAME_INTERVAL_NANOS = 66_000_000L

        /**
         * I420 → BGRA（BT.601 有限范围），原地写入复用缓冲 out。
         *
         * [rotation] 是帧携带的显示旋转（RTP CVO 透传，如手机竖屏拍摄=传感器横放
         * buffer + 90°/270°）：直接烤进像素映射，输出宽高按角度换位，不做第二趟
         * 旋转拷贝。90/270 的行主序遍历对 CPU cache 不友好，但 ~15fps 限频下
         * 720p 实测无压力，换来零额外分配。
         */
        fun convertI420ToBgra(buffer: I420Buffer, width: Int, height: Int, rotation: Int, out: ByteArray) {
            val y = buffer.getDataY()
            val u = buffer.getDataU()
            val v = buffer.getDataV()
            val strideY = buffer.getStrideY()
            val strideU = buffer.getStrideU()
            val strideV = buffer.getStrideV()
            val swap = rotation == 90 || rotation == 270
            val outWidth = if (swap) height else width
            val outHeight = if (swap) width else height
            val pixels = out
            var p = 0
            for (row in 0 until outHeight) {
                for (col in 0 until outWidth) {
                    // 输出像素 (col,row) 反查源平面坐标（srcCol,srcRow）
                    val srcCol: Int
                    val srcRow: Int
                    when (rotation) {
                        90 -> { srcCol = row; srcRow = height - 1 - col }          // 顺时针：源左上 → 输出右上
                        180 -> { srcCol = width - 1 - col; srcRow = height - 1 - row }
                        270 -> { srcCol = width - 1 - row; srcRow = col }          // 逆时针：源左上 → 输出左下
                        else -> { srcCol = col; srcRow = row }
                    }
                    val yp = y.get(srcRow * strideY + srcCol).toInt() and 0xFF
                    val up = (u.get((srcRow / 2) * strideU + srcCol / 2).toInt() and 0xFF) - 128
                    val vp = (v.get((srcRow / 2) * strideV + srcCol / 2).toInt() and 0xFF) - 128
                    var r = yp + (1.402f * vp).toInt()
                    var g = yp - ((0.344136f * up).toInt() + (0.714136f * vp).toInt())
                    var b = yp + (1.772f * up).toInt()
                    r = r.coerceIn(0, 255)
                    g = g.coerceIn(0, 255)
                    b = b.coerceIn(0, 255)
                    pixels[p] = b.toByte()
                    pixels[p + 1] = g.toByte()
                    pixels[p + 2] = r.toByte()
                    pixels[p + 3] = 0xFF.toByte()
                    p += 4
                }
            }
        }
    }
}
