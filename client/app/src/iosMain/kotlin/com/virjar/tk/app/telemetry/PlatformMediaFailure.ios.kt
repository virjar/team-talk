package com.virjar.tk.app.telemetry

// Native transports normalize NSError to AppError before reaching UI telemetry.
internal actual fun platformMediaFailureReason(failure: Throwable): MediaFailureReason? = null
