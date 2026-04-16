package com.virjar.tk.util

/**
 * Build the full file URL from a base URL and a relative path.
 * Handles absolute URLs (returns as-is) and relative paths (prepends base + /api/v1/files/).
 */
fun buildFileUrl(baseUrl: String, path: String): String {
    if (path.isEmpty()) return ""
    if (path.startsWith("http://") || path.startsWith("https://")) return path
    return "$baseUrl/api/v1/files/$path"
}
