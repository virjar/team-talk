package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.infra.db.ConversationUsages
import com.virjar.tk.server.infra.db.Conversations
import com.virjar.tk.server.infra.db.execRawSql
import com.virjar.tk.protocol.model.ConversationCapacityPolicy
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.IColumnType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.update
import java.sql.ResultSet

/** 在对应的 [ConversationUsages] 行被锁定时应用的一个聚合变更。 */
internal data class ConversationUsageDelta(
    val conversationCount: Int = 0,
    val draftCharacters: Long = 0L,
)

internal data class LockedConversationUsage(
    val conversationCount: Int,
    val draftCharacters: Long,
)

/**
 * 事务本地的 O(1) 容量台账。
 *
 * 调用方已经持有其拥有的 Chat/User/组织锁。它们必须先获取此每用户
 * 行，再锁定或变更任何 Conversation 行，然后用 [apply] 应用它们将要
 * 新增、删除或替换的确切行。排序获取会序列化同一用户拥有的独立聊天，
 * 而不会引入 Conversation 行扫描或 check-then-write 竞态。
 */
internal object ConversationUsageLedger {
    fun lock(uids: Collection<String>): MutableMap<String, LockedConversationUsage> = lockBatched(uids)

    /**
     * 与 [lock] 相同的容量 fence，以有界 PostgreSQL 批次获取。
     *
     * 消息投影可以合法地针对完整的 1,000 人群。为每个 uid 各发一条
     * INSERT（对新台账还要加一次一致性探测），会把一条已接受的消息
     * 变成数千次数据库往返。候选行与锁在此处仍是全局按 uid 排序的，
     * 但每条数据库语句最多携带 [LEDGER_BATCH_SIZE] 个 uid。
     */
    fun lockBatched(uids: Collection<String>): MutableMap<String, LockedConversationUsage> {
        val canonicalUids = uids.distinct().sorted()
        if (canonicalUids.isEmpty()) return linkedMapOf()

        val transaction = TransactionManager.current()
        val insertedUids = linkedSetOf<String>()
        canonicalUids.chunked(LEDGER_BATCH_SIZE).forEach { uidBatch ->
            val candidateRows = uidBatch.joinToString(", ") { "(?::varchar, ?::integer)" }
            val args = buildList<Pair<IColumnType<*>, Any?>> {
                uidBatch.forEachIndexed { lockOrder, uid ->
                    add(ConversationUsages.uid.columnType to uid)
                    add(ConversationUsages.conversationCount.columnType to lockOrder)
                }
                add(ConversationUsages.updatedAt.columnType to System.currentTimeMillis())
            }
            val inserted: List<String> = transaction.execRawSql(
                stmt = """
                    WITH candidates(uid, lock_order) AS (VALUES $candidateRows),
                    inserted AS (
                        INSERT INTO conversation_usages
                            (uid, conversation_count, draft_characters, updated_at)
                        SELECT candidate.uid, 0, 0, ?::bigint
                        FROM candidates candidate
                        ORDER BY candidate.lock_order
                        ON CONFLICT (uid) DO NOTHING
                        RETURNING uid
                    )
                    SELECT uid FROM inserted ORDER BY uid
                """.trimIndent(),
                args = args,
                explicitStatementType = StatementType.SELECT,
            ) { resultSet: ResultSet ->
                buildList<String> {
                    while (resultSet.next()) add(resultSet.getString("uid"))
                }
            } ?: error("Conversation usage ledger insert returned no result set")
            insertedUids += inserted
        }

        val locked = linkedMapOf<String, LockedConversationUsage>()
        canonicalUids.chunked(LEDGER_BATCH_SIZE).forEach { uidBatch ->
            val candidateRows = uidBatch.joinToString(", ") { "(?::varchar, ?::integer)" }
            val args = buildList<Pair<IColumnType<*>, Any?>> {
                uidBatch.forEachIndexed { lockOrder, uid ->
                    add(ConversationUsages.uid.columnType to uid)
                    add(ConversationUsages.conversationCount.columnType to lockOrder)
                }
            }
            val lockedBatch: List<Pair<String, LockedConversationUsage>> = transaction.execRawSql(
                stmt = """
                    WITH candidates(uid, lock_order) AS (VALUES $candidateRows)
                    SELECT usage_row.uid,
                           usage_row.conversation_count,
                           usage_row.draft_characters
                    FROM candidates candidate
                    JOIN conversation_usages usage_row ON usage_row.uid = candidate.uid
                    ORDER BY candidate.lock_order
                    FOR UPDATE OF usage_row
                """.trimIndent(),
                args = args,
                explicitStatementType = StatementType.SELECT,
            ) { resultSet: ResultSet ->
                buildList<Pair<String, LockedConversationUsage>> {
                    while (resultSet.next()) {
                        add(
                            resultSet.getString("uid") to LockedConversationUsage(
                                conversationCount = resultSet.getInt("conversation_count"),
                                draftCharacters = resultSet.getLong("draft_characters"),
                            ),
                        )
                    }
                }
            } ?: error("Conversation usage ledger lock returned no result set")
            check(lockedBatch.map { it.first } == uidBatch) {
                "Conversation usage ledger locks were not acquired in canonical uid order"
            }
            lockedBatch.forEach { (uid, usage) ->
                check(locked.put(uid, usage) == null) {
                    "Conversation usage ledger lock returned a duplicate uid"
                }
            }
        }
        check(locked.size == canonicalUids.size) { "Conversation usage ledger lock is incomplete" }

        // 预发布 epoch 没有修复/回填模式。在现有 Conversation 旁创建零值台账
        // 会认可一个已经损坏的聚合并击穿容量界限。
        insertedUids.toList().chunked(LEDGER_BATCH_SIZE).forEach { uidBatch ->
            check(
                Conversations.selectAll().where { Conversations.uid inList uidBatch }
                    .limit(1).count() == 0L,
            ) { "Conversation usage ledger is missing for an existing projection" }
        }
        return locked
    }

    fun apply(
        locked: MutableMap<String, LockedConversationUsage>,
        deltas: Map<String, ConversationUsageDelta>,
    ) {
        if (deltas.isEmpty()) return
        deltas.toSortedMap().forEach { (uid, delta) ->
            val current = checkNotNull(locked[uid]) {
                "Conversation usage delta was applied without its per-user lock"
            }
            val next = nextUsage(current, delta)

            check(ConversationUsages.update({ ConversationUsages.uid eq uid }) {
                it[ConversationUsages.conversationCount] = next.conversationCount
                it[ConversationUsages.draftCharacters] = next.draftCharacters
                it[ConversationUsages.updatedAt] = System.currentTimeMillis()
            } == 1) { "Locked Conversation usage ledger disappeared" }
            locked[uid] = next
        }
    }

    /** 以有界的多行 UPDATE 语句应用已锁定的聚合增量。 */
    fun applyBatched(
        locked: MutableMap<String, LockedConversationUsage>,
        deltas: Map<String, ConversationUsageDelta>,
    ) {
        if (deltas.isEmpty()) return
        val transitions = deltas.toSortedMap().map { (uid, delta) ->
            val current = checkNotNull(locked[uid]) {
                "Conversation usage delta was applied without its per-user lock"
            }
            UsageTransition(uid, current, nextUsage(current, delta))
        }
        val transaction = TransactionManager.current()
        transitions.chunked(LEDGER_BATCH_SIZE).forEach { transitionBatch ->
            val deltaRows = transitionBatch.joinToString(", ") {
                "(?::varchar, ?::integer, ?::bigint, ?::integer, ?::bigint)"
            }
            val args = buildList<Pair<IColumnType<*>, Any?>> {
                transitionBatch.forEach { transition ->
                    add(ConversationUsages.uid.columnType to transition.uid)
                    add(
                        ConversationUsages.conversationCount.columnType to
                            transition.current.conversationCount,
                    )
                    add(
                        ConversationUsages.draftCharacters.columnType to
                            transition.current.draftCharacters,
                    )
                    add(ConversationUsages.conversationCount.columnType to transition.next.conversationCount)
                    add(ConversationUsages.draftCharacters.columnType to transition.next.draftCharacters)
                }
                add(ConversationUsages.updatedAt.columnType to System.currentTimeMillis())
            }
            val updatedUids: List<String> = transaction.execRawSql(
                stmt = """
                    WITH deltas(
                        uid,
                        expected_conversation_count,
                        expected_draft_characters,
                        next_conversation_count,
                        next_draft_characters
                    ) AS (VALUES $deltaRows),
                    updated AS (
                        UPDATE conversation_usages usage
                        SET conversation_count = delta.next_conversation_count,
                            draft_characters = delta.next_draft_characters,
                            updated_at = ?::bigint
                        FROM deltas delta
                        WHERE usage.uid = delta.uid
                          AND usage.conversation_count = delta.expected_conversation_count
                          AND usage.draft_characters = delta.expected_draft_characters
                        RETURNING usage.uid
                    )
                    SELECT uid FROM updated ORDER BY uid
                """.trimIndent(),
                args = args,
                explicitStatementType = StatementType.SELECT,
            ) { resultSet: ResultSet ->
                buildList<String> {
                    while (resultSet.next()) add(resultSet.getString("uid"))
                }
            } ?: error("Conversation usage ledger update returned no result set")
            requireExactUpdatedUids(transitionBatch.map(UsageTransition::uid), updatedUids)
        }
        transitions.forEach { transition -> locked[transition.uid] = transition.next }
    }

    private fun nextUsage(
        current: LockedConversationUsage,
        delta: ConversationUsageDelta,
    ): LockedConversationUsage {
        val nextCountLong = current.conversationCount.toLong() + delta.conversationCount
        check(nextCountLong in 0L..Int.MAX_VALUE.toLong()) {
            "Conversation usage count ledger is inconsistent"
        }
        val nextCount = nextCountLong.toInt()
        if (delta.conversationCount > 0) {
            ConversationCapacityPolicy.requireAdditionalConversations(
                current.conversationCount,
                delta.conversationCount,
            )
        } else {
            ConversationCapacityPolicy.requireConversationCount(nextCount)
        }

        val nextDraftCharacters = current.draftCharacters + delta.draftCharacters
        check(
            delta.draftCharacters <= 0L ||
                nextDraftCharacters >= current.draftCharacters,
        ) { "Conversation draft usage ledger overflowed" }
        check(nextDraftCharacters >= 0L) {
            "Conversation draft usage ledger is inconsistent"
        }
        ConversationCapacityPolicy.requireDraftCharacters(nextDraftCharacters)
        return LockedConversationUsage(nextCount, nextDraftCharacters)
    }

    private data class UsageTransition(
        val uid: String,
        val current: LockedConversationUsage,
        val next: LockedConversationUsage,
    )

    private fun requireExactUpdatedUids(expectedUids: List<String>, updatedUids: List<String>) {
        val expected = expectedUids.toSet()
        val updated = updatedUids.toSet()
        check(
            expected.size == expectedUids.size &&
                updated.size == updatedUids.size &&
                expected.size == updated.size &&
                expected == updated,
        ) {
            "Locked Conversation usage ledger batch update was incomplete: " +
                "expectedCount=${expectedUids.size}, updatedCount=${updatedUids.size}, " +
                "missing=${(expected - updated).take(LEDGER_DIAGNOSTIC_UID_LIMIT)}, " +
                "unexpected=${(updated - expected).take(LEDGER_DIAGNOSTIC_UID_LIMIT)}"
        }
    }
}

private const val LEDGER_BATCH_SIZE = 512
private const val LEDGER_DIAGNOSTIC_UID_LIMIT = 8

internal fun conversationUsageDeltaForInsert(draft: String? = null) = ConversationUsageDelta(
    conversationCount = 1,
    draftCharacters = draft?.length?.toLong() ?: 0L,
)

internal fun conversationUsageDeltaForDelete(row: ResultRow) = ConversationUsageDelta(
    conversationCount = -1,
    draftCharacters = -(row[Conversations.draft]?.length?.toLong() ?: 0L),
)
