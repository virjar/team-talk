package com.virjar.tk.app.ui.screen

import androidx.compose.ui.graphics.ImageBitmap

/** 头像上传在到达 FileRepository 之前就会被规范化。 */
const val PROFILE_AVATAR_OUTPUT_SIZE: Int = 512
const val PROFILE_AVATAR_MAX_SOURCE_BYTES: Long = 32L * 1024L * 1024L
const val PROFILE_AVATAR_MAX_SOURCE_PIXELS: Long = 64L * 1024L * 1024L
const val PROFILE_AVATAR_MAX_DECODED_PIXELS: Long = 16L * 1024L * 1024L
const val PROFILE_AVATAR_TEMP_SCAN_LIMIT: Int = 128
const val PROFILE_AVATAR_TEMP_DELETE_LIMIT: Int = 32
const val PROFILE_AVATAR_TEMP_RETAINED_FILES: Int = 8
const val PROFILE_AVATAR_TEMP_STALE_MILLIS: Long = 24L * 60L * 60L * 1000L

data class ProfileAvatarTempEntry(
    val fileName: String,
    val lastModifiedMillis: Long,
)

/** 由共享的编辑资料界面渲染的平台持有选择状态。 */
data class ProfileAvatarEditState(
    val preview: ImageBitmap? = null,
    val hasReplacement: Boolean = false,
    val removeRequested: Boolean = false,
    val processing: Boolean = false,
    val uploadProgress: Float? = null,
    val errorMessage: String? = null,
) {
    val busy: Boolean get() = processing || uploadProgress != null
}

/** 两个平台 codec 共用的像素空间裁剪计划；输出绝不放大。 */
data class ProfileAvatarCropPlan(
    val sourceLeft: Int,
    val sourceTop: Int,
    val sourceSize: Int,
    val outputSize: Int,
)

fun profileAvatarCropPlan(
    width: Int,
    height: Int,
    maxOutputSize: Int = PROFILE_AVATAR_OUTPUT_SIZE,
): ProfileAvatarCropPlan {
    require(width > 0 && height > 0) { "图片尺寸无效" }
    require(maxOutputSize > 0) { "头像输出尺寸必须为正数" }
    val sourceSize = minOf(width, height)
    return ProfileAvatarCropPlan(
        sourceLeft = (width - sourceSize) / 2,
        sourceTop = (height - sourceSize) / 2,
        sourceSize = sourceSize,
        outputSize = minOf(sourceSize, maxOutputSize),
    )
}

/**
 * Android 的 2 的幂解码采样。只要解码像素预算允许，就至少保留请求的方形尺寸，
 * 然后增大采样倍数，使恶意的超大全景图保持有界。
 */
fun profileAvatarDecodeSampleSize(
    width: Int,
    height: Int,
    targetSize: Int = PROFILE_AVATAR_OUTPUT_SIZE,
    maxDecodedPixels: Long = PROFILE_AVATAR_MAX_DECODED_PIXELS,
): Int {
    require(width > 0 && height > 0) { "图片尺寸无效" }
    require(targetSize > 0) { "头像目标尺寸必须为正数" }
    require(maxDecodedPixels > 0L) { "解码像素预算必须为正数" }

    var sample = 1
    while (
        width / (sample * 2) >= targetSize &&
        height / (sample * 2) >= targetSize &&
        sample <= Int.MAX_VALUE / 2
    ) {
        sample *= 2
    }
    while (
        decodedPixels(width, height, sample) > maxDecodedPixels &&
        sample <= Int.MAX_VALUE / 2
    ) {
        sample *= 2
    }
    return sample
}

private fun decodedPixels(width: Int, height: Int, sample: Int): Long =
    maxOf(1, width / sample).toLong() * maxOf(1, height / sample).toLong()

/**
 * 只选择精确的头像临时文件名进行有界清理。平台枚举器单独受
 * [PROFILE_AVATAR_TEMP_SCAN_LIMIT] 限制，且绝不遍历当前账号目录之外。
 */
fun profileAvatarTempCleanupCandidates(
    entries: List<ProfileAvatarTempEntry>,
    nowMillis: Long,
    protectedFileNames: Set<String> = emptySet(),
    staleAfterMillis: Long = PROFILE_AVATAR_TEMP_STALE_MILLIS,
    retainedFiles: Int = PROFILE_AVATAR_TEMP_RETAINED_FILES,
    deleteLimit: Int = PROFILE_AVATAR_TEMP_DELETE_LIMIT,
): List<String> {
    require(nowMillis >= 0L) { "当前时间不能为负数" }
    require(staleAfterMillis >= 0L) { "临时文件保留时间不能为负数" }
    require(retainedFiles >= 0) { "临时文件保留数量不能为负数" }
    require(deleteLimit >= 0) { "单次清理数量不能为负数" }
    if (deleteLimit == 0) return emptyList()

    val candidates = entries
        .asSequence()
        .filter { it.fileName.isProfileAvatarTempName() }
        .filterNot { it.fileName in protectedFileNames }
        .distinctBy(ProfileAvatarTempEntry::fileName)
        .sortedWith(
            compareByDescending<ProfileAvatarTempEntry> { it.lastModifiedMillis }
                .thenBy(ProfileAvatarTempEntry::fileName),
        )
        .toList()
    val staleBoundary = (nowMillis - staleAfterMillis).coerceAtLeast(0L)
    val stale = candidates.filter { it.lastModifiedMillis <= staleBoundary }.map { it.fileName }.toSet()
    val overflow = candidates.drop(retainedFiles).map { it.fileName }.toSet()
    return candidates
        .asReversed()
        .asSequence()
        .map(ProfileAvatarTempEntry::fileName)
        .filter { it in stale || it in overflow }
        .take(deleteLimit)
        .toList()
}

private fun String.isProfileAvatarTempName(): Boolean =
    startsWith("avatar-") && endsWith(".png") && length > "avatar-.png".length &&
        none { it == '/' || it == '\\' }
