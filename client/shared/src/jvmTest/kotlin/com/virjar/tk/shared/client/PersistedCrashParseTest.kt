package com.virjar.tk.shared.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PersistedCrashParseTest {
    @Test
    fun parsesThreadExceptionAndFrames() {
        val text = """
            Crash in main: android.app.RemoteServiceException${'$'}CrashedByAdbException: shell-induced crash
            	at android.app.ActivityThread.throwRemoteServiceException(ActivityThread.java:2668)
            	at android.app.ActivityThread.main(ActivityThread.java:10111)
            Caused by: java.lang.IllegalStateException: boom
            	at com.example.Foo.bar(Foo.kt:42)
        """.trimIndent()
        val parsed = parsePersistedCrashText(text)
        println("summary=[" + parsed.summary + "]")
        println("class=[" + parsed.exceptionClass + "]")
        parsed.stackFrames.forEach { println("frame=" + it) }
        assertEquals("android.app.RemoteServiceException${'$'}CrashedByAdbException", parsed.exceptionClass)
        assertTrue(parsed.stackFrames.size in 1..48)
        assertEquals("throwRemoteServiceException", parsed.stackFrames.first().methodName)
    }

    @Test
    fun markerOnlyFallsBackToEmpty() {
        val parsed = parsePersistedCrashText("client-telemetry-fatal-v1")
        println("marker summary=[" + parsed.summary + "] class=" + parsed.exceptionClass + " frames=" + parsed.stackFrames.size)
    }
}
