package com.virjar.tk.infra.db

import com.virjar.tk.infra.ServerDataEpoch
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.sql.Connection

private val logger = LoggerFactory.getLogger("DatabaseFactory")

object DatabaseFactory {

    /** Any incompatible relational change increments this number and requires a test-data reset. */
    const val CURRENT_SCHEMA_EPOCH = ServerDataEpoch.CURRENT_EPOCH

    private val schemaTables = arrayOf(
        SchemaMetadata,
        Users, Devices, Chats, GroupChats, GroupMembers, GroupMemberMutes,
        Conversations, Friends, FriendApplies, GroupInviteLinks, SyncEvents,
        OrganizationUnits, OrganizationMemberships,
        AutomationBots, AutomationBotGrants,
        GroupFileEntries, GroupFileVersions, GroupFileAudits,
        DocumentSpaces, DocumentSpaceGrants, DocumentNodes, DocumentContentRevisions,
        DocumentUserRecents,
    )

    @Volatile
    private var current: HikariDataSource? = null

    /**
     * 幂等初始化：重复 create 先关闭旧池（测试环境每用例重建；同一 JDBC 复用连接）。
     * 池上限可注入——测试进程内多环境共存时必须压小，防打爆 PG max_connections。
     */
    @Synchronized
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
            if (old.jdbcUrl == jdbcUrl && old.username == user && !old.isClosed) {
                try {
                    transaction(Connection.TRANSACTION_SERIALIZABLE) { requireCurrentSchemaEpoch() }
                    return
                } catch (error: Throwable) {
                    current = null
                    runCatching { old.close() }.onFailure(error::addSuppressed)
                    throw error
                }
            }
            current = null
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
        try {
            org.jetbrains.exposed.sql.Database.connect(ds)
            transaction(Connection.TRANSACTION_SERIALIZABLE) { initializeOrValidateSchema() }
            current = ds
        } catch (error: Throwable) {
            runCatching { ds.close() }.onFailure(error::addSuppressed)
            throw error
        }

        logger.info("Database schema epoch {} initialized: {}", CURRENT_SCHEMA_EPOCH, jdbcUrl)
    }

    /** Release the process-owned JDBC pool. Safe to call repeatedly during partial startup. */
    @Synchronized
    fun close() {
        val dataSource = current ?: return
        current = null
        dataSource.close()
    }

    private fun Transaction.initializeOrValidateSchema() {
        if (relationExists(SchemaMetadata.tableName)) {
            requireCurrentSchemaEpoch()
            return
        }

        val existingTables = schemaTables
            .asSequence()
            .drop(1)
            .map { it.tableName }
            .filter { relationExists(it) }
            .toList()
        if (existingTables.isNotEmpty()) {
            throw SchemaResetRequiredException(
                "Database predates schema epoch $CURRENT_SCHEMA_EPOCH; reset the disposable " +
                    "pre-release database before startup (found: ${existingTables.take(5).joinToString()})",
            )
        }

        SchemaUtils.create(*schemaTables)
        SchemaMetadata.insert {
            it[id] = SCHEMA_METADATA_ID
            it[epoch] = CURRENT_SCHEMA_EPOCH
            it[createdAt] = System.currentTimeMillis()
        }
    }

    private fun Transaction.requireCurrentSchemaEpoch() {
        if (!relationExists(SchemaMetadata.tableName)) {
            throw SchemaResetRequiredException("Database schema metadata is missing; reset the pre-release database")
        }
        val actual = SchemaMetadata.selectAll()
            .where { SchemaMetadata.id eq SCHEMA_METADATA_ID }
            .singleOrNull()
            ?.get(SchemaMetadata.epoch)
            ?: throw SchemaResetRequiredException("Database schema epoch marker is missing; reset the pre-release database")
        if (actual != CURRENT_SCHEMA_EPOCH) {
            throw SchemaResetRequiredException(
                "Database schema epoch $actual is incompatible with required epoch $CURRENT_SCHEMA_EPOCH; " +
                    "reset the pre-release database",
            )
        }
    }

    private fun Transaction.relationExists(tableName: String): Boolean {
        require(tableName.matches(RELATION_IDENTIFIER)) { "Unsafe relation name: $tableName" }
        return exec(
            "SELECT to_regclass(quote_ident(current_schema()) || '.' || quote_ident('$tableName')) IS NOT NULL",
        ) { result -> result.next() && result.getBoolean(1) } ?: false
    }

    private const val SCHEMA_METADATA_ID = 1
    private val RELATION_IDENTIFIER = Regex("[a-z][a-z0-9_]*")
}

class SchemaResetRequiredException(message: String) : IllegalStateException(message)
