package com.virjar.tk.keepawake

import com.sun.jna.Native
import com.sun.jna.win32.StdCallLibrary
import com.virjar.tk.util.AppLog
import com.virjar.tk.util.DesktopPlatform

/**
 * Prevents OS power management from throttling or suspending the app,
 * ensuring TCP keep-alive (PING) packets are sent on time.
 */
interface KeepAwake {
    fun keepAwake()
    fun allowSleep()
}

fun createKeepAwake(): KeepAwake = when (DesktopPlatform.current) {
    DesktopPlatform.MACOS -> MacKeepAwake()
    DesktopPlatform.WINDOWS -> WindowsKeepAwake()
    else -> NoOpKeepAwake()
}

/**
 * macOS: `caffeinate -i -w <pid>` prevents system idle sleep while our process is alive.
 * User-initiated sleep (closing lid) still works. Zero dependency.
 */
private class MacKeepAwake : KeepAwake {
    private var process: Process? = null

    override fun keepAwake() {
        allowSleep()
        try {
            val pid = ProcessHandle.current().pid()
            process = ProcessBuilder("caffeinate", "-i", "-w", pid.toString())
                .redirectErrorStream(true)
                .start()
            AppLog.i("KeepAwake", "macOS caffeinate started (watching pid=$pid)")
        } catch (e: Exception) {
            AppLog.w("KeepAwake", "Failed to start caffeinate", e)
        }
    }

    override fun allowSleep() {
        process?.destroy()
        process = null
    }
}

/**
 * Windows: `SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED)` prevents
 * the system from entering sleep while the app is running. Screen saver and monitor
 * off are not affected.
 */
private class WindowsKeepAwake : KeepAwake {
    private var awake = false

    override fun keepAwake() {
        if (awake) return
        try {
            val ret = Kernel32Lib.INSTANCE.SetThreadExecutionState(
                ES_CONTINUOUS or ES_SYSTEM_REQUIRED
            )
            if (ret == 0L) {
                AppLog.w("KeepAwake", "SetThreadExecutionState returned 0 (failed)")
            } else {
                awake = true
                AppLog.i("KeepAwake", "Windows SetThreadExecutionState: system sleep prevented")
            }
        } catch (e: Throwable) {
            AppLog.w("KeepAwake", "SetThreadExecutionState failed (JNA unavailable?)", e)
        }
    }

    override fun allowSleep() {
        if (!awake) return
        try {
            Kernel32Lib.INSTANCE.SetThreadExecutionState(ES_CONTINUOUS)
            awake = false
            AppLog.i("KeepAwake", "Windows SetThreadExecutionState: system sleep restored")
        } catch (_: Throwable) {
        }
    }

    private companion object {
        private const val ES_CONTINUOUS = 0x80000000L
        private const val ES_SYSTEM_REQUIRED = 0x00000001L
    }
}

/**
 * Linux / other: no standard mechanism to prevent idle sleep from user space.
 * TCP 90s read-idle timeout + auto-reconnect serve as fallback.
 */
private class NoOpKeepAwake : KeepAwake {
    override fun keepAwake() {
        AppLog.i("KeepAwake", "No keep-awake mechanism for this platform")
    }

    override fun allowSleep() {}
}

/** JNA binding for Windows Kernel32 — only loaded when WindowsKeepAwake is instantiated. */
private interface Kernel32Lib : StdCallLibrary {
    companion object {
        val INSTANCE: Kernel32Lib = Native.load("kernel32", Kernel32Lib::class.java)
    }

    fun SetThreadExecutionState(esFlags: Long): Long
}
