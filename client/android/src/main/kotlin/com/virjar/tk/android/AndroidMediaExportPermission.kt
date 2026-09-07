package com.virjar.tk.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class MediaExportPermissionRequest {
    var pending: CompletableDeferred<Boolean>? = null
    var closed = false
}

/** 权限弹窗属于当前页面；离页取消等待，不能把迟到的授权结果交给另一份导出请求。 */
@Composable
internal fun rememberAndroidMediaExportPermission(): suspend () -> Boolean {
    val context = LocalContext.current
    val request = remember { MediaExportPermissionRequest() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        request.pending?.complete(granted)
        request.pending = null
    }
    DisposableEffect(request) {
        onDispose {
            request.closed = true
            request.pending?.cancel()
        }
    }
    return remember(context, launcher) {
        suspend {
            withContext(Dispatchers.Main.immediate) {
                if (request.closed) {
                    false
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                        PackageManager.PERMISSION_GRANTED
                ) {
                    true
                } else {
                    // 多次点击共用系统正在展示的授权请求。单次导出被取消不会重绑其回调。
                    val result = request.pending ?: CompletableDeferred<Boolean>().also {
                        request.pending = it
                        try {
                            launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        } catch (failure: Exception) {
                            request.pending = null
                            it.complete(false)
                        }
                    }
                    result.await()
                }
            }
        }
    }
}
