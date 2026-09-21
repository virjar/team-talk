package com.virjar.tk.app.ui.platform

import androidx.compose.ui.Modifier

// iOS accessibility identifiers come from Compose testTag; Android resource IDs do not apply.
internal actual fun Modifier.testTagResourceIds(): Modifier = this
