package com.virjar.tk.app.ui.platform

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

internal actual fun Modifier.testTagResourceIds(): Modifier = semantics { testTagsAsResourceId = true }
