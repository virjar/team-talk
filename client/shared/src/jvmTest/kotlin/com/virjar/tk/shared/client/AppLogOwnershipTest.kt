package com.virjar.tk.shared.client

import com.virjar.tk.shared.log.AppLog
import com.virjar.tk.shared.log.AppLogOwner
import com.virjar.tk.shared.log.LogBuffer
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AppLogOwnershipTest {
    @Test
    fun `retiring session cannot clear newer session logging hooks`() {
        val oldTrace = LogBuffer(2)
        val oldFault = LogBuffer(2)
        val newTrace = LogBuffer(2)
        val newFault = LogBuffer(2)
        val oldOwner = AppLogOwner(oldTrace, oldFault, {}, null)
        val newOwner = AppLogOwner(newTrace, newFault, {}, null)
        val retainedOldLogger = oldOwner.logger("old")

        try {
            AppLog.install(oldOwner)
            AppLog.install(newOwner)
            assertFalse(AppLog.release(oldOwner))

            assertSame(newOwner, AppLog.ownerSnapshot())
            retainedOldLogger.trace("late A")
            AppLog.trace("current", "B")
            assertFalse(oldTrace.drain().orEmpty().contains("late A"))
            assertFalse(newTrace.drain().orEmpty().contains("late A"))

            assertTrue(AppLog.release(newOwner))
            assertSame(null, AppLog.ownerSnapshot())
        } finally {
            AppLog.release(oldOwner)
            AppLog.release(newOwner)
        }
    }

    @Test
    fun `retired fixed logger cannot append or schedule fault upload`() {
        val trace = LogBuffer(4)
        val fault = LogBuffer(4)
        val faultCallbacks = AtomicInteger()
        val owner = AppLogOwner(trace, fault, { faultCallbacks.incrementAndGet() }, null)
        val logger = owner.logger("desktop-session")

        try {
            AppLog.install(owner)
            logger.trace("before retirement")
            logger.fault("before retirement")
            assertTrue(trace.drain().orEmpty().contains("before retirement"))
            assertTrue(fault.drain().orEmpty().contains("before retirement"))
            assertEquals(1, faultCallbacks.get())

            assertTrue(AppLog.release(owner))
            logger.trace("after retirement")
            logger.fault("after retirement")

            assertFalse(trace.drain().orEmpty().contains("after retirement"))
            assertFalse(fault.drain().orEmpty().contains("after retirement"))
            assertEquals(1, faultCallbacks.get())

            owner.recordCleanupFault("cleanup", "retirement cleanup failed")
            assertTrue(fault.drain().orEmpty().contains("retirement cleanup failed"))
            assertEquals(1, faultCallbacks.get())
        } finally {
            AppLog.release(owner)
        }
    }

    @Test
    fun `fault scheduling isolates ordinary diagnostics but propagates cancellation and fatal defects`() {
        var callbackFailure: Throwable = IOException("ordinary callback failure")
        val fault = LogBuffer(4)
        val owner = AppLogOwner(LogBuffer(1), fault, { throw callbackFailure }, null)

        owner.appendFault("test", "ordinary")
        assertTrue(fault.drain().orEmpty().contains("ordinary"))

        val cancelled = CancellationException("callback cancelled")
        callbackFailure = cancelled
        assertSame(cancelled, assertFailsWith<CancellationException> {
            owner.appendFault("test", "cancelled")
        })

        val fatal = AssertionError("callback invariant")
        callbackFailure = fatal
        assertSame(fatal, assertFailsWith<AssertionError> {
            owner.appendFault("test", "fatal")
        })
    }

    @Test
    fun `disabled session neither replaces AppLog nor crash attribution owner`() {
        val dataDir = Files.createTempDirectory("teamtalk-disabled-log-owner-").toFile()
        val graphicalTrace = LogBuffer(2)
        val graphicalFault = LogBuffer(2)
        val deploymentIdentity = DeploymentIdentity.from(
            tcpHost = "im.example.test",
            tcpPort = 5100,
            serverUrl = "https://im.example.test",
        )
        val graphicalCrash = CrashDumper(
            dataDir,
            deploymentIdentity,
            TEST_SYNC_DATASET_ID,
            "graphical",
        )
        val disabledCrash = CrashDumper(
            dataDir,
            deploymentIdentity,
            TEST_SYNC_DATASET_ID,
            "headless",
        )

        try {
            val graphicalOwner = checkNotNull(installAppLogOwnershipIfEnabled(
                enabled = true,
                traceBuffer = graphicalTrace,
                faultBuffer = graphicalFault,
                faultHandler = {},
                crashDumper = graphicalCrash,
            ))
            val installed = installAppLogOwnershipIfEnabled(
                enabled = false,
                traceBuffer = LogBuffer(2),
                faultBuffer = LogBuffer(2),
                faultHandler = {},
                crashDumper = disabledCrash,
            )
            flushPendingCrash(dataDir, "belongs to graphical owner")

            assertSame(null, installed)
            assertSame(graphicalOwner, AppLog.ownerSnapshot())
            assertTrue(graphicalCrash.hasPending())
            assertFalse(disabledCrash.hasPending())
        } finally {
            AppLog.ownerSnapshot()?.let(AppLog::release)
            dataDir.deleteRecursively()
        }
    }

    @Test
    fun `failed session construction restores previous AppLog snapshot by compare and swap`() {
        val previous = AppLogOwner(LogBuffer(2), LogBuffer(2), {}, null)
        val failed = AppLogOwner(LogBuffer(2), LogBuffer(2), {}, null)
        val rollback = SessionConstructionRollback()

        try {
            AppLog.install(previous)
            val observedPrevious = AppLog.installReturningPrevious(failed)
            rollback.own("AppLog") {
                AppLog.restoreAfterFailedInstall(failed, observedPrevious)
            }

            assertTrue(rollback.rollback().isEmpty())
            assertSame(previous, AppLog.ownerSnapshot())
        } finally {
            AppLog.release(previous)
            AppLog.release(failed)
        }
    }

    @Test
    fun `failed construction cannot revive a previous owner retired while hidden`() {
        val ownerA = AppLogOwner(LogBuffer(2), LogBuffer(2), {}, null)
        val failedOwnerB = AppLogOwner(LogBuffer(2), LogBuffer(2), {}, null)

        try {
            AppLog.install(ownerA)
            val previous = AppLog.installReturningPrevious(failedOwnerB)
            assertSame(ownerA, previous)

            assertFalse(AppLog.release(ownerA), "A is hidden by B but must still retire permanently")
            assertTrue(AppLog.restoreAfterFailedInstall(failedOwnerB, previous))

            assertSame(null, AppLog.ownerSnapshot())
        } finally {
            AppLog.release(ownerA)
            AppLog.release(failedOwnerB)
        }
    }

    @Test
    fun `platform-only client diagnostics never enter an installed account buffer`() {
        val graphicalTrace = LogBuffer(20)
        val graphicalFault = LogBuffer(20)
        val graphicalOwner = AppLogOwner(graphicalTrace, graphicalFault, {}, null)
        val client = ImClient()

        try {
            AppLog.install(graphicalOwner)
            client.connect("127.0.0.1", 1)

            assertFalse(graphicalTrace.drain().orEmpty().contains("127.0.0.1"))
            assertFalse(graphicalFault.drain().orEmpty().contains("ImClient"))
        } finally {
            client.destroy()
            AppLog.release(graphicalOwner)
        }
    }
}
