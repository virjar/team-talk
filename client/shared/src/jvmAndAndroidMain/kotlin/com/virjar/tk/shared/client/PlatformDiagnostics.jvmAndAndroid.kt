package com.virjar.tk.shared.client

import com.virjar.tk.protocol.telemetry.TelemetryStackFrame
import com.virjar.tk.protocol.telemetry.ClientTelemetryLimits

internal actual fun platformSqliteBusy(failure: Throwable): Boolean =
    failure is java.sql.SQLException && failure.errorCode and 0xff in setOf(5, 6)
internal actual fun platformExceptionClassName(failure: Throwable): String = failure.javaClass.name
internal actual fun platformTelemetryStackFrames(failure: Throwable, limit: Int): List<TelemetryStackFrame> =
    failure.stackTrace.take(limit).map { frame ->
        TelemetryStackFrame(
            className = frame.className.take(ClientTelemetryLimits.MAX_STACK_FIELD_CHARS),
            methodName = frame.methodName.take(ClientTelemetryLimits.MAX_STACK_FIELD_CHARS),
            fileName = frame.fileName?.substringAfterLast('/')?.substringAfterLast('\\')?.take(ClientTelemetryLimits.MAX_STACK_FIELD_CHARS),
            lineNumber = frame.lineNumber,
        )
    }
