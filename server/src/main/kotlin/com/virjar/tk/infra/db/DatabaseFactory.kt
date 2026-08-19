package com.virjar.tk.infra.db

import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.SchemaUtils
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

        logger.info("Database initialized: $jdbcUrl")
    }
}
