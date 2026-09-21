package com.virjar.tk.ios

import com.virjar.tk.app.navigation.feature.document.*
import com.virjar.tk.shared.client.platformDataDir
import com.virjar.tk.shared.client.privateAtomicTextFileStore
import com.virjar.tk.shared.platform.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException

/** Immutable records are installed first; the atomic manifest is the sole commit point. */
internal class IosDocumentDraftPersistence : DocumentDraftPersistence {
    private val root = platformDataDir()
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
        var total = 0L
        val retained = mutableSetOf("manifest.json", "tombstones.json")
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
                    recordFile.replaceText(content, MAX_DOCUMENT_DRAFT_RECORD_BYTES.toLong())
                }
                put(record.key, buildJsonObject { put("name", name); put("digest", digest); put("size", bytes.size) })
            }
        }
        file(ownerKey, "manifest.json").replaceText(buildJsonObject {
            put("manifest", snapshot.manifest); put("records", descriptors)
        }.toString(), MAX_STORAGE_MANIFEST_BYTES)
        // Shrinking tombstones before publishing would revive an explicitly discarded draft after a crash.
        file(ownerKey, "tombstones.json").replaceText(
            JsonArray(retired.intersect(snapshot.activeRecoveryKeys).map(::JsonPrimitive)).toString(),
            MAX_DOCUMENT_DRAFT_MANIFEST_BYTES.toLong(),
        )
        val directory = directories(ownerKey).fold(root) { parent, child -> parent.resolve(child) }
        directory.listFiles()?.filter { it.name !in retained }?.forEach { check(it.delete()) }
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
