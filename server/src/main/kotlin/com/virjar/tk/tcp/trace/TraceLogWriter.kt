package com.virjar.tk.tcp.trace

import com.virjar.tk.looper.Looper
import org.slf4j.LoggerFactory
import java.util.*
import java.util.function.Supplier


object NopWriter : LogWriter {
    override fun write(message: Supplier<String>, throwable: Throwable?) {

    }

    override fun enable(): Boolean {
        return false
    }
}

class RealWriter(val uid: String, val deviceId: String) : LogWriter {

    override fun write(message: Supplier<String>, throwable: Throwable?) {
        SamplingManager.write(uid, deviceId, message, throwable)
    }

    override fun enable(): Boolean {
        return true
    }
}

object SamplingManager {
    private val registry = mutableSetOf<RealWriter>()
    private val slf4jLogger = LoggerFactory.getLogger("EventTrace")
    private val workThread = Looper("trace").apply {
        start()
        unlockIoProtect()
    }
    private const val MAX_SAMPLE = 100

    fun acquireWriter(force: Boolean, uid: String, deviceId: String): LogWriter {
        if (!force && registry.size > MAX_SAMPLE) {
            return NopWriter
        }
        return RealWriter(uid, deviceId).apply {
            synchronized(registry) {
                registry.add(this)
            }
        }
    }

    fun releaseWriter(writer: LogWriter) {
        if (writer !is RealWriter) {
            return
        }
        synchronized(registry) {
            registry.remove(writer)
        }
    }

    fun write(uid: String, deviceId: String, messageGetter: Supplier<String>, throwable: Throwable?) {
        workThread.post {
            for (line in splitMsg(messageGetter.get(), throwable)) {
                // 写入、切换、压缩、清理，直接交给logback来处理
                slf4jLogger.info("uid: {} deviceId:{} -> {}", uid, deviceId, line)
            }
        }
    }

    private fun splitMsg(msg: String, throwable: Throwable?): MutableCollection<String> {
        val strings = msg.lineSequence().toMutableList()
        if (throwable == null) {
            return strings
        }
        ThrowablePrinter.printStackTrace(strings, throwable)
        return strings
    }
}


object ThrowablePrinter {
    /**
     * Caption  for labeling causative exception stack traces
     */
    private const val CAUSE_CAPTION = "Caused by: "

    /**
     * Caption for labeling suppressed exception stack traces
     */
    private const val SUPPRESSED_CAPTION = "Suppressed: "


    fun printStackTrace(out: MutableCollection<String>, throwable: Throwable) {
        // Guard against malicious overrides of Throwable.equals by
        // using a Set with identity equality semantics.
        val dejaVu = Collections.newSetFromMap<Throwable?>(IdentityHashMap<Throwable?, Boolean?>())
        dejaVu.add(throwable)


        // Print our stack trace
        //s.println(this);
        out.add(throwable.toString())
        val trace = throwable.stackTrace
        for (traceElement in trace) out.add("\tat $traceElement")

        // Print suppressed exceptions, if any
        for (se in throwable.suppressed) printEnclosedStackTrace(out, se, trace, SUPPRESSED_CAPTION, "\t", dejaVu)

        // Print cause, if any
        val ourCause = throwable.cause
        if (ourCause != null) printEnclosedStackTrace(out, ourCause, trace, CAUSE_CAPTION, "", dejaVu)
    }

    /**
     * Print our stack trace as an enclosed exception for the specified
     * stack trace.
     */
    private fun printEnclosedStackTrace(
        out: MutableCollection<String>,
        throwable: Throwable,
        enclosingTrace: Array<StackTraceElement?>,
        caption: String?,
        prefix: String?,
        dejaVu: MutableSet<Throwable?>
    ) {
        if (dejaVu.contains(throwable)) {
            out.add("\t[CIRCULAR REFERENCE:$throwable]")
        } else {
            dejaVu.add(throwable)
            // Compute number of frames in common between this and enclosing trace
            val trace = throwable.stackTrace
            var m = trace.size - 1
            var n = enclosingTrace.size - 1
            while (m >= 0 && n >= 0 && trace[m] == enclosingTrace[n]) {
                m--
                n--
            }
            val framesInCommon = trace.size - 1 - m

            // Print our stack trace
            out.add(prefix + caption + throwable)
            for (i in 0..m) out.add(prefix + "\tat " + trace[i])
            if (framesInCommon != 0) out.add("$prefix\t... $framesInCommon more")

            // Print suppressed exceptions, if any
            for (se in throwable.suppressed) printEnclosedStackTrace(
                out, se, trace, SUPPRESSED_CAPTION, prefix + "\t", dejaVu
            )

            // Print cause, if any
            val ourCause = throwable.cause
            if (ourCause != null) printEnclosedStackTrace(out, ourCause, trace, CAUSE_CAPTION, prefix, dejaVu)
        }
    }
}