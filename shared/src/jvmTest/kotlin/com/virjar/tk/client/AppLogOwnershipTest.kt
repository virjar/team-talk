package com.virjar.tk.client

import com.virjar.tk.util.AppLog
import com.virjar.tk.util.LogBuffer
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class AppLogOwnershipTest {
    @Test
    fun `retiring session cannot clear newer session logging hooks`() {
        val oldTrace = LogBuffer(2)
        val oldFault = LogBuffer(2)
        val oldHandler = {}
        val newTrace = LogBuffer(2)
        val newFault = LogBuffer(2)
        val newHandler = {}

        try {
            installAppLogOwnership(oldTrace, oldFault, oldHandler)
            installAppLogOwnership(newTrace, newFault, newHandler)
            releaseAppLogOwnership(oldTrace, oldFault, oldHandler)

            assertSame(newTrace, AppLog.traceBuffer)
            assertSame(newFault, AppLog.faultBuffer)
            assertSame(newHandler, AppLog.onFault)

            releaseAppLogOwnership(newTrace, newFault, newHandler)
            assertNull(AppLog.traceBuffer)
            assertNull(AppLog.faultBuffer)
            assertNull(AppLog.onFault)
        } finally {
            synchronized(AppLog) {
                AppLog.traceBuffer = null
                AppLog.faultBuffer = null
                AppLog.onFault = null
            }
        }
    }
}
