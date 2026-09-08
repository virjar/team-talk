package com.virjar.tk.app.ui.platform

import androidx.compose.ui.Modifier

/** 在独立语义根启用 Android testTag → resource-id 映射；其他平台保留原语义。 */
internal expect fun Modifier.testTagResourceIds(): Modifier
