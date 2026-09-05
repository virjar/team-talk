package com.virjar.tk.server.infra.db

import com.virjar.tk.server.infra.ServerDataEpoch
import com.virjar.tk.protocol.payload.SyncDatasetIdPolicy
import com.virjar.tk.server.runtime.RuntimeFailureCollector
import com.virjar.tk.server.runtime.ServerResourceOwner
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CancellationException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.util.UUID

private val logger = LoggerFactory.getLogger("DatabaseFactory")

object DatabaseFactory {

    /** 独立的持久化版本；变更必须提供保留数据的迁移，不能随发布/协议重新编号。 */
    const val CURRENT_SCHEMA_EPOCH = ServerDataEpoch.CURRENT_EPOCH

    private val schemaTables = arrayOf(
        SchemaMetadata, SchemaMigrations,
        Users, Devices, Credentials, Chats, GroupChats, GroupCreationCommands,
        GroupMembers, GroupMemberMutes,
        Conversations, ConversationUsages, Friends, FriendApplies, ContactDecisionReceipts,
        GroupInviteLinks, InviteLinkCreationReceipts, SyncStreams, SyncEvents,
        ExternalProjectionReceipts, MessageReactions,
        OrganizationState, OrganizationUnits, OrganizationMemberships, OrganizationManagedChatProjections,
        AutomationBots, AutomationBotGrants, BotCredentialCommands,
        GroupFileEntries, GroupFileVersions, GroupFileChatUsages, GroupFileCommands, GroupFileAudits,
        DocumentDirectoryState, DocumentSpaces, DocumentSpaceCustodyTransfers, DocumentSpacePolicyCommands,
        DocumentNodeMoveCommands,
        DocumentCustodyBatchTransfers, DocumentCustodyBatchTransferItems,
        DocumentSpaceGrants, DocumentNodes, DocumentContentRevisions, DocumentEmbeddedAssets,
        DocumentUserRecents,
        ClientTelemetryDevices, ClientTelemetryPolicies, ClientTelemetryPolicyAudits,
        ClientTelemetryAdminAudits,
    )

    /**
     * 创建一个独立拥有的 PostgreSQL 运行时。
     *
     * Exposed 内部仍保留一个遗留的进程级 "default database"，但 TeamTalk 从不
     * 读取它：每个事务都显式接收 [PostgresDatabase.database]。因此两个
     * Ktor/测试容器可以共存，关闭一个句柄不能替换或关闭另一个的
     * 连接池。连接池大小保持可注入，使并行测试环境不会耗尽 PostgreSQL。
     */
    fun create(
        jdbcUrl: String = System.getenv("DATABASE_JDBC_URL")
            ?: "jdbc:postgresql://localhost:5432/teamtalk",
        user: String = System.getenv("DATABASE_USER")
            ?: "teamtalk",
        password: String = System.getenv("DATABASE_PASSWORD")
            ?: "postgres",
        maxPoolSize: Int = 10,
    ): PostgresDatabase {
        require(maxPoolSize > 0) { "maxPoolSize must be positive" }
        PostgresConnectionTimeoutPolicy.requireBoundedJdbcUrlTimeouts(jdbcUrl)
        val ds = HikariDataSource()
        try {
            ds.jdbcUrl = jdbcUrl
            ds.username = user
            ds.password = password
            ds.maximumPoolSize = maxPoolSize
            ds.driverClassName = "org.postgresql.Driver"
            PostgresConnectionTimeoutPolicy.apply(ds)
            // 泄漏诊断：连接被占用超过阈值时打印借用者堆栈（定位测试类切换
            // 窗口残留协程在池关闭后拿连接的 flaky）
            ds.leakDetectionThreshold = 5_000
            ds.validate()
        } catch (error: Throwable) {
            throw failureAfterCleanup(error, ds::close)
        }
        val database = try {
            // 这是唯一的生产 Exposed 连接工厂。守卫 DataSource
            // 而非每个仓库方法，可使每个外层 PostgreSQL 事务在意外运行于
            // 受保护的 EventLoop 上时，在获取连接池之前就失败。
            Database.connect(BlockingIoGuardDataSource(ds))
        } catch (error: Throwable) {
            throw failureAfterCleanup(error, ds::close)
        }
        try {
            val datasetId = transaction(
                // Existing schemas serialize startup on schema_metadata; after waiting, each statement
                // must see the preceding startup's committed migration receipts.
                transactionIsolation = Connection.TRANSACTION_READ_COMMITTED,
                db = database,
            ) { initializeOrValidateSchema() }
            // JDBC URL 可能在 query/user-info 组件中携带凭据；绝不输出它们。
            logger.info("Database schema epoch {} initialized", CURRENT_SCHEMA_EPOCH)
            return PostgresDatabase(database, ds, datasetId)
        } catch (error: Throwable) {
            throw failureAfterCleanup(
                error,
                { TransactionManager.closeAndUnregister(database) },
                { ds.close() },
            )
        }
    }

    private fun Transaction.initializeOrValidateSchema(): String {
        if (relationExists(SchemaMetadata.tableName)) {
            exec("LOCK TABLE schema_metadata IN EXCLUSIVE MODE")
            val datasetId = requireCurrentSchemaEpoch()
            applySchemaMigrations()
            return datasetId
        }

        val existingTables = schemaTables
            .asSequence()
            .drop(1)
            .map { it.tableName }
            .filter { relationExists(it) }
            .toList()
        if (existingTables.isNotEmpty()) {
            throw SchemaResetRequiredException(
                "Database predates schema epoch $CURRENT_SCHEMA_EPOCH; preserve the database and " +
                    "complete an explicit migration before startup (found: ${existingTables.take(5).joinToString()})",
            )
        }

        SchemaUtils.create(*schemaTables)
        val datasetId = UUID.randomUUID().toString()
        SchemaMetadata.insert {
            it[id] = SCHEMA_METADATA_ID
            it[epoch] = CURRENT_SCHEMA_EPOCH
            it[SchemaMetadata.datasetId] = datasetId
            it[createdAt] = System.currentTimeMillis()
        }
        OrganizationState.insert {
            it[id] = ORGANIZATION_STATE_ID
            it[revision] = 0L
            it[updatedAt] = System.currentTimeMillis()
        }
        DocumentDirectoryState.insert {
            it[id] = DOCUMENT_DIRECTORY_STATE_ID
            it[revision] = 0L
            it[updatedAt] = System.currentTimeMillis()
        }
        applySchemaMigrations()
        return datasetId
    }

    private fun Transaction.requireCurrentSchemaEpoch(): String {
        if (!relationExists(SchemaMetadata.tableName)) {
            throw SchemaResetRequiredException("Database schema metadata is missing; preserve the database and recover its verified metadata")
        }
        val metadata = SchemaMetadata.selectAll()
            .where { SchemaMetadata.id eq SCHEMA_METADATA_ID }
            .singleOrNull()
            ?: throw SchemaResetRequiredException("Database schema epoch marker is missing; preserve the database and recover its verified metadata")
        val actual = metadata[SchemaMetadata.epoch]
        if (actual != CURRENT_SCHEMA_EPOCH) {
            throw SchemaResetRequiredException(
                "Database schema epoch $actual is incompatible with required epoch $CURRENT_SCHEMA_EPOCH; " +
                    "preserve the database and use a compatible release or an explicit migration",
            )
        }
        val datasetId = metadata[SchemaMetadata.datasetId]
        try {
            SyncDatasetIdPolicy.requireValid(datasetId)
        } catch (_: IllegalArgumentException) {
            throw SchemaResetRequiredException(
                "Database dataset identity is invalid; preserve the database and recover its verified metadata",
            )
        }
        return datasetId
    }

    private fun Transaction.relationExists(tableName: String): Boolean {
        require(tableName.matches(RELATION_IDENTIFIER)) { "Unsafe relation name: $tableName" }
        return exec(
            "SELECT to_regclass(quote_ident(current_schema()) || '.' || quote_ident('$tableName')) IS NOT NULL",
        ) { result -> result.next() && result.getBoolean(1) } ?: false
    }

    private const val SCHEMA_METADATA_ID = 1
    private const val ORGANIZATION_STATE_ID = 1
    private const val DOCUMENT_DIRECTORY_STATE_ID = 1
    private val RELATION_IDENTIFIER = Regex("[a-z][a-z0-9_]*")
}

/**
 * 一个服务器/容器拥有的 Exposed 数据库及其 JDBC 连接池。
 *
 * [close] 是幂等的，只注销 [database]，且不能关闭另一个句柄的连接池。
 * 调用方必须把 [database] 绑定到其 Koin 模块中，并将此句柄注册到其自己的
 * 生命周期拥有者上。
 */
class PostgresDatabase internal constructor(
    val database: Database,
    private val dataSource: HikariDataSource,
    /** 仅对该 PostgreSQL 数据集稳定；每次空初始化都会生成一个新值。 */
    val datasetId: String,
) : AutoCloseable {
    private val closeResources = ServerResourceOwner { name, error ->
        logger.warn("Failed to close PostgreSQL resource {}", name, error)
    }.apply {
        // 逆获取顺序关闭：诊断、Exposed 注册，然后是 JDBC 连接池。
        own("Hikari JDBC pool") { dataSource.close() }
        own("Exposed database registration") { TransactionManager.closeAndUnregister(database) }
        own("active connection diagnostic") {
            val active = try {
                dataSource.hikariPoolMXBean.activeConnections
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logger.warn("Failed to inspect active PostgreSQL connections during close", failure)
                0
            }
            if (active > 0) {
                logger.warn(
                    "Closing HikariDataSource with {} active connection(s)",
                    active,
                )
            }
        }
    }

    override fun close() {
        closeResources.close()
    }
}

private fun failureAfterCleanup(
    primary: Throwable,
    vararg cleanupActions: () -> Unit,
): Throwable {
    val failures = RuntimeFailureCollector()
    failures.record(primary)
    cleanupActions.forEach(failures::capture)
    return checkNotNull(failures.failureOrNull())
}

class SchemaResetRequiredException(message: String) : IllegalStateException(message)
