package com.virjar.tk.android

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * 系统版本支持 Photo Picker 不等于设备安装了它的 Activity（裁剪 ROM/部分模拟器即如此）。
 * 两个选择器共用原 URI 处理链；均不可用时完成取消并显示原因，不遗留待完成的导入令牌。
 */
@Composable
internal fun rememberAndroidVisualMediaPicker(
    mediaType: ActivityResultContracts.PickVisualMedia.VisualMediaType = ActivityResultContracts.PickVisualMedia.ImageOnly,
    onResult: (Uri?) -> Unit,
): () -> Unit {
    val currentResult by rememberUpdatedState(onResult)
    var unavailable by remember { mutableStateOf(false) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { currentResult(it) }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { currentResult(it) }
    // 相册混选同时接受图片与视频；Photo Picker 原生混选，OpenDocument 回退用双 MIME。
    val documentMimeTypes = when (mediaType) {
        ActivityResultContracts.PickVisualMedia.VideoOnly -> arrayOf("video/*")
        ActivityResultContracts.PickVisualMedia.ImageAndVideo -> arrayOf("image/*", "video/*")
        else -> arrayOf("image/*")
    }

    if (unavailable) {
        AlertDialog(
            onDismissRequest = { unavailable = false },
            title = { Text("无法打开文件选择器") },
            text = { Text("系统未提供可用的文件选择器，请安装或启用系统文件应用后重试。") },
            confirmButton = {
                TextButton(onClick = { unavailable = false }, modifier = Modifier.testTag("media.picker.unavailable.close")) {
                    Text("知道了")
                }
            },
        )
    }

    return {
        try {
            photoPicker.launch(PickVisualMediaRequest.Builder().setMediaType(mediaType).build())
        } catch (_: ActivityNotFoundException) {
            try {
                documentPicker.launch(documentMimeTypes)
            } catch (_: ActivityNotFoundException) {
                currentResult(null)
                unavailable = true
            }
        }
    }
}
