package com.virjar.tk.client

import com.virjar.tk.util.AppLog
import com.virjar.tk.util.HttpUtil
import java.io.File

internal fun interface CrashUploadTransport {
    fun post(url: String, compressed: ByteArray, headers: Map<String, String>): Int
}

private object DefaultCrashUploadTransport : CrashUploadTransport {
    override fun post(url: String, compressed: ByteArray, headers: Map<String, String>): Int =
        HttpUtil.postGzip(url, compressed, headers)
}

private data class CrashOwnerIdentity(
    val serverBaseUrl: String,
    val deploymentFingerprint: String,
    val uid: String,
)

/**
 * Crash persistence isolated by immutable server identity and authenticated uid.
 *
 * A dumper created with only [dataDir] is deliberately unowned. That namespace is never scanned by
 * an authenticated uploader, so a pre-login crash cannot be attributed to whichever account logs
 * in next. Authenticated sessions use the three-argument constructor and can only see their exact
 * canonical TCP+HTTP deployment + uid namespace.
 */
class CrashDumper private constructor(
    dataDir: File,
    private val owner: CrashOwnerIdentity?,
    private val uploadTransport: CrashUploadTransport,
) {
    constructor(dataDir: File) : this(dataDir, null, DefaultCrashUploadTransport)

    constructor(dataDir: File, deploymentIdentity: DeploymentIdentity, ownerUid: String) : this(
        dataDir = dataDir,
        owner = crashOwnerIdentity(deploymentIdentity, ownerUid),
        uploadTransport = DefaultCrashUploadTransport,
    )

    internal constructor(
        dataDir: File,
        deploymentIdentity: DeploymentIdentity,
        ownerUid: String,
        uploadTransport: CrashUploadTransport,
    ) : this(dataDir, crashOwnerIdentity(deploymentIdentity, ownerUid), uploadTransport)

    private val pendingStore = privateAtomicTextFileStore(
        dataDir = dataDir,
        privateDirectories = owner?.let { identity ->
            listOf(
                CRASH_ROOT,
                stableCrashNamespace(identity.deploymentFingerprint),
                stableCrashNamespace(identity.uid),
            )
        } ?: listOf(CRASH_ROOT, UNOWNED_NAMESPACE),
        fileName = PENDING_FILE,
    )

    /** Whether this exact identity has a pending crash. */
    fun hasPending(): Boolean = synchronized(this) {
        runCatching(pendingStore::existsNonEmpty).getOrDefault(false)
    }

    /** Fixed-owner uploader input; reading another namespace is impossible by construction. */
    internal fun pendingContent(): String? = synchronized(this) {
        runCatching(pendingStore::readText).getOrNull()?.takeIf(String::isNotEmpty)
    }

    /** Delete only the exact payload uploaded; a newer crash written in flight must survive. */
    internal fun markPendingUploaded(expectedContent: String) = synchronized(this) {
        if (runCatching(pendingStore::readText).getOrNull() == expectedContent) {
            runCatching(pendingStore::delete)
        }
    }

    /** Atomic best-effort persistence. Failure never masks the original crash. */
    fun flushPending(content: String) {
        synchronized(this) {
            try {
                pendingStore.replaceText(content)
            } catch (_: Throwable) {
                // Crash persistence itself must never become a second fatal error.
            }
        }
    }

    /** Uploads only the namespace fixed in this object; unowned dumpers never upload. */
    fun uploadPending(accessToken: String?) {
        val identity = owner ?: return
        if (accessToken.isNullOrBlank()) return
        synchronized(this) {
            val content = runCatching(pendingStore::readText).getOrNull()
                ?.takeIf(String::isNotEmpty)
                ?: return
            try {
                val compressed = HttpUtil.gzip(content)
                val code = uploadTransport.post(
                    "${identity.serverBaseUrl}/api/client-logs",
                    compressed,
                    mapOf("Authorization" to "Bearer $accessToken"),
                )
                if (code == 200 && pendingStore.readText() == content) pendingStore.delete()
            } catch (_: Throwable) {
                // Keep the pending file for this same identity's next session.
            }
        }
    }

    private companion object {
        const val CRASH_ROOT = "pending-crashes"
        const val UNOWNED_NAMESPACE = "unowned"
        const val PENDING_FILE = "pending-crash.log"
    }
}

private fun crashOwnerIdentity(
    deploymentIdentity: DeploymentIdentity,
    ownerUid: String,
): CrashOwnerIdentity {
    require(ownerUid.isNotBlank()) { "Crash owner uid must not be blank" }
    return CrashOwnerIdentity(
        serverBaseUrl = deploymentIdentity.httpBaseUrl,
        deploymentFingerprint = deploymentIdentity.fingerprint,
        uid = ownerUid,
    )
}

/** Stable, path-safe two-lane hash; raw server coordinates and uid never become path components. */
private fun stableCrashNamespace(value: String): String {
    var first = 1_125_899_906_842_597L
    var second = -7_046_029_254_386_353_131L
    value.forEach { char ->
        first = first * 31L + char.code
        second = (second xor char.code.toLong()) * 1_099_511_628_211L
    }
    return "${value.length}-${first.toString(36)}-${second.toString(36)}"
}

/** Entry point for process uncaught-exception handlers. */
fun flushPendingCrash(dataDir: File, content: String) {
    val fixedOwner = AppLog.ownerSnapshot()
    if (fixedOwner?.flushCrash(dataDir, content) != true) {
        CrashDumper(dataDir).flushPending(content)
    }
}
