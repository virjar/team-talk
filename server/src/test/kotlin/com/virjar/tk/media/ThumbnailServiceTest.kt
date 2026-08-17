package com.virjar.tk.media

import com.virjar.tk.infra.media.ThumbnailService
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue

/** ThumbnailService 单测：图片 Java2D 缩略图 + 视频 javacv JNI（降级安全）。 */
class ThumbnailServiceTest {

    private val svc = ThumbnailService()

    @Test
    fun `图片缩略图与尺寸`() {
        val src = File("/tmp/test-media/photo.png")
        if (!src.exists()) { println("[skip] no sample"); return }
        val info = svc.processImage(src)!!
        assertTrue(info.width == 800 && info.height == 600, "尺寸读取: ${info.width}x${info.height}")
        val thumb = info.thumbFile!!
        val t = ImageIO.read(thumb)!!
        assertTrue(t.width == 480 && t.height == 360, "缩略图: ${t.width}x${t.height}")
        thumb.delete()
    }

    @Test
    fun `视频解码降级安全（无效视频不崩溃）`() {
        val fake = File("/tmp/test-media/fake.mp4")
        fake.writeBytes(ByteArray(4096) { (it % 251).toByte() })
        val info = svc.processVideo(fake)
        println("fake video thumb=${info?.thumbFile != null}")
        fake.delete()
    }
}
