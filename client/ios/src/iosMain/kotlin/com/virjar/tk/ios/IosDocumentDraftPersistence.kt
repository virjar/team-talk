package com.virjar.tk.ios

import com.virjar.tk.app.navigation.feature.document.*
import com.virjar.tk.shared.client.platformDataDir
import com.virjar.tk.shared.client.privateAtomicTextFileStore
import com.virjar.tk.shared.platform.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.*

/** Process-owned writer. Editor admission only replaces a lazy, bounded snapshot. */
internal class IosDocumentDraftPersistence(
    private val storage: DocumentDraftPersistence = IosDocumentDraftStorage(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val barrierTimeoutMillis: Long = 15_000,
) : DocumentDraftPersistence {
    private val admissionLock = PlatformLock()
    private val stateLock = PlatformLock()
    private val ioLock = PlatformLock()
    private val pending = DocumentDraftPendingWrites(2)
    private val failedOwners = mutableSetOf<DocumentDraftOwnerKey>()
    private var controlFailed = false
    private var generation = 0L
    private var draining: CompletableDeferred<Boolean>? = null
    private var controlling: CompletableDeferred<Boolean>? = null

    init { require(barrierTimeoutMillis > 0) }

    override fun write(ownerKey: DocumentDraftOwnerKey, payload: () -> DocumentDraftPayload): Boolean =
        synchronized(admissionLock) {
            var start: CompletableDeferred<Boolean>? = null
            val admitted = synchronized(stateLock) state@ {
                if (draining == null && pending.isEmpty) pending.forgetIdleOwnersExcept()
                if (!pending.canAccept(ownerKey) || (ownerKey !in failedOwners && failedOwners.size >= 2)) {
                    return@state false
                }
                pending.put(ownerKey, nextGeneration(), payload)
                if (draining == null) {
                    start = CompletableDeferred()
                    draining = start
                }
                true
            }
            start?.let { completion -> scope.launch {
                try { drain(completion) }
                catch (failure: Throwable) {
                    synchronized(stateLock) {
                        pending.clear()
                        controlFailed = true
                        draining = null
                        completion.complete(false)
                    }
                    throw failure
                }
            } }
            admitted
        }

    /** The same in-flight drain is shared by lifecycle observers; no waiter task is added per edit. */
    fun requestFlush(): Deferred<Boolean> = synchronized(stateLock) {
        controlling ?: draining ?: CompletableDeferred(failedOwners.isEmpty() && !controlFailed)
    }

    suspend fun awaitFlush(): Boolean = withTimeoutOrNull(barrierTimeoutMillis) { requestFlush().await() } == true
    override suspend fun awaitDurability(): Boolean = awaitFlush()
    /** Destructive account cleanup needs writer exit even when preserving its last frame failed. */
    suspend fun awaitQuiescence(): Boolean = withTimeoutOrNull(barrierTimeoutMillis) {
        // A control can time out while its preceding writer is still finishing. Recheck the actual
        // owners after every completion; observing a failed control is not proof of writer exit.
        while (true) {
            val current = synchronized(stateLock) { controlling ?: draining } ?: break
            current.await()
        }
        true
    } == true

    /** Blocking adapter for the shared persistence contract; UI lifecycle uses requestFlush instead. */
    override fun flush(): Boolean = runBlocking { awaitFlush() }

    override fun read(ownerKey: DocumentDraftOwnerKey, consume: (DocumentDraftRecordSource) -> Unit): DocumentDraftReadStatus =
        synchronized(admissionLock) {
            // A failed replacement leaves the previous atomic manifest readable. A still-running
            // write must finish before exposing its records to the restoration callback.
            if (awaitDrain() == null) DocumentDraftReadStatus.RETRYABLE
            else synchronized(ioLock) { storage.read(ownerKey, consume) }
        }

    override fun tombstone(ownerKey: DocumentDraftOwnerKey, recoveryKeys: Set<String>): Boolean =
        control(ownerKey, discard = false) { storage.tombstone(ownerKey, recoveryKeys) }

    override fun delete(ownerKey: DocumentDraftOwnerKey): Boolean =
        control(ownerKey, discard = true) { storage.delete(ownerKey) }

    override fun clearAll(): Boolean = synchronized(admissionLock) {
        val completion = beginControl { pending.clear() }
        var succeeded = false
        try {
            if (awaitDrain() != null) succeeded = synchronized(ioLock) { storage.clearAll() }
            succeeded
        } finally {
            synchronized(stateLock) {
                controlFailed = !succeeded
                if (succeeded) failedOwners.clear()
                controlling = null
                completion.complete(succeeded)
            }
        }
    }

    private fun control(owner: DocumentDraftOwnerKey, discard: Boolean, action: () -> Boolean): Boolean =
        synchronized(admissionLock) {
            val completion = beginControl { if (discard) pending.invalidate(owner, nextGeneration()) }
            var succeeded = false
            try {
                if (awaitDrain() != null) succeeded = synchronized(ioLock) { action() }
                succeeded
            } finally {
                synchronized(stateLock) {
                    if (succeeded && discard) failedOwners.remove(owner)
                    if (!succeeded) failedOwners += owner
                    controlling = null
                    completion.complete(succeeded && failedOwners.isEmpty() && !controlFailed)
                }
            }
        }

    private fun beginControl(invalidate: () -> Unit): CompletableDeferred<Boolean> = synchronized(stateLock) {
        check(controlling == null)
        invalidate()
        CompletableDeferred<Boolean>().also { controlling = it }
    }

    private fun awaitDrain(): Boolean? {
        // A control barrier includes the preceding writer; it must never await itself.
        val current = synchronized(stateLock) { draining } ?: return true
        return runBlocking { withTimeoutOrNull(barrierTimeoutMillis) { current.await() } }
    }

    private fun drain(completion: CompletableDeferred<Boolean>) {
        while (true) {
            val next = synchronized(stateLock) {
                pending.take().also { next ->
                    if (next == null) {
                        draining = null
                        completion.complete(failedOwners.isEmpty() && !controlFailed)
                    }
                }
            } ?: return
            var succeeded = false
            try {
                // All encoding, hashing, fsync and reclamation happen on the process writer.
                val payload = next.payload()
                synchronized(ioLock) {
                    if (synchronized(stateLock) { pending.isCurrent(next) }) {
                        succeeded = storage.write(next.ownerKey) { payload }
                    }
                }
            } catch (_: Exception) {
                // The barrier reports failure; no stale snapshot is acknowledged as durable.
            } finally {
                synchronized(stateLock) {
                    if (pending.isCurrent(next)) {
                        if (succeeded) failedOwners.remove(next.ownerKey) else failedOwners += next.ownerKey
                    }
                    pending.complete(next)
                }
            }
        }
    }

    private fun nextGeneration(): Long {
        check(generation < Long.MAX_VALUE) { "Document draft generation exhausted" }
        return ++generation
    }
}

/** Immutable records are installed first; the atomic manifest is the sole commit point. */
internal class IosDocumentDraftStorage(private val root: PlatformFile = platformDataDir()) : DocumentDraftPersistence {
    private val lock = PlatformLock()
    private var lastWriteSucceeded = true

    private fun directories(owner: DocumentDraftOwnerKey): List<String> {
        require(owner.uid.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid draft owner" }
        return listOf("document-drafts", owner.deploymentFingerprint, owner.datasetId, owner.uid)
    }
    private fun file(owner: DocumentDraftOwnerKey, name: String) =
        privateAtomicTextFileStore(root, directories(owner), name)
    private fun tombstones(owner: DocumentDraftOwnerKey): Set<String> =
        file(owner, "tombstones.json").readText(MAX_DOCUMENT_DRAFT_MANIFEST_BYTES.toLong())?.let { text ->
            Json.parseToJsonElement(text).jsonArray.map { it.jsonPrimitive.content }.toSet().also(::validateKeys)
        }.orEmpty()
    private fun validateKeys(keys: Set<String>) {
        require(keys.size <= MAX_DOCUMENT_DRAFT_RECORDS * 4 && keys.all { it.matches(Regex("[a-z0-9-]{1,128}")) })
    }

    override fun read(ownerKey: DocumentDraftOwnerKey, consume: (DocumentDraftRecordSource) -> Unit): DocumentDraftReadStatus = synchronized(lock) {
        try {
            val encoded = file(ownerKey, "manifest.json").readText(MAX_STORAGE_MANIFEST_BYTES)
                ?: return@synchronized DocumentDraftReadStatus.ABSENT
            val stored = Json.parseToJsonElement(encoded).jsonObject
            if (stored["deleted"]?.jsonPrimitive?.booleanOrNull == true) return@synchronized DocumentDraftReadStatus.ABSENT
            val manifest = stored.getValue("manifest").jsonPrimitive.content
            require(manifest.encodeToByteArray().size <= MAX_DOCUMENT_DRAFT_MANIFEST_BYTES)
            val records = stored.getValue("records").jsonObject
            require(records.size <= MAX_DOCUMENT_DRAFT_RECORDS)
            val retired = tombstones(ownerKey)
            consume(object : DocumentDraftRecordSource {
                override val manifest = manifest
                override val tombstones = retired
                override fun recordByteCount(key: String): Long? = records[key]?.jsonObject?.get("size")?.jsonPrimitive?.longOrNull
                override fun readRecord(key: String): String? {
                    val descriptor = records[key]?.jsonObject ?: return null
                    val name = descriptor.getValue("name").jsonPrimitive.content
                    require(name.matches(Regex("record-[a-f0-9]{64}\\.json")))
                    val content = file(ownerKey, name).readText(MAX_DOCUMENT_DRAFT_RECORD_BYTES.toLong()) ?: return null
                    val bytes = content.encodeToByteArray()
                    return content.takeIf { bytes.size.toLong() == recordByteCount(key) &&
                        platformSha256Hex(bytes) == descriptor.getValue("digest").jsonPrimitive.content }
                }
            })
            DocumentDraftReadStatus.AVAILABLE
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { DocumentDraftReadStatus.RETRYABLE }
    }

    override fun write(ownerKey: DocumentDraftOwnerKey, payload: () -> DocumentDraftPayload): Boolean = mutate {
        val snapshot = payload()
        require(snapshot.manifest.encodeToByteArray().size <= MAX_DOCUMENT_DRAFT_MANIFEST_BYTES)
        val retired = tombstones(ownerKey)
        val directory = directories(ownerKey).fold(root) { parent, child -> parent.resolve(child) }
        val originalNames = directory.listFiles().orEmpty().mapTo(mutableSetOf()) { it.name }
        val installed = mutableSetOf<String>()
        val retained = mutableSetOf("manifest.json", "tombstones.json")
        var manifestReplacementStarted = false
        try {
            var total = 0L
            val descriptors = buildJsonObject {
                snapshot.records.forEach { record ->
                    val content = record.payload()
                    val bytes = content.encodeToByteArray()
                    require(bytes.size <= MAX_DOCUMENT_DRAFT_RECORD_BYTES)
                    total += bytes.size
                    require(total <= MAX_TOTAL_DOCUMENT_DRAFT_RECORD_BYTES)
                    val digest = platformSha256Hex(bytes)
                    val name = "record-$digest.json"
                    retained += name
                    val recordFile = file(ownerKey, name)
                    val existing = recordFile.readText(MAX_DOCUMENT_DRAFT_RECORD_BYTES.toLong())
                    if (existing == null || platformSha256Hex(existing.encodeToByteArray()) != digest) {
                        if (name !in originalNames) installed += name
                        recordFile.replaceText(content, MAX_DOCUMENT_DRAFT_RECORD_BYTES.toLong())
                    }
                    put(record.key, buildJsonObject { put("name", name); put("digest", digest); put("size", bytes.size) })
                }
            }
            // replaceText can throw while syncing the directory after rename. Once replacement
            // begins, retain every new record even on failure: the manifest may already refer to it.
            manifestReplacementStarted = true
            file(ownerKey, "manifest.json").replaceText(buildJsonObject {
                put("manifest", snapshot.manifest); put("records", descriptors)
            }.toString(), MAX_STORAGE_MANIFEST_BYTES)
            // As on Android/Desktop: immutable records, manifest commit, then tombstone compaction
            // and reclamation. A failed generation must never erase the previous recovery point.
            file(ownerKey, "tombstones.json").replaceText(
                JsonArray(retired.intersect(snapshot.activeRecoveryKeys).map(::JsonPrimitive)).toString(),
                MAX_DOCUMENT_DRAFT_MANIFEST_BYTES.toLong(),
            )
            directory.listFiles()?.filter { it.name !in retained }?.forEach { check(it.delete()) }
        } catch (failure: Throwable) {
            if (!manifestReplacementStarted) installed.forEach { name ->
                try { check(file(ownerKey, name).delete()) }
                catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            }
            throw failure
        }
    }

    override fun tombstone(ownerKey: DocumentDraftOwnerKey, recoveryKeys: Set<String>): Boolean = mutate {
        val retired = tombstones(ownerKey) + recoveryKeys
        validateKeys(retired)
        file(ownerKey, "tombstones.json").replaceText(JsonArray(retired.map(::JsonPrimitive)).toString(),
            MAX_DOCUMENT_DRAFT_MANIFEST_BYTES.toLong())
    }

    override fun delete(ownerKey: DocumentDraftOwnerKey): Boolean = mutate {
        // Keep a durable deleted marker until physical record cleanup has completed.
        file(ownerKey, "manifest.json").replaceText("{\"deleted\":true}", MAX_STORAGE_MANIFEST_BYTES)
        val directory = directories(ownerKey).fold(root) { parent, child -> parent.resolve(child) }
        directory.listFiles()?.filter { it.name != "manifest.json" }?.forEach { check(it.delete()) }
    }

    override fun clearAll(): Boolean = mutate {
        val directory = root.resolve("document-drafts")
        check(!directory.exists() || directory.deleteRecursively())
    }
    override fun flush(): Boolean = synchronized(lock) { lastWriteSucceeded }
    private fun mutate(action: () -> Unit): Boolean = synchronized(lock) {
        try { action(); lastWriteSucceeded = true; true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { lastWriteSucceeded = false; false }
    }
    private companion object { const val MAX_STORAGE_MANIFEST_BYTES = 4L * 1024 * 1024 }
}
