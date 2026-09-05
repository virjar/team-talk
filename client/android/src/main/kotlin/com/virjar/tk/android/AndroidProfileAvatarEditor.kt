package com.virjar.tk.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.ProfilePatchValue
import com.virjar.tk.app.navigation.AppDataState
import com.virjar.tk.shared.repository.asUploadSource
import com.virjar.tk.app.telemetry.ClientUiPage
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
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

private const val ANDROID_PROFILE_AVATAR_CONTENT_TYPE = "image/png"
private const val MAX_ANDROID_PROFILE_AVATAR_OUTPUT_BYTES = 2L * 1024L * 1024L

@Composable
internal fun AndroidEditProfileHost(
    dataState: AppDataState,
    resourceOwner: AndroidAuthenticatedResourceOwner,
    onBack: () -> Unit,
) {
    if (!dataState.acceptsRendering) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val actionAdmission = dataState.uiActionAdmission
    val mediaLease = remember(dataState, resourceOwner) {
        resourceOwner.acquire {
            AndroidMediaSession.create(
                deploymentIdentity = dataState.deploymentIdentity,
                datasetId = dataState.datasetId,
                ownerUid = dataState.userSession.uid,
                credentialsProvider = dataState::httpCredentialsSnapshot,
                onAuthExpired = dataState::reportHttpAuthExpired,
            )
        }
    }
    val mediaSession = mediaLease.resourceOrNull() ?: return
    val selectionOwner = remember { AndroidProfileAvatarSelectionOwner() }
    var selection by remember { mutableStateOf<PreparedAndroidProfileAvatar?>(null) }
    var removeRequested by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableStateOf<Float?>(null) }
    var avatarError by remember { mutableStateOf<String?>(null) }
    var uploadGeneration by remember { mutableStateOf(0L) }
    val routeOwner = remember(scope, mediaLease, selectionOwner, dataState.telemetry) {
        val lifecycleFault = AndroidPlatformLifecycleFaultReporter(
            telemetry = dataState.telemetry,
            page = ClientUiPage.EDIT_PROFILE,
        )
        AndroidProfileAvatarRouteOwner(scope) {
            selectionOwner.close()
            disposeAndroidAuthenticatedResources(
                closeResources = mediaLease::close,
                recordFailure = { failure ->
                    lifecycleFault.report()
                    Log.e("ProfileAvatar", "Failed to dispose avatar media session", failure)
                },
            )
        }
    }
    DisposableEffect(routeOwner) {
        onDispose(routeOwner::close)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            actionAdmission.runIfOpen {
                val protectedFile = selection?.file
                routeOwner.launch {
                    processing = true
                    avatarError = null
                    try {
                        val prepared = AndroidProfileAvatarProcessor.prepare(
                            context = context.applicationContext,
                            source = uri,
                            mediaSession = mediaSession,
                            protectedFile = protectedFile,
                        ) { candidate ->
                            mediaSession.ensureOpen()
                            selectionOwner.replace(candidate)
                        } ?: return@launch
                        selection = prepared
                        removeRequested = false
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        avatarError = androidAvatarFailureMessage(failure, processing = true)
                    } finally {
                        processing = false
                    }
                }
            }
        }
    }

    EditProfileScreen(
        currentUser = dataState.account.currentUser,
        avatarEditState = ProfileAvatarEditState(
            preview = selection?.preview,
            hasReplacement = selection != null,
            removeRequested = removeRequested,
            processing = processing,
            uploadProgress = uploadProgress,
            errorMessage = avatarError,
        ),
        onChooseAvatar = {
            avatarError = null
            picker.launch(
                PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    .build(),
            )
        },
        onRemoveAvatar = {
            selectionOwner.clear()
            selection = null
            removeRequested = true
            uploadProgress = null
            avatarError = null
        },
        onSave = save@{ name, phone ->
            routeOwner.runOwned {
                if (!actionAdmission.runIfOpen {}) return@runOwned false
                val avatarPatch = when {
                    removeRequested -> ProfilePatchValue.Set(null)
                    selection != null -> {
                        val currentSelection = checkNotNull(selection)
                        val attachment = currentSelection.uploadedAttachment ?: try {
                            val generation = ++uploadGeneration
                            uploadProgress = 0f
                            val uploadSource = withContext(Dispatchers.IO) {
                                currentSelection.file.asUploadSource()
                            }
                            mediaSession.fileRepository.uploadWithMeta(
                                source = uploadSource,
                                fileName = currentSelection.file.name,
                                contentType = ANDROID_PROFILE_AVATAR_CONTENT_TYPE,
                            ) { progress ->
                                routeOwner.launch {
                                    actionAdmission.runIfOpen {
                                        if (uploadGeneration == generation) {
                                            uploadProgress = progress.coerceIn(uploadProgress ?: 0f, 1f)
                                        }
                                    }
                                }
                            }.getOrThrow().file.also { uploaded ->
                                mediaSession.ensureOpen()
                                currentSelection.uploadedAttachment = uploaded
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            avatarError = androidAvatarFailureMessage(failure, processing = false)
                            return@runOwned false
                        } finally {
                            uploadGeneration++
                            uploadProgress = null
                        }
                        ProfilePatchValue.Set(attachment)
                    }
                    else -> ProfilePatchValue.Unchanged
                }
                val saved = dataState.account.saveProfile(name, phone, avatarPatch)
                saved && actionAdmission.runIfOpen {}
            }
        },
        onBack = onBack,
    )
}

internal class PreparedAndroidProfileAvatar(
    val file: File,
    private val bitmap: Bitmap,
    var uploadedAttachment: Attachment? = null,
) {
    val preview: ImageBitmap = bitmap.asImageBitmap()

    /** 删除上传源文件，但有意保留已经发布的预览 drawable。 */
    fun releaseForUiReplacement() {
        if (file.exists()) file.delete()
    }

    /** 只在宿主组合已被销毁、无法再绘制预览之后调用。 */
    fun disposeAfterHost() {
        releaseForUiReplacement()
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}

internal class AndroidProfileAvatarSelectionOwner : AutoCloseable {
    private var current: PreparedAndroidProfileAvatar? = null
    private var closed = false

    @Synchronized
    fun replace(next: PreparedAndroidProfileAvatar): Boolean {
        if (closed) {
            next.disposeAfterHost()
            return false
        }
        // 状态替换之后，Compose 可能还会在一帧内绘制旧的 ImageBitmap。
        // 这里只删除它的临时上传文件，让旧的 512px Bitmap 跟随 ImageBitmap 进入 GC；
        // recycle 保留给绘制停止之后的宿主销毁阶段。
        if (current !== next) current?.releaseForUiReplacement()
        current = next
        return true
    }

    @Synchronized
    fun clear() {
        current?.releaseForUiReplacement()
        current = null
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        current?.disposeAfterHost()
        current = null
    }
}

/** 路由持有的工作先排空，然后才关闭其已选文件和已认证的媒体租约。 */
internal class AndroidProfileAvatarRouteOwner(
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

    fun launch(action: suspend CoroutineScope.() -> Unit): Job =
        scope.launch(block = action)

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

internal data class AndroidAvatarOrientation(
    val rotationDegrees: Float,
    val flipHorizontal: Boolean,
    val flipVertical: Boolean = false,
)

internal fun androidAvatarOrientation(exifOrientation: Int): AndroidAvatarOrientation = when (exifOrientation) {
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> AndroidAvatarOrientation(0f, flipHorizontal = true)
    ExifInterface.ORIENTATION_ROTATE_180 -> AndroidAvatarOrientation(180f, flipHorizontal = false)
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> AndroidAvatarOrientation(0f, flipHorizontal = false, flipVertical = true)
    ExifInterface.ORIENTATION_TRANSPOSE -> AndroidAvatarOrientation(90f, flipHorizontal = true)
    ExifInterface.ORIENTATION_ROTATE_90 -> AndroidAvatarOrientation(90f, flipHorizontal = false)
    ExifInterface.ORIENTATION_TRANSVERSE -> AndroidAvatarOrientation(-90f, flipHorizontal = true)
    ExifInterface.ORIENTATION_ROTATE_270 -> AndroidAvatarOrientation(-90f, flipHorizontal = false)
    else -> AndroidAvatarOrientation(0f, flipHorizontal = false)
}

internal object AndroidProfileAvatarProcessor {
    suspend fun prepare(
        context: Context,
        source: Uri,
        mediaSession: AndroidMediaSession,
        protectedFile: File? = null,
        accept: (PreparedAndroidProfileAvatar) -> Boolean,
    ): PreparedAndroidProfileAvatar? = withContext(Dispatchers.IO) {
        val selected = MediaHelper.prepareSelectedMedia(
            context = context,
            uri = source,
            mediaSession = mediaSession,
            maxBytes = PROFILE_AVATAR_MAX_SOURCE_BYTES,
        )
        try {
            val prepared = prepareSelectedFile(
                selected.file,
                mediaCacheDirectory(
                    context.cacheDir,
                    mediaSession.cacheNamespace,
                    "outgoing-avatar",
                ),
                protectedFile,
            )
            try {
                if (accept(prepared)) prepared else null
            } catch (failure: Throwable) {
                prepared.disposeAfterHost()
                throw failure
            }
        } finally {
            selected.delete()
        }
    }

    private fun prepareSelectedFile(
        source: File,
        outputDirectory: File,
        protectedFile: File?,
    ): PreparedAndroidProfileAvatar {
        require(outputDirectory.isDirectory || outputDirectory.mkdirs()) { "无法创建头像临时目录" }
        cleanupAndroidProfileAvatarTempFiles(outputDirectory, protectedFile)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        require(width > 0 && height > 0) { "无法读取图片尺寸" }
        require(width.toLong() * height.toLong() <= PROFILE_AVATAR_MAX_SOURCE_PIXELS) {
            "图片像素过大，请选择较小的图片"
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = profileAvatarDecodeSampleSize(width, height)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(source.absolutePath, options)
            ?: throw IllegalArgumentException("无法解码所选图片")
        var retained: Bitmap? = decoded
        try {
            require(decoded.width.toLong() * decoded.height.toLong() <= PROFILE_AVATAR_MAX_DECODED_PIXELS) {
                "图片无法在安全内存范围内解码，请选择较小的图片"
            }
            val plan = profileAvatarCropPlan(decoded.width, decoded.height)
            val transform = androidAvatarOrientation(readExifOrientation(source))
            val matrix = Matrix().apply {
                if (transform.rotationDegrees != 0f) postRotate(transform.rotationDegrees)
                if (transform.flipHorizontal || transform.flipVertical) {
                    postScale(
                        if (transform.flipHorizontal) -1f else 1f,
                        if (transform.flipVertical) -1f else 1f,
                    )
                }
            }
            val cropped = Bitmap.createBitmap(
                decoded,
                plan.sourceLeft,
                plan.sourceTop,
                plan.sourceSize,
                plan.sourceSize,
                matrix,
                true,
            )
            if (cropped !== decoded) {
                decoded.recycle()
                retained = cropped
            }
            val outputBitmap = if (cropped.width == plan.outputSize && cropped.height == plan.outputSize) {
                cropped
            } else {
                Bitmap.createScaledBitmap(cropped, plan.outputSize, plan.outputSize, true).also {
                    if (it !== cropped) cropped.recycle()
                }
            }
            retained = outputBitmap

            val output = File.createTempFile("avatar-", ".png", outputDirectory)
            try {
                val encoded = output.outputStream().buffered().use { stream ->
                    outputBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
                require(encoded && output.length() in 1L..MAX_ANDROID_PROFILE_AVATAR_OUTPUT_BYTES) {
                    "无法生成有界头像图片"
                }
                retained = null
                return PreparedAndroidProfileAvatar(output, outputBitmap)
            } catch (failure: Throwable) {
                output.delete()
                throw failure
            }
        } finally {
            retained?.takeUnless(Bitmap::isRecycled)?.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun readExifOrientation(source: File): Int = runCatching {
        ExifInterface(source.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
}

internal fun cleanupAndroidProfileAvatarTempFiles(
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

private fun androidAvatarFailureMessage(failure: Exception, processing: Boolean): String = when {
    processing && failure is IllegalArgumentException && !failure.message.isNullOrBlank() -> failure.message!!
    processing -> "无法处理所选图片，请选择有效的 PNG、JPEG 或 WebP 图片"
    else -> "头像上传失败，请检查网络后重试"
}
