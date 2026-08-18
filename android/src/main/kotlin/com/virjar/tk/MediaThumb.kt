package com.virjar.tk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
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
    val bitmap by produceState<Bitmap?>(null, url) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = MediaHelper.downloadToCache(url, File(context.cacheDir, "media"))
                BitmapFactory.decodeFile(file.absolutePath)
            }.onFailure { Log.e("MediaThumb", "加载失败: $url", it) }.getOrNull()
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(placeholderColor)
            }
        },
        update = { view -> view.setImageBitmap(bitmap) },
    )
}
