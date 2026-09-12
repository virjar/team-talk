package com.virjar.tk.shared.client

import com.virjar.tk.protocol.ReliableCommandContract
import java.util.UUID
import com.virjar.tk.protocol.body.MarkdownAssetPolicy
import com.virjar.tk.protocol.body.MessageBodyPolicy
import com.virjar.tk.protocol.http.AttachmentUploadIdentity
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.shared.database.AppDatabaseQueries
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class LocalChatDraftStore(
    private val queries: AppDatabaseQueries,
    private val gate: CacheUseGate,
    private val lock: Any,
    private val writeMirror: (String, String?) -> PendingConversationDraft,
    private val publishMirror: (PendingConversationDraft) -> Unit,
    private val needsMirror: (String, String?) -> Boolean,
) : LocalChatDrafts, LocalChatAssetUploads {
    private val version = MutableStateFlow(0L)
    override val changes = version.asStateFlow()
    private val json = Json { encodeDefaults = true }
    internal var sharedSync: LocalChatDraftSyncStore? = null
    private val revisionClock = java.util.concurrent.atomic.AtomicLong(queries.selectMaxChatComposerRevision().executeAsOne())
    internal fun reserveRevision(): Long = revisionClock.updateAndGet { check(it < Long.MAX_VALUE); it + 1 }

    override fun maxRevision(): Long = use { queries.selectMaxChatComposerRevision().executeAsOne() }

    override fun get(chatId: String): ChatDraftSnapshot? = use { getLocked(chatId) }

    private fun getLocked(chatId: String): ChatDraftSnapshot? {
        val snapshot = queries.selectChatComposerDraft(chatId).executeAsOneOrNull()
            ?.let { decodeDraft(it.payload) } ?: return null
        val references = referenced(snapshot)
        val jobs = jobsLocked(chatId)
        val notReady = jobs.filter { it.state != ChatAssetUploadState.READY }.mapTo(hashSetOf()) { it.assetId }
        val ready = jobs.mapNotNull { it.asset }.filter { it.assetId in references && it.assetId !in notReady }
        val assets = (snapshot.assets.filter { it.assetId !in notReady } + ready).associateBy { it.assetId }
        return (snapshot.copy(
            assets = references.mapNotNull(assets::get),
            pendingAssetIds = references.filter { it !in assets && (it in snapshot.pendingAssetIds || it in notReady) },
        )).let { sharedSync?.decorateLocked(it) ?: it }
    }

    override fun save(snapshot: ChatDraftSnapshot): ChatDraftSnapshot = use {
        validate(snapshot)
        val previous = getLocked(snapshot.chatId)
        val current = queries.selectChatComposerDraft(snapshot.chatId).executeAsOneOrNull()
        if (snapshot.revision <= queries.selectMaxChatComposerRevision().executeAsOne()) {
            // 远端安装也使用同一个时钟；已准入但尚未持久的编辑仍须保存并检查 sharedRevision。
            if (sharedSync?.managedLocked(snapshot.chatId) == true) return@use save(snapshot.copy(revision = reserveRevision()))
            return@use getLocked(snapshot.chatId) ?: ChatDraftSnapshot(snapshot.chatId, snapshot.revision)
        }
        revisionClock.accumulateAndGet(snapshot.revision) { a, b -> maxOf(a, b) }
        val references = referenced(snapshot)
        val bounded = snapshot.copy(
            assets = snapshot.assets.filter { it.assetId in references },
            pendingAssetIds = snapshot.pendingAssetIds.filter { it in references }.distinct(),
        )
        val bytes = json.encodeToString(bounded).encodeToByteArray()
        val capacity = queries.selectChatComposerCapacity().executeAsOne()
        val isEmpty = bounded.markdown.isEmpty() && bounded.replyToClientMsgId == null
        check(capacity.entries - (if (current?.is_empty == 0L) 1 else 0) + (if (isEmpty) 0 else 1) <= 1_000 &&
            capacity.bytes - (if (current?.is_empty == 0L) current.payload.size else 0) + (if (isEmpty) 0 else bytes.size) <= 48L * 1024 * 1024) {
            "聊天草稿存储已满，请先发送或清理现有草稿"
        }
        var mirror: PendingConversationDraft? = null
        queries.transaction {
            sharedSync?.savedLocked(bounded, previous)
            val plain = snapshot.markdown.takeIf { MarkdownAssetPolicy.recoveryReferences(it).isEmpty() }?.takeIf { it.isNotEmpty() }
            if (needsMirror(snapshot.chatId, plain)) mirror = writeMirror(snapshot.chatId, plain)
            queries.upsertChatComposerDraft(snapshot.chatId, snapshot.revision, if (isEmpty) 1L else 0L, bytes)
            queries.advanceChatComposerClock(snapshot.revision)
            queries.pruneChatComposerTombstones()
            reconcileLocked(snapshot.chatId, references)
        }
        mirror?.let(publishMirror)
        sharedSync?.publishedLocalChange()
        changed()
        checkNotNull(getLocked(snapshot.chatId))
    }

    internal fun installRemoteLocked(snapshot: ChatDraftSnapshot) {
        val installed = snapshot.copy(revision = reserveRevision())
        validate(installed)
        val bytes = json.encodeToString(installed).encodeToByteArray()
        val empty = installed.markdown.isEmpty() && installed.replyToClientMsgId == null
        val existing = queries.selectChatComposerDraft(installed.chatId).executeAsOneOrNull()
        val capacity = queries.selectChatComposerCapacity().executeAsOne()
        check(capacity.entries - (if (existing?.is_empty == 0L) 1 else 0) + (if (empty) 0 else 1) <= 1_000 &&
            capacity.bytes - (if (existing?.is_empty == 0L) existing.payload.size else 0) + (if (empty) 0 else bytes.size) <= 48L * 1024 * 1024) {
            "聊天草稿存储已满，请先发送或清理现有草稿"
        }
        queries.upsertChatComposerDraft(installed.chatId, installed.revision, if (empty) 1 else 0, bytes)
        queries.advanceChatComposerClock(installed.revision)
        queries.pruneChatComposerTombstones()
        reconcileLocked(installed.chatId, referenced(installed))
    }

    /** 调用方持有同一 cache lock，且已进入 outgoing 的 SQLite 事务。 */
    internal fun consumeLocked(chatId: String, revision: Long): Boolean {
        val current = queries.selectChatComposerDraft(chatId).executeAsOneOrNull() ?: return false
        if (current.revision != revision) return false
        val empty = ChatDraftSnapshot(chatId = chatId, revision = revision, sharedRevision = decodeDraft(current.payload).sharedRevision)
        queries.upsertChatComposerDraft(chatId, revision, 1L, json.encodeToString(empty).encodeToByteArray())
        queries.pruneChatComposerTombstones()
        referenced(decodeDraft(current.payload)).forEach(::removeDraftReferenceLocked)
        return true
    }

    internal fun changed() { version.value += 1 }

    override fun jobs(chatId: String?): List<ChatAssetUpload> = use { jobsLocked(chatId) }
    private fun jobsLocked(chatId: String?): List<ChatAssetUpload> {
        val owned = queries.selectAllOutgoingChatAssetLinks().executeAsList().mapTo(hashSetOf()) { it.asset_id }
        return queries.selectChatAssetUploads().executeAsList()
            .filter { (chatId == null || it.chat_id == chatId) && (it.referenced == 1L || it.asset_id !in owned) }
            .map { decodeJob(it.payload) }
    }
    override fun retainedSourceIds(): Set<String> = use {
        queries.selectChatAssetUploads().executeAsList().mapTo(hashSetOf()) { it.source_id }
    }
    override fun upload(assetId: String): ChatAssetUpload? = use {
        queries.selectChatAssetUpload(assetId).executeAsOneOrNull()?.let { decodeJob(it.payload) }
    }
    override fun nextUploadWakeAt(includeRetries: Boolean): Long? = use {
        queries.selectChatAssetUploads().executeAsList().mapNotNull { row ->
            val job = decodeJob(row.payload)
            when {
                includeRetries && job.state == ChatAssetUploadState.QUEUED && job.nextAttemptAt > 0 && (row.referenced == 1L || job.repairForClientMsgId != null) -> job.nextAttemptAt
                job.state == ChatAssetUploadState.READY -> job.issuedAt + ReliableCommandContract.RETRY_HORIZON_MILLIS + 1
                else -> null
            }
        }.minOrNull()
    }
    override fun outgoingAssets(chatId: String, clientMsgId: String): List<ChatAssetUpload> = use {
        queries.selectOutgoingChatAssets(chatId, clientMsgId).executeAsList().map { decodeJob(it.payload) }
    }

    override fun prepareReplacement(ownerUid: String, chatId: String, clientMsgId: String, assetIds: Set<String>?): List<ChatAssetUpload> = use {
        val rows = queries.selectOutgoingChatAssets(chatId, clientMsgId).executeAsList().filter { assetIds == null || it.asset_id in assetIds }
        if (rows.isEmpty()) return@use emptyList()
        val projection = queries.selectMessageById(chatId, clientMsgId).executeAsOneOrNull()
        val receipt = queries.selectOutgoingMessageById(chatId, clientMsgId).executeAsOneOrNull()
        check(projection != null && projection.sender_uid == ownerUid && projection.server_seq == 0L &&
            projection.send_status == Message.SEND_STATUS_FAILED.toLong()) { "该消息不再是可替换的本地失败" }
        val failure = projection.outgoing_failure_code?.let(OutgoingFailureCode::fromStorageCode)
        check(failure?.allowsFreshClientMsgIdReplacement == true && (receipt == null ||
            (receipt.sender_uid == ownerUid && receipt.state == OutgoingMessageState.TERMINAL_FAILED.code && receipt.failure_code == projection.outgoing_failure_code))) {
            "消息结果仍不确定，不能用新身份重发"
        }
        val now = System.currentTimeMillis()
        queries.transaction {
            rows.forEach { row ->
                val job = decodeJob(row.payload)
                val newIdentity = job.repairForClientMsgId != clientMsgId || expired(job, now) || job.failure == EXPIRED_FAILURE
                if (newIdentity || job.state == ChatAssetUploadState.FAILED) {
                    writeJobLocked(job.copy(
                        repairForClientMsgId = clientMsgId, state = ChatAssetUploadState.QUEUED, failure = null, nextAttemptAt = 0,
                        uploadId = if (newIdentity) UUID.randomUUID().toString() else job.uploadId,
                        issuedAt = if (newIdentity) now else job.issuedAt,
                        asset = if (newIdentity) null else job.asset,
                    ), row.referenced)
                }
            }
        }
        changed()
        queries.selectOutgoingChatAssets(chatId, clientMsgId).executeAsList().filter { assetIds == null || it.asset_id in assetIds }.map { decodeJob(it.payload) }
    }

    internal fun retainOutgoingLocked(message: Message, now: Long, validateReady: Boolean) {
        val assets = when (val body = message.body) {
            is com.virjar.tk.protocol.body.RichTextBody -> body.assets
            is com.virjar.tk.protocol.body.ReplyBody -> body.assets
            else -> emptyList()
        }
        assets.forEach { asset ->
            val row = queries.selectChatAssetUpload(asset.assetId).executeAsOneOrNull() ?: return@forEach
            val job = decodeJob(row.payload)
            check(job.chatId == message.chatId) { "附件源不属于当前会话" }
            if (validateReady) check(job.state == ChatAssetUploadState.READY && job.asset == asset && !expired(job, now)) {
                "附件尚未就绪或上传凭据已过期，请保留草稿并重试上传"
            }
            check(queries.countOutgoingChatAssetLinks().executeAsOne() < 8_192) { "待发附件引用已满，请发送或丢弃现有失败消息" }
            queries.retainOutgoingChatAsset(message.chatId, message.clientMsgId, message.senderUid, asset.assetId)
        }
    }

    private fun removeDraftReferenceLocked(assetId: String) {
        queries.removeChatAssetDraftReference(assetId)
        queries.deleteUnownedChatAsset(assetId)
    }

    override fun register(job: ChatAssetUpload): ChatAssetUpload = use {
        EmbeddedAsset.requireCanonicalAssetId(job.assetId)
        EmbeddedAsset.requireCanonicalAssetId(job.sourceId)
        AttachmentUploadIdentity(job.uploadId, job.issuedAt)
        require(job.chatId.length in 1..64 && job.length in 0..512L * 1024 * 1024)
        require(job.fileName.length in 1..512 && job.contentType.length in 1..255)
        require(job.sha256.length == 64 && job.sha256.all { it in '0'..'9' || it in 'a'..'f' })
        val existing = queries.selectChatAssetUpload(job.assetId).executeAsOneOrNull()
        if (existing != null) {
            val previous = decodeJob(existing.payload)
            require(previous.chatId == job.chatId && previous.sha256 == job.sha256 &&
                previous.fileName == job.fileName && previous.contentType == job.contentType) { "Asset id already has a different source" }
            return@use previous
        }
        val all = queries.selectChatAssetUploads().executeAsList().map { decodeJob(it.payload) }
        check(all.size < 128 && all.sumOf { it.length } + job.length <= 512L * 1024 * 1024) {
            "待发附件源文件已满，请先发送或移除附件"
        }
        val draft = queries.selectChatComposerDraft(job.chatId).executeAsOneOrNull()?.let { decodeDraft(it.payload) }
        writeJobLocked(job, if (draft != null && job.assetId in referenced(draft)) 1 else 0)
        changed()
        job
    }

    override fun claimNext(now: Long): ChatAssetUpload? = use {
        val row = queries.selectChatAssetUploads().executeAsList().firstOrNull { row ->
            val job = decodeJob(row.payload)
            (row.referenced == 1L || job.repairForClientMsgId != null) && job.state == ChatAssetUploadState.QUEUED && job.nextAttemptAt <= now
        } ?: return@use null
        val job = decodeJob(row.payload).let { it.copy(state = ChatAssetUploadState.UPLOADING, attempt = it.attempt + 1, failure = null) }
        writeJobLocked(job, row.referenced)
        changed()
        job
    }

    override fun complete(assetId: String, attempt: Long, asset: EmbeddedAsset): Boolean = use {
        val row = queries.selectChatAssetUpload(assetId).executeAsOneOrNull() ?: return@use false
        val job = decodeJob(row.payload)
        if (job.attempt != attempt || job.state != ChatAssetUploadState.UPLOADING) return@use false
        require(asset.assetId == assetId)
        queries.transaction {
            writeJobLocked(job.copy(state = ChatAssetUploadState.READY, asset = canonicalAsset(asset), failure = null), row.referenced)
            if (row.referenced == 1L) sharedSync?.assetReadyLocked(job.chatId)
        }
        sharedSync?.publishedLocalChange()
        changed()
        true
    }

    override fun fail(assetId: String, attempt: Long, reason: String, retryAt: Long): Boolean = use {
        val row = queries.selectChatAssetUpload(assetId).executeAsOneOrNull() ?: return@use false
        val job = decodeJob(row.payload)
        if (job.attempt != attempt || job.state != ChatAssetUploadState.UPLOADING) return@use false
        writeJobLocked(job.copy(
            state = if (retryAt > 0) ChatAssetUploadState.QUEUED else ChatAssetUploadState.FAILED,
            nextAttemptAt = retryAt, failure = reason.take(500),
        ), row.referenced)
        changed()
        true
    }

    override fun retry(assetId: String) = use {
        val row = queries.selectChatAssetUpload(assetId).executeAsOneOrNull() ?: return@use
        val job = decodeJob(row.payload)
        val expired = expired(job, System.currentTimeMillis()) || job.failure == EXPIRED_FAILURE
        if (job.state == ChatAssetUploadState.FAILED || (job.state == ChatAssetUploadState.READY && expired)) {
            writeJobLocked(job.copy(state = ChatAssetUploadState.QUEUED, nextAttemptAt = 0, failure = null,
                uploadId = if (expired) UUID.randomUUID().toString() else job.uploadId,
                issuedAt = if (expired) System.currentTimeMillis() else job.issuedAt,
                asset = if (expired) null else job.asset), row.referenced)
            changed()
        }
    }

    override fun remove(assetId: String) = use { queries.transaction { removeDraftReferenceLocked(assetId) }; changed() }

    override fun expireUploads(now: Long) = use {
        var modified = false
        queries.transaction {
            queries.selectChatAssetUploads().executeAsList().forEach { row ->
                val job = decodeJob(row.payload)
                if (job.state != ChatAssetUploadState.UPLOADING && job.failure != EXPIRED_FAILURE && expired(job, now)) {
                    writeJobLocked(job.copy(state = ChatAssetUploadState.FAILED, asset = null, failure = EXPIRED_FAILURE, nextAttemptAt = 0), row.referenced)
                    modified = true
                }
            }
        }
        if (modified) changed()
    }

    override fun recoverUploads() = use {
        queries.transaction {
            queries.selectChatAssetUploads().executeAsList().forEach { row ->
                val job = decodeJob(row.payload)
                if (job.state == ChatAssetUploadState.UPLOADING) {
                    writeJobLocked(job.copy(state = ChatAssetUploadState.QUEUED, nextAttemptAt = 0), row.referenced)
                }
            }
        }
        changed()
    }

    private fun reconcileLocked(chatId: String, references: Set<String>) {
        queries.selectChatAssetUploadsByChat(chatId).executeAsList().forEach { row ->
            if (row.asset_id in references) queries.markChatAssetUploadReferenced(row.asset_id)
            else if (row.referenced == 1L) removeDraftReferenceLocked(row.asset_id)
        }
    }

    private fun expired(job: ChatAssetUpload, now: Long) = now > job.issuedAt && now - job.issuedAt > ReliableCommandContract.RETRY_HORIZON_MILLIS

    private fun writeJobLocked(job: ChatAssetUpload, referenced: Long) =
        queries.upsertChatAssetUpload(job.assetId, job.chatId, job.sourceId, referenced, json.encodeToString(job).encodeToByteArray())
    private fun decodeDraft(bytes: ByteArray) = json.decodeFromString<ChatDraftSnapshot>(bytes.decodeToString())
    private fun decodeJob(bytes: ByteArray) = json.decodeFromString<ChatAssetUpload>(bytes.decodeToString())
    private fun referenced(snapshot: ChatDraftSnapshot) = MarkdownAssetPolicy.recoveryReferences(snapshot.markdown)
        .mapNotNullTo(linkedSetOf()) { it.assetId }
    private fun canonicalAsset(asset: EmbeddedAsset): EmbeddedAsset =
        MarkdownAssetPolicy.canonicalize("[asset](${EmbeddedAsset.uri(asset.assetId)})", listOf(asset)).single()

    private fun validate(snapshot: ChatDraftSnapshot) {
        require(snapshot.chatId.length in 1..64 && snapshot.revision > 0)
        require(snapshot.markdown.length <= MessageBodyPolicy.MAX_MARKDOWN_LENGTH)
        require(snapshot.mode in 0..2 && snapshot.selectionStart >= 0 && snapshot.selectionEnd >= 0)
        require(snapshot.replyToServerSeq >= 0 && (snapshot.replyToClientMsgId?.length ?: 0) <= 64)
        require(snapshot.assets.size <= EmbeddedAsset.MAX_ASSETS_PER_CONTENT && snapshot.pendingAssetIds.size <= EmbeddedAsset.MAX_ASSETS_PER_CONTENT)
        snapshot.assets.forEach { canonicalAsset(it) }
        snapshot.pendingAssetIds.forEach(EmbeddedAsset::requireCanonicalAssetId)
    }
    private companion object { const val EXPIRED_FAILURE = "上传凭据已过期，请重试以重新上传保留的源文件" }
    private fun <T> use(block: () -> T): T = gate.use { synchronized(lock) { block() } }
}
