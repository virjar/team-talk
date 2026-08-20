package com.virjar.tk

/**
 * Keeps Android lifecycle ordering executable: capture publishes/enqueues the final immutable
 * snapshot first, then the process writer receives a non-blocking barrier behind that write.
 */
internal inline fun captureThenScheduleDocumentDraftFlush(
    captureDrafts: () -> Boolean,
    scheduleFlush: () -> Unit,
): Boolean {
    val captured = captureDrafts()
    scheduleFlush()
    return captured
}
