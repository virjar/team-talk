package com.virjar.tk.shared.update

import com.virjar.tk.protocol.http.ClientPayloadFile
import com.virjar.tk.protocol.http.ClientReleaseInfo
import com.virjar.tk.protocol.http.ClientReleaseManifest
import com.virjar.tk.protocol.http.ClientUpdateCheckResponse
import com.virjar.tk.protocol.http.ClientUpdateContracts
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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
            val corruptBytes = "broken-v2".toByteArray()
            val declaredBytes = "shared-v2".toByteArray()
            val files = listOf(
                ClientPayloadFile(
                    "lib/shared.jar",
                    sha256(declaredBytes),
                    declaredBytes.size.toLong(),
                    null,
                    "/api/v1/client/files/${sha256(declaredBytes)}",
                ),
            )
            val manifestJson = ClientUpdateContractsJson.encodeToString(
                ClientReleaseManifest.serializer(),
                manifest(6, files),
            )
            val http = FakeHttp(
                mapOf(
                    "$server/api/v1/client/releases/6/manifest.json" to manifestJson.toByteArray(),
                    "$server/api/v1/client/files/${sha256(declaredBytes)}" to corruptBytes,
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

    @Test
    fun `会话仅安装用户确认的发布且保留当前通道检查`() = runTest {
        val fixture = Fixture()
        try {
            val bytes = "app-v1".toByteArray()
            val file = ClientPayloadFile("lib/app.jar", sha256(bytes), bytes.size.toLong(), null,
                "/api/v1/client/files/${sha256(bytes)}")
            val release = info("/manifest").copy(notes = "已确认的发行说明", totalBytes = 1_000_000)
            val requests = mutableListOf<String>()
            val http = object : UpdateHttpClient {
                override suspend fun get(url: String): ByteArray {
                    requests += url
                    return when {
                        url.startsWith("$server/api/v1/client/updates/check?") ->
                            ClientUpdateContracts.json.encodeToString(ClientUpdateCheckResponse.serializer(),
                                ClientUpdateCheckResponse(ClientUpdateContracts.STATUS_UPDATE_AVAILABLE, release)).toByteArray()
                        url == "$server/manifest" ->
                            ClientUpdateContracts.json.encodeToString(ClientReleaseManifest.serializer(),
                                manifest(6, listOf(file))).toByteArray()
                        else -> error("Unexpected download: $url")
                    }
                }
            }
            var applied = false
            val session = DesktopUpdateSession(server, httpClient = http, context = fixture.context(), scope = this,
                onUpdateApplied = { applied = true })
            session.startCheck()
            val offered = assertIs<DesktopUpdateUiState.Available>(session.state.first { it !is DesktopUpdateUiState.Checking })
            assertEquals(release, offered.release)
            session.startDownload()
            // 尚未比对本地文件时，不把安装器大小当作增量下载大小。
            assertEquals(DesktopUpdateUiState.Downloading(0, 0), session.state.value)
            assertEquals(DesktopUpdateUiState.ReadyToRestart("0.0.3", 6),
                session.state.first { it !is DesktopUpdateUiState.Downloading })
            assertTrue(applied)
            assertEquals(6, PayloadLayout.readCurrentPointer(fixture.root)!!.build)
            assertEquals(2, requests.count { it.contains("/updates/check?") })
            assertTrue(requests.filter { it.contains("/updates/check?") }.all { it.contains("channel=stable") })
            assertEquals(3, requests.size, "本地已有相同 jar，无需再次下载")
        } finally { fixture.root.deleteRecursively() }
    }

    @Test
    fun `确认后发布切换或通道停用不下载也不切换指针`() = runTest {
        val release = info("/manifest").copy(buildIdentity = "source-a")
        val decisions = listOf(
            ClientUpdateCheckResponse(ClientUpdateContracts.STATUS_UPDATE_AVAILABLE, release.copy(id = 7)),
            ClientUpdateCheckResponse(ClientUpdateContracts.STATUS_UPDATE_AVAILABLE, release.copy(buildIdentity = "source-b")),
            ClientUpdateCheckResponse(ClientUpdateContracts.STATUS_CHANNEL_DISABLED),
            ClientUpdateCheckResponse(ClientUpdateContracts.STATUS_SHELL_UPDATE_REQUIRED, release),
        )
        for (nextDecision in decisions) {
            val fixture = Fixture()
            try {
                var checks = 0
                val http = object : UpdateHttpClient {
                    override suspend fun get(url: String): ByteArray {
                        check(url.startsWith("$server/api/v1/client/updates/check?")) { "Unexpected download: $url" }
                        val decision = if (checks++ == 0)
                            ClientUpdateCheckResponse(ClientUpdateContracts.STATUS_UPDATE_AVAILABLE, release)
                        else nextDecision
                        return ClientUpdateContracts.json.encodeToString(ClientUpdateCheckResponse.serializer(), decision).toByteArray()
                    }
                }
                val session = DesktopUpdateSession(server, httpClient = http, context = fixture.context(), scope = this,
                    onUpdateApplied = { error("必须保留当前安装") })
                session.startCheck()
                assertIs<DesktopUpdateUiState.Available>(session.state.first { it !is DesktopUpdateUiState.Checking })
                session.startDownload()
                val failure = assertIs<DesktopUpdateUiState.Failed>(session.state.first { it !is DesktopUpdateUiState.Downloading })
                assertEquals("发布已变化，请重新检查更新", failure.message)
                assertEquals(2, checks)
                assertEquals("5", PayloadLayout.readCurrentPointer(fixture.root)!!.directory)
            } finally { fixture.root.deleteRecursively() }
        }
    }

    @Test
    fun `真实慢速下载报告文件中途进度且取消后立即释放暂存与锁`() = runBlocking {
        val fixture = Fixture()
        val releaseFirstDownload = CountDownLatch(1)
        try {
            val bytes = ByteArray(256 * 1024) { (it % 251).toByte() }
            val file = ClientPayloadFile("lib/app.jar", sha256(bytes), bytes.size.toLong(), null,
                "/api/v1/client/files/${sha256(bytes)}")
            val manifestBytes = ClientUpdateContracts.json.encodeToString(ClientReleaseManifest.serializer(),
                manifest(6, listOf(file))).toByteArray()
            val downloads = AtomicInteger()
            withHttpServer({ exchange ->
                if (exchange.requestURI.path == "/manifest") {
                    exchange.sendResponseHeaders(200, manifestBytes.size.toLong())
                    exchange.responseBody.write(manifestBytes)
                } else {
                    check(exchange.requestURI.path == file.url)
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    if (downloads.incrementAndGet() == 1) {
                        exchange.responseBody.write(bytes, 0, 64 * 1024)
                        exchange.responseBody.flush()
                        check(releaseFirstDownload.await(10, TimeUnit.SECONDS))
                        exchange.responseBody.write(bytes, 64 * 1024, bytes.size - 64 * 1024)
                    } else exchange.responseBody.write(bytes)
                }
            }) { serverUrl ->
                val updater = DesktopUpdater(fixture.context())
                val progress = CopyOnWriteArrayList<DesktopUpdater.DownloadProgress>()
                val partial = CompletableDeferred<Unit>()
                val download = async(Dispatchers.IO) {
                    updater.downloadAndApply(serverUrl, info("/manifest")) {
                        progress += it
                        if (it.completedBytes in 1 until it.totalBytes) partial.complete(Unit)
                    }
                }
                try {
                    withTimeout(5_000) { partial.await() }
                    assertEquals(DesktopUpdater.DownloadProgress(0, bytes.size.toLong()), progress.first())
                    withTimeout(2_000) { download.cancelAndJoin() }
                    assertEquals(1, releaseFirstDownload.count, "取消不需要等待服务器继续发送")
                    assertEquals("5", PayloadLayout.readCurrentPointer(fixture.root)!!.directory)
                    assertTrue(fixture.root.listFiles()!!.none { it.name.startsWith(PayloadLayout.STAGING_PREFIX) })

                    // 首次请求仍停在服务端，新的下载必须能够立即拿锁并独立完成。
                    progress.clear()
                    val result = withTimeout(5_000) {
                        updater.downloadAndApply(serverUrl, info("/manifest")) { progress += it }
                    }
                    assertEquals(bytes.size.toLong(), result.downloadedBytes)
                    assertEquals(DesktopUpdater.DownloadProgress(bytes.size.toLong(), bytes.size.toLong()), progress.last())
                    assertTrue(progress.zipWithNext().all { (before, after) -> before.completedBytes <= after.completedBytes })
                    assertEquals(2, downloads.get())
                    assertEquals(6, PayloadLayout.readCurrentPointer(fixture.root)!!.build)
                } finally {
                    releaseFirstDownload.countDown()
                    download.cancelAndJoin()
                }
            }
        } finally {
            releaseFirstDownload.countDown()
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun `等待真实响应头时也可取消`() = runBlocking {
        val arrived = CompletableDeferred<Unit>()
        val releaseResponse = CountDownLatch(1)
        withHttpServer({ exchange ->
            arrived.complete(Unit)
            check(releaseResponse.await(10, TimeUnit.SECONDS))
            exchange.sendResponseHeaders(200, 1)
            exchange.responseBody.write(1)
        }) { serverUrl ->
            val request = async(Dispatchers.IO) { JdkUpdateHttpClient.get(serverUrl) }
            try {
                withTimeout(5_000) { arrived.await() }
                withTimeout(2_000) { request.cancelAndJoin() }
                assertEquals(1, releaseResponse.count, "取消不需要等到响应头返回")
            } finally {
                releaseResponse.countDown()
                request.cancelAndJoin()
            }
        }
    }

    @Test
    fun `真实HTTP保留状态码禁止重定向与响应大小校验`() = runBlocking {
        val target = Files.createTempFile("update-http-", ".part").toFile()
        val followedRedirects = AtomicInteger()
        try {
            withHttpServer({ exchange ->
                val bytes = when (exchange.requestURI.path) {
                    "/redirect" -> {
                        exchange.responseHeaders.add("Location", "/unexpected")
                        exchange.sendResponseHeaders(302, -1)
                        null
                    }
                    "/unexpected" -> { followedRedirects.incrementAndGet(); byteArrayOf(1) }
                    "/manifest" -> ByteArray(4 * 1024 * 1024 + 1)
                    "/short" -> byteArrayOf(1, 2, 3)
                    else -> byteArrayOf(1, 2, 3, 4, 5)
                }
                if (bytes != null) {
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                }
            }) { serverUrl ->
                assertEquals(302, assertFailsWith<UpdateHttpException> { JdkUpdateHttpClient.get("$serverUrl/redirect") }.statusCode)
                assertEquals(0, followedRedirects.get())
                assertFailsWith<IllegalArgumentException> { JdkUpdateHttpClient.get("$serverUrl/manifest") }
                assertFailsWith<IllegalArgumentException> { JdkUpdateHttpClient.download("$serverUrl/short", target, 4) }
                assertFailsWith<IllegalArgumentException> { JdkUpdateHttpClient.download("$serverUrl/long", target, 4) }
            }
        } finally { target.delete() }
    }

    private suspend fun withHttpServer(handler: (HttpExchange) -> Unit, block: suspend (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val workers = Executors.newFixedThreadPool(2)
        server.executor = workers
        server.createContext("/") { exchange ->
            try { handler(exchange) } catch (_: IOException) {
                // 被测客户端下载取消或拒绝超大响应时会主动关闭连接。
            } finally { exchange.close() }
        }
        server.start()
        try { block("http://127.0.0.1:${server.address.port}") } finally {
            server.stop(0)
            workers.shutdownNow()
            check(workers.awaitTermination(5, TimeUnit.SECONDS)) { "HTTP fixture workers did not stop" }
        }
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
