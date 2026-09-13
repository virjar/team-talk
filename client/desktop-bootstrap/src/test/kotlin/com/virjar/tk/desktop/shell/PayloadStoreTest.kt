package com.virjar.tk.desktop.shell

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PayloadStoreTest {
    @Test
    fun `首装种子只应用一次且全量换装采用新负载`() {
        val root = Files.createTempDirectory("bootstrap-test-").toFile()
        try {
            val install = File(root, "install").apply { mkdirs() }
            val versions = File(root, "versions").apply { mkdirs() }
            seed(install, "first")
            val first = PayloadStore.selectPayload(listOf(install), versions)!!
            assertEquals("first", File(versions, "${first.directory}/lib/app.jar").readText())

            // 应用内更新使用相同的指针契约并保留 seedId。
            val updated = File(versions, "downloaded").apply { mkdirs() }
            File(versions, first.directory).copyRecursively(updated, overwrite = true)
            PayloadStore.writePointer(versions, PayloadStore.Pointer("0.0.2", 3, "downloaded", first.seedId))
            assertEquals("downloaded", PayloadStore.selectPayload(listOf(install), versions)!!.directory)

            // 用户换装新安装包，即使安装 build 相同，也必须采用新种子。
            seed(install, "second")
            val second = PayloadStore.selectPayload(listOf(install), versions)!!
            assertNotEquals(first.directory, second.directory)
            assertEquals("second", File(versions, "${second.directory}/lib/app.jar").readText())
            assertTrue(File(versions, first.directory).isDirectory)
            assertTrue(updated.isDirectory)
            assertEquals(second.directory, PayloadStore.selectPayload(listOf(install), versions)!!.directory)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `损坏负载从安装种子恢复且不覆盖原目录`() {
        val root = Files.createTempDirectory("bootstrap-repair-").toFile()
        try {
            val install = File(root, "install").apply { mkdirs() }
            val versions = File(root, "versions").apply { mkdirs() }
            seed(install, "healthy")
            val old = PayloadStore.selectPayload(listOf(install), versions)!!
            File(versions, "${old.directory}/lib/app.jar").delete()
            val repaired = PayloadStore.selectPayload(listOf(install), versions)!!
            assertNotEquals(old.directory, repaired.directory)
            assertEquals(old.seedId, repaired.seedId)
            assertEquals("healthy", File(versions, "${repaired.directory}/lib/app.jar").readText())
            assertTrue(File(versions, old.directory).isDirectory)
        } finally { root.deleteRecursively() }
    }

    private fun seed(install: File, body: String) {
        val root = File(install, "seed-payload").apply { mkdirs() }
        val file = File(root, "lib/app.jar").also { it.parentFile.mkdirs(); it.writeText(body) }
        val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val props = Properties().apply {
            setProperty("version", "0.0.2")
            setProperty("build", "3")
            setProperty("minShellAbi", "1")
            setProperty("buildIdentity", "0.0.2+" + "a".repeat(40))
            setProperty("files.count", "1")
            setProperty("file.0.path", "lib/app.jar")
            setProperty("file.0.sha256", sha)
            setProperty("file.0.size", file.length().toString())
        }
        File(root, "payload.properties").outputStream().use { props.store(it, "test") }
    }
}
