package release.publish

import kotlinx.serialization.json.put
import release.BundleIdentity
import release.DesktopTarget
import release.HeadlessDistribution
import release.ReleaseBundle
import java.io.File
import java.io.InputStream
import java.time.Duration
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 客户端发布注册中心上传器（替代旧的 SFTP 静态目录发布）：
 * 把密封 release bundle 里的桌面/Android/无头产物组成本章 M1 定义的发布上传包
 * （release.json + payload/ + bundle/ + installers/），经管理 API 推送并激活。
 *
 * 单一写入方是服务端 DB；上传包服务端解包、哈希校验、内容寻址入库。
 */
class ClientReleasePublisher(
    private val serverBaseUrl: String,
    private val token: String,
) {

    class PublicationResult(val uploaded: List<String>)

    fun publish(bundle: File, identity: BundleIdentity): PublicationResult {
        val channel = when (identity.distributionKind) {
            "release" -> "stable"
            "private-first" -> "preview"
            else -> "snapshot"
        }
        val notes = File(bundle, "RELEASE_NOTES.md").readText().lineSequence()
            .dropWhile { it.isBlank() }.toList()
        // 说明压缩成一段（保留前 32 行，避免把整份发行说明塞进更新对话框）。
        val noteText = notes.take(32).joinToString("\n").take(4000).ifBlank { null }

        val staging = Files.createTempDirectory("client-release-upload-").toFile()
        val uploaded = mutableListOf<String>()
        try {
            // ── 桌面四目标 ──
            DesktopTarget.values().forEach { target ->
                val key = target.key
                val platform = target.platform.id
                val arch = target.arch
                val desktopDir = File(bundle, "desktop/$key")
                val payloadZip = File(desktopDir, "payload.zip")
                val minShellAbi = payloadMinShellAbi(payloadZip)
                val installers = ReleaseBundle.desktopInstallers(desktopDir)
                    .map { it.name to installerLabel(target, it.name) }
                val upload = File(staging, "$key.zip")
                buildUploadZip(upload) { writer ->
                    writer.entry("release.json", metadataJson(channel, identity, UploadTarget(
                        clientType = "desktop", platform = platform, arch = arch,
                        build = identity.desktopRevision.toLong(),
                        notes = noteText, minShellAbi = minShellAbi,
                        shellDigest = identity.buildIdentity, bundleFilename = "payload.zip",
                        installers = installers,
                    )))
                    // payload.zip 的文件体按相对路径平铺（payload.properties 不进 payload/）。
                    ZipFile(payloadZip).use { zip ->
                        zip.entries().asSequence().filterNot { it.isDirectory }.forEach { e ->
                            if (e.name == "payload.properties") return@forEach
                            zip.getInputStream(e).use { writer.entry("payload/${e.name}", it) }
                        }
                    }
                    writer.entry("bundle/payload.zip", payloadZip)
                    installers.forEach { (filename, _) ->
                        writer.entry("installers/$filename", File(desktopDir, filename))
                    }
                }
                uploaded += upload(upload, "$key")
            }

            // ── Android ──
            val apk = ReleaseBundle.assets(bundle).single { it.extension == "apk" }
            val androidUpload = File(staging, "android.zip")
            buildUploadZip(androidUpload) { writer ->
                writer.entry("release.json", metadataJson(channel, identity, UploadTarget(
                    clientType = "android", platform = "android", arch = "any",
                    build = identity.version.buildNumber + 1L,
                    notes = noteText,
                    installers = listOf(apk.name to "Android 安装包"),
                )))
                writer.entry("installers/${apk.name}", apk)
            }
            uploaded += upload(androidUpload, "android")

            // ── 无头 / CLI ──
            val headless = File(bundle, "assets/${HeadlessDistribution.archiveName(identity.buildIdentity)}")
            val headlessUpload = File(staging, "headless.zip")
            buildUploadZip(headlessUpload) { writer ->
                writer.entry("release.json", metadataJson(channel, identity, UploadTarget(
                    clientType = "headless", platform = "any", arch = "any",
                    build = identity.version.buildNumber.toLong(),
                    notes = noteText, bundleFilename = headless.name,
                )))
                writer.entry("bundle/${headless.name}", headless)
            }
            uploaded += upload(headlessUpload, "headless")

            return PublicationResult(uploaded)
        } finally {
            staging.deleteRecursively()
        }
    }

    private inline fun buildUploadZip(target: File, block: (UploadEntryWriter) -> Unit) {
        val writer = UploadEntryWriter(target)
        try {
            block(writer)
        } finally {
            writer.close()
        }
    }

    private class UploadEntryWriter(target: File) : AutoCloseable {
        private val zip = ZipOutputStream(target.outputStream().buffered())
        fun entry(name: String, bytes: ByteArray) = bytes.inputStream().use { entry(name, it) }
        fun entry(name: String, file: File) = file.inputStream().buffered().use { entry(name, it) }
        fun entry(name: String, input: InputStream) {
            zip.putNextEntry(java.util.zip.ZipEntry(name).apply { time = 0 })
            input.copyTo(zip)
            zip.closeEntry()
        }

        override fun close() = zip.close()
    }

    private data class UploadTarget(
        val clientType: String,
        val platform: String,
        val arch: String,
        val build: Long,
        val notes: String? = null,
        val minShellAbi: Int? = null,
        val shellDigest: String? = null,
        val bundleFilename: String? = null,
        val installers: List<Pair<String, String>> = emptyList(),
    )

    /** buildSrc 不启用序列化编译插件：release.json 用 JsonObject 显式构建。 */
    private fun metadataJson(channel: String, identity: BundleIdentity, target: UploadTarget): ByteArray {
        val root = kotlinx.serialization.json.buildJsonObject {
            put("clientType", target.clientType)
            put("platform", target.platform)
            put("arch", target.arch)
            put("version", identity.version.name)
            put("buildIdentity", identity.buildIdentity)
            put("build", target.build)
            put("channel", channel)
            target.notes?.let { put("notes", it) }
            target.minShellAbi?.let { put("minShellAbi", it) }
            target.shellDigest?.let { put("shellDigest", it) }
            target.bundleFilename?.let { put("bundleFilename", it) }
            put("installers", kotlinx.serialization.json.buildJsonArray {
                target.installers.forEach { (filename, label) ->
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("filename", filename)
                        put("label", label)
                    })
                }
            })
            put("activate", true)
        }
        return kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(), root,
        ).toByteArray()
    }

    private fun payloadMinShellAbi(payloadZip: File): Int {
        ZipFile(payloadZip).use { zip ->
            val entry = zip.getEntry("payload.properties") ?: error("payload.zip lacks payload.properties")
            val props = java.util.Properties().apply { zip.getInputStream(entry).reader().use(::load) }
            return props.getProperty("minShellAbi")?.toIntOrNull()
                ?: error("payload.zip lacks minShellAbi")
        }
    }

    private fun installerLabel(target: DesktopTarget, filename: String): String = when (target) {
        DesktopTarget.MACOS_AARCH64 -> "macOS（Apple 芯片）"
        DesktopTarget.MACOS_AMD64 -> "macOS（Intel 芯片）"
        DesktopTarget.WINDOWS_AMD64 -> if (filename.endsWith("-setup.exe")) "Windows 安装器（64 位）" else "Windows 便携版"
        DesktopTarget.LINUX_AMD64 -> if (filename.endsWith(".deb")) "Linux 安装包（deb）" else "Linux 便携版（tar.gz）"
    }

    private fun upload(uploadZip: File, label: String): String {
        val boundary = "teamtalk-${UUID.randomUUID()}"
        // Keep large desktop runtimes on disk throughout ZIP assembly and HTTP upload.
        val prefix = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"release\"; filename=\"release.zip\"\r\n" +
            "Content-Type: application/zip\r\n\r\n"
        val body = HttpRequest.BodyPublishers.concat(
            HttpRequest.BodyPublishers.ofString(prefix),
            HttpRequest.BodyPublishers.ofFile(uploadZip.toPath()),
            HttpRequest.BodyPublishers.ofString("\r\n--$boundary--\r\n"),
        )
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${serverBaseUrl.trimEnd('/')}/api/v1/client/releases"))
            .header("X-Publish-Token", token)
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .timeout(Duration.ofMinutes(30))
            .POST(body)
            .build()
        val response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build().send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) {
            "client release upload ($label) failed: HTTP ${response.statusCode()} ${response.body().take(500)}"
        }
        return label
    }

}
