package com.virjar.tk.shared.client

import com.virjar.tk.protocol.ReliableCommandContract
import com.virjar.tk.protocol.body.MarkdownAssetPolicy
import com.virjar.tk.protocol.model.ChatDraftCommand
import com.virjar.tk.protocol.model.ChatDraftContent
import com.virjar.tk.protocol.model.ChatDraftMutationResult
import com.virjar.tk.protocol.model.ChatDraftSnapshot as SharedChatDraftSnapshot
import com.virjar.tk.shared.database.AppDatabaseQueries
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** 一个 chat 只有一个不可变在途操作；新的编辑合并在本机 composer 中，不覆盖在途字节。 */
internal class LocalChatDraftSyncStore(
    private val queries: AppDatabaseQueries,
    private val gate: CacheUseGate,
    private val lock: Any,
    private val drafts: LocalChatDraftStore,
    private val publishPreview: (String, String?) -> Unit,
) : LocalChatDraftSync {
    override val changes = MutableStateFlow(0L)
    private val json = Json { encodeDefaults = true }
    @Serializable
    private data class Consumption(val clientMsgId: String?, val expectedRevision: Long, val afterOperationId: String? = null)
    @Serializable
    private data class Record(
        val chatId: String,
        val remote: SharedChatDraftSnapshot? = null,
        val stale: Boolean = true,
        val requiredRevision: Long = 0,
        val localRevision: Long = 0,
        val dirty: Boolean = false,
        val pending: PendingSharedChatDraft? = null,
        val consume: Consumption? = null,
        val conflict: Boolean = false,
        val failure: String? = null,
        val pendingRejected: Boolean = false,
        // 自有连续 ACK 可推进热编辑帧的旧基线；外部变更绝不扩展这个区间。
        val acceptedBaseFloor: Long = 0,
    )
    private fun <T> use(block: () -> T): T = gate.use { synchronized(lock) { block() } }
    private fun read(chatId: String): Record? = queries.selectChatDraftSync(chatId).executeAsOneOrNull()?.let { json.decodeFromString<Record>(it) }
    private fun all(): List<Record> = queries.selectAllChatDraftSync().executeAsList().map { json.decodeFromString<Record>(it) }
    private fun write(record: Record) {
        val payload = json.encodeToString(record)
        val existing = queries.selectChatDraftSync(record.chatId).executeAsOneOrNull()
        var capacity = queries.selectChatDraftSyncCapacity().executeAsOne()
        if (capacity.entries + (if (existing == null) 1 else 0) > 1_000 ||
            capacity.bytes - (existing?.encodeToByteArray()?.size ?: 0) + payload.encodeToByteArray().size > 64L * 1024 * 1024) {
            // 空墓碑的权威 revision 在服务器；纯投影可按需重新 get，可靠本机事实不参与回收。
            all().filter { it.chatId != record.chatId && !it.dirty && it.pending == null && it.consume == null &&
                !it.conflict && it.failure == null && it.remote != null && it.remote.content == null &&
                queries.selectChatComposerDraft(it.chatId).executeAsOneOrNull()?.is_empty != 0L
            }.forEach { queries.deleteChatDraftSync(it.chatId) }
            capacity = queries.selectChatDraftSyncCapacity().executeAsOne()
        }
        check(capacity.entries + (if (existing == null) 1 else 0) <= 1_000 &&
            capacity.bytes - (existing?.encodeToByteArray()?.size ?: 0) + payload.encodeToByteArray().size <= 64L * 1024 * 1024) {
            "跨设备草稿存储已满，请先处理现有草稿"
        }
        queries.upsertChatDraftSync(record.chatId, payload)
    }
    private fun changed() { changes.value += 1 }
    internal fun managedLocked(chatId: String) = read(chatId) != null
    override fun state(chatId: String): ChatDraftSyncState = use {
        val record = read(chatId) ?: return@use ChatDraftSyncState()
        ChatDraftSyncState(record.remote, record.dirty || record.pending != null || record.consume != null,
            record.conflict, record.failure, record.remote?.assetsAvailable != false)
    }
    override fun ensure(chatId: String) = use {
        if (read(chatId) == null) {
            var draft = drafts.get(chatId)
            val legacyPending = queries.selectAllConversationDraftOutbox().executeAsList().firstOrNull { it.chat_id == chatId }
            if (draft == null) {
                val legacy = if (legacyPending != null) legacyPending.draft else
                    queries.selectAllConversations().executeAsList().firstOrNull { it.chat_id == chatId }?.draft
                if (legacy != null || legacyPending != null) {
                    drafts.installRemoteLocked(ChatDraftSnapshot(chatId, markdown = legacy.orEmpty()))
                    draft = drafts.get(chatId)
                }
            }
            write(Record(chatId, localRevision = draft?.revision ?: 0,
                dirty = legacyPending != null || (draft != null && !empty(draft))))
            // 此后只有新 CAS 契约负责出站，旧 scalar 覆盖层不能越过它。
            if (draft != null) publishPreview(chatId, draft.markdown.takeIf { MarkdownAssetPolicy.recoveryReferences(it).isEmpty() && it.isNotEmpty() })
            changed()
        }
    }
    override fun invalidate(chatId: String, revision: Long) = use {
        ensure(chatId)
        val record = checkNotNull(read(chatId))
        if (revision == 0L || revision > (record.remote?.revision ?: -1L) || record.remote == null) {
            write(record.copy(stale = true, requiredRevision = maxOf(record.requiredRevision, revision))); changed()
        }
    }
    override fun invalidateAll() = use {
        all().forEach { write(it.copy(stale = true)) }; changed()
    }
    override fun refreshTargets(): List<String> = use { all().filter { it.stale }.map { it.chatId } }
    override fun workChats(): List<String> = use { all().filter { it.pending != null || it.dirty || it.consume != null }.map { it.chatId } }

    internal fun savedLocked(snapshot: ChatDraftSnapshot, previous: ChatDraftSnapshot?) {
        var record = read(snapshot.chatId) ?: if (snapshot.sharedRevision != null) Record(snapshot.chatId) else return
        val semanticChange = previous == null || !sameContent(previous, snapshot)
        if (semanticChange) {
            val base = snapshot.sharedRevision ?: 0L
            val current = record.remote?.revision
            val staleEdit = current != null && base !in record.acceptedBaseFloor..current
            record = record.copy(localRevision = snapshot.revision, dirty = true,
                conflict = record.conflict || staleEdit, failure = record.failure.takeIf { record.pendingRejected })
        } else record = record.copy(localRevision = snapshot.revision)
        write(record)
    }
    internal fun assetReadyLocked(chatId: String) {
        val record = read(chatId) ?: return
        val draft = drafts.get(chatId) ?: return
        if (!ready(draft) || readyContent(draft) == record.remote?.content) return
        // get 已叠加 READY job，因此不能等 UI save 再对比该覆盖层；此事务拥有 descriptor 变更。
        // 独立推进本机 revision，防止一个更早的 pending SET ACK 消费刚生成的新 sidecar。
        drafts.installRemoteLocked(draft)
        write(record.copy(localRevision = checkNotNull(drafts.get(chatId)).revision, dirty = true))
    }
    internal fun publishedLocalChange() { changed() }

    /** 与精确 composer 消费及消息 outbox 准入处于同一个 SQLite 事务。 */
    internal fun consumedLocked(chatId: String, clientMsgId: String) {
        val record = read(chatId) ?: return
        val pending = record.pending
        val consumption = record.consume ?: Consumption(clientMsgId, record.remote?.revision ?: 0,
            pending?.takeIf { it.command.content != null }?.command?.operationId)
        write(record.copy(dirty = false, consume = consumption, conflict = false, failure = null))
    }

    /** 用户显式恢复失败消息，与替换/丢弃的 outbox 变更共用外层事务。 */
    internal fun replacedLocked(chatId: String, oldClientMsgId: String, newClientMsgId: String) {
        val record = read(chatId) ?: return
        val consume = record.consume?.takeIf { it.clientMsgId == oldClientMsgId } ?: return
        write(record.copy(consume = if (record.dirty) null else consume.copy(clientMsgId = newClientMsgId)))
    }
    internal fun discardedLocked(chatId: String, clientMsgId: String) {
        val record = read(chatId) ?: return
        val consume = record.consume?.takeIf { it.clientMsgId == clientMsgId } ?: return
        // 手动丢弃不需要消息成功，但仍只针对原先绑定的服务器 revision。
        write(record.copy(consume = if (record.dirty) null else consume.copy(clientMsgId = null)))
    }

    override fun readFailed(chatId: String, reason: String) = use {
        val record = read(chatId) ?: return@use
        write(record.copy(stale = false, failure = reason.take(240))); changed()
    }

    override fun applyRemote(snapshot: SharedChatDraftSnapshot): Boolean = use {
        ensure(snapshot.chatId)
        var applied = false
        queries.transaction {
            val record = checkNotNull(read(snapshot.chatId))
            if (snapshot.revision < (record.remote?.revision ?: 0) || snapshot.revision < record.requiredRevision) return@transaction
            var next = record.copy(remote = snapshot, stale = false, failure = record.failure.takeIf { record.pendingRejected })
            if (record.pending == null) {
                if (record.consume != null && record.consume.afterOperationId == null && snapshot.revision > record.consume.expectedRevision) {
                    next = next.copy(consume = null)
                }
                if (next.consume == null) {
                    if (record.dirty) {
                        val draft = drafts.get(snapshot.chatId)
                        val equal = draft != null && readyContent(draft) == snapshot.content && ready(draft)
                        val changedByOther = snapshot.revision > (record.remote?.revision ?: 0)
                        next = when {
                            equal -> next.copy(dirty = false, conflict = false, acceptedBaseFloor = snapshot.revision)
                            changedByOther -> next.copy(conflict = true)
                            else -> next
                        }
                        if (equal) stampLocalLocked(snapshot.chatId, snapshot.revision)
                    } else {
                        installLocked(snapshot)
                        next = next.copy(localRevision = drafts.get(snapshot.chatId)?.revision ?: 0,
                            conflict = false, failure = null, acceptedBaseFloor = if (snapshot.revision > (record.remote?.revision ?: -1L)) snapshot.revision else record.acceptedBaseFloor)
                    }
                }
            }
            write(next)
            applied = true
        }
        if (applied) { publishCurrentPreview(snapshot.chatId); changed(); drafts.changed() }
        applied
    }

    override fun nextCommand(chatId: String, now: Long): PendingSharedChatDraft? = use {
        var record = read(chatId) ?: return@use null
        if (record.failure != null || record.conflict) return@use null
        record.pending?.let { return@use it }
        val remote = record.remote ?: return@use null
        if (record.stale) return@use null
        record.consume?.let { consume ->
            if (consume.afterOperationId != null) return@use null
            if (consume.expectedRevision != remote.revision || remote.content == null) {
                record = record.copy(consume = null); write(record)
            } else {
                val receipt = consume.clientMsgId?.let { queries.selectOutgoingMessageById(chatId, it).executeAsOneOrNull() }
                val message = consume.clientMsgId?.let { queries.selectMessageById(chatId, it).executeAsOneOrNull() }
                val successful = consume.clientMsgId == null || receipt?.state == OutgoingMessageState.SUCCESS.code || (message?.server_seq ?: 0) > 0
                if (!successful) {
                    val failed = receipt?.state == OutgoingMessageState.TERMINAL_FAILED.code ||
                        (receipt == null && message?.send_status == com.virjar.tk.protocol.model.Message.SEND_STATUS_FAILED.toLong())
                    if (failed && record.dirty) {
                        record = record.copy(consume = null); write(record)
                    } else return@use null
                } else {
                    return@use prepareLocked(record, ChatDraftCommand(chatId, remote.revision, id(), now,
                        consumedClientMsgId = consume.clientMsgId))
                }
            }
        }
        if (!record.dirty) return@use null
        val draft = drafts.get(chatId) ?: return@use null
        if (!ready(draft)) return@use null
        val content = readyContent(draft)
        if (content == remote.content) {
            write(record.copy(dirty = false)); stampLocalLocked(chatId, remote.revision); changed(); return@use null
        }
        prepareLocked(record, ChatDraftCommand(chatId, remote.revision, id(), now, content))
    }
    private fun prepareLocked(record: Record, command: ChatDraftCommand): PendingSharedChatDraft {
        val pending = PendingSharedChatDraft(command, record.localRevision)
        write(record.copy(pending = pending)); changed(); return pending
    }

    override fun acknowledge(pending: PendingSharedChatDraft, result: ChatDraftMutationResult) {
        gate.runIfOpen { synchronized(lock) {
            val command = pending.command
            val record = read(command.chatId) ?: return@synchronized false
            if (record.pending != pending) return@synchronized false
            require(result.current.chatId == command.chatId)
            queries.transaction {
                val current = if ((record.remote?.revision ?: 0) > result.current.revision) record.remote!! else result.current
                val ownCurrent = result.applied && current.revision == result.operationRevision && current.revision >= record.requiredRevision
                var consume = record.consume
                if (consume?.afterOperationId == command.operationId) consume = if (ownCurrent)
                    consume.copy(expectedRevision = result.operationRevision, afterOperationId = null) else null
                if (command.content == null) consume = null
                // 消费清空只确认已发送/丢弃的旧稿；生成 clear 前后写入的后继稿均不属于它。
                val consumptionClear = command.content == null && record.consume != null
                val dirty = record.dirty && (consumptionClear || record.localRevision != pending.localRevision || !result.applied)
                var next = record.copy(remote = current, stale = current.revision < record.requiredRevision,
                    pending = null, pendingRejected = false, consume = consume, dirty = dirty,
                    conflict = !ownCurrent && dirty, failure = null,
                    acceptedBaseFloor = record.acceptedBaseFloor)
                if (!result.applied && record.consume == null) next = next.copy(dirty = true, conflict = true)
                if (ownCurrent) stampLocalLocked(command.chatId, current.revision)
                // 已发送的本机空稿不接纳旧 SET 回显；只能等待有消息成功支撑的条件清空。
                if (next.consume == null && !next.dirty && !next.conflict && !next.stale) {
                    installLocked(current); next = next.copy(acceptedBaseFloor = if (ownCurrent) record.acceptedBaseFloor else current.revision)
                }
                write(next)
            }
            publishCurrentPreview(command.chatId)
            changed(); drafts.changed(); true
        } }
    }
    override fun fail(pending: PendingSharedChatDraft, reason: String) = use {
        val record = read(pending.command.chatId) ?: return@use
        if (record.pending == pending) { write(record.copy(failure = reason.take(240), pendingRejected = true)); changed() }
    }
    override fun resolve(chatId: String, keepLocal: Boolean) = use {
        val record = read(chatId) ?: return@use
        check(record.pending == null || record.pendingRejected) { "上次草稿同步结果尚未确认，请联网等待确认" }
        val remote = checkNotNull(record.remote) { "请先读取其他设备的草稿" }
        check(!record.stale) { "草稿正在刷新，请稍后重试" }
        queries.transaction {
            if (keepLocal) stampLocalLocked(chatId, remote.revision) else installLocked(remote)
            write(record.copy(localRevision = drafts.get(chatId)?.revision ?: 0, dirty = keepLocal,
                pending = null, pendingRejected = false, consume = null, conflict = false, failure = null, acceptedBaseFloor = remote.revision))
        }
        publishCurrentPreview(chatId)
        changed(); drafts.changed()
    }
    override fun nextExpiryAt(): Long? = use { all().filter { it.failure == null }.mapNotNull { it.pending }
        .minOfOrNull { ReliableCommandContract.firstExpiredAt(it.command.issuedAt) } }

    internal fun decorateLocked(snapshot: ChatDraftSnapshot): ChatDraftSnapshot {
        val record = read(snapshot.chatId) ?: return snapshot
        val remote = record.remote ?: return snapshot
        val base = snapshot.sharedRevision ?: 0
        return if (!record.conflict && base in record.acceptedBaseFloor..remote.revision)
            snapshot.copy(sharedRevision = remote.revision) else snapshot
    }
    private fun stampLocalLocked(chatId: String, revision: Long) {
        val row = queries.selectChatComposerDraft(chatId).executeAsOneOrNull() ?: return
        val snapshot = json.decodeFromString<ChatDraftSnapshot>(row.payload.decodeToString()).copy(sharedRevision = revision)
        queries.upsertChatComposerDraft(chatId, row.revision, row.is_empty, json.encodeToString(snapshot).encodeToByteArray())
    }
    private fun installLocked(remote: SharedChatDraftSnapshot) {
        val content = remote.content
        val previous = drafts.get(remote.chatId)
        val desired = ChatDraftSnapshot(remote.chatId, markdown = content?.markdown.orEmpty(), assets = content?.assets.orEmpty(),
            mode = content?.mode ?: 0, selectionStart = previous?.selectionStart?.coerceAtMost(content?.markdown?.length ?: 0) ?: 0,
            selectionEnd = previous?.selectionEnd?.coerceAtMost(content?.markdown?.length ?: 0) ?: 0,
            replyToClientMsgId = content?.replyToClientMsgId, replyToServerSeq = content?.replyToServerSeq ?: 0,
            sharedRevision = remote.revision)
        if (previous != null && sameContent(previous, desired)) stampLocalLocked(remote.chatId, remote.revision)
        else drafts.installRemoteLocked(desired)
    }
    private fun publishCurrentPreview(chatId: String) {
        val draft = drafts.get(chatId)
        publishPreview(chatId, draft?.markdown?.takeIf { MarkdownAssetPolicy.recoveryReferences(it).isEmpty() && it.isNotEmpty() })
    }
    private fun ready(snapshot: ChatDraftSnapshot): Boolean = snapshot.pendingAssetIds.isEmpty() &&
        (snapshot.replyToClientMsgId == null || snapshot.replyToServerSeq > 0) &&
        runCatching { MarkdownAssetPolicy.canonicalize(snapshot.markdown, snapshot.assets) }.isSuccess
    private fun readyContent(snapshot: ChatDraftSnapshot): ChatDraftContent? = if (empty(snapshot)) null else
        runCatching { ChatDraftContent(snapshot.markdown, MarkdownAssetPolicy.canonicalize(snapshot.markdown, snapshot.assets),
            snapshot.mode, snapshot.replyToClientMsgId, snapshot.replyToServerSeq) }.getOrNull()
    private fun empty(snapshot: ChatDraftSnapshot) = snapshot.markdown.isEmpty() && snapshot.replyToClientMsgId == null
    private fun sameContent(a: ChatDraftSnapshot, b: ChatDraftSnapshot) = a.markdown == b.markdown && a.assets == b.assets &&
        a.pendingAssetIds == b.pendingAssetIds && a.mode == b.mode && a.replyToClientMsgId == b.replyToClientMsgId && a.replyToServerSeq == b.replyToServerSeq
    private fun id() = UUID.randomUUID().toString()
}
