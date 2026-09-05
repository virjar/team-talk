package com.virjar.tk.android

/**
 * 让 Android 生命周期顺序可执行：捕获先发布/入队最终不可变的快照，
 * 然后进程写入器在该写入之后收到一个非阻塞屏障。
 */
internal inline fun captureThenScheduleDocumentDraftFlush(
    captureDrafts: () -> Boolean,
    scheduleFlush: () -> Unit,
): Boolean {
    val captured = captureDrafts()
    scheduleFlush()
    return captured
}
