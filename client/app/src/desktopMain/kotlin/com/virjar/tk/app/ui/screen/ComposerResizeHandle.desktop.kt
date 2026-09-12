package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.virjar.tk.app.identity.ClientIdentity
import java.awt.Cursor
import java.util.prefs.Preferences

/** 输入区拖拽手柄：悬停变调整光标，拖动时高亮，向上拖增大编辑区。 */
@Composable
internal actual fun ComposerResizeHandle(
    onDelta: (Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR)))
            .background(
                if (dragging) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                } else {
                    Color.Transparent
                },
            )
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false; onDragEnd() },
                    onDragCancel = { dragging = false },
                ) { _, dragAmount ->
                    onDelta(dragAmount)
                }
            },
    )
}

private val composerLayoutPreferences: Preferences
    get() = if (ClientIdentity.APPLICATION_ID == "com.virjar.tk") {
        Preferences.userRoot().node("/com/virjar/tk/teamtalk/desktop/layout")
    } else {
        Preferences.userRoot().node("/${ClientIdentity.APPLICATION_ID.replace('.', '/')}/teamtalk/desktop/layout")
    }

internal actual fun savedComposerMaxHeight(): Dp? =
    runCatching {
        val stored = composerLayoutPreferences.get("composer.max", null) ?: return null
        Dp(stored.toFloat())
    }.getOrNull()

internal actual fun persistComposerMaxHeight(value: Dp) {
    runCatching { composerLayoutPreferences.putFloat("composer.max", value.value) }
}

internal actual val composerManualResizeSupported: Boolean = true
