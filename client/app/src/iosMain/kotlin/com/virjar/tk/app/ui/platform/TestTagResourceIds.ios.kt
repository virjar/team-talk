package com.virjar.tk.app.ui.platform

import androidx.compose.ui.Modifier

// iOS 的辅助功能标识来自 Compose testTag；不适用 Android 资源 ID。
internal actual fun Modifier.testTagResourceIds(): Modifier = this
