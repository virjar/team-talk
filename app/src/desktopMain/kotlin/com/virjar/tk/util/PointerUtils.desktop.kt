@file:OptIn(ExperimentalComposeUiApi::class)

package com.virjar.tk.util

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEvent

actual fun PointerEvent.isSecondaryButtonPressed(): Boolean {
    return button == PointerButton.Secondary
}
