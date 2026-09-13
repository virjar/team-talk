package com.virjar.tk.shared.update

import com.virjar.tk.protocol.http.ClientPayloadFile
import com.virjar.tk.protocol.http.ClientReleaseInfo
import com.virjar.tk.protocol.http.ClientReleaseManifest
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopUpdaterTest {

    private val server = "https://updates.example.test"

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private inner class Fixture {
        val root = Files.createTempDirectory("payload-root-").toFile()
        val currentDir = File(root, "5").apply { mkdirs() }

        init {
            File(currentDir, "lib").mkdirs()
            File(currentDir, "lib/app.jar").writeBytes("app-v1".toByteArray())
            File(currentDir, "lib/shared.jar").writeBytes("shared-v1".toByteArray())
            PayloadLayout.writeDescriptor(
                currentDir,
                PayloadLayout.PayloadDescriptor(
                    version = "0.0.2",
                    build = 5,
                    minShellAbi = 1,
                    files = listOf(
                        PayloadLayout.PayloadFile("lib/app.jar", sha256("app-v1".toByteArray()), 6),
                        PayloadLayout.PayloadFile("lib/shared.jar", sha256("shared-v1".toByteArray()), 10),
                    ),
                ),
            )
            PayloadLayout.writeCurrentPointer(root, PayloadLayout.CurrentPointer("0.0.2", 5))
        }

        fun context() = DesktopUpdateContext(
            versionsRoot = root,
            currentDir = currentDir,
            descriptor = PayloadLayout.readDescriptor(currentDir)!!,
            launcherCommand = null,
            shellAbi = 1,
        )
    }

    private fun manifest(build: Long, files: List<ClientPayloadFile>) = ClientReleaseManifest(
        releaseId = build,
        clientType = "desktop",
        platform = "macos",
        arch = "aarch64",
        version = "0.0.3",
        build = build,
        minShellAbi = 1,
        files = files,
    )

    private fun info(manifestUrl: String) = ClientReleaseInfo(
        id = 6,
        clientType = "desktop",
        platform = "macos",
        arch = "aarch64",
        version = "0.0.3",
        build = 6,
        channel = "stable",
        manifestUrl = manifestUrl,
    )

    @Test
    fun `只下载变化文件并原子切换指针`() = runTest {
        val fixture = Fixture()
        try {
            val appJarBytes = "app-v1".toByteArray()
            val newSharedBytes = "shared-v2-longer".toByteArray()
            val newNativeBytes = "native-new".toByteArray()
            val files = listOf(
                ClientPayloadFile("lib/app.jar", sha256(appJarBytes), appJarBytes.size.toLong(), null, "/api/v1/client/files/${sha256(appJarBytes)}"),
                ClientPayloadFile("lib/shared.jar", sha256(newSharedBytes), newSharedBytes.size.toLong(), null, "/api/v1/client/files/${sha256(newSharedBytes)}"),
                ClientPayloadFile("native/libskiko.dylib", sha256(newNativeBytes), newNativeBytes.size.toLong(), null, "/api/v1/client/files/${sha256(newNativeBytes)}"),
            )
            val manifestJson = ClientUpdateContractsJson.encodeToString(
                ClientReleaseManifest.serializer(),
                manifest(6, files),
            )
            val http = FakeHttp(
                mapOf(
                    "$server/api/v1/client/releases/6/manifest.json" to manifestJson.toByteArray(),
                    "$server/api/v1/client/files/${sha256(newSharedBytes)}" to newSharedBytes,
                    "$server/api/v1/client/files/${sha256(newNativeBytes)}" to newNativeBytes,
                ),
            )
            val updater = DesktopUpdater(fixture.context(), http)

            var lastProgress: DesktopUpdater.DownloadProgress? = null
            val result = updater.downloadAndApply(server, info("/api/v1/client/releases/6/manifest.json")) {
                lastProgress = it
            }

            assertEquals(6, result.build)
            // 增量语义：app.jar 未变化不进入下载字节。
            assertEquals(newSharedBytes.size + newNativeBytes.size.toLong(), result.downloadedBytes)
            assertEquals(result.downloadedBytes, lastProgress?.totalBytes)

            val newDir = File(fixture.root, PayloadLayout.readCurrentPointer(fixture.root)!!.directory)
            assertTrue(newDir.isDirectory)
            assertEquals("app-v1", File(newDir, "lib/app.jar").readText())
            assertEquals("shared-v2-longer", File(newDir, "lib/shared.jar").readText())
            assertEquals("native-new", File(newDir, "native/libskiko.dylib").readText())
            val pointer = PayloadLayout.readCurrentPointer(fixture.root)!!
            assertEquals(6, pointer.build)
            assertEquals("0.0.3", pointer.version)
            // 旧版本目录保留（可回滚/再次指向）。
            assertTrue(File(fixture.root, "5").isDirectory)
            // 描述符可被 bootstrap 侧解析。
            val descriptor = PayloadLayout.readDescriptor(newDir)!!
            assertEquals(3, descriptor.files.size)
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun `下载内容损坏时整体回退且指针不变`() = runTest {
        val fixture = Fixture()
        try {
            val corruptBytes = "corrupted-content".toByteArray()
            val declaredBytes = "shared-v2".toByteArray()
            val files = listOf(
                ClientPayloadFile(
                    "lib/shared.jar",
                    sha256(declaredBytes),
                    declaredBytes.size.toLong(),
                    null,
                    "/api/v1/client/files/${sha256(corruptBytes)}",
                ),
            )
            val manifestJson = ClientUpdateContractsJson.encodeToString(
                ClientReleaseManifest.serializer(),
                manifest(6, files),
            )
            val http = FakeHttp(
                mapOf(
                    "$server/api/v1/client/releases/6/manifest.json" to manifestJson.toByteArray(),
                    "$server/api/v1/client/files/${sha256(corruptBytes)}" to corruptBytes,
                ),
            )
            val updater = DesktopUpdater(fixture.context(), http)

            assertFailsWith<DesktopUpdateException> {
                updater.downloadAndApply(server, info("/api/v1/client/releases/6/manifest.json"))
            }
            // 指针仍在旧版本；没有残留暂存目录。
            assertEquals(5, PayloadLayout.readCurrentPointer(fixture.root)!!.build)
            assertTrue(fixture.root.listFiles()!!.none { it.name.startsWith(PayloadLayout.STAGING_PREFIX) })
            assertFalseDir(File(fixture.root, "6"))
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun `描述符与指针属性文件往返`() {
        val root = Files.createTempDirectory("payload-contract-").toFile()
        try {
            val dir = File(root, "9").apply { mkdirs() }
            PayloadLayout.writeDescriptor(
                dir,
                PayloadLayout.PayloadDescriptor(
                    version = "0.0.4",
                    build = 9,
                    minShellAbi = 2,
                    files = listOf(
                        PayloadLayout.PayloadFile("lib/a.jar", "a".repeat(64), 1),
                        PayloadLayout.PayloadFile("lib/b.jar", "b".repeat(64), 2),
                    ),
                ),
            )
            val descriptor = PayloadLayout.readDescriptor(dir)!!
            assertEquals("0.0.4", descriptor.version)
            assertEquals(9, descriptor.build)
            assertEquals(2, descriptor.minShellAbi)
            assertEquals(listOf("lib/a.jar", "lib/b.jar"), descriptor.files.map { it.path })

            PayloadLayout.writeCurrentPointer(root, PayloadLayout.CurrentPointer("0.0.4", 9))
            assertEquals(9, PayloadLayout.readCurrentPointer(root)!!.build)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `同构建号不同版本保留运行目录且可修复本地损坏`() = runTest {
        val fixture = Fixture()
        try {
            File(fixture.currentDir, "lib/app.jar").writeText("broken")
            val bytes = "app-v1".toByteArray()
            val file = ClientPayloadFile("lib/app.jar", sha256(bytes), bytes.size.toLong(), null,
                "/api/v1/client/files/${sha256(bytes)}")
            val incoming = manifest(5, listOf(file))
            val http = FakeHttp(mapOf(
                "$server/manifest" to ClientUpdateContractsJson.encodeToString(ClientReleaseManifest.serializer(), incoming).toByteArray(),
                "$server${file.url}" to bytes,
            ))
            DesktopUpdater(fixture.context(), http).downloadAndApply(server, info("/manifest").copy(id = 5, build = 5))
            val pointer = PayloadLayout.readCurrentPointer(fixture.root)!!
            assertEquals("0.0.3", pointer.version)
            assertEquals("broken", File(fixture.currentDir, "lib/app.jar").readText())
            assertEquals("app-v1", File(File(fixture.root, pointer.directory), "lib/app.jar").readText())
            assertTrue(pointer.directory != "5")
        } finally { fixture.root.deleteRecursively() }
    }

    @Test
    fun `不匹配身份过旧壳和越界路径在写文件前拒绝`() = runTest {
        val fixture = Fixture()
        try {
            val bytes = "jar".toByteArray()
            val file = ClientPayloadFile("lib/app.jar", sha256(bytes), 3, null, "/api/v1/client/files/${sha256(bytes)}")
            val invalid = listOf(
                manifest(6, listOf(file)).copy(buildIdentity = "different-source"),
                manifest(6, listOf(file)).copy(minShellAbi = 2),
                manifest(6, listOf(file.copy(path = "../outside.jar"))),
            )
            invalid.forEach { incoming ->
                val http = FakeHttp(mapOf("$server/manifest" to
                    ClientUpdateContractsJson.encodeToString(ClientReleaseManifest.serializer(), incoming).toByteArray()))
                assertFailsWith<IllegalArgumentException> {
                    DesktopUpdater(fixture.context(), http).downloadAndApply(server, info("/manifest"))
                }
                assertEquals("5", PayloadLayout.readCurrentPointer(fixture.root)!!.directory)
            }
        } finally { fixture.root.deleteRecursively() }
    }

    private fun assertFalseDir(file: File) {
        assertTrue(!file.isDirectory, "${file.absolutePath} should not exist")
    }

    private class FakeHttp(private val responses: Map<String, ByteArray>) : UpdateHttpClient {
        override suspend fun get(url: String): ByteArray =
            responses[url] ?: throw UpdateHttpException(404, "no fixture for $url")
    }
}

/** 测试用 JSON 实例（与生产 ClientUpdateContracts.json 同配置）。 */
private val ClientUpdateContractsJson = com.virjar.tk.protocol.http.ClientUpdateContracts.json
