package com.virjar.tk.shared.update

import com.virjar.tk.protocol.http.ClientReleaseInfo
import com.virjar.tk.protocol.http.ClientReleaseManifest
import com.virjar.tk.protocol.http.ClientUpdateCheckResponse
import com.virjar.tk.protocol.http.ClientUpdateContracts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.net.URLEncoder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class DesktopUpdateException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 桌面负载更新器：check → 本地文件清单 diff → 只下载变化文件 → 暂存校验 → 原子切换指针。
 *
 * 粒度是文件级：jar 未变化零下载；任意旧版本可直达最新（无需增量链）。
 * 壳不满足 minShellAbi 时调用方应引导去下载页全量升级（本类不处理安装器执行）。
 */
class DesktopUpdater(
    private val context: DesktopUpdateContext,
    private val httpClient: UpdateHttpClient = JdkUpdateHttpClient,
) {

    sealed class CheckOutcome {
        data object UpToDate : CheckOutcome()
        data class UpdateAvailable(val release: ClientReleaseInfo) : CheckOutcome()
        data class ShellUpdateRequired(val release: ClientReleaseInfo) : CheckOutcome()
        data object ChannelDisabled : CheckOutcome()
    }

    suspend fun check(serverBaseUrl: String, channel: String): CheckOutcome {
        val url = buildString {
            append(serverBaseUrl.trimEnd('/'))
            append("/api/v1/client/updates/check?client=").append(ClientUpdateContracts.CLIENT_DESKTOP)
            append("&platform=").append(DesktopPlatform.platform())
            append("&arch=").append(DesktopPlatform.arch())
            append("&channel=").append(channel)
            append("&version=").append(URLEncoder.encode(context.version, "UTF-8"))
            context.descriptor.buildIdentity?.let { append("&buildIdentity=").append(URLEncoder.encode(it, "UTF-8")) }
            append("&build=").append(context.build)
            append("&shellAbi=").append(context.shellAbi)
        }
        val body = try {
            httpClient.get(url)
        } catch (failure: IOException) {
            throw DesktopUpdateException("检查更新失败：无法访问更新服务", failure)
        }
        val decision = try {
            ClientUpdateContracts.json.decodeFromString(ClientUpdateCheckResponse.serializer(), body.decodeToString())
        } catch (failure: Exception) {
            throw DesktopUpdateException("检查更新失败：响应格式异常", failure)
        }
        return when (decision.status) {
            ClientUpdateContracts.STATUS_UP_TO_DATE -> CheckOutcome.UpToDate
            ClientUpdateContracts.STATUS_CHANNEL_DISABLED -> CheckOutcome.ChannelDisabled
            ClientUpdateContracts.STATUS_SHELL_UPDATE_REQUIRED ->
                CheckOutcome.ShellUpdateRequired(requireNotNull(decision.release))
            ClientUpdateContracts.STATUS_UPDATE_AVAILABLE -> CheckOutcome.UpdateAvailable(requireNotNull(decision.release))
            else -> throw DesktopUpdateException("未知更新状态：${decision.status}")
        }
    }

    data class DownloadProgress(val completedBytes: Long, val totalBytes: Long)

    data class ApplyResult(val version: String, val build: Long, val downloadedBytes: Long)

    /**
     * 下载并落位目标发布。完成后需重启进程才生效（当前进程仍运行旧负载）。
     * [progress] 在 IO 线程回调，UI 层自行切主线程。
     */
    suspend fun downloadAndApply(
        serverBaseUrl: String,
        release: ClientReleaseInfo,
        progress: (DownloadProgress) -> Unit = {},
    ): ApplyResult = withContext(Dispatchers.IO) {
        val manifestUrl = release.manifestUrl
            ?: throw DesktopUpdateException("该发布没有负载清单，无法增量更新")
        val manifest = try {
            ClientUpdateContracts.json.decodeFromString(
                ClientReleaseManifest.serializer(),
                httpClient.get(serverBaseUrl.trimEnd('/') + manifestUrl).decodeToString(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            throw DesktopUpdateException("下载更新清单失败", failure)
        } catch (failure: Exception) {
            throw DesktopUpdateException("更新清单格式异常", failure)
        }

        validateManifest(release, manifest)
        val descriptor = PayloadLayout.PayloadDescriptor(
            version = manifest.version,
            build = manifest.build,
            minShellAbi = manifest.minShellAbi,
            files = manifest.files.map { PayloadLayout.PayloadFile(it.path, it.sha256, it.size) },
            buildIdentity = manifest.buildIdentity,
            channel = release.channel,
        )
        val local = context.descriptor.files.associateBy { it.path }
        val reusable = manifest.files.filter { file ->
            val source = File(context.currentDir, file.path)
            local[file.path]?.sha256 == file.sha256 && source.isFile &&
                source.length() == file.size && sha256Hex(source) == file.sha256
        }.map { it.path }.toSet()
        val totalBytes = manifest.files.filterNot { it.path in reusable }.sumOf { it.size }
        var doneBytes = 0L
        var lastProgressAt = System.nanoTime() - PROGRESS_INTERVAL_NANOS
        progress(DownloadProgress(0, totalBytes))
        context.versionsRoot.mkdirs()
        FileChannel.open(File(context.versionsRoot, ".update.lock").toPath(), CREATE, WRITE).use { channel ->
            val lock = channel.tryLock() ?: throw DesktopUpdateException("另一个进程正在更新，请稍后重试")
            lock.use {
                val staging = Files.createTempDirectory(context.versionsRoot.toPath(), PayloadLayout.STAGING_PREFIX).toFile()
                try {
                    for (file in manifest.files) {
                        currentCoroutineContext().ensureActive()
                        val target = File(staging, file.path)
                        target.parentFile.mkdirs()
                        if (file.path in reusable) {
                            File(context.currentDir, file.path).copyTo(target)
                        } else {
                            val jobContext = currentCoroutineContext()
                            httpClient.download(serverBaseUrl.trimEnd('/') + file.url, target, file.size) { fileBytes ->
                                jobContext.ensureActive()
                                val now = System.nanoTime()
                                if (now - lastProgressAt >= PROGRESS_INTERVAL_NANOS) {
                                    progress(DownloadProgress(doneBytes + fileBytes, totalBytes))
                                    lastProgressAt = now
                                }
                            }
                            doneBytes += file.size
                            progress(DownloadProgress(doneBytes, totalBytes))
                            lastProgressAt = System.nanoTime()
                        }
                    }
                    verifyStaging(staging, manifest)
                    currentCoroutineContext().ensureActive()
                    PayloadLayout.writeDescriptor(staging, descriptor)
                    var directory = PayloadLayout.directoryName(descriptor)
                    var finalDir = File(context.versionsRoot, directory)
                    if (finalDir.exists()) {
                        // 已发布版本只验证并复用，绝不删除当前进程或回滚版本正在使用的文件。
                        if (runCatching { verifyStaging(finalDir, manifest) }.isFailure) {
                            // 损坏目录可能仍被旧进程持有；恢复副本使用新路径，不原地覆盖。
                            directory += "-" + java.util.UUID.randomUUID()
                            finalDir = File(context.versionsRoot, directory)
                        }
                    }
                    if (!finalDir.exists()) Files.move(staging.toPath(), finalDir.toPath(), ATOMIC_MOVE)
                    val seedId = PayloadLayout.readCurrentPointer(context.versionsRoot)?.seedId
                    PayloadLayout.writeCurrentPointer(
                        context.versionsRoot,
                        PayloadLayout.CurrentPointer(manifest.version, manifest.build, directory, seedId),
                    )
                    ApplyResult(manifest.version, manifest.build, totalBytes)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (failure is DesktopUpdateException) throw failure
                    throw DesktopUpdateException("应用更新失败：${failure.message}", failure)
                } finally {
                    staging.deleteRecursively()
                }
            }
        }
    }

    private fun validateManifest(release: ClientReleaseInfo, manifest: ClientReleaseManifest) {
        require(manifest.releaseId == release.id && manifest.version == release.version && manifest.build == release.build &&
            manifest.clientType == release.clientType && manifest.platform == release.platform && manifest.arch == release.arch &&
            manifest.buildIdentity == release.buildIdentity) { "发布清单与检查结果不一致，请重新检查更新" }
        require(manifest.clientType == ClientUpdateContracts.CLIENT_DESKTOP) { "发布不是桌面负载" }
        require((manifest.minShellAbi ?: 0) <= context.shellAbi) { "安装壳过旧，请下载安装包" }
        require(manifest.files.isNotEmpty() && manifest.files.size <= 5000) { "负载文件清单为空或过大" }
        require(manifest.files.map { it.path }.distinct().size == manifest.files.size) { "负载路径重复" }
        manifest.files.forEach { file ->
            require(file.path.isNotBlank() && file.path != PayloadLayout.DESCRIPTOR_FILE &&
                !file.path.startsWith('/') && !file.path.contains('\\') && !file.path.contains(':') &&
                file.path.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
                !file.path.contains('\u0000')) { "无效负载路径：${file.path}" }
            require(file.size >= 0 && Regex("[0-9a-f]{64}").matches(file.sha256)) { "无效负载摘要或大小" }
            require(file.url == "/api/v1/client/files/${file.sha256}") { "无效制品地址" }
        }
    }

    private fun verifyStaging(staging: File, manifest: ClientReleaseManifest) {
        for (file in manifest.files) {
            val target = File(staging, file.path)
            require(target.isFile && target.length() == file.size && sha256Hex(target) == file.sha256) {
                "负载文件大小或摘要不符：${file.path}"
            }
        }
    }

    /** 拉起新进程并退出当前进程；由调用方在合适的生命周期时机调用。 */
    fun relaunch(): Boolean {
        val launcher = context.launcherCommand ?: return false
        return try {
            ProcessBuilder(launcher, "--teamtalk-restart-parent=${ProcessHandle.current().pid()}").start()
            true
        } catch (failure: IOException) {
            false
        }
    }

    companion object {
        private const val PROGRESS_INTERVAL_NANOS = 100_000_000L

        internal fun sha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
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
    }
}
