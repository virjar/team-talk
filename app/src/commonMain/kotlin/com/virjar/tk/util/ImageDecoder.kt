package com.virjar.tk.util

import androidx.compose.ui.graphics.ImageBitmap

/** Decode a byte array (e.g. PNG/JPEG) into an ImageBitmap. Platform-specific implementation. */
expect fun ByteArray.decodeToImageBitmap(): ImageBitmap
