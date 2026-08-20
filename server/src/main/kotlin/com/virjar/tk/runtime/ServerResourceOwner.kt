package com.virjar.tk.runtime

/**
 * Owns process resources acquired during server startup.
 *
 * Resources are released exactly once in reverse acquisition order. One broken
 * closer must not prevent the remaining native handles and thread pools from
 * being released, especially while unwinding a partially completed startup.
 */
internal class ServerResourceOwner(
    private val onCloseFailure: (name: String, error: Throwable) -> Unit,
) : AutoCloseable {
    private data class OwnedResource(
        val name: String,
        val close: () -> Unit,
    )

    private val lock = Any()
    private val resources = ArrayDeque<OwnedResource>()
    private var closed = false

    fun own(name: String, close: () -> Unit) {
        val closeImmediately = synchronized(lock) {
            if (closed) {
                true
            } else {
                resources.addLast(OwnedResource(name, close))
                false
            }
        }
        if (closeImmediately) {
            closeSafely(OwnedResource(name, close))
            error("Server resources are already closed")
        }
    }

    fun <T> own(name: String, resource: T, close: (T) -> Unit): T {
        own(name) { close(resource) }
        return resource
    }

    override fun close() {
        val pending = synchronized(lock) {
            if (closed) return
            closed = true
            buildList(resources.size) {
                while (resources.isNotEmpty()) add(resources.removeLast())
            }
        }

        pending.forEach(::closeSafely)
    }

    private fun closeSafely(resource: OwnedResource) {
        try {
            resource.close()
        } catch (error: Throwable) {
            runCatching { onCloseFailure(resource.name, error) }
        }
    }
}
