package com.virjar.tk.util

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image as SkiaImage

actual fun ByteArray.decodeToImageBitmap(): ImageBitmap {
    return SkiaImage.makeFromEncoded(this).toComposeImageBitmap()
}
