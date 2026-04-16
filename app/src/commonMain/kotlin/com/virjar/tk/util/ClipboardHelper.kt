package com.virjar.tk.util

/** Copy text to the system clipboard. Platform-specific implementation required. */
expect fun copyToClipboard(text: String)
