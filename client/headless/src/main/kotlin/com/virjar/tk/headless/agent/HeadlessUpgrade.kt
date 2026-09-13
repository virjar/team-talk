package com.virjar.tk.headless.agent

import com.virjar.tk.protocol.http.ClientReleaseInfo
import com.virjar.tk.protocol.http.ClientUpdateCheckResponse
import com.virjar.tk.protocol.http.ClientUpdateContracts
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.time.Duration
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * `tt-agent upgrade`：无头客户端在线升级（统一发布注册中心）。
 *
 * check → 全量 bundle 下载（内容地址 sha256 校验）→ 复用 HeadlessBundleInstaller 的
 * upgrade-bundle 原子切换。升级不删旧版本；systemd 场景重启后生效。
 *
 * 必须从“当前在用”的分发目录运行（与 install-bundle 同语义）；服务器地址解析顺序：
 * --server-url > TK_SERVER_URL 环境变量。
 */
internal object HeadlessUpgrade {

    fun execute(args: List<String>, currentBundle: File?) {
        var channel: String? = null
        var serverUrl: String? = null
        var prefixArg: String? = null
        var index = 0
        while (index < args.size) {
            when (args[index]) {
                "--channel" -> {
                    channel = args.getOrNull(index + 1)?.takeIf { it.isNotBlank() }
                        ?: usage("missing --channel value")
                    index += 2
                }
                "--server-url" -> {
                    serverUrl = args.getOrNull(index + 1)?.takeIf { it.isNotBlank() }
                        ?: usage("missing --server-url value")
                    index += 2
                }
                "--prefix" -> {
                    prefixArg = args.getOrNull(index + 1)?.takeIf { it.isNotBlank() }
                        ?: usage("missing --prefix value")
                    index += 2
                }
                else -> usage("unknown option ${args[index]}")
            }
        }
        require(channel == null || channel in setOf("stable", "preview", "snapshot")) { "channel must be stable/preview/snapshot" }
        val server = (serverUrl ?: System.getenv("TK_SERVER_URL"))
            ?.let { it.trim().trimEnd('/') }
            ?: usage("set --server-url or TK_SERVER_URL to the TeamTalk server base URL")

        val running = requireNotNull(currentBundle) {
            "run tt-agent upgrade from the current headless distribution (bin/tt-agent upgrade)"
        }
        val current = HeadlessBundleInstaller.verifyBundle(running)

        val prefix = prefixArg?.let(::File)
            ?: HeadlessBundleInstaller.managedPrefix(running)
            ?: usage("no managed installation found; pass --prefix <directory>")

        val selectedChannel = channel ?: current.channel
        val release = checkForUpdate(server, selectedChannel, current)
        if (release == null) {
            println("当前通道暂无更新（${current.version} build ${current.releaseBuildNumber}，通道 $selectedChannel）。")
            return
        }
        require(release.clientType == ClientUpdateContracts.CLIENT_HEADLESS &&
            release.platform == ClientUpdateContracts.PLATFORM_ANY && release.arch == ClientUpdateContracts.ARCH_ANY) {
            "registry returned a release for a different client"
        }
        val bundleUrl = release.bundleUrl ?: error("registry returned a headless release without a bundle URL")
        require(Regex("/api/v1/client/files/[0-9a-f]{64}").matches(bundleUrl)) { "invalid bundle URL" }
        println("发现新版本 v${release.version}（build ${release.build}），开始下载…")

        val staging = Files.createTempDirectory("tt-agent-upgrade-").toFile()
        try {
            val downloaded = download(server + bundleUrl, File(staging, "bundle.zip"), bundleUrl.substringAfterLast('/'))
            val extractedRoot = extractBundle(downloaded, staging)
            val incoming = HeadlessBundleInstaller.verifyBundle(extractedRoot)
            require(incoming.version == release.version && incoming.releaseBuildNumber == release.build &&
                (release.buildIdentity == null || incoming.buildIdentity == release.buildIdentity)) {
                "downloaded bundle does not match the selected release"
            }
            val result = HeadlessBundleInstaller.upgrade(prefix, incoming)
            println("升级就绪：${result.prefix.absolutePath}（重启 tt-agent / systemd 服务后生效）")
        } finally {
            staging.deleteRecursively()
        }
    }

    /** null = 已是最新；返回目标发布信息。 */
    private fun checkForUpdate(
        server: String,
        channel: String,
        current: HeadlessBundleInstaller.BundleFacts,
    ): ClientReleaseInfo? {
        val url = buildString {
            append(server).append("/api/v1/client/updates/check")
            append("?client=").append(ClientUpdateContracts.CLIENT_HEADLESS)
            append("&platform=").append(ClientUpdateContracts.PLATFORM_ANY)
            append("&arch=").append(ClientUpdateContracts.ARCH_ANY)
            append("&channel=").append(channel)
            append("&version=").append(URLEncoder.encode(current.version, "UTF-8"))
            append("&buildIdentity=").append(URLEncoder.encode(current.buildIdentity, "UTF-8"))
            append("&build=").append(current.releaseBuildNumber)
        }
        val response = http.send(
            HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        require(response.statusCode() == 200) { "检查更新失败：HTTP ${response.statusCode()}" }
        val decision = ClientUpdateContracts.json.decodeFromString(
            ClientUpdateCheckResponse.serializer(),
            response.body(),
        )
        return when (decision.status) {
            ClientUpdateContracts.STATUS_UPDATE_AVAILABLE,
            ClientUpdateContracts.STATUS_SHELL_UPDATE_REQUIRED -> requireNotNull(decision.release) { "更新响应缺少发布信息" }
            ClientUpdateContracts.STATUS_UP_TO_DATE,
            ClientUpdateContracts.STATUS_CHANNEL_DISABLED -> null
            else -> error("未知更新状态：${decision.status}")
        }
    }

    /** 下载 bundle 并用检查结果的内容地址校验完整性。 */
    private fun download(url: String, target: File, expectedSha: String): File {
        val response = http.send(
            HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).GET().build(),
            HttpResponse.BodyHandlers.ofFile(target.toPath()),
        )
        require(response.statusCode() == 200) { "下载失败：HTTP ${response.statusCode()}" }
        require(sha256Hex(target) == expectedSha) { "下载内容校验失败" }
        return target
    }

    /** 分发 zip 只含一个顶层目录；返回该目录（upgrade-bundle 的 source 语义）。 */
    private fun extractBundle(zip: File, staging: File): File {
        ZipFile(zip).use { archive ->
            val topDirs = archive.entries().asSequence()
                .filterNot { it.isDirectory }
                .map { it.name.substringBefore('/') }
                .filter { it.isNotBlank() }
                .toSet()
            require(topDirs.size == 1) { "unexpected headless bundle layout: $topDirs" }
            val root = File(staging, topDirs.single())
            archive.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                val name = entry.name
                require(!name.startsWith("/") && !name.contains("..") && !name.contains('\\')) {
                    "unsafe bundle entry: $name"
                }
                val target = File(staging, name)
                target.parentFile?.mkdirs()
                archive.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                // bin/ 启动脚本带可执行位。
                if (name.removePrefix(topDirs.single() + "/").startsWith("bin/") && !name.endsWith(".bat")) target.setExecutable(true)
            }
            return root
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    private fun usage(message: String): Nothing {
        throw IllegalArgumentException(
            "$message\n用法：tt-agent upgrade [--channel stable|preview|snapshot] " +
                "[--server-url <url>] [--prefix <dir>]",
        )
    }
}
