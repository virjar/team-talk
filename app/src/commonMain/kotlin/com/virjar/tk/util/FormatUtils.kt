package com.virjar.tk.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Format a timestamp into a human-readable time string for chat separators. */
fun formatMessageTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val date = Date(timestamp)
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return when {
        diff < 86_400_000L -> sdf.format(date)
        diff < 172_800_000L -> "Yesterday ${sdf.format(date)}"
        diff < 604_800_000L -> {
            val dayFmt = SimpleDateFormat("EEE HH:mm", Locale.getDefault())
            dayFmt.format(date)
        }
        else -> {
            val fullFmt = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
            fullFmt.format(date)
        }
    }
}

/** Format a timestamp into a short time string (HH:mm) for message bubbles. */
fun formatMessageTimeShort(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

/** Format a file size in bytes to a human-readable string. */
fun formatFileSize(size: Long): String {
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${size / 1024} KB"
        else -> "${"%.1f".format(size / (1024.0 * 1024.0))} MB"
    }
}

/** Format a duration in seconds to "MM:SS". */
fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}

/** Whether to show a time separator between two consecutive messages. */
fun shouldShowTimeSeparator(prevTimestamp: Long, currentTimestamp: Long): Boolean {
    return currentTimestamp - prevTimestamp > 5 * 60_000L
}

/** Format a timestamp into a relative time string (e.g. "5m", "2h", "1d"). */
fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000L -> "now"
        diff < 3_600_000L -> "${diff / 60_000L}m"
        diff < 86_400_000L -> "${diff / 3_600_000L}h"
        diff < 604_800_000L -> "${diff / 86_400_000L}d"
        else -> "${diff / 604_800_000L}w"
    }
}
