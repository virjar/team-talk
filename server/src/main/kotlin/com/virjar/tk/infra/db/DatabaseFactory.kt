package com.virjar.tk.infra.db

import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.sql.Connection

private val logger = LoggerFactory.getLogger("DatabaseFactory")

object DatabaseFactory {

    @Volatile
    private var current: HikariDataSource? = null

    /**
     * 幂等初始化：重复 create 先关闭旧池（测试环境每用例重建；同一 JDBC 复用连接）。
     * 池上限可注入——测试进程内多环境共存时必须压小，防打爆 PG max_connections。
     */
    fun create(
        jdbcUrl: String = System.getenv("DATABASE_JDBC_URL")
            ?: "jdbc:postgresql://localhost:5432/teamtalk",
        user: String = System.getenv("DATABASE_USER")
            ?: "teamtalk",
        password: String = System.getenv("DATABASE_PASSWORD")
            ?: "postgres",
        maxPoolSize: Int = 10,
    ) {
        current?.let { old ->
            if (old.jdbcUrl == jdbcUrl) return  // 同库已连接，幂等复用
            old.close()
        }
        val ds = HikariDataSource().apply {
            this.jdbcUrl = jdbcUrl
            this.username = user
            this.password = password
            maximumPoolSize = maxPoolSize
            driverClassName = "org.postgresql.Driver"
            validate()
        }
        current = ds

        org.jetbrains.exposed.sql.Database.connect(ds)

        transaction(Connection.TRANSACTION_SERIALIZABLE) {
            SchemaUtils.createMissingTablesAndColumns(
                Users, Devices, Chats, GroupChats, GroupMembers, GroupMemberMutes,
                Conversations, Friends, FriendApplies, GroupInviteLinks, SyncEvents,
                OrganizationUnits, OrganizationMemberships,
                AutomationBots, AutomationBotGrants,
                GroupFileEntries, GroupFileVersions, GroupFileAudits,
                DocumentSpaces, DocumentSpaceGrants, DocumentNodes, DocumentContentRevisions,
                DocumentUserRecents,
            )
            // Exposed 的 createMissingTablesAndColumns 不会把既有 VARCHAR(500) 自动扩为 TEXT。
            // Markdown 源码草稿必须升级为无损正文列；该 PostgreSQL DDL 对新库和已升级库均幂等。
            exec("ALTER TABLE conversations ALTER COLUMN draft TYPE TEXT")
        }

        val reconciledFriendApplies = reconcilePendingFriendApplyUniqueness()
        if (reconciledFriendApplies > 0) {
            logger.info(
                "Reconciled {} duplicate pending friend applications before creating the uniqueness guard",
                reconciledFriendApplies,
            )
        }

        logger.info("Database initialized: $jdbcUrl")
    }

    /**
     * 兼容旧客户端曾重复写入的同方向 pending 申请。
     *
     * Schema 初始化使用 SERIALIZABLE 长事务，不能直接把数据修复塞进去：滚动部署时，旧节点可能
     * 在该事务早期快照之后继续写入。因此这里使用独立 READ_COMMITTED 事务，并在读取前锁表：
     * 1. 多个新节点的启动修复彼此串行；
     * 2. 旧节点的 INSERT/UPDATE 在“清理 -> 建唯一索引”期间被阻塞；
     * 3. 部分唯一索引提交后永久阻止同一 from/to 再出现两个 pending。
     *
     * 只把每个有向用户对中 id 较小的 pending 标为 superseded（status=3），不删除任何历史记录。
     * 已存在唯一索引时快速返回，避免正常重启无意义地锁表。
     */
    internal fun reconcilePendingFriendApplyUniqueness(): Int =
        transaction(Connection.TRANSACTION_READ_COMMITTED) {
            if (pendingFriendApplyIndexExists()) return@transaction 0

            exec("LOCK TABLE friend_applies IN SHARE ROW EXCLUSIVE MODE")

            // 另一启动节点可能在本节点等待表锁期间已经完成迁移；取得锁后必须复查。
            if (pendingFriendApplyIndexExists()) return@transaction 0

            val reconciled = exec(
                """
                WITH ranked_pending AS (
                    SELECT
                        id,
                        ROW_NUMBER() OVER (
                            PARTITION BY from_uid, to_uid
                            ORDER BY id DESC
                        ) AS pending_rank
                    FROM friend_applies
                    WHERE status = 0
                ), superseded_duplicates AS (
                    UPDATE friend_applies AS applies
                    SET
                        status = 3,
                        updated_at = GREATEST(
                            applies.updated_at,
                            (EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::BIGINT
                        )
                    FROM ranked_pending AS ranked
                    WHERE applies.id = ranked.id
                      AND ranked.pending_rank > 1
                      AND applies.status = 0
                    RETURNING applies.id
                )
                SELECT COUNT(*) FROM superseded_duplicates
                """.trimIndent(),
                explicitStatementType = StatementType.SELECT,
            ) { result ->
                if (result.next()) result.getInt(1) else 0
            } ?: 0

            exec(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS $PENDING_FRIEND_APPLY_INDEX
                ON friend_applies (from_uid, to_uid)
                WHERE status = 0
                """.trimIndent(),
            )
            reconciled
        }

    private fun Transaction.pendingFriendApplyIndexExists(): Boolean = exec(
        "SELECT to_regclass('public.$PENDING_FRIEND_APPLY_INDEX') IS NOT NULL",
    ) { result ->
        result.next() && result.getBoolean(1)
    } ?: false

    internal const val PENDING_FRIEND_APPLY_INDEX = "uq_friend_applies_pending_direction"
}
