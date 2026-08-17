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
    // 媒体缓存体系（Android 端）：Coil 全局磁盘 LRU 缓存（TeamTalkApp.newImageLoader 配置），
    // 命中零网络；未命中下载后落盘。替代手写 cacheDir 下载（无配额管理，曾无爆炸防护）。
    coil3.compose.AsyncImage(
        model = url,
        contentDescription = "媒体缩略图",
        modifier = modifier,
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
    )
}
