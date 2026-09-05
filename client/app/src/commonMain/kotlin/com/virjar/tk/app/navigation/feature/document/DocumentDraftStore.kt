package com.virjar.tk.app.navigation.feature.document

import com.virjar.tk.protocol.payload.SyncDatasetIdPolicy
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class DocumentDraftReadStatus { ABSENT, AVAILABLE, RETRYABLE }

/** 一份清单视图，其记录被惰性读取，且同一时间至多读取一个有界记录。 */
interface DocumentDraftRecordSource {
    val manifest: String
    val tombstones: Set<String>
    /** 确切的持久化 UTF-8 字节数，无需读取或分配记录即可获得。 */
    fun recordByteCount(key: String): Long?
    fun readRecord(key: String): String?
}

class DocumentDraftRecord(
    val key: String,
    val payload: () -> String,
) {
    init {
        require(key.isDocumentDraftRecordKey()) { "Invalid document draft record key" }
    }
}

class DocumentDraftPayload(
    val manifest: String,
    val records: List<DocumentDraftRecord>,
    /** 记录/命令身份仍然存活于这份清单中；用于压缩旧的墓碑。 */
    val activeRecoveryKeys: Set<String>,
) {
    init {
        require(records.size <= MAX_DOCUMENT_DRAFT_RECORDS) { "Too many document draft records" }
        require(activeRecoveryKeys.size <= MAX_DOCUMENT_DRAFT_RECORDS) {
            "Too many active document draft recovery keys"
        }
        require(records.map(DocumentDraftRecord::key).distinct().size == records.size) {
            "Duplicate document draft record key"
        }
        require(activeRecoveryKeys.all(String::isDocumentDraftRecordKey)) {
            "Invalid active document draft recovery key"
        }
        require(records.all { it.key in activeRecoveryKeys }) {
            "Document draft records must be active recovery identities"
        }
    }
}

class DocumentDraftReadRetryableException(
    cause: Throwable? = null,
) : IllegalStateException("Document draft storage is temporarily unavailable", cause)

/**
 * 用于一个小型原子清单加独立有界不可变记录的平台存储。
 *
 * [write] 可以在 UI 线程之外合并工作。[tombstone]、[delete] 和 [clearAll] 必须
 * 与挂起的写入线性化，这样显式的丢弃/注销就绝不可能复活旧数据。
 */
interface DocumentDraftPersistence {
    fun read(
        ownerKey: DocumentDraftOwnerKey,
        consume: (DocumentDraftRecordSource) -> Unit,
    ): DocumentDraftReadStatus
    fun write(ownerKey: DocumentDraftOwnerKey, payload: () -> DocumentDraftPayload): Boolean
    /** 等待本次调用之前的每一个已接受的修改都持久化或终止性失败。 */
    fun flush(): Boolean
    /**
     * 恰好当每一个被请求的身份都被持久地退役时返回 true。在那个不可逆点之后，
     * 发布一份无关热快照的失败必须由 [flush] 暴露，而不是把这个结果改回 false。
     */
    fun tombstone(ownerKey: DocumentDraftOwnerKey, recoveryKeys: Set<String>): Boolean
    fun delete(ownerKey: DocumentDraftOwnerKey): Boolean
    fun clearAll(): Boolean
}

const val MAX_DOCUMENT_DRAFT_RECORDS = 1024
const val MAX_DOCUMENT_DRAFT_RECORD_BYTES = 16 * 1024 * 1024
const val MAX_TOTAL_DOCUMENT_DRAFT_RECORD_BYTES = 32L * 1024 * 1024
const val MAX_DOCUMENT_DRAFT_MANIFEST_BYTES = 2 * 1024 * 1024
private val DOCUMENT_DRAFT_RECORD_KEY = Regex("[a-z0-9-]{1,128}")
private fun String.isDocumentDraftRecordKey(): Boolean = matches(DOCUMENT_DRAFT_RECORD_KEY)

internal fun DocumentTabState.draftRecoveryKey(): String = "tab-$recoveryId"

internal fun PendingDocumentCreateCommand.draftRecoveryKey(): String =
    "document-command-$documentId"

internal fun DocumentSpaceCreateRequest.draftRecoveryKey(): String = "space-command-$spaceId"

/** 一份本地草稿属于一个确切权威部署数据集中的一个账号。 */
data class DocumentDraftOwnerKey(
    val deploymentFingerprint: String,
    val datasetId: String,
    val uid: String,
) {
    init {
        require(
            deploymentFingerprint.length == SHA256_HEX_LENGTH &&
                deploymentFingerprint.all { it in '0'..'9' || it in 'a'..'f' },
        ) { "Deployment fingerprint must be lowercase SHA-256" }
        SyncDatasetIdPolicy.requireValid(datasetId)
        require(uid.isNotBlank() && uid.length <= MAX_UID_LENGTH && '\u0000' !in uid) {
            "Document draft owner uid is invalid"
        }
    }

    private companion object {
        const val SHA256_HEX_LENGTH = 64
        const val MAX_UID_LENGTH = 64
    }
}

/**
 * 一个按部署-数据集-uid 作用域的延续存储，用于未保存的文档 tab 和可靠的文档命令。
 *
 * 内存快照是 session 存活期间使用的热副本。Android 额外注入一个 AtomicFile 支撑的
 * [DocumentDraftPersistence]，这样进程重启可以恢复同样的状态，而无需把大块 Markdown 正文
 * 放进 SavedState/Bundle。快照本身不包含任何 Compose 或平台对象。
 */
class DocumentDraftStore(
    private val persistence: DocumentDraftPersistence,
) {
    private val lock = Any()
    private var ownerKey: DocumentDraftOwnerKey? = null
    private var snapshot: DocumentWorkspaceDraftSnapshot? = null
    /** 观察到 ABSENT 或已成功删除；绝不只从内存快照推断。 */
    private var knownEmptyOwnerKey: DocumentDraftOwnerKey? = null
    private var retiredOwnerKey: DocumentDraftOwnerKey? = null

    internal fun restore(key: DocumentDraftOwnerKey): DocumentWorkspaceDraftSnapshot? = synchronized(lock) {
        if (retiredOwnerKey == key) return@synchronized null
        snapshot?.takeIf { ownerKey == key }?.let { return@synchronized it }

        var decoded: DecodedDocumentDraft? = null
        var sourceConsumed = false
        val readStatus = try {
            persistence.read(key) { source ->
                sourceConsumed = true
                decoded = decodeSnapshot(source)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (retryable: DocumentDraftReadRetryableException) {
            throw retryable
        } catch (failure: Exception) {
            throw DocumentDraftReadRetryableException(failure)
        }
        when (readStatus) {
            DocumentDraftReadStatus.RETRYABLE -> throw DocumentDraftReadRetryableException()
            DocumentDraftReadStatus.ABSENT -> {
                ownerKey = key
                snapshot = null
                knownEmptyOwnerKey = key
                return@synchronized null
            }
            DocumentDraftReadStatus.AVAILABLE -> check(sourceConsumed) {
                "Available document draft storage must expose one record source"
            }
        }

        val raw = decoded?.snapshot
        val restored = raw?.normalized()
        ownerKey = key
        snapshot = restored
        when {
            restored == null -> {
                val deleted = safely { persistence.delete(key) } == true
                knownEmptyOwnerKey = key.takeIf { deleted }
            }
            decoded?.requiresRewrite == true || restored != raw -> {
                knownEmptyOwnerKey = null
                safely { persistence.write(key) { encodeSnapshot(restored) } }
            }
            else -> knownEmptyOwnerKey = null
        }
        restored
    }

    internal fun save(
        key: DocumentDraftOwnerKey,
        tabs: List<DocumentTabState>,
        activeTabId: String?,
        selectedSpaceId: String?,
        pendingSpaceCreates: List<DocumentSpaceCreateRequest> = emptyList(),
        pendingDocumentCreates: List<PendingDocumentCreateCommand> = emptyList(),
        pendingDestructiveIntents: List<DocumentDestructiveIntent> = emptyList(),
    ): Boolean = synchronized(lock) {
        // 显式注销在旧 Compose 树销毁之前就退役了这个 store 实例。迟到的编辑器回调
        // 可能仍然到达它旧的 DocumentWorkspaceFeature，但它绝不能复活注销已经删除的草稿。
        // 之后的登录会收到一个新的 store。
        if (retiredOwnerKey == key) return@synchronized false
        val draftTabs = tabs
            .asSequence()
            .filter { it.dirty || it.creating }
            .distinctBy { it.instanceId }
            .distinctBy { it.tabId }
            .toList()

        val candidate = DocumentWorkspaceDraftSnapshot(
            tabs = draftTabs,
            activeTabInstanceId = draftTabs.firstOrNull { it.tabId == activeTabId }?.instanceId,
            selectedSpaceId = selectedSpaceId,
            pendingSpaceCreates = pendingSpaceCreates,
            pendingDocumentCreates = pendingDocumentCreates,
            pendingDestructiveIntents = pendingDestructiveIntents,
        ).normalized()
        // 这个检查刻意在构造惰性平台载荷之前执行。否则异步编码器可能在 save()
        // 已经报告准入之后拒绝一个状态，把之前的清单留成一个静默过期的恢复点。
        if (candidate != null && !candidate.hasBoundedPersistenceShape()) {
            return@synchronized false
        }
        if (ownerKey != key) {
            ownerKey = key
            snapshot = null
            if (knownEmptyOwnerKey != key) knownEmptyOwnerKey = null
        }
        if (candidate == null) {
            snapshot = null
            if (knownEmptyOwnerKey == key) return@synchronized true
            val deleted = safely { persistence.delete(key) } == true
            knownEmptyOwnerKey = key.takeIf { deleted }
            return@synchronized deleted
        }

        knownEmptyOwnerKey = null
        snapshot = candidate
        val persistedSnapshot = requireNotNull(snapshot)
        safely { persistence.write(key) { encodeSnapshot(persistedSnapshot) } } == true
    }

    /** 在其可见状态被显式丢弃之前，持久地压制身份。 */
    internal fun tombstone(key: DocumentDraftOwnerKey, recoveryKeys: Set<String>): Boolean =
        synchronized(lock) {
            if (retiredOwnerKey == key) return@synchronized false
            if (recoveryKeys.isEmpty()) return@synchronized true
            if (recoveryKeys.size > MAX_DOCUMENT_DRAFT_RECORDS ||
                recoveryKeys.any { !it.isDocumentDraftRecordKey() }
            ) return@synchronized false
            if (knownEmptyOwnerKey == key) knownEmptyOwnerKey = null
            safely { persistence.tombstone(key, recoveryKeys.toSet()) } == true
        }

    internal fun clear(key: DocumentDraftOwnerKey? = null) = synchronized(lock) {
        if (key == null) {
            safely { persistence.clearAll() }
            ownerKey = null
            snapshot = null
            knownEmptyOwnerKey = null
            return@synchronized
        }
        val deleted = safely { persistence.delete(key) } == true
        knownEmptyOwnerKey = key.takeIf { deleted }
        if (ownerKey == key) {
            ownerKey = null
            snapshot = null
        }
    }

    /** 阻塞式持久化屏障；调用方必须在 UI/event-loop 线程之外调用它。 */
    internal fun flush(): Boolean = safely(persistence::flush) == true

    /** 在这个 session 拥有的 store 上，永久拒绝之后针对 [key] 的写入。 */
    internal fun clearAndRetire(key: DocumentDraftOwnerKey) = synchronized(lock) {
        retiredOwnerKey = key
        var deletionFailure: Throwable? = null
        val deleted = try {
            persistence.delete(key)
        } catch (failure: Throwable) {
            deletionFailure = failure
            false
        }
        if (ownerKey == key) {
            ownerKey = null
            snapshot = null
        }
        knownEmptyOwnerKey = key.takeIf { deleted }
        if (!deleted) {
            throw deletionFailure ?: IllegalStateException(
                "Document draft retirement could not durably seal owner deletion",
            )
        }
    }

    private fun encodeSnapshot(value: DocumentWorkspaceDraftSnapshot): DocumentDraftPayload {
        val tabRecords = value.tabs.map { tab ->
            val persisted = PersistedDocumentTabDraft.from(tab)
            DocumentDraftRecord(tab.draftRecoveryKey()) {
                payloadJson.encodeToString(persisted)
            }
        }
        val documentCommandRecords = value.pendingDocumentCreates.map { command ->
            val persisted = PersistedDocumentCreateCommand.from(command)
            DocumentDraftRecord(command.draftRecoveryKey()) {
                payloadJson.encodeToString(persisted)
            }
        }
        val spaceRequests = value.pendingSpaceCreates.map(PersistedDocumentSpaceCreateRequest::from)
        val destructiveIntents = value.pendingDestructiveIntents
            .map(PersistedDocumentDestructiveIntent::from)
        val manifest = PersistedDocumentWorkspaceManifest(
            schemaVersion = DOCUMENT_DRAFT_SCHEMA_VERSION,
            tabRecordKeys = tabRecords.map(DocumentDraftRecord::key),
            pendingDocumentRecordKeys = documentCommandRecords.map(DocumentDraftRecord::key),
            activeTabInstanceId = value.activeTabInstanceId,
            selectedSpaceId = value.selectedSpaceId,
            pendingSpaceCreates = spaceRequests,
            pendingDestructiveIntents = destructiveIntents,
        )
        val records = tabRecords + documentCommandRecords
        val activeKeys = buildSet {
            records.forEach { add(it.key) }
            value.pendingSpaceCreates.forEach { add(it.draftRecoveryKey()) }
            value.pendingDestructiveIntents.forEach { add(it.draftRecoveryKey()) }
        }
        return DocumentDraftPayload(
            manifest = payloadJson.encodeToString(manifest),
            records = records,
            activeRecoveryKeys = activeKeys,
        )
    }

    private fun decodeSnapshot(source: DocumentDraftRecordSource): DecodedDocumentDraft? {
        val manifest = safely {
            payloadJson.decodeFromString<PersistedDocumentWorkspaceManifest>(source.manifest)
        } ?: return null
        if (manifest.schemaVersion != DOCUMENT_DRAFT_SCHEMA_VERSION || !manifest.hasBoundedIdentityCount()) {
            return null
        }
        val tombstones = source.tombstones
        if (tombstones.size > MAX_DOCUMENT_DRAFT_RECORDS ||
            tombstones.any { !it.isDocumentDraftRecordKey() }
        ) return null
        var requiresRewrite = false
        val observedRecordKeys = mutableSetOf<String>()
        fun readableManifestKeys(keys: List<String>): List<String> = buildList {
            keys.forEach { recordKey ->
                if (!recordKey.isDocumentDraftRecordKey() ||
                    !observedRecordKeys.add(recordKey) || recordKey in tombstones
                ) {
                    requiresRewrite = true
                } else {
                    add(recordKey)
                }
            }
        }
        val candidateTabKeys = readableManifestKeys(manifest.tabRecordKeys)
        val candidateCommandKeys = readableManifestKeys(manifest.pendingDocumentRecordKeys)

        // 在加载第一个正文之前，先预检完整的描述符集合。仅靠每条记录的限制
        // 允许一个形式上有效的多 GB 快照，因此不能约束恢复内存。缺失/损坏的描述符保持隔离；
        // 超出预算的聚合在不分配任何记录正文的情况下使快照失效。
        var totalRecordBytes = 0L
        val readableRecordKeys = buildSet {
            (candidateTabKeys + candidateCommandKeys).forEach { recordKey ->
                val byteCount = recordByteCount(source, recordKey)
                if (byteCount == null || byteCount !in 0L..MAX_DOCUMENT_DRAFT_RECORD_BYTES.toLong()) {
                    requiresRewrite = true
                    return@forEach
                }
                totalRecordBytes += byteCount
                if (totalRecordBytes > MAX_TOTAL_DOCUMENT_DRAFT_RECORD_BYTES) return null
                add(recordKey)
            }
        }
        val tabs = buildList {
            candidateTabKeys.filter(readableRecordKeys::contains).forEach { recordKey ->
                val tab = decodeRecord<PersistedDocumentTabDraft>(source, recordKey)
                    ?.toTab()
                    ?.takeIf { it.draftRecoveryKey() == recordKey }
                if (tab == null) requiresRewrite = true else add(tab)
            }
        }
        val commands = buildList {
            candidateCommandKeys.filter(readableRecordKeys::contains).forEach { recordKey ->
                val command = decodeRecord<PersistedDocumentCreateCommand>(source, recordKey)
                    ?.toCommand()
                    ?.takeIf { it.draftRecoveryKey() == recordKey }
                if (command == null) requiresRewrite = true else add(command)
            }
        }
        val spaces = buildList {
            manifest.pendingSpaceCreates.forEach { persisted ->
                val request = persisted.toRequest()
                if (request.draftRecoveryKey() in tombstones) {
                    requiresRewrite = true
                } else {
                    add(request)
                }
            }
        }
        val destructiveIntents = buildList {
            manifest.pendingDestructiveIntents.forEach { persisted ->
                val normalized = persisted.toIntent()?.normalized()
                if (normalized == null || normalized.draftRecoveryKey() in tombstones) {
                    requiresRewrite = true
                } else {
                    add(normalized)
                }
            }
        }
        return DecodedDocumentDraft(
            snapshot = DocumentWorkspaceDraftSnapshot(
                tabs = tabs,
                activeTabInstanceId = manifest.activeTabInstanceId,
                selectedSpaceId = manifest.selectedSpaceId,
                pendingSpaceCreates = spaces,
                pendingDocumentCreates = commands,
                pendingDestructiveIntents = destructiveIntents,
            ),
            requiresRewrite = requiresRewrite,
        )
    }

    private inline fun <reified T> decodeRecord(
        source: DocumentDraftRecordSource,
        recordKey: String,
    ): T? {
        val payload = try {
            source.readRecord(recordKey)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (retryable: DocumentDraftReadRetryableException) {
            throw retryable
        } catch (failure: Exception) {
            throw DocumentDraftReadRetryableException(failure)
        } ?: return null
        return safely { payloadJson.decodeFromString<T>(payload) }
    }

    private fun recordByteCount(source: DocumentDraftRecordSource, recordKey: String): Long? = try {
        source.recordByteCount(recordKey)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (retryable: DocumentDraftReadRetryableException) {
        throw retryable
    } catch (failure: Exception) {
        throw DocumentDraftReadRetryableException(failure)
    }

    private inline fun <T> safely(action: () -> T): T? = try {
        action()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private companion object {
        val payloadJson = Json {
            encodeDefaults = true
        }
    }
}

private data class DecodedDocumentDraft(
    val snapshot: DocumentWorkspaceDraftSnapshot,
    val requiresRewrite: Boolean,
)

/** 不可变的、平台无关的状态，足以恢复每一个未保存的文档实例。 */
internal data class DocumentWorkspaceDraftSnapshot(
    val tabs: List<DocumentTabState>,
    val activeTabInstanceId: Long?,
    val selectedSpaceId: String?,
    val pendingSpaceCreates: List<DocumentSpaceCreateRequest> = emptyList(),
    val pendingDocumentCreates: List<PendingDocumentCreateCommand> = emptyList(),
    val pendingDestructiveIntents: List<DocumentDestructiveIntent> = emptyList(),
)

/**
 * 热 UI 快照的低开销、零分配准入上限。
 *
 * kotlinx.serialization 可能把一个 UTF-16 码元转义为 `\\uXXXX`，所以每个字符六字节
 * 是一个保守的上界，可以从 String.length 计算，而无需在每次按键时扫描
 * 数 MB 的编辑器正文。平台存储仍然会在顺序编码每条记录时校验确切的 UTF-8 字节数。
 */
private fun DocumentWorkspaceDraftSnapshot.hasBoundedPersistenceShape(): Boolean {
    val identityCount = tabs.size + pendingSpaceCreates.size + pendingDocumentCreates.size +
        pendingDestructiveIntents.size
    if (identityCount > MAX_DOCUMENT_DRAFT_RECORDS) return false
    if (pendingDestructiveIntents.size > MAX_DOCUMENT_DESTRUCTIVE_INTENTS) return false

    var totalRecordBytes = 0L
    tabs.forEach { tab ->
        val estimate = tab.estimatedPersistedRecordBytes()
        if (estimate > MAX_DOCUMENT_DRAFT_RECORD_BYTES) return false
        totalRecordBytes += estimate
        if (totalRecordBytes > MAX_TOTAL_DOCUMENT_DRAFT_RECORD_BYTES) return false
    }
    pendingDocumentCreates.forEach { command ->
        val estimate = command.estimatedPersistedRecordBytes()
        if (estimate > MAX_DOCUMENT_DRAFT_RECORD_BYTES) return false
        totalRecordBytes += estimate
        if (totalRecordBytes > MAX_TOTAL_DOCUMENT_DRAFT_RECORD_BYTES) return false
    }
    return estimatedManifestBytes() <= MAX_DOCUMENT_DRAFT_MANIFEST_BYTES
}

private fun DocumentWorkspaceDraftSnapshot.estimatedManifestBytes(): Long {
    var bytes = JSON_OBJECT_FIXED_ESTIMATE_BYTES
    tabs.forEach { bytes += it.draftRecoveryKey().estimatedJsonStringBytes() }
    pendingDocumentCreates.forEach { bytes += it.draftRecoveryKey().estimatedJsonStringBytes() }
    bytes += selectedSpaceId.estimatedJsonStringBytes()
    pendingSpaceCreates.forEach { request ->
        bytes += JSON_OBJECT_FIXED_ESTIMATE_BYTES
        bytes += request.intent.name.estimatedJsonStringBytes()
        bytes += request.intent.description.estimatedJsonStringBytes()
        bytes += request.spaceId.estimatedJsonStringBytes()
    }
    pendingDestructiveIntents.forEach { intent ->
        bytes += JSON_OBJECT_FIXED_ESTIMATE_BYTES
        bytes += intent.operationId.estimatedJsonStringBytes()
        bytes += intent.spaceId.estimatedJsonStringBytes()
        if (intent is PendingDocumentLeafDeleteIntent) {
            bytes += intent.documentId.estimatedJsonStringBytes()
            bytes += intent.parentId.estimatedJsonStringBytes()
        }
    }
    return bytes
}

private fun DocumentTabState.estimatedPersistedRecordBytes(): Long {
    var bytes = JSON_OBJECT_FIXED_ESTIMATE_BYTES
    bytes += tabId.estimatedJsonStringBytes()
    bytes += recoveryId.estimatedJsonStringBytes()
    bytes += documentId.estimatedJsonStringBytes()
    bytes += spaceId.estimatedJsonStringBytes()
    bytes += parentId.estimatedJsonStringBytes()
    ancestorIds.forEach { bytes += it.estimatedJsonStringBytes() }
    bytes += savedTitle.estimatedJsonStringBytes()
    bytes += savedMarkdown.estimatedJsonStringBytes()
    bytes += draftTitle.estimatedJsonStringBytes()
    bytes += draftMarkdown.estimatedJsonStringBytes()
    return bytes
}

private fun PendingDocumentCreateCommand.estimatedPersistedRecordBytes(): Long =
    JSON_OBJECT_FIXED_ESTIMATE_BYTES +
        documentId.estimatedJsonStringBytes() +
        spaceId.estimatedJsonStringBytes() +
        parentId.estimatedJsonStringBytes() +
        title.estimatedJsonStringBytes() +
        markdown.estimatedJsonStringBytes()

private fun String?.estimatedJsonStringBytes(): Long =
    if (this == null) JSON_NULL_ESTIMATE_BYTES else JSON_STRING_QUOTES_BYTES + length * 6L

private const val JSON_OBJECT_FIXED_ESTIMATE_BYTES = 1024L
private const val JSON_STRING_QUOTES_BYTES = 2L
private const val JSON_NULL_ESTIMATE_BYTES = 4L

/** 在恢复的快照成为存活的 feature 状态之前，重新校验它。 */
internal fun DocumentWorkspaceDraftSnapshot.normalized(): DocumentWorkspaceDraftSnapshot? {
    val normalizedTabs = tabs
        .asSequence()
        .filter { it.dirty || it.creating }
        .filter { it.instanceId in 1L until Long.MAX_VALUE }
        .filter { it.tabId.isNotBlank() && it.spaceId.isNotBlank() }
        .filter { it.recoveryId.isCanonicalUuid() }
        .filter { tab ->
            if (tab.creating) {
                !tab.remoteMissing && tab.documentId == null && tab.revision == null &&
                    tab.tabId.isCanonicalUuid()
            } else {
                tab.documentId != null && tab.revision != null && (!tab.remoteMissing || tab.dirty)
            }
        }
        .distinctBy { it.instanceId }
        .distinctBy { it.tabId }
        .distinctBy { it.recoveryId }
        // 持久化拥有可恢复的正文和写入身份，从不拥有服务器路径租约。
        // 保留 parent/ancestor ID 对稳定的挂起创建命令是必要的，但在一次新的服务器校验之前，
        // 任何恢复的 tab 都不得把这些提示暴露给导航。
        .map { tab -> if (tab.pathResolved) tab.copy(pathResolved = false) else tab }
        .toList()
    val normalizedSpaceCreates = pendingSpaceCreates
        .mapNotNull(DocumentSpaceCreateRequest::normalized)
        .distinct()
        .let(::retainUnambiguousSpaceCreateRequests)
    val normalizedDocumentCreates = pendingDocumentCreates
        .mapNotNull(PendingDocumentCreateCommand::normalized)
        .distinct()
        .let { commands -> retainUnambiguousDocumentCreateCommands(commands, normalizedTabs) }
    val normalizedDestructiveIntents = normalizeDocumentDestructiveIntents(
        pendingDestructiveIntents,
    ) ?: return null
    if (normalizedTabs.isEmpty() && normalizedSpaceCreates.isEmpty() &&
        normalizedDestructiveIntents.isEmpty()
    ) return null

    val activeInstanceId = activeTabInstanceId
        ?.takeIf { instanceId -> normalizedTabs.any { it.instanceId == instanceId } }
    return copy(
        tabs = normalizedTabs,
        activeTabInstanceId = activeInstanceId,
        selectedSpaceId = selectedSpaceId?.takeIf(String::isNotBlank),
        pendingSpaceCreates = normalizedSpaceCreates,
        pendingDocumentCreates = normalizedDocumentCreates,
        pendingDestructiveIntents = normalizedDestructiveIntents,
    )
}

private fun retainUnambiguousSpaceCreateRequests(
    requests: List<DocumentSpaceCreateRequest>,
): List<DocumentSpaceCreateRequest> {
    val intentCounts = requests.groupingBy(DocumentSpaceCreateRequest::intent).eachCount()
    val idCounts = requests.groupingBy(DocumentSpaceCreateRequest::spaceId).eachCount()
    return requests.filter { intentCounts[it.intent] == 1 && idCounts[it.spaceId] == 1 }
}

private fun retainUnambiguousDocumentCreateCommands(
    commands: List<PendingDocumentCreateCommand>,
    tabs: List<DocumentTabState>,
): List<PendingDocumentCreateCommand> {
    val idCounts = commands.groupingBy(PendingDocumentCreateCommand::documentId).eachCount()
    val instanceCounts = commands.groupingBy(PendingDocumentCreateCommand::tabInstanceId).eachCount()
    return commands.filter { command ->
        idCounts[command.documentId] == 1 && instanceCounts[command.tabInstanceId] == 1 &&
            tabs.any(command::matches)
    }
}

private fun String.isCanonicalUuid(): Boolean = try {
    UUID.fromString(this).toString() == this
} catch (_: IllegalArgumentException) {
    false
}

/**
 * 重新打开一个已经恢复的 dirty tab 可能会远程校验 ACL/路径，但远程内容绝不能
 * 替换它的本地标题或 Markdown 草稿。
 */
internal fun refreshRestoredDocumentPath(
    existing: DocumentTabState,
    verified: com.virjar.tk.protocol.model.Document,
): DocumentTabState? {
    if (existing.documentId != verified.documentId || existing.spaceId != verified.spaceId) return null
    if (!verified.hasValidDocumentPath() ||
        (existing.revision != null && verified.revision < existing.revision)
    ) return null
    return existing.copy(
        parentId = verified.parentId,
        ancestorIds = verified.ancestorIds,
        pathResolved = true,
        remoteMissing = false,
    )
}
