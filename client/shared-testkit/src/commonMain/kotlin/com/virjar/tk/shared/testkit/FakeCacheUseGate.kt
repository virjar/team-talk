package com.virjar.tk.shared.testkit

/** [FakeLocalCache] 拥有的同步关闭/准入边界。 */
internal class FakeCacheUseGate {
    private val ownerLock = Any()

    @Volatile
    private var open = true
    private var operationDepth = 0
    private var deferredRelease: (() -> Unit)? = null

    fun <T> use(block: () -> T): T = synchronized(ownerLock) {
        operationDepth += 1
        try {
            check(open) { "FakeLocalCache is closed" }
            block()
        } finally {
            operationDepth -= 1
            releaseDeferredOwnerIfDrained()
        }
    }

    fun runIfOpen(block: () -> Boolean): Boolean = synchronized(ownerLock) {
        if (!open) return@synchronized false
        operationDepth += 1
        try {
            block()
        } finally {
            operationDepth -= 1
            releaseDeferredOwnerIfDrained()
        }
    }

    fun close(releaseOwner: () -> Unit): Boolean = synchronized(ownerLock) {
        if (!open) return@synchronized false
        open = false
        if (operationDepth > 0) {
            deferredRelease = releaseOwner
            throw FakeCacheReentrantCloseException()
        }
        releaseOwner()
        true
    }

    private fun releaseDeferredOwnerIfDrained() {
        if (operationDepth != 0) return
        val releaseOwner = deferredRelease ?: return
        deferredRelease = null
        releaseOwner()
    }
}

internal class FakeCacheReentrantCloseException : IllegalStateException(
    "FakeLocalCache cannot close reentrantly from an admitted operation",
)
