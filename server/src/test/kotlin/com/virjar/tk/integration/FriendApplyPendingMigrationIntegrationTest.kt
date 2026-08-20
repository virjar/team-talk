package com.virjar.tk.integration

import com.virjar.tk.infra.db.DatabaseFactory
import com.virjar.tk.infra.db.FriendApplies
import com.virjar.tk.model.ContactApplyRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FriendApplyPendingMigrationIntegrationTest {

    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `startup reconciliation preserves history and is concurrent idempotent`() = runTest {
        val sender = ctx.registerUser(uniqueUsername("migration-sender"))
        val recipient = ctx.registerUser(uniqueUsername("migration-recipient"))
        val other = ctx.registerUser(uniqueUsername("migration-other"))

        transaction {
            exec("DROP INDEX IF EXISTS ${DatabaseFactory.PENDING_FRIEND_APPLY_INDEX}")
        }

        try {
            val inserted = transaction {
                val older1 = insertApply(sender, recipient, ContactApplyRecord.STATUS_PENDING, updatedAt = 10)
                val older2 = insertApply(sender, recipient, ContactApplyRecord.STATUS_PENDING, updatedAt = 20)
                val latest = insertApply(sender, recipient, ContactApplyRecord.STATUS_PENDING, updatedAt = 30)
                val accepted = insertApply(sender, recipient, ContactApplyRecord.STATUS_ACCEPTED, updatedAt = 40)
                val otherPair = insertApply(sender, other, ContactApplyRecord.STATUS_PENDING, updatedAt = 50)
                val reverse = insertApply(recipient, sender, ContactApplyRecord.STATUS_PENDING, updatedAt = 60)
                MigrationRows(older1, older2, latest, accepted, otherPair, reverse)
            }

            // 模拟两个服务节点同时启动：表锁必须让迁移串行，且总共只处理两条重复记录。
            val reconciledCounts = coroutineScope {
                List(2) {
                    async(Dispatchers.IO) {
                        DatabaseFactory.reconcilePendingFriendApplyUniqueness()
                    }
                }.awaitAll()
            }
            assertEquals(2, reconciledCounts.sum())

            val states = transaction {
                FriendApplies.selectAll().where {
                    ((FriendApplies.fromUid eq sender) and
                        ((FriendApplies.toUid eq recipient) or (FriendApplies.toUid eq other))) or
                        ((FriendApplies.fromUid eq recipient) and (FriendApplies.toUid eq sender))
                }
                    .orderBy(FriendApplies.id, SortOrder.ASC)
                    .associate { row ->
                        row[FriendApplies.id].value to ApplyState(
                            status = row[FriendApplies.status],
                            updatedAt = row[FriendApplies.updatedAt],
                        )
                    }
            }

            assertEquals(6, states.size, "迁移只能改状态，不能删除申请历史")
            assertEquals(ContactApplyRecord.STATUS_SUPERSEDED, states.getValue(inserted.older1).status)
            assertEquals(ContactApplyRecord.STATUS_SUPERSEDED, states.getValue(inserted.older2).status)
            assertTrue(states.getValue(inserted.older1).updatedAt > 10)
            assertTrue(states.getValue(inserted.older2).updatedAt > 20)
            assertEquals(ContactApplyRecord.STATUS_PENDING, states.getValue(inserted.latest).status)
            assertEquals(ContactApplyRecord.STATUS_ACCEPTED, states.getValue(inserted.accepted).status)
            assertEquals(ContactApplyRecord.STATUS_PENDING, states.getValue(inserted.otherPair).status)
            // 本次兼容规则按有向 from/to 分区，反向申请不属于同方向重复。
            assertEquals(ContactApplyRecord.STATUS_PENDING, states.getValue(inserted.reverse).status)

            assertEquals(0, DatabaseFactory.reconcilePendingFriendApplyUniqueness())

            // 部分唯一索引是迁移之后的永久兜底；同方向已有 pending 时不能再次写入。
            assertFailsWith<ExposedSQLException> {
                transaction {
                    insertApply(sender, recipient, ContactApplyRecord.STATUS_PENDING, updatedAt = 70)
                }
            }
        } finally {
            // 即使断言失败，也恢复共享集成测试数据库的唯一索引，避免污染后续测试类。
            DatabaseFactory.reconcilePendingFriendApplyUniqueness()
        }
    }

    private fun insertApply(fromUid: String, toUid: String, status: Int, updatedAt: Long): Long {
        val token = UUID.randomUUID().toString()
        return (FriendApplies.insert {
            it[FriendApplies.fromUid] = fromUid
            it[FriendApplies.toUid] = toUid
            it[FriendApplies.token] = token
            it[FriendApplies.remark] = null
            it[FriendApplies.status] = status
            it[FriendApplies.createdAt] = updatedAt
            it[FriendApplies.updatedAt] = updatedAt
        } get FriendApplies.id).value
    }

    private data class MigrationRows(
        val older1: Long,
        val older2: Long,
        val latest: Long,
        val accepted: Long,
        val otherPair: Long,
        val reverse: Long,
    )

    private data class ApplyState(
        val status: Int,
        val updatedAt: Long,
    )
}
