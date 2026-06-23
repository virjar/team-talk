package com.virjar.tk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ImageView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.virjar.tk.ui.component.GalleryItem
import com.virjar.tk.ui.component.buildMediaList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 图片/视频缩略图渲染：下载到本地缓存 → 解码 → ImageView 显示。
 */
@Composable
fun rememberAsyncThumb(
    url: String,
    modifier: Modifier = Modifier,
    placeholderColor: Int = android.graphics.Color.LTGRAY,
) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(url) {
        try {
            val cacheDir = File(context.cacheDir, "media")
            val file = MediaHelper.downloadToCache(url, cacheDir)
            val bm = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.absolutePath) }
            bitmap = bm
            isLoading = false
        } catch (_: Exception) {
            isLoading = false
        }
    }

    AndroidView(
        factory = { ctx ->
            ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        },
        modifier = modifier,
        update = { iv ->
            val bm = bitmap
            if (bm != null) {
                iv.setImageBitmap(bm)
                iv.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            } else {
                iv.setImageBitmap(null)
                iv.setBackgroundColor(if (isLoading) placeholderColor else 0xFF333333.toInt())
            }
        },
    )
}
