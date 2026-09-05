package com.virjar.tk.server.infra.db

import com.virjar.tk.server.domain.event.requireNotifyContract
import com.virjar.tk.server.domain.transaction.PgUnitOfWork
import com.virjar.tk.server.domain.transaction.PgReadScope
import com.virjar.tk.server.domain.transaction.PgReadTransactionContext
import com.virjar.tk.server.domain.transaction.PgWriteScope
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.server.runtime.isFatalRuntimeFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.IColumnType
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSet
import java.util.IdentityHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

enum class PgUnitOfWorkStage {
    BEFORE_EVENT_FLUSH,
    AFTER_EVENT_FLUSH_BEFORE_COMMIT,
    AFTER_COMMIT_BEFORE_CALLBACKS,
}

/** 用于确定性回滚/崩溃窗口覆盖的测试缝隙；生产环境使用 [None]。 */
fun interface PgUnitOfWorkHooks {
    suspend fun hit(stage: PgUnitOfWorkStage)

    object None : PgUnitOfWorkHooks {
        override suspend fun hit(stage: PgUnitOfWorkStage) = Unit
    }
}

/**
 * 聚合命令边界的 Exposed 实现。
 *
 * 领域块先运行。事件意图随后刷新，以字典序 uid 顺序获取 `sync_streams`
 * 行作为事务的最终锁。因此流锁会序列化
 * 序号分配与提交顺序，而不会让 Exposed 接触领域 API。已被取消的命令
 * 在准入前就被拒绝；一旦准入，事务、本地可见性
 * 回调与分发器信号构成一个不可取消的终结阶段。
 */
class ExposedPgUnitOfWork(
    private val database: Database,
    private val onEventsCommitted: (Set<String>) -> Unit,
    private val hooks: PgUnitOfWorkHooks = PgUnitOfWorkHooks.None,
    private val clock: () -> Long = System::currentTimeMillis,
) : PgUnitOfWork {
    private val logger = LoggerFactory.getLogger(ExposedPgUnitOfWork::class.java)

    override suspend fun <T> read(block: PgReadScope.() -> T): T {
        rejectNestedUnitOfWork()
        coroutineContext.ensureActive()
        return withContext(ActivePgUnitOfWorkElement) {
            newSuspendedTransaction(
                context = coroutineContext + Dispatchers.IO,
                db = database,
                transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ,
                readOnly = true,
            ) {
                // 读快照是一个应用级决策边界。在任意仓库块运行之后再重试，
                // 会让一个 RPC 观察到两个快照。
                maxAttempts = 1
                ExposedPgReadScope(this).block()
            }
        }
    }

    override suspend fun <T> write(block: PgWriteScope.() -> T): T {
        rejectNestedUnitOfWork()
        // 拒绝已被取消的工作，然后把已准入的聚合命令当作一个
        // 终结阶段。特别地，取消绝不能发生在 PostgreSQL 已提交之后、
        // 而缓存失效与分发器唤醒尚未在本进程可见之前。
        coroutineContext.ensureActive()
        val terminalResult = withContext(NonCancellable + ActivePgUnitOfWorkElement) {
            executeWrite(block)
        }
        // kotlinx.coroutines 在跨 withContext 边界恢复栈轨迹时可能复制 Throwable。
        // 把提交后的致命失败作为数据带出来，然后在这里抛出，
        // 这样调用方在每次发布步骤都排空之后能观察到确切的失败对象。
        terminalResult.fatalPublicationFailure?.let { throw it }
        return terminalResult.value
    }

    private suspend fun rejectNestedUnitOfWork() {
        check(coroutineContext[ActivePgUnitOfWorkKey] == null) {
            "Nested PgUnitOfWork is forbidden; compose work through the active transaction scope"
        }
    }

    private suspend fun <T> executeWrite(block: PgWriteScope.() -> T): TerminalWriteResult<T> {
        var committedUids: Set<String> = emptySet()
        var afterCommitActions: List<() -> Unit> = emptyList()

        val result = newSuspendedTransaction(
            context = coroutineContext + Dispatchers.IO,
            db = database,
            // 带锁围栏的命令在等待围栏拥有者提交后，会刻意重新读取事实。
            // 固定此隔离级别而不是继承角色/数据库默认值：REPEATABLE READ
            // 会保留等待前的快照，破坏资产交接/归档的线性化。
            transactionIsolation = Connection.TRANSACTION_READ_COMMITTED,
        ) {
            // 重跑任意领域块会重复生成 ID 和进程本地工作。
            // 调用方应通过自己的稳定请求/幂等键来重试命令。
            maxAttempts = 1
            val scope = ExposedPgWriteScope(this, clock)
            val value = scope.block()

            hooks.hit(PgUnitOfWorkStage.BEFORE_EVENT_FLUSH)
            committedUids = scope.flushEvents()
            hooks.hit(PgUnitOfWorkStage.AFTER_EVENT_FLUSH_BEFORE_COMMIT)
            afterCommitActions = scope.afterCommitActions.toList()
            value
        }

        // 这个 hook 刻意位于 Exposed 事务之外。测试用它来模拟
        // 提交之后、所有进程本地提示发布之前的进程退出。
        hooks.hit(PgUnitOfWorkStage.AFTER_COMMIT_BEFORE_CALLBACKS)
        // 在唤醒在线投递之前先发布进程本地状态。一个持久事件可能导致
        // 客户端立即发出读 RPC；在匹配事件已在线可见之后，该读操作绝不能观察到
        // 提交前的缓存快照。每个动作都是尽力而为的，
        // 即使某个缓存回调失败，分发器唤醒仍会运行（启动/周期性
        // 扫描是丢失唤醒的持久回退方案）。
        var fatalPublicationFailure: Throwable? = null
        fun retainFatal(error: Throwable) {
            val first = fatalPublicationFailure
            if (first == null) {
                fatalPublicationFailure = error
            } else if (first !== error) {
                first.addSuppressed(error)
            }
        }
        afterCommitActions.forEach { action ->
            try {
                action()
            } catch (failure: Throwable) {
                if (failure.isFatalRuntimeFailure()) {
                    // 取消或 VM 级致命不变量仍会逃逸，但只在每次
                    // 已提交的缓存失效与分发器提示都获得终结机会之后。
                    retainFatal(failure)
                } else {
                    logger.warn("Post-commit callback failed; durable transaction remains committed", failure)
                }
            }
        }
        if (committedUids.isNotEmpty()) {
            try {
                onEventsCommitted(committedUids)
            } catch (failure: Throwable) {
                if (failure.isFatalRuntimeFailure()) {
                    retainFatal(failure)
                } else {
                    logger.warn("Failed to wake sync dispatcher for uids={}", committedUids, failure)
                }
            }
        }
        return TerminalWriteResult(result, fatalPublicationFailure)
    }

    private data class TerminalWriteResult<out T>(
        val value: T,
        val fatalPublicationFailure: Throwable?,
    )

    private object ActivePgUnitOfWorkKey : CoroutineContext.Key<ActivePgUnitOfWorkElement>
    private object ActivePgUnitOfWorkElement : AbstractCoroutineContextElement(ActivePgUnitOfWorkKey)

    private class ExposedPgReadScope(exposedTransaction: Transaction) : PgReadScope {
        override val transaction: PgReadTransactionContext =
            ExposedPgReadTransactionContext(exposedTransaction)
    }

    private class ExposedPgWriteScope(
        private val exposedTransaction: Transaction,
        private val clock: () -> Long,
    ) : PgWriteScope {
        override val transaction: PgWriteTransactionContext =
            ExposedPgWriteTransactionContext(exposedTransaction)

        private data class EventIntent(
            val uid: String,
            val notifyType: NotifyType,
            val encoded: ByteArray,
            val createdAt: Long,
        )

        private data class SequencedEvent(
            val intent: EventIntent,
            val streamSeq: Long,
        )

        private val eventIntents = mutableListOf<EventIntent>()
        private val encodedPayloadsByValue = HashMap<IProto, ByteArray>()
        val afterCommitActions = mutableListOf<() -> Unit>()

        override fun appendEvent(
            uid: String,
            notifyType: NotifyType,
            payload: IProto,
        ) {
            require(uid.isNotBlank()) { "Durable event uid must not be blank" }
            require(uid.length <= 36) { "Durable event uid exceeds 36 characters" }
            requireNotifyContract(notifyType, payload)
            // 协议载荷是不可变值。消息投影为每个接收者追加相同的 Message，
            // 大多数群 Conversation 快照也是值相等的。
            // 每个值只捕获一次，而不是在有界的 PostgreSQL 写入器
            // 去重其载荷参数之前就物化 1,000 个相同的字节数组。
            val encoded = encodedPayloadsByValue[payload] ?: ProtoCodec.encode(payload).also {
                encodedPayloadsByValue[payload] = it
            }
            eventIntents += EventIntent(
                uid = uid,
                notifyType = notifyType,
                encoded = encoded,
                createdAt = clock(),
            )
        }

        override fun afterCommit(action: () -> Unit) {
            afterCommitActions += action
        }

        fun flushEvents(): Set<String> {
            if (eventIntents.isEmpty()) return emptySet()

            val intentsByUid = eventIntents.groupBy { it.uid }
            val sortedUids = intentsByUid.keys.sorted()
            ensureSyncStreams(sortedUids)

            // 按一个确定性顺序强制行锁。此点之后不要再添加领域 SQL。
            val streams = lockSyncStreams(sortedUids)
            check(streams.size == sortedUids.size) { "Failed to materialize every durable sync stream" }

            val sequencedEvents = mutableListOf<SequencedEvent>()
            val nextSeqByUid = linkedMapOf<String, Long>()
            sortedUids.forEach { uid ->
                var nextSeq = streams.getValue(uid)
                intentsByUid.getValue(uid).forEach { intent ->
                    check(nextSeq < Long.MAX_VALUE) { "Durable sync stream sequence exhausted" }
                    nextSeq += 1L
                    sequencedEvents += SequencedEvent(intent, nextSeq)
                }
                if (nextSeq != streams.getValue(uid)) {
                    nextSeqByUid[uid] = nextSeq
                }
            }
            if (sequencedEvents.isEmpty()) return emptySet()

            insertSyncEvents(sequencedEvents)
            updateSyncStreams(streams, nextSeqByUid)
            return nextSeqByUid.keys.toSet()
        }

        private fun ensureSyncStreams(sortedUids: List<String>) {
            sortedUids.chunked(SYNC_SQL_UID_BATCH_SIZE).forEach { uidBatch ->
                val rows = uidBatch.joinToString(", ") { "(?::varchar, ?::integer)" }
                val args = buildList<Pair<IColumnType<*>, Any?>> {
                    uidBatch.forEachIndexed { lockOrder, uid ->
                        add(SyncStreams.uid.columnType to uid)
                        add(SyncEvents.eventType.columnType to lockOrder)
                    }
                }
                exposedTransaction.execRawSql(
                    stmt = """
                        WITH candidates(uid, lock_order) AS (VALUES $rows)
                        INSERT INTO sync_streams(uid, last_seq)
                        SELECT candidate.uid, 0
                        FROM candidates candidate
                        ORDER BY candidate.lock_order
                        ON CONFLICT (uid) DO NOTHING
                    """.trimIndent(),
                    args = args,
                    explicitStatementType = StatementType.INSERT,
                )
            }
        }

        private fun lockSyncStreams(sortedUids: List<String>): Map<String, Long> {
            val streams = linkedMapOf<String, Long>()
            sortedUids.chunked(SYNC_SQL_UID_BATCH_SIZE).forEach { uidBatch ->
                val rows = uidBatch.joinToString(", ") { "(?::varchar, ?::integer)" }
                val args = buildList<Pair<IColumnType<*>, Any?>> {
                    uidBatch.forEachIndexed { lockOrder, uid ->
                        add(SyncStreams.uid.columnType to uid)
                        add(SyncEvents.eventType.columnType to lockOrder)
                    }
                }
                val lockedBatch: List<Pair<String, Long>> = exposedTransaction.execRawSql(
                    stmt = """
                        WITH candidates(uid, lock_order) AS (VALUES $rows)
                        SELECT stream_row.uid, stream_row.last_seq
                        FROM candidates candidate
                        JOIN sync_streams stream_row ON stream_row.uid = candidate.uid
                        ORDER BY candidate.lock_order
                        FOR UPDATE OF stream_row
                    """.trimIndent(),
                    args = args,
                    explicitStatementType = StatementType.SELECT,
                ) { resultSet: ResultSet ->
                    buildList<Pair<String, Long>> {
                        while (resultSet.next()) {
                            add(resultSet.getString("uid") to resultSet.getLong("last_seq"))
                        }
                    }
                } ?: error("Durable sync stream lock returned no result set")
                check(lockedBatch.map { it.first } == uidBatch) {
                    "Durable sync stream locks were not acquired in canonical uid order"
                }
                lockedBatch.forEach { (uid, lastSeq) ->
                    check(streams.put(uid, lastSeq) == null) {
                        "Durable sync stream lock returned a duplicate uid"
                    }
                }
            }
            return streams
        }

        private fun insertSyncEvents(events: List<SequencedEvent>) {
            var offset = 0
            while (offset < events.size) {
                val payloads = IdentityHashMap<ByteArray, Unit>()
                var uniquePayloadBytes = 0L
                var endExclusive = offset
                while (endExclusive < events.size && endExclusive - offset < SYNC_EVENT_INSERT_MAX_ROWS) {
                    val payload = events[endExclusive].intent.encoded
                    val isNewPayload = !payloads.containsKey(payload)
                    val nextBytes = if (isNewPayload) uniquePayloadBytes + payload.size else uniquePayloadBytes
                    if (
                        endExclusive > offset &&
                        isNewPayload &&
                        nextBytes > SYNC_EVENT_INSERT_MAX_UNIQUE_PAYLOAD_BYTES
                    ) {
                        break
                    }
                    if (isNewPayload) {
                        payloads[payload] = Unit
                        uniquePayloadBytes = nextBytes
                    }
                    endExclusive += 1
                }
                insertSyncEventBatch(events.subList(offset, endExclusive))
                offset = endExclusive
            }
        }

        private fun insertSyncEventBatch(events: List<SequencedEvent>) {
            check(events.isNotEmpty()) { "Durable event insert batch must not be empty" }
            val payloadSlots = IdentityHashMap<ByteArray, Int>()
            val payloads = mutableListOf<ByteArray>()
            events.forEach { event ->
                if (!payloadSlots.containsKey(event.intent.encoded)) {
                    payloadSlots[event.intent.encoded] = payloads.size
                    payloads += event.intent.encoded
                }
            }
            val payloadRows = payloads.joinToString(", ") { "(?::integer, ?::bytea)" }
            val eventRows = events.joinToString(", ") {
                "(?::varchar, ?::bigint, ?::integer, ?::integer, ?::bigint, ?::bigint)"
            }
            val args = buildList<Pair<IColumnType<*>, Any?>> {
                payloads.forEachIndexed { slot, payload ->
                    add(SyncEvents.eventType.columnType to slot)
                    add(SyncEvents.payload.columnType to payload)
                }
                events.forEach { event ->
                    add(SyncEvents.uid.columnType to event.intent.uid)
                    add(SyncEvents.streamSeq.columnType to event.streamSeq)
                    add(SyncEvents.eventType.columnType to event.intent.notifyType.code)
                    add(SyncEvents.eventType.columnType to payloadSlots.getValue(event.intent.encoded))
                    add(SyncEvents.createdAt.columnType to event.intent.createdAt)
                    add(SyncEvents.nextAttemptAt.columnType to event.intent.createdAt)
                }
            }
            val insertedCount: Int = exposedTransaction.execRawSql(
                stmt = """
                    WITH payload_values(payload_slot, payload) AS (VALUES $payloadRows),
                    event_values(
                        uid,
                        stream_seq,
                        event_type,
                        payload_slot,
                        created_at,
                        next_attempt_at
                    ) AS (VALUES $eventRows),
                    inserted AS (
                        INSERT INTO sync_events (
                            uid,
                            stream_seq,
                            event_type,
                            payload,
                            created_at,
                            next_attempt_at
                        )
                        SELECT event_row.uid,
                               event_row.stream_seq,
                               event_row.event_type,
                               payload.payload,
                               event_row.created_at,
                               event_row.next_attempt_at
                        FROM event_values event_row
                        JOIN payload_values payload USING (payload_slot)
                        ORDER BY event_row.uid, event_row.stream_seq
                        RETURNING uid, stream_seq
                    )
                    SELECT COUNT(*) AS inserted_count FROM inserted
                """.trimIndent(),
                args = args,
                explicitStatementType = StatementType.SELECT,
            ) { resultSet: ResultSet ->
                check(resultSet.next()) { "Durable event insert returned no count row" }
                resultSet.getInt("inserted_count")
            } ?: error("Durable event insert returned no result set")
            check(insertedCount == events.size) { "Durable event batch insert was incomplete" }
        }

        private fun updateSyncStreams(
            lockedStreams: Map<String, Long>,
            nextSeqByUid: Map<String, Long>,
        ) {
            nextSeqByUid.entries.chunked(SYNC_SQL_UID_BATCH_SIZE).forEach { streamBatch ->
                val rows = streamBatch.joinToString(", ") {
                    "(?::varchar, ?::bigint, ?::bigint)"
                }
                val args = buildList<Pair<IColumnType<*>, Any?>> {
                    streamBatch.forEach { (uid, nextSeq) ->
                        add(SyncStreams.uid.columnType to uid)
                        add(SyncStreams.lastSeq.columnType to lockedStreams.getValue(uid))
                        add(SyncStreams.lastSeq.columnType to nextSeq)
                    }
                }
                val updatedUids: List<String> = exposedTransaction.execRawSql(
                    stmt = """
                        WITH next_streams(uid, expected_last_seq, next_last_seq) AS (VALUES $rows),
                        updated AS (
                            UPDATE sync_streams stream_row
                            SET last_seq = next_stream.next_last_seq
                            FROM next_streams next_stream
                            WHERE stream_row.uid = next_stream.uid
                              AND stream_row.last_seq = next_stream.expected_last_seq
                            RETURNING stream_row.uid
                        )
                        SELECT uid FROM updated ORDER BY uid
                    """.trimIndent(),
                    args = args,
                    explicitStatementType = StatementType.SELECT,
                ) { resultSet: ResultSet ->
                    buildList<String> {
                        while (resultSet.next()) add(resultSet.getString("uid"))
                    }
                } ?: error("Durable sync stream update returned no result set")
                requireExactUpdatedUids(streamBatch.map { it.key }, updatedUids)
            }
        }

        private fun requireExactUpdatedUids(expectedUids: List<String>, updatedUids: List<String>) {
            val expected = expectedUids.toSet()
            val updated = updatedUids.toSet()
            val exactUniqueSet =
                expected.size == expectedUids.size &&
                    updated.size == updatedUids.size &&
                    expected.size == updated.size &&
                    expected == updated
            check(exactUniqueSet) {
                val duplicates = updatedUids.groupingBy { it }.eachCount()
                    .filterValues { count -> count > 1 }
                    .keys
                    .take(DIAGNOSTIC_UID_LIMIT)
                "Locked durable sync stream batch update was incomplete: " +
                    "expectedCount=${expectedUids.size}, updatedCount=${updatedUids.size}, " +
                    "missing=${(expected - updated).take(DIAGNOSTIC_UID_LIMIT)}, " +
                    "unexpected=${(updated - expected).take(DIAGNOSTIC_UID_LIMIT)}, " +
                    "duplicates=$duplicates"
            }
        }
    }
}

private const val SYNC_SQL_UID_BATCH_SIZE = 512
// 即使每个事件的载荷都不同，一个批次最多绑定 16,384 个参数
// （每个载荷 2 个 + 每个事件 6 个），远低于 PostgreSQL 的 65,535 参数协议上限。
private const val SYNC_EVENT_INSERT_MAX_ROWS = 2_048
private const val SYNC_EVENT_INSERT_MAX_UNIQUE_PAYLOAD_BYTES = 32L * 1024L * 1024L
private const val DIAGNOSTIC_UID_LIMIT = 8

internal open class ExposedPgReadTransactionContext(
    internal val exposedTransaction: Transaction,
) : PgReadTransactionContext

internal class ExposedPgWriteTransactionContext(
    exposedTransaction: Transaction,
) : ExposedPgReadTransactionContext(exposedTransaction), PgWriteTransactionContext

/** 解析一个可写的不透明句柄，同时不允许通过读快照进行变更。 */
internal fun PgWriteTransactionContext.requireExposedTransaction(): Transaction {
    val context = this as? ExposedPgWriteTransactionContext
        ?: error("Repository mutation requires an Exposed PgUnitOfWork transaction")
    check(TransactionManager.currentOrNull() === context.exposedTransaction) {
        "Repository mutation escaped its active Exposed PgUnitOfWork transaction"
    }
    return context.exposedTransaction
}

/** 为查询解析不透明句柄；读和写 UoW 都可以组合事务读。 */
internal fun PgReadTransactionContext.requireExposedReadTransaction(): Transaction {
    val context = this as? ExposedPgReadTransactionContext
        ?: error("Repository read requires an Exposed PgUnitOfWork transaction")
    check(TransactionManager.currentOrNull() === context.exposedTransaction) {
        "Repository read escaped its active Exposed PgUnitOfWork transaction"
    }
    return context.exposedTransaction
}
