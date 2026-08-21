package com.virjar.tk

import android.media.MediaRecorder
import com.virjar.tk.client.SessionEndReason

internal enum class AndroidUiRetirementPolicy {
    DISCARD_DRAFTS,
    PRESERVE_DURABLE_DRAFTS,
    PRESERVE_SAME_USER_CONTINUATION,
}

internal fun SessionEndReason.androidUiRetirementPolicy(): AndroidUiRetirementPolicy = when (this) {
    SessionEndReason.USER_LOGOUT -> AndroidUiRetirementPolicy.DISCARD_DRAFTS
    SessionEndReason.AUTH_REVOKED,
    SessionEndReason.PROTOCOL_UPGRADE -> AndroidUiRetirementPolicy.PRESERVE_DURABLE_DRAFTS
    SessionEndReason.PROCESS_REPLACED,
    SessionEndReason.SHUTDOWN -> AndroidUiRetirementPolicy.PRESERVE_SAME_USER_CONTINUATION
}

/** Referential owner gate shared by Activity recreation, auth retirement and ViewModel clearing. */
internal class AndroidSessionOwnerGate<T : Any> {
    private val lock = Any()
    private var owner: T? = null

    fun <R> replaceOwner(nextOwner: T, transition: (previousOwner: T?) -> R): R = synchronized(lock) {
        val result = transition(owner)
        owner = nextOwner
        result
    }

    fun retireIfOwner(expectedOwner: T, retire: () -> Unit): Boolean = synchronized(lock) {
        if (owner !== expectedOwner) return@synchronized false
        owner = null
        retire()
        true
    }

    fun retireCurrent(retire: (owner: T?) -> Unit) = synchronized(lock) {
        val previousOwner = owner
        owner = null
        retire(previousOwner)
    }

    fun <R> withOwner(block: (owner: T?) -> R): R = synchronized(lock) { block(owner) }
}

/**
 * Session-scoped barrier for Android resources which can retain bearer credentials or active I/O.
 *
 * Registration, lease disposal and owner sealing share one monitor. A retirement therefore waits
 * for a concurrent acquisition or Compose disposal. Once sealed, acquisition does not execute its
 * factory, so a stale composition cannot construct or expose a new bearer-capable resource.
 */
internal class AndroidAuthenticatedResourceOwner {
    private val lock = Any()
    private var sealed = false
    private val leases = LinkedHashSet<AndroidAuthenticatedResourceLease<*>>()

    fun <T : AutoCloseable> acquire(
        createResource: () -> T,
    ): AndroidAuthenticatedResourceLease<T> = synchronized(lock) {
        if (sealed) return@synchronized AndroidAuthenticatedResourceLease.closed(this)
        AndroidAuthenticatedResourceLease.open(this, createResource()).also(leases::add)
    }

    /** Seals the owner permanently and best-effort closes every admitted resource. */
    fun closeAll(): List<Throwable> = synchronized(lock) {
        sealed = true
        val failures = mutableListOf<Throwable>()
        leases.toList().forEach { lease ->
            leases.remove(lease)
            lease.closeLocked()?.let(failures::add)
        }
        failures
    }

    internal fun closeLease(lease: AndroidAuthenticatedResourceLease<*>): Throwable? = synchronized(lock) {
        leases.remove(lease)
        lease.closeLocked()
    }

    internal fun <T : AutoCloseable> resourceOrNull(
        lease: AndroidAuthenticatedResourceLease<T>,
    ): T? = synchronized(lock) { lease.resourceLocked() }
}

internal class AndroidAuthenticatedResourceLease<T : AutoCloseable> private constructor(
    private val owner: AndroidAuthenticatedResourceOwner,
    private var resource: T?,
) : AutoCloseable {
    fun resourceOrNull(): T? = owner.resourceOrNull(this)

    override fun close() {
        owner.closeLease(this)?.let { throw it }
    }

    internal fun closeLocked(): Throwable? {
        val closing = resource ?: return null
        resource = null
        return runCatching(closing::close).exceptionOrNull()
    }

    internal fun resourceLocked(): T? = resource

    companion object {
        fun <T : AutoCloseable> open(
            owner: AndroidAuthenticatedResourceOwner,
            resource: T,
        ) = AndroidAuthenticatedResourceLease(owner, resource)

        fun <T : AutoCloseable> closed(
            owner: AndroidAuthenticatedResourceOwner,
        ) = AndroidAuthenticatedResourceLease<T>(owner, null)
    }
}

/** Close order is security-significant: stop producers/controllers before closing bearer I/O. */
internal class AndroidAuthenticatedMediaResources private constructor(
    val mediaSession: AndroidMediaSession,
    val fileDownloads: AndroidFileDownloadController?,
    private val stopVoice: () -> Unit,
) : AutoCloseable {
    override fun close() {
        closeAndroidAuthenticatedMediaResources(
            closeControllers = { fileDownloads?.close() },
            stopVoice = stopVoice,
            closeMedia = mediaSession::close,
        )
    }

    companion object {
        fun create(
            createMediaSession: () -> AndroidMediaSession,
            createFileDownloads: (AndroidMediaSession) -> AndroidFileDownloadController? = { null },
            stopVoice: (AndroidMediaSession) -> Unit = {},
        ): AndroidAuthenticatedMediaResources {
            val mediaSession = createMediaSession()
            return try {
                AndroidAuthenticatedMediaResources(
                    mediaSession = mediaSession,
                    fileDownloads = createFileDownloads(mediaSession),
                    stopVoice = { stopVoice(mediaSession) },
                )
            } catch (failure: Throwable) {
                runCatching(mediaSession::close).exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }
    }
}

internal fun closeAndroidAuthenticatedMediaResources(
    closeControllers: () -> Unit,
    stopVoice: () -> Unit,
    closeMedia: () -> Unit,
) {
    val failures = mutableListOf<Throwable>()
    listOf(closeControllers, stopVoice, closeMedia).forEach { closeResource ->
        try {
            closeResource()
        } catch (failure: Throwable) {
            failures += failure
        }
    }
    if (failures.isNotEmpty()) throw AndroidAuthenticatedResourceCloseException(failures)
}

internal fun closeAndroidChatVoiceResources(
    permissionGate: VoiceRecordPermissionGate,
    recording: VoiceRecordingLease<MediaRecorder>,
    cacheNamespace: String,
) {
    val failures = mutableListOf<Throwable>()
    listOf<() -> Unit>(
        permissionGate::clear,
        {
            recording.discard(
                stop = MediaRecorder::stop,
                release = MediaRecorder::release,
            )
        },
        { VoicePlayer.stop(cacheNamespace) },
    ).forEach { closeVoice ->
        try {
            closeVoice()
        } catch (failure: Throwable) {
            failures += failure
        }
    }
    if (failures.isNotEmpty()) throw AndroidAuthenticatedResourceCloseException(failures)
}

internal class AndroidAuthenticatedResourceCloseException(
    val failures: List<Throwable>,
) : IllegalStateException("Failed to close ${failures.size} Android authenticated resource(s)", failures.first()) {
    init {
        failures.drop(1).forEach(::addSuppressed)
    }
}
