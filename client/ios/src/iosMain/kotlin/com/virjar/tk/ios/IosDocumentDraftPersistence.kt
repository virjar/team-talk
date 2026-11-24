package com.virjar.tk.ios

import com.virjar.tk.app.navigation.feature.document.*
import com.virjar.tk.shared.client.platformDataDir
import com.virjar.tk.shared.client.privateAtomicTextFileStore
import com.virjar.tk.shared.platform.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.*

/** 进程级拥有的草稿写入器；编辑器准入阶段仅惰性替换有界快照，不在主线程执行磁盘 IO。 */
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

    /** 多个生命周期观察者共用同一次在途刷盘过程，每次编辑不会额外堆积等待任务。 */
    fun requestFlush(): Deferred<Boolean> = synchronized(stateLock) {
        controlling ?: draining ?: CompletableDeferred(failedOwners.isEmpty() && !controlFailed)
    }

    suspend fun awaitFlush(): Boolean = withTimeoutOrNull(barrierTimeoutMillis) { requestFlush().await() } == true
    override suspend fun awaitDurability(): Boolean = awaitFlush()
    /** 破坏性账号清理即使在最后一帧保存失败时也必须等待写入线程真正退出。 */
    suspend fun awaitQuiescence(): Boolean = withTimeoutOrNull(barrierTimeoutMillis) {
        // 控制屏障超时时，前序写入任务可能仍在收尾；每次完成后重新检查当前真实持有者，控制等待超时不能直接视作写入已退出。
        while (true) {
            val current = synchronized(stateLock) { controlling ?: draining } ?: break
            current.await()
        }
        true
    } == true

    /** 共享持久化契约的阻塞适配器；UI 生命周期应优先调用 [requestFlush]。 */
    override fun flush(): Boolean = runBlocking { awaitFlush() }

    override fun read(ownerKey: DocumentDraftOwnerKey, consume: (DocumentDraftRecordSource) -> Unit): DocumentDraftReadStatus =
        synchronized(admissionLock) {
            // 替换失败会完整保留上一版可读的原子清单；仍在进行的写入必须结束后再把记录暴露给恢复回调。
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
        // 控制屏障本身涵盖前序写入者，但绝不能等待自身，避免自死锁。
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
                // 所有编码、哈希校验、fsync 与旧文件回收均在进程写入线程内串行完成。
                val payload = next.payload()
                synchronized(ioLock) {
                    if (synchronized(stateLock) { pending.isCurrent(next) }) {
                        succeeded = storage.write(next.ownerKey) { payload }
                    }
                }
            } catch (_: Exception) {
                // 失败通过控制屏障上抛，不向调用方确认任何陈旧快照为已持久化。
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

/** 先落盘不可变内容记录，再以原子切换的 manifest 作为唯一提交点。 */
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
            // replaceText 在重命名完成后同步目录仍可能抛出异常；一旦进入替换阶段，即使失败也必须保留所有新写入记录，因为 manifest 可能已指向它们。
            manifestReplacementStarted = true
            file(ownerKey, "manifest.json").replaceText(buildJsonObject {
                put("manifest", snapshot.manifest); put("records", descriptors)
            }.toString(), MAX_STORAGE_MANIFEST_BYTES)
            // 与 Android/Desktop 一致：先落盘不可变记录、提交 manifest，再执行 tombstone 压缩与回收；失败的一代绝不能抹掉上一个可恢复点。
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
        // 先保留持久化的已删除标记，直到物理记录清理全部完成。
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
