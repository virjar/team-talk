package com.virjar.tk.server.media

import com.virjar.tk.server.infra.media.LocalThumbnailGenerator
import com.virjar.tk.server.infra.storage.ManagedTempResidueException
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Helper 内部解码器单测：图片 Java2D 缩略图 + 视频 javacv JNI（降级安全）。 */
class ThumbnailServiceTest {

    @Test
    fun `图片缩略图与尺寸`() {
        val root = Files.createTempDirectory("tk-image-thumbnail-").toFile()
        val source = File(root, "source.png")
        var thumbnail: File? = null
        try {
            val managedTmp = File(root, "managed-tmp")
            val svc = LocalThumbnailGenerator(managedTmp)
            assertTrue(ImageIO.write(BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB), "png", source))

            val info = checkNotNull(svc.processImage(source))
            assertEquals(800, info.width)
            assertEquals(600, info.height)
            thumbnail = checkNotNull(info.thumbFile)
            assertEquals(managedTmp.canonicalFile, thumbnail.parentFile.canonicalFile)
            val decoded = checkNotNull(ImageIO.read(thumbnail))
            assertEquals(480, decoded.width)
            assertEquals(360, decoded.height)
        } finally {
            thumbnail?.delete()
            check(root.deleteRecursively() || !root.exists()) {
                "Failed to delete image-thumbnail test root: $root"
            }
        }
    }

    @Test
    fun `无效图片释放输入流并安全降级`() {
        val root = Files.createTempDirectory("tk-invalid-image-").toFile()
        val source = File(root, "invalid.png")
        try {
            val svc = LocalThumbnailGenerator(File(root, "tmp"))
            source.writeText("not an image")
            assertNull(svc.processImage(source))
        } finally {
            assertTrue(root.deleteRecursively() || !root.exists())
        }
    }

    @Test
    fun `缩略图编码失败时删除失败作为 typed residue 向上传播`() {
        val root = Files.createTempDirectory("tk-thumbnail-retirement-failure-").toFile()
        val source = File(root, "alpha.png")
        try {
            assertTrue(ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", source))
            val expected = ManagedTempResidueException()
            val svc = LocalThumbnailGenerator(
                tempDirectory = File(root, "tmp"),
                retireTempFile = { throw expected },
                jpgEncoder = { _, _ -> false },
            )

            val observed = assertFailsWith<ManagedTempResidueException> { svc.processImage(source) }
            assertTrue(observed === expected)
        } finally {
            assertTrue(root.deleteRecursively() || !root.exists())
        }
    }

    @Test
    fun `缩略图生成后交接前失败会退休已生成文件`() {
        val root = Files.createTempDirectory("tk-thumbnail-handoff-failure-").toFile()
        val source = File(root, "alpha.png")
        var generated: File? = null
        var retired: File? = null
        try {
            assertTrue(ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", source))
            val managedTmp = File(root, "tmp")
            val svc = LocalThumbnailGenerator(
                tempDirectory = managedTmp,
                jpgEncoder = { image, file ->
                    generated = file
                    ImageIO.write(image, "jpg", file)
                },
                beforeThumbnailHandoff = { throw InjectedThumbnailHandoffFailure() },
                retireTempFile = { file ->
                    retired = file
                    Files.delete(file.toPath())
                },
            )

            assertNull(svc.processImage(source))
            assertSame(generated, retired)
            assertFalse(checkNotNull(generated).exists())
        } finally {
            assertTrue(root.deleteRecursively() || !root.exists())
        }
    }

    @Test
    fun `缩略图交接前失败且退休失败时 typed residue 向上传播`() {
        val root = Files.createTempDirectory("tk-thumbnail-handoff-residue-").toFile()
        val source = File(root, "alpha.png")
        try {
            assertTrue(ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", source))
            val expected = ManagedTempResidueException()
            val svc = LocalThumbnailGenerator(
                tempDirectory = File(root, "tmp"),
                beforeThumbnailHandoff = { throw InjectedThumbnailHandoffFailure() },
                retireTempFile = { throw expected },
            )

            val observed = assertFailsWith<ManagedTempResidueException> { svc.processImage(source) }
            assertSame(expected, observed)
        } finally {
            assertTrue(root.deleteRecursively() || !root.exists())
        }
    }

    @Test
    fun `像素炸弹在 reader 只读元信息阶段被拒绝`() {
        val root = Files.createTempDirectory("tk-image-pixel-bomb-").toFile()
        val source = File(root, "bomb.png")
        try {
            assertTrue(ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", source))
            rewritePngDimensions(source, width = 50_000, height = 50_000)
            val svc = LocalThumbnailGenerator(
                tempDirectory = File(root, "tmp"),
                maxImageDimension = 100_000,
                maxImagePixels = 1_000_000,
                maxDecodedPixels = 1_000_000,
            )

            assertNull(svc.processImage(source))
            assertTrue(!File(root, "tmp").exists(), "拒绝发生在创建输出临时文件之前")
        } finally {
            assertTrue(root.deleteRecursively() || !root.exists())
        }
    }

    @Test
    fun `尺寸与总像素硬预算接受精确边界并拒绝越界`() {
        val root = Files.createTempDirectory("tk-image-budget-boundary-").toFile()
        val accepted = File(root, "accepted.png")
        val rejected = File(root, "rejected.png")
        var thumbnail: File? = null
        try {
            assertTrue(ImageIO.write(BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "png", accepted))
            assertTrue(ImageIO.write(BufferedImage(10, 11, BufferedImage.TYPE_INT_RGB), "png", rejected))
            val svc = LocalThumbnailGenerator(
                tempDirectory = File(root, "tmp"),
                maxImageDimension = 10,
                maxImagePixels = 100,
                maxDecodedPixels = 100,
            )

            thumbnail = checkNotNull(svc.processImage(accepted)).thumbFile
            assertTrue(thumbnail?.isFile == true)
            assertNull(svc.processImage(rejected))
        } finally {
            thumbnail?.delete()
            assertTrue(root.deleteRecursively() || !root.exists())
        }
    }

    @Test
    fun `允许的大图按解码像素预算安全 subsampling`() {
        val root = Files.createTempDirectory("tk-image-subsampling-").toFile()
        val source = File(root, "source.png")
        var thumbnail: File? = null
        try {
            assertTrue(ImageIO.write(BufferedImage(1_000, 1_000, BufferedImage.TYPE_INT_RGB), "png", source))
            val svc = LocalThumbnailGenerator(
                tempDirectory = File(root, "tmp"),
                maxImageDimension = 1_000,
                maxImagePixels = 1_000_000,
                maxDecodedPixels = 250_000,
            )

            val info = checkNotNull(svc.processImage(source))
            assertEquals(1_000, info.width)
            assertEquals(1_000, info.height)
            thumbnail = checkNotNull(info.thumbFile)
            val decoded = checkNotNull(ImageIO.read(thumbnail))
            assertEquals(480, decoded.width)
            assertEquals(480, decoded.height)
        } finally {
            thumbnail?.delete()
            assertTrue(root.deleteRecursively() || !root.exists())
        }
    }

    @Test
    fun `视频解码降级安全（无效视频不崩溃）`() {
        // createTempFile：直接写死 /tmp/test-media/ 在全新 CI 环境必挂
        //（目录不存在，FileNotFoundException），本地曾因历史目录存在而误绿
        val fake = kotlin.io.path.createTempFile(prefix = "tt-fake-", suffix = ".mp4").toFile()
        try {
            val svc = LocalThumbnailGenerator(fake.parentFile)
            fake.writeBytes(ByteArray(4096) { (it % 251).toByte() })
            val info = svc.processVideo(fake)
            println("fake video thumb=${info?.thumbFile != null}")
        } finally {
            fake.delete()
        }
    }
}

private class InjectedThumbnailHandoffFailure : RuntimeException()

private fun rewritePngDimensions(file: File, width: Int, height: Int) {
    val bytes = file.readBytes()
    fun writeInt(offset: Int, value: Int) {
        for (index in 0 until Int.SIZE_BYTES) {
            bytes[offset + index] = (value ushr (24 - index * 8)).toByte()
        }
    }
    writeInt(16, width)
    writeInt(20, height)
    val crc = CRC32().apply { update(bytes, 12, 17) }.value.toInt()
    writeInt(29, crc)
    file.writeBytes(bytes)
}
