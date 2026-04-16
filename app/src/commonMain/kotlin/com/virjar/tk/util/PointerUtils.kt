package com.virjar.tk.util

import androidx.compose.ui.input.pointer.PointerEvent

/**
 * Check if the pointer event represents a secondary (right) button press.
 * Returns false on platforms that don't support secondary buttons (e.g. Android).
 */
expect fun PointerEvent.isSecondaryButtonPressed(): Boolean
