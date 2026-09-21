package com.virjar.tk.shared.client

import com.virjar.tk.protocol.telemetry.TelemetryStackFrame
import com.virjar.tk.protocol.telemetry.ClientTelemetryLimits

internal actual fun platformSqliteBusy(failure: Throwable): Boolean =
    failure.message?.let { it.startsWith("database is locked") || it.startsWith("database table is locked") } == true
internal actual fun platformExceptionClassName(failure: Throwable): String = failure::class.simpleName ?: "Throwable"
internal actual fun platformTelemetryStackFrames(failure: Throwable, limit: Int): List<TelemetryStackFrame> =
    failure.stackTraceToString().lineSequence().drop(1).mapNotNull { line ->
        // Native symbol lines carry a kfun symbol. Never retain exception text or arbitrary paths.
        val symbol = line.substringAfter("kfun:", "").substringBefore(" + ").takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        TelemetryStackFrame(
            className = symbol.substringBefore('#').take(ClientTelemetryLimits.MAX_STACK_FIELD_CHARS),
            methodName = symbol.substringAfter('#', symbol).substringBefore('(').take(ClientTelemetryLimits.MAX_STACK_FIELD_CHARS),
            fileName = null,
            lineNumber = -1,
        )
    }.take(limit).toList()
