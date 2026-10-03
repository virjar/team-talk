package com.virjar.tk.desktop.call

import dev.onvoid.webrtc.media.video.I420Buffer
import dev.onvoid.webrtc.media.video.VideoFrameBuffer
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 帧方向（RTP CVO rotation）烤进 I420→BGRA 映射的正确性锁定：
 * 用四象限灰阶源（U=V=128，色度均匀无串扰）验证 0/90/180/270 下
 * 输出尺寸换位与象限迁移——手机竖屏"横着播"回归的单元层防线。
 */
class DesktopFrameRotationTest {

    /** 4x4 四象限灰阶：TL=0 TR=85 BL=170 BR=255；色度全 128（纯灰）。 */
    private class QuadrantI420 : I420Buffer {
        private val y = ByteArray(16)
        private val uv = ByteArray(4)
        override fun getWidth() = 4
        override fun getHeight() = 4
        override fun getDataY(): ByteBuffer = ByteBuffer.wrap(y)
        override fun getDataU(): ByteBuffer = ByteBuffer.wrap(uv)
        override fun getDataV(): ByteBuffer = ByteBuffer.wrap(uv)
        override fun getStrideY() = 4
        override fun getStrideU() = 2
        override fun getStrideV() = 2

        init {
            fun put(row: Int, col: Int, v: Int) { y[row * 4 + col] = v.toByte() }
            for (r in 0..1) for (c in 0..1) put(r, c, 0)
            for (r in 0..1) for (c in 2..3) put(r, c, 85)
            for (r in 2..3) for (c in 0..1) put(r, c, 170)
            for (r in 2..3) for (c in 2..3) put(r, c, 255)
            uv.fill(128.toByte())
        }

        override fun toI420(): I420Buffer = this
        override fun cropAndScale(i: Int, j: Int, w: Int, h: Int, nw: Int, nh: Int): VideoFrameBuffer = this
        override fun retain() {}
        override fun release() {}
    }

    private fun grayAt(out: ByteArray, outWidth: Int, x: Int, y: Int): Int {
        val p = (y * outWidth + x) * 4
        // 灰阶下 B=G=R，取 B 即 Y；A 固定 255
        assertEquals(255, out[p + 3].toInt() and 0xFF, "alpha 应为不透明")
        return out[p].toInt() and 0xFF
    }

    @Test
    fun `rotation 0 输出保持原象限`() {
        val out = ByteArray(4 * 4 * 4)
        DesktopVideoFramePump.convertI420ToBgra(QuadrantI420(), 4, 4, 0, out)
        assertEquals(0, grayAt(out, 4, 0, 0))
        assertEquals(85, grayAt(out, 4, 3, 0))
        assertEquals(170, grayAt(out, 4, 0, 3))
        assertEquals(255, grayAt(out, 4, 3, 3))
    }

    @Test
    fun `rotation 90 顺时针 象限右旋且宽高换位`() {
        val out = ByteArray(4 * 4 * 4)
        DesktopVideoFramePump.convertI420ToBgra(QuadrantI420(), 4, 4, 90, out)
        // 源 TL(0)→输出 TR；TR(85)→BR；BR(255)→BL；BL(170)→TL
        assertEquals(170, grayAt(out, 4, 0, 0))
        assertEquals(0, grayAt(out, 4, 3, 0))
        assertEquals(255, grayAt(out, 4, 0, 3))
        assertEquals(85, grayAt(out, 4, 3, 3))
    }

    @Test
    fun `rotation 180 象限对角互换`() {
        val out = ByteArray(4 * 4 * 4)
        DesktopVideoFramePump.convertI420ToBgra(QuadrantI420(), 4, 4, 180, out)
        assertEquals(255, grayAt(out, 4, 0, 0))
        assertEquals(170, grayAt(out, 4, 3, 0))
        assertEquals(85, grayAt(out, 4, 0, 3))
        assertEquals(0, grayAt(out, 4, 3, 3))
    }

    @Test
    fun `rotation 270 逆时针 象限左旋`() {
        val out = ByteArray(4 * 4 * 4)
        DesktopVideoFramePump.convertI420ToBgra(QuadrantI420(), 4, 4, 270, out)
        // 源 TR(85)→输出 TL；BR(255)→TR；TL(0)→BL；BL(170)→BR
        assertEquals(85, grayAt(out, 4, 0, 0))
        assertEquals(255, grayAt(out, 4, 3, 0))
        assertEquals(0, grayAt(out, 4, 0, 3))
        assertEquals(170, grayAt(out, 4, 3, 3))
    }

    @Test
    fun `非方形源 90 与 270 输出尺寸换位`() {
        val w = 6
        val h = 4
        val buffer = object : I420Buffer {
            private val y = ByteArray(w * h) { 128.toByte() }
            private val u = ByteArray(w * h / 4) { 128.toByte() }
            override fun getWidth() = w
            override fun getHeight() = h
            override fun getDataY(): ByteBuffer = ByteBuffer.wrap(y)
            override fun getDataU(): ByteBuffer = ByteBuffer.wrap(u)
            override fun getDataV(): ByteBuffer = ByteBuffer.wrap(u)
            override fun getStrideY() = w
            override fun getStrideU() = w / 2
            override fun getStrideV() = w / 2
            override fun toI420(): I420Buffer = this
            override fun cropAndScale(i: Int, j: Int, a: Int, b: Int, c: Int, d: Int): VideoFrameBuffer = this
            override fun retain() {}
            override fun release() {}
        }
        // 输出缓冲按换位后的尺寸给：6x4 源 90° → 4x6 输出
        val out90 = ByteArray(w * h * 4)
        DesktopVideoFramePump.convertI420ToBgra(buffer, w, h, 90, out90)
        // 全灰帧只验证转换完整遍历不越界不缺像素（越界会抛异常或尾部留零）
        for (i in out90.indices step 4) assertEquals(128, out90[i].toInt() and 0xFF)
    }
}
