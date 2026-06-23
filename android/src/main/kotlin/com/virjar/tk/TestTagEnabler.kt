package com.virjar.tk

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

/**
 * 启用 testTag → Android resourceId 映射。
 * 包装后，所有带 Modifier.testTag("xxx") 的元素可通过 d(resourceId="xxx") 定位。
 */
@Composable
fun TestTagEnabler(content: @Composable () -> Unit) {
    Box(modifier = Modifier.semantics { testTagsAsResourceId = true }) {
        content()
    }
}
