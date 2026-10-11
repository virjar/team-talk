package com.virjar.tk.app.telemetry

// Native 传输层在到达 UI 遥测之前已把 NSError 归一化为 AppError。
internal actual fun platformMediaFailureReason(failure: Throwable): MediaFailureReason? = null
