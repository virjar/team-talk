package com.virjar.tk.shared.client

import com.virjar.tk.protocol.telemetry.*
import kotlin.test.*

class ClientTelemetryStackFramesJvmTest {
    @Test
    fun `oversized JVM frames remain bounded in a complete persisted batch`() {
        val store = InMemoryTelemetrySegmentStore { 2_000_000L }
        val spool = ClientTelemetrySpool(store, clock = { 2_000_000L })
        val recorder = ClientTelemetryRecorder(ClientRuntimeInfo.unknown(), spool, clock = { 2_000_000L })
        val failure = IllegalStateException("password=must-not-leak")
        failure.stackTrace = Array(64) { index ->
            StackTraceElement("com.example.${"C".repeat(150)}", "method${index}${"M".repeat(140)}",
                "/private/user/Secret.kt", index + 1)
        }
        assertTrue(recorder.recordAppLog(TelemetryLogLevel.ERROR, "FaultLogger", "bounded stack", failure))
        assertTrue(recorder.flush())
        val queued = checkNotNull(spool.oldest())
        val payload = queued.batch.events.single().payload as TelemetryFaultPayload
        assertEquals(48, payload.stackFrames.size)
        assertTrue(payload.stackFrames.all { it.className.length <= ClientTelemetryLimits.MAX_STACK_FIELD_CHARS &&
            it.methodName.length <= ClientTelemetryLimits.MAX_STACK_FIELD_CHARS && it.fileName == "Secret.kt" })
        assertFalse("must-not-leak" in queued.encodedJson)
        assertFalse("/private/user" in queued.encodedJson)
        assertTrue(queued.encodedJson.encodeToByteArray().size < 256 * 1024)
    }
}
