package com.virjar.tk.server.infra.media

import com.virjar.tk.protocol.body.AttachmentPolicy
import com.virjar.tk.server.env.Environment
import com.virjar.tk.server.infra.storage.ManagedTempFiles
import com.virjar.tk.server.infra.storage.STAGING_TEMP_SUFFIX
import com.virjar.tk.server.infra.storage.THUMBNAIL_RESULT_TEMP_PREFIX
import com.virjar.tk.server.infra.storage.THUMBNAIL_TEMP_PREFIX
import com.virjar.tk.server.infra.storage.THUMBNAIL_TEMP_SUFFIX
import com.virjar.tk.server.infra.storage.UPLOAD_STAGING_TEMP_PREFIX
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * 围绕 ImageIO 与 JavaCV/JNI 缩略图工作的可杀死进程边界。
 *
 * 每个获准的请求恰好拥有一个辅助 JVM 与两个预先创建的受管临时文件。
 * 父进程从不解析辅助进程 stdout，也从不信任子进程返回的输出路径。
 * 饱和、超时、非零退出或无效输出都是一次可选的缩略图 miss；受管临时文件
 * 回收失败仍保持 fail-closed，因为缺失已无法被证明。
 */
class ThumbnailService internal constructor(
    private val tempDirectory: File,
    maxConcurrentHelpers: Int,
    private val helperTimeoutMillis: Long,
    private val terminationGraceMillis: Long,
    private val retireTempFile: (File) -> Unit,
    private val processLauncher: ThumbnailHelperProcessLauncher,
) {
    constructor(
        tempDirectory: File = Environment.fileStoreTmpDir,
        maxConcurrentHelpers: Int = DEFAULT_MAX_CONCURRENT_HELPERS,
        helperTimeoutMillis: Long = DEFAULT_HELPER_TIMEOUT_MILLIS,
        terminationGraceMillis: Long = DEFAULT_TERMINATION_GRACE_MILLIS,
        retireTempFile: (File) -> Unit = { file ->
            ManagedTempFiles.retire(tempDirectory, file)
        },
    ) : this(
        tempDirectory = tempDirectory,
        maxConcurrentHelpers = maxConcurrentHelpers,
        helperTimeoutMillis = helperTimeoutMillis,
        terminationGraceMillis = terminationGraceMillis,
        retireTempFile = retireTempFile,
        processLauncher = DefaultThumbnailHelperProcessLauncher(),
    )

    init {
        require(maxConcurrentHelpers in 1..MAX_CONCURRENT_HELPERS_HARD_LIMIT) {
            "maxConcurrentHelpers must be within the hard process limit"
        }
        require(helperTimeoutMillis in 1..MAX_HELPER_TIMEOUT_MILLIS) {
            "helperTimeoutMillis must be within the hard timeout limit"
        }
        require(terminationGraceMillis in 1..MAX_TERMINATION_GRACE_MILLIS) {
            "terminationGraceMillis must be within the termination limit"
        }
    }

    private val logger = LoggerFactory.getLogger(ThumbnailService::class.java)
    private val admission = Semaphore(maxConcurrentHelpers, true)

    /** 生成的缩略图所有权转移给调用方；调用方必须回收该文件。 */
    data class MediaInfo(
        val thumbFile: File?,
        val width: Int,
        val height: Int,
        val durationSec: Int?,
    )

    fun processImage(source: File): MediaInfo? = process(ThumbnailMediaKind.IMAGE, source)

    fun processVideo(source: File): MediaInfo? = process(ThumbnailMediaKind.VIDEO, source)

    private fun process(kind: ThumbnailMediaKind, source: File): MediaInfo? {
        if (!admission.tryAcquire()) {
            logger.debug("thumbnail helper saturated; optional thumbnail omitted")
            return null
        }
        var releasePermit = true
        return try {
            processAdmitted(kind, source) {
                // 若连强制终止都无法证明子进程已死，保留许可
                // 就维持了潜在存活辅助进程数量的配置上界。
                releasePermit = false
            }
        } finally {
            if (releasePermit) admission.release()
        }
    }

    private fun processAdmitted(
        kind: ThumbnailMediaKind,
        source: File,
        onUnreapedProcess: () -> Unit,
    ): MediaInfo? {
        val root = ManagedTempFiles.ensureDirectory(tempDirectory).toPath().toAbsolutePath().normalize()
        val sourcePath = source.toPath().toAbsolutePath().normalize()
        if (!isManagedUploadSource(root, sourcePath)) {
            logger.warn("thumbnail source rejected by managed-input boundary")
            return null
        }

        var outputFile: File? = null
        var resultFile: File? = null
        var outputHandedOff = false
        var terminalFailure: Throwable? = null
        try {
            outputFile = ManagedTempFiles.create(
                root.toFile(),
                THUMBNAIL_TEMP_PREFIX,
                THUMBNAIL_TEMP_SUFFIX,
            )
            resultFile = ManagedTempFiles.create(
                root.toFile(),
                THUMBNAIL_RESULT_TEMP_PREFIX,
                STAGING_TEMP_SUFFIX,
            )
            val request = ThumbnailHelperRequest(
                kind = kind,
                tempRoot = root,
                source = sourcePath,
                output = outputFile.toPath().toAbsolutePath().normalize(),
                result = resultFile.toPath().toAbsolutePath().normalize(),
            )
            val exitCode = runHelper(request, onUnreapedProcess) ?: return null
            if (exitCode != 0) {
                logger.warn("thumbnail helper exited unsuccessfully; optional thumbnail omitted")
                return null
            }

            val response = ThumbnailHelperProtocol.read(resultFile.toPath()) ?: run {
                logger.warn("thumbnail helper returned an invalid result; optional thumbnail omitted")
                return null
            }
            if (response.status == ThumbnailHelperStatus.NONE) return null
            if (kind == ThumbnailMediaKind.IMAGE && response.durationSec != null) {
                logger.warn("thumbnail helper returned invalid image metadata")
                return null
            }
            if (response.status == ThumbnailHelperStatus.METADATA) {
                return MediaInfo(
                    thumbFile = null,
                    width = response.width,
                    height = response.height,
                    durationSec = response.durationSec,
                )
            }
            if (!validateThumbnailOutput(outputFile.toPath(), response)) {
                logger.warn("thumbnail helper output failed validation; optional thumbnail omitted")
                return null
            }
            outputHandedOff = true
            return MediaInfo(
                thumbFile = outputFile,
                width = response.width,
                height = response.height,
                durationSec = response.durationSec,
            )
        } catch (interrupted: InterruptedException) {
            terminalFailure = interrupted
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (failure: Throwable) {
            if (
                failure is Error ||
                failure is com.virjar.tk.server.infra.storage.ManagedTempResidueException
            ) {
                terminalFailure = failure
                throw failure
            }
            logger.warn("thumbnail helper invocation failed: {}", failure::class.simpleName)
            return null
        } finally {
            val files = buildList {
                resultFile?.let(::add)
                if (!outputHandedOff) outputFile?.let(::add)
            }
            try {
                retireOwnedTemporaryFiles(files)
            } catch (cleanupFailure: Throwable) {
                val first = terminalFailure
                if (first == null) throw cleanupFailure
                if (first !== cleanupFailure) first.addSuppressed(cleanupFailure)
            }
        }
    }

    private fun runHelper(
        request: ThumbnailHelperRequest,
        onUnreapedProcess: () -> Unit,
    ): Int? {
        val process = processLauncher.start(request)
        try {
            if (process.waitFor(helperTimeoutMillis, TimeUnit.MILLISECONDS)) {
                return process.exitValue()
            }
            val reaped = terminateAndReap(process)
            if (!reaped) onUnreapedProcess()
            logger.warn("thumbnail helper timed out and was terminated")
            return null
        } catch (interrupted: InterruptedException) {
            val reaped = terminateAndReap(process)
            if (!reaped) onUnreapedProcess()
            throw interrupted
        } catch (failure: Throwable) {
            if (process.isAlive) {
                val reaped = terminateAndReap(process)
                if (!reaped) onUnreapedProcess()
            }
            throw failure
        }
    }

    private fun terminateAndReap(process: Process): Boolean {
        var interruptedWhileReaping = Thread.interrupted()
        fun waitBounded(): Boolean = try {
            process.waitFor(terminationGraceMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            interruptedWhileReaping = true
            false
        }

        return try {
            try {
                process.destroy()
            } catch (_: Exception) {
                // 继续走强制终止路径。
            }
            if (waitBounded() || !process.isAlive) return true
            try {
                process.destroyForcibly()
            } catch (_: Exception) {
                // 保留的信号量许可防止替代进程的数量放大。
            }
            waitBounded() || !process.isAlive
        } finally {
            if (interruptedWhileReaping) Thread.currentThread().interrupt()
        }
    }

    private fun retireOwnedTemporaryFiles(files: List<File>) {
        var failure: Throwable? = null
        files.distinctBy { it.toPath().toAbsolutePath().normalize() }.forEach { file ->
            try {
                retireTempFile(file)
            } catch (retirementFailure: Throwable) {
                val first = failure
                if (first == null) failure = retirementFailure
                else if (first !== retirementFailure) first.addSuppressed(retirementFailure)
            }
        }
        failure?.let { throw it }
    }

    private companion object {
        const val DEFAULT_MAX_CONCURRENT_HELPERS = 2
        const val MAX_CONCURRENT_HELPERS_HARD_LIMIT = 4
        const val DEFAULT_HELPER_TIMEOUT_MILLIS = 15_000L
        const val MAX_HELPER_TIMEOUT_MILLIS = 60_000L
        const val DEFAULT_TERMINATION_GRACE_MILLIS = 250L
        const val MAX_TERMINATION_GRACE_MILLIS = 5_000L
    }
}

internal enum class ThumbnailMediaKind(val wireName: String) {
    IMAGE("image"),
    VIDEO("video");

    companion object {
        fun parse(value: String): ThumbnailMediaKind = entries.singleOrNull { it.wireName == value }
            ?: throw IllegalArgumentException("Unknown thumbnail media kind")
    }
}

internal data class ThumbnailHelperRequest(
    val kind: ThumbnailMediaKind,
    val tempRoot: Path,
    val source: Path,
    val output: Path,
    val result: Path,
)

internal fun interface ThumbnailHelperProcessLauncher {
    fun start(request: ThumbnailHelperRequest): Process
}

internal class DefaultThumbnailHelperProcessLauncher : ThumbnailHelperProcessLauncher {
    override fun start(request: ThumbnailHelperRequest): Process {
        val javaHome = System.getProperty("java.home")?.takeIf(String::isNotBlank)
            ?: error("java.home is unavailable")
        val executableName = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "java.exe"
        } else {
            "java"
        }
        val javaExecutable = File(File(javaHome, "bin"), executableName)
        check(javaExecutable.isFile) { "Thumbnail helper Java executable is unavailable" }
        val classpath = System.getProperty(THUMBNAIL_HELPER_CLASSPATH_PROPERTY)
            ?.takeIf(String::isNotBlank)
            ?: System.getProperty("java.class.path")?.takeIf(String::isNotBlank)
            ?: error("Thumbnail helper classpath is unavailable")
        val command = listOf(
            javaExecutable.absolutePath,
            "-Xms16m",
            "-Xmx256m",
            "-XX:MaxDirectMemorySize=128m",
            "-XX:ActiveProcessorCount=1",
            "-XX:+UseSerialGC",
            "-XX:+ExitOnOutOfMemoryError",
            "-Djava.awt.headless=true",
            "-Dlogback.configurationFile=thumbnail-helper-logback.xml",
            "-cp",
            classpath,
            ThumbnailHelperMain::class.java.name,
            THUMBNAIL_HELPER_REQUEST_MAGIC,
            request.kind.wireName,
            request.tempRoot.toString(),
            request.source.toString(),
            request.output.toString(),
            request.result.toString(),
        )
        return ProcessBuilder(command)
            .directory(request.tempRoot.toFile())
            // 辅助进程从不读 stdin。继承它可避免一条父进程拥有的管道，
            // 该管道可能在 spawn 之后关闭失败并丢失唯一的 Process 句柄。
            .redirectInput(ProcessBuilder.Redirect.INHERIT)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .apply {
                environment().clear()
                environment()["LANG"] = "C"
                environment()["LC_ALL"] = "C"
            }
            .start()
    }
}

/** 仅供 [DefaultThumbnailHelperProcessLauncher] 使用的入口点。 */
internal object ThumbnailHelperMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == THUMBNAIL_HELPER_ARGUMENT_COUNT) {
            "Invalid thumbnail helper argument count"
        }
        require(arguments[0] == THUMBNAIL_HELPER_REQUEST_MAGIC) {
            "Invalid thumbnail helper protocol"
        }
        val kind = ThumbnailMediaKind.parse(arguments[1])
        val root = strictAbsolutePath(arguments[2])
        val source = strictAbsolutePath(arguments[3])
        val output = strictAbsolutePath(arguments[4])
        val result = strictAbsolutePath(arguments[5])
        validateHelperPaths(root, source, output, result)

        var outputClaimed = false
        val generator = LocalThumbnailGenerator(
            tempDirectory = root.toFile(),
            retireTempFile = { file -> ManagedTempFiles.retire(root.toFile(), file) },
            thumbnailFileFactory = {
                check(!outputClaimed) { "Thumbnail output was requested more than once" }
                outputClaimed = true
                output.toFile()
            },
        )
        val mediaInfo = when (kind) {
            ThumbnailMediaKind.IMAGE -> generator.processImage(source.toFile())
            ThumbnailMediaKind.VIDEO -> generator.processVideo(source.toFile())
        }
        ThumbnailHelperProtocol.write(result, output, mediaInfo)
    }
}

private enum class ThumbnailHelperStatus(val code: Int) {
    NONE(0),
    METADATA(1),
    THUMBNAIL(2);

    companion object {
        fun fromCode(code: Int): ThumbnailHelperStatus? = entries.singleOrNull { it.code == code }
    }
}

private data class ThumbnailHelperResponse(
    val status: ThumbnailHelperStatus,
    val width: Int,
    val height: Int,
    val durationSec: Int?,
    val outputLength: Long,
    val outputSha256: ByteArray,
)

private object ThumbnailHelperProtocol {
    fun write(result: Path, output: Path, mediaInfo: ThumbnailService.MediaInfo?) {
        requireRegularFile(result, expectedEmpty = true)
        val status = when {
            mediaInfo == null -> ThumbnailHelperStatus.NONE
            mediaInfo.thumbFile == null -> ThumbnailHelperStatus.METADATA
            else -> ThumbnailHelperStatus.THUMBNAIL
        }
        val width = mediaInfo?.width ?: 0
        val height = mediaInfo?.height ?: 0
        val duration = mediaInfo?.durationSec
        if (status == ThumbnailHelperStatus.NONE) {
            require(width == 0 && height == 0 && duration == null)
        } else {
            requireValidMetadata(width, height, duration)
        }

        val length: Long
        val digest: ByteArray
        if (status == ThumbnailHelperStatus.THUMBNAIL) {
            val thumbnail = checkNotNull(mediaInfo?.thumbFile).toPath().toAbsolutePath().normalize()
            require(thumbnail == output) { "Helper thumbnail path did not match the owned output" }
            requireRegularFile(output, expectedEmpty = false)
            length = Files.size(output)
            require(length in 1..MAX_THUMBNAIL_OUTPUT_BYTES) { "Helper thumbnail exceeded output budget" }
            require(hasJpegEnvelope(output, length)) { "Helper thumbnail was not a complete JPEG" }
            digest = sha256(output)
        } else {
            length = 0L
            digest = ZERO_SHA256
        }

        DataOutputStream(
            BufferedOutputStream(
                Files.newOutputStream(result, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING),
            ),
        ).use { data ->
            data.writeInt(THUMBNAIL_RESULT_MAGIC)
            data.writeByte(THUMBNAIL_RESULT_VERSION)
            data.writeByte(status.code)
            data.writeInt(width)
            data.writeInt(height)
            data.writeInt(duration ?: NO_DURATION)
            data.writeLong(length)
            data.write(digest)
        }
        check(Files.size(result) == THUMBNAIL_RESULT_LENGTH_BYTES.toLong()) {
            "Thumbnail helper result length mismatch"
        }
    }

    fun read(result: Path): ThumbnailHelperResponse? {
        if (!isRegularFile(result) || Files.size(result) != THUMBNAIL_RESULT_LENGTH_BYTES.toLong()) return null
        val response = DataInputStream(BufferedInputStream(Files.newInputStream(result))).use { data ->
            if (data.readInt() != THUMBNAIL_RESULT_MAGIC) return null
            if (data.readUnsignedByte() != THUMBNAIL_RESULT_VERSION) return null
            val status = ThumbnailHelperStatus.fromCode(data.readUnsignedByte()) ?: return null
            val width = data.readInt()
            val height = data.readInt()
            val rawDuration = data.readInt()
            val duration = if (rawDuration == NO_DURATION) null else rawDuration
            val length = data.readLong()
            val digest = data.readNBytes(SHA256_BYTES)
            if (digest.size != SHA256_BYTES || data.read() != -1) return null
            ThumbnailHelperResponse(status, width, height, duration, length, digest)
        }
        return when (response.status) {
            ThumbnailHelperStatus.NONE -> response.takeIf {
                it.width == 0 && it.height == 0 && it.durationSec == null &&
                    it.outputLength == 0L && MessageDigest.isEqual(it.outputSha256, ZERO_SHA256)
            }
            ThumbnailHelperStatus.METADATA -> response.takeIf {
                isValidMetadata(it.width, it.height, it.durationSec) &&
                    it.outputLength == 0L && MessageDigest.isEqual(it.outputSha256, ZERO_SHA256)
            }
            ThumbnailHelperStatus.THUMBNAIL -> response.takeIf {
                isValidMetadata(it.width, it.height, it.durationSec) &&
                    it.outputLength in 1..MAX_THUMBNAIL_OUTPUT_BYTES
            }
        }
    }
}

private fun validateThumbnailOutput(output: Path, response: ThumbnailHelperResponse): Boolean {
    if (response.status != ThumbnailHelperStatus.THUMBNAIL || !isRegularFile(output)) return false
    val length = Files.size(output)
    if (length != response.outputLength || length !in 1..MAX_THUMBNAIL_OUTPUT_BYTES) return false
    if (!hasJpegEnvelope(output, length)) return false
    return MessageDigest.isEqual(sha256(output), response.outputSha256)
}

private fun isManagedUploadSource(root: Path, source: Path): Boolean {
    if (source.parent != root || source == root || !isRegularFile(source)) return false
    val name = source.fileName.toString()
    if (!name.startsWith(UPLOAD_STAGING_TEMP_PREFIX) || !name.endsWith(STAGING_TEMP_SUFFIX)) return false
    if (name.length <= UPLOAD_STAGING_TEMP_PREFIX.length + STAGING_TEMP_SUFFIX.length) return false
    return Files.size(source) in 1..AttachmentPolicy.MAX_UPLOAD_BYTES
}

private fun validateHelperPaths(root: Path, source: Path, output: Path, result: Path) {
    require(!Files.isSymbolicLink(root) && Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
        "Invalid thumbnail helper root"
    }
    require(isManagedUploadSource(root, source)) { "Invalid thumbnail helper source" }
    require(output.parent == root && output != root) { "Invalid thumbnail helper output" }
    require(result.parent == root && result != root) { "Invalid thumbnail helper result" }
    require(output != result && output != source && result != source) { "Thumbnail helper paths overlap" }
    requireManagedName(output, THUMBNAIL_TEMP_PREFIX, THUMBNAIL_TEMP_SUFFIX)
    requireManagedName(result, THUMBNAIL_RESULT_TEMP_PREFIX, STAGING_TEMP_SUFFIX)
    requireRegularFile(output, expectedEmpty = true)
    requireRegularFile(result, expectedEmpty = true)
}

private fun strictAbsolutePath(value: String): Path {
    val raw = Path.of(value)
    require(raw.isAbsolute) { "Thumbnail helper path must be absolute" }
    val normalized = raw.normalize()
    require(normalized.toString() == value) { "Thumbnail helper path must be normalized" }
    return normalized
}

private fun requireManagedName(path: Path, prefix: String, suffix: String) {
    val name = path.fileName.toString()
    require(name.startsWith(prefix) && name.endsWith(suffix)) { "Invalid managed thumbnail filename" }
    require(name.length > prefix.length + suffix.length) { "Invalid managed thumbnail filename" }
}

private fun requireRegularFile(path: Path, expectedEmpty: Boolean) {
    require(isRegularFile(path)) { "Thumbnail helper file must be a regular file" }
    if (expectedEmpty) require(Files.size(path) == 0L) { "Thumbnail helper file must be empty" }
}

private fun isRegularFile(path: Path): Boolean =
    !Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)

private fun requireValidMetadata(width: Int, height: Int, durationSec: Int?) {
    require(isValidMetadata(width, height, durationSec)) { "Invalid thumbnail helper metadata" }
}

private fun isValidMetadata(width: Int, height: Int, durationSec: Int?): Boolean =
    width in 1..MAX_MEDIA_DIMENSION &&
        height in 1..MAX_MEDIA_DIMENSION &&
        width.toLong() * height.toLong() <= MAX_MEDIA_PIXELS &&
        (durationSec == null || durationSec in 1..MAX_MEDIA_DURATION_SECONDS)

private fun hasJpegEnvelope(path: Path, length: Long): Boolean {
    if (length < MIN_JPEG_BYTES) return false
    return RandomAccessFile(path.toFile(), "r").use { file ->
        val start = file.readUnsignedShort()
        file.seek(length - JPEG_MARKER_BYTES)
        val end = file.readUnsignedShort()
        start == JPEG_START_OF_IMAGE && end == JPEG_END_OF_IMAGE
    }
}

private fun sha256(path: Path): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { raw ->
        val input = BufferedInputStream(raw)
        val buffer = ByteArray(DIGEST_BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest()
}

internal const val THUMBNAIL_HELPER_CLASSPATH_PROPERTY = "teamtalk.thumbnail.helper.classpath"
private const val THUMBNAIL_HELPER_REQUEST_MAGIC = "teamtalk-thumbnail-v1"
private const val THUMBNAIL_HELPER_ARGUMENT_COUNT = 6
private const val THUMBNAIL_RESULT_MAGIC = 0x54544831
private const val THUMBNAIL_RESULT_VERSION = 1
private const val THUMBNAIL_RESULT_LENGTH_BYTES = 58
private const val SHA256_BYTES = 32
private const val NO_DURATION = -1
private const val MAX_THUMBNAIL_OUTPUT_BYTES = 2L * 1024 * 1024
private const val MAX_MEDIA_DIMENSION = 16_384
private const val MAX_MEDIA_PIXELS = 64L * 1024 * 1024
private const val MAX_MEDIA_DURATION_SECONDS = 7 * 24 * 60 * 60
private const val MIN_JPEG_BYTES = 4L
private const val JPEG_MARKER_BYTES = 2L
private const val JPEG_START_OF_IMAGE = 0xFFD8
private const val JPEG_END_OF_IMAGE = 0xFFD9
private const val DIGEST_BUFFER_BYTES = 16 * 1024
private val ZERO_SHA256 = ByteArray(SHA256_BYTES)
