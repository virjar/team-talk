package com.virjar.tk.shared.client

import com.virjar.tk.shared.platform.PlatformLock
import com.virjar.tk.shared.platform.synchronized

internal actual fun createPlatformTelemetryHttpIoWorker(): PlatformTelemetryHttpIoWorker = object : PlatformTelemetryHttpIoWorker {
    private val lock = PlatformLock()
    private val worker = IosSerialExecutor("teamtalk.telemetry.http", capacity = 8)
    private var accepting = true
    private var failure: Throwable? = null

    override fun execute(task: () -> Unit): Boolean = synchronized(lock) {
        if (!accepting) return@synchronized false
        worker.execute {
            try { task() }
            catch (caught: Throwable) {
                synchronized(lock) {
                    accepting = false
                    failure = mergeSessionLifecycleFailures(failure, caught)
                }
                worker.closeAfterQueuedWork()
            }
        }
    }

    override fun closeAndDrain() {
        synchronized(lock) { accepting = false }
        worker.closeAndDrain()
        synchronized(lock) { failure }?.let { throw it }
    }
}
