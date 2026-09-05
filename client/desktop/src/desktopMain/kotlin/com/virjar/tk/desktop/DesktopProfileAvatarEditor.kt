package com.virjar.tk.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.virjar.tk.desktop.media.DesktopSessionResources
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.ProfilePatchValue
import com.virjar.tk.protocol.model.User
import com.virjar.tk.app.ui.screen.EditProfileScreen
import com.virjar.tk.app.ui.screen.PROFILE_AVATAR_MAX_DECODED_PIXELS
import com.virjar.tk.app.ui.screen.PROFILE_AVATAR_MAX_SOURCE_BYTES
import com.virjar.tk.app.ui.screen.PROFILE_AVATAR_MAX_SOURCE_PIXELS
import com.virjar.tk.app.ui.screen.PROFILE_AVATAR_TEMP_SCAN_LIMIT
import com.virjar.tk.app.ui.screen.ProfileAvatarEditState
import com.virjar.tk.app.ui.screen.ProfileAvatarTempEntry
import com.virjar.tk.app.ui.screen.profileAvatarCropPlan
import com.virjar.tk.app.ui.screen.profileAvatarDecodeSampleSize
import com.virjar.tk.app.ui.screen.profileAvatarTempCleanupCandidates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import org.jetbrains.skia.impl.use
import org.jetbrains.skia.makeFromFileName
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

private const val PROFILE_AVATAR_CONTENT_TYPE = "image/png"
private const val MAX_PROFILE_AVATAR_OUTPUT_BYTES = 2L * 1024L * 1024L

@Composable
internal fun DesktopEditProfileHost(
    currentUser: User?,
    resources: DesktopSessionResources,
    onSave: suspend (String, String?, ProfilePatchValue<Attachment?>) -> Boolean,
    onBack: (() -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    val selectionOwner = remember { DesktopProfileAvatarSelectionOwner() }
    var selection by remember { mutableStateOf<PreparedDesktopProfileAvatar?>(null) }
    var removeRequested by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableStateOf<Float?>(null) }
    var avatarError by remember { mutableStateOf<String?>(null) }
    var uploadGeneration by remember { mutableStateOf(0L) }
    val routeOwner = remember(scope, selectionOwner) {
        DesktopProfileAvatarRouteOwner(scope, selectionOwner::close)
    }
    DisposableEffect(routeOwner) {
        onDispose(routeOwner::close)
    }

    fun chooseAvatar() {
        avatarError = null
        val source = try {
            resources.ensureOpen()
            DesktopFilePicker.chooseImage()
        } catch (failure: Exception) {
            avatarError = desktopAvatarFailureMessage(failure, processing = true)
            null
        } ?: return
        val protectedFile = selection?.file

        routeOwner.launch {
            processing = true
            try {
                val prepared = withContext(Dispatchers.IO) {
                    resources.ensureOpen()
                    val candidate = DesktopProfileAvatarProcessor.prepare(
                        source = source,
                        outputDirectory = File(resources.mediaDirectory, "outgoing-avatar"),
                        protectedFile = protectedFile,
                    )
                    try {
                        resources.ensureOpen()
                        if (selectionOwner.replace(candidate)) candidate else null
                    } catch (failure: Throwable) {
                        candidate.delete()
                        throw failure
                    }
                } ?: return@launch
                selection = prepared
                removeRequested = false
                avatarError = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                avatarError = desktopAvatarFailureMessage(failure, processing = true)
            } finally {
                processing = false
            }
        }
    }

    fun removeAvatar() {
        selectionOwner.clear()
        selection = null
        removeRequested = true
        uploadProgress = null
        avatarError = null
    }

    EditProfileScreen(
        currentUser = currentUser,
        avatarEditState = ProfileAvatarEditState(
            preview = selection?.preview,
            hasReplacement = selection != null,
            removeRequested = removeRequested,
            processing = processing,
            uploadProgress = uploadProgress,
            errorMessage = avatarError,
        ),
        onChooseAvatar = ::chooseAvatar,
        onRemoveAvatar = ::removeAvatar,
        onSave = save@{ name, phone ->
            routeOwner.runOwned {
                val avatarPatch = when {
                    removeRequested -> ProfilePatchValue.Set(null)
                    selection != null -> {
                        val currentSelection = checkNotNull(selection)
                        val attachment = currentSelection.uploadedAttachment ?: try {
                            val generation = ++uploadGeneration
                            uploadProgress = 0f
                            resources.fileTransfer.uploadWithMeta(
                                file = currentSelection.file,
                                contentType = PROFILE_AVATAR_CONTENT_TYPE,
                            ) { progress ->
                                routeOwner.launch {
                                    if (uploadGeneration == generation) {
                                        uploadProgress = progress.coerceIn(uploadProgress ?: 0f, 1f)
                                    }
                                }
                            }.file.also { uploaded ->
                                resources.ensureOpen()
                                currentSelection.uploadedAttachment = uploaded
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            avatarError = desktopAvatarFailureMessage(failure, processing = false)
                            return@runOwned false
                        } finally {
                            uploadGeneration++
                            uploadProgress = null
                        }
                        ProfilePatchValue.Set(attachment)
                    }
                    else -> ProfilePatchValue.Unchanged
                }
                resources.ensureOpen()
                onSave(name, phone, avatarPatch)
            }
        },
        onBack = onBack,
    )
}

internal data class PreparedDesktopProfileAvatar(
    val file: File,
    val preview: ImageBitmap,
    var uploadedAttachment: Attachment? = null,
) {
    fun delete() {
        if (file.exists()) file.delete()
    }
}

internal class DesktopProfileAvatarSelectionOwner : AutoCloseable {
    private var current: PreparedDesktopProfileAvatar? = null
    private var closed = false

    @Synchronized
    fun replace(next: PreparedDesktopProfileAvatar): Boolean {
        if (closed) {
            next.delete()
            return false
        }
        if (current !== next) current?.delete()
        current = next
        return true
    }

    @Synchronized
    fun clear() {
        current?.delete()
        current = null
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        current?.delete()
        current = null
    }
}

/** Route 归属的上传/准备任务会在所选临时文件被删除之前全部排空。 */
internal class DesktopProfileAvatarRouteOwner(
    parentScope: CoroutineScope,
    disposeAfterDrain: () -> Unit,
) : AutoCloseable {
    private val job = SupervisorJob(
        checkNotNull(parentScope.coroutineContext[Job]) { "avatar route scope requires a Job" },
    )
    private val scope = CoroutineScope(parentScope.coroutineContext + job)

    init {
        job.invokeOnCompletion { disposeAfterDrain() }
    }

    fun launch(action: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = action)

    suspend fun <T> runOwned(action: suspend CoroutineScope.() -> T): T {
        val work = scope.async(start = CoroutineStart.UNDISPATCHED) {
            coroutineContext.ensureActive()
            action()
        }
        return try {
            work.await()
        } catch (cancelled: CancellationException) {
            work.cancel(cancelled)
            throw cancelled
        }
    }

    override fun close() {
        job.cancel(CancellationException("profile avatar route disposed"))
    }
}

internal object DesktopProfileAvatarProcessor {
    fun prepare(
        source: File,
        outputDirectory: File,
        protectedFile: File? = null,
    ): PreparedDesktopProfileAvatar {
        require(source.isFile) { "所选图片不存在" }
        require(source.length() in 1L..PROFILE_AVATAR_MAX_SOURCE_BYTES) {
            "头像源文件不能超过 ${PROFILE_AVATAR_MAX_SOURCE_BYTES / (1024 * 1024)} MB"
        }
        require(outputDirectory.isDirectory || outputDirectory.mkdirs()) { "无法创建头像临时目录" }
        cleanupDesktopProfileAvatarTempFiles(outputDirectory, protectedFile)

        val encodedBytes = Data.makeFromFileName(source.absolutePath).use { encoded ->
            Codec.makeFromData(encoded).use { codec ->
                val rawWidth = codec.size.x
                val rawHeight = codec.size.y
                require(rawWidth > 0 && rawHeight > 0) { "无法读取图片尺寸" }
                require(rawWidth.toLong() * rawHeight.toLong() <= PROFILE_AVATAR_MAX_SOURCE_PIXELS) {
                    "图片像素过大，请选择较小的图片"
                }

                val sample = profileAvatarDecodeSampleSize(rawWidth, rawHeight)
                decodeDesktopAvatar(codec, rawWidth, rawHeight, sample).use { decoded ->
                    Image.makeFromBitmap(decoded).use { rawImage ->
                        val origin = codec.encodedOrigin
                        val orientedWidth = if (origin.swapsWidthHeight()) decoded.height else decoded.width
                        val orientedHeight = if (origin.swapsWidthHeight()) decoded.width else decoded.height
                        Surface.makeRasterN32Premul(orientedWidth, orientedHeight).use { orientedSurface ->
                            orientedSurface.canvas.concat(origin.toMatrix(orientedWidth, orientedHeight))
                            orientedSurface.canvas.drawImage(rawImage, 0f, 0f)
                            orientedSurface.makeImageSnapshot().use { orientedImage ->
                                val plan = profileAvatarCropPlan(orientedWidth, orientedHeight)
                                Surface.makeRasterN32Premul(plan.outputSize, plan.outputSize).use { outputSurface ->
                                    outputSurface.canvas.drawImageRect(
                                        image = orientedImage,
                                        src = Rect.makeXYWH(
                                            plan.sourceLeft.toFloat(),
                                            plan.sourceTop.toFloat(),
                                            plan.sourceSize.toFloat(),
                                            plan.sourceSize.toFloat(),
                                        ),
                                        dst = Rect.makeWH(plan.outputSize.toFloat(), plan.outputSize.toFloat()),
                                        samplingMode = SamplingMode.MITCHELL,
                                        paint = null,
                                        strict = true,
                                    )
                                    outputSurface.makeImageSnapshot().use { outputImage ->
                                        outputImage.encodeToData(EncodedImageFormat.PNG)?.use { data -> data.bytes }
                                            ?: throw IllegalArgumentException("无法编码头像图片")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        require(encodedBytes.size.toLong() in 1L..MAX_PROFILE_AVATAR_OUTPUT_BYTES) {
            "处理后的头像文件过大"
        }

        val output = File.createTempFile("avatar-", ".png", outputDirectory)
        return try {
            output.writeBytes(encodedBytes)
            val preview = Image.makeFromEncoded(encodedBytes).use { image -> image.toComposeImageBitmap() }
            PreparedDesktopProfileAvatar(output, preview)
        } catch (failure: Throwable) {
            output.delete()
            throw failure
        }
    }

    private fun decodeDesktopAvatar(
        codec: Codec,
        rawWidth: Int,
        rawHeight: Int,
        sample: Int,
    ): Bitmap {
        if (sample == 1) return codec.readPixels()
        val scaledWidth = maxOf(1, rawWidth / sample)
        val scaledHeight = maxOf(1, rawHeight / sample)
        val scaled = Bitmap()
        try {
            check(scaled.allocPixels(codec.imageInfo.withWidthHeight(scaledWidth, scaledHeight))) {
                "无法分配头像解码缓冲区"
            }
            return codec.readPixels(scaled).let { scaled }
        } catch (failure: Exception) {
            scaled.close()
            if (rawWidth.toLong() * rawHeight.toLong() > PROFILE_AVATAR_MAX_DECODED_PIXELS) {
                throw IllegalArgumentException("图片无法在安全内存范围内解码，请选择较小的图片", failure)
            }
            return codec.readPixels()
        }
    }
}

internal fun cleanupDesktopProfileAvatarTempFiles(
    directory: File,
    protectedFile: File? = null,
    nowMillis: Long = System.currentTimeMillis(),
) {
    if (!directory.isDirectory) return
    val root = directory.toPath()
    val entries = mutableListOf<ProfileAvatarTempEntry>()
    runCatching {
        Files.newDirectoryStream(root, "avatar-*.png").use { stream ->
            val iterator = stream.iterator()
            while (iterator.hasNext() && entries.size < PROFILE_AVATAR_TEMP_SCAN_LIMIT) {
                val path = iterator.next()
                val attributes = runCatching {
                    Files.readAttributes(
                        path,
                        BasicFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                }.getOrNull() ?: continue
                if (!attributes.isRegularFile) continue
                entries += ProfileAvatarTempEntry(
                    fileName = path.fileName.toString(),
                    lastModifiedMillis = attributes.lastModifiedTime().toMillis(),
                )
            }
        }
    }
    val protectedNames = protectedFile
        ?.takeIf { it.parentFile == directory }
        ?.let { setOf(it.name) }
        .orEmpty()
    profileAvatarTempCleanupCandidates(entries, nowMillis, protectedNames).forEach { fileName ->
        val target = root.resolve(fileName)
        if (target.parent != root || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return@forEach
        runCatching { Files.deleteIfExists(target) }
    }
}

private fun desktopAvatarFailureMessage(failure: Exception, processing: Boolean): String = when {
    processing && failure is IllegalArgumentException && !failure.message.isNullOrBlank() -> failure.message!!
    processing -> "无法处理所选图片，请选择有效的 PNG、JPEG 或 WebP 图片"
    else -> "头像上传失败，请检查网络后重试"
}
