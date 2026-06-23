package com.virjar.tk.infra.db

import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.sql.Connection

private val logger = LoggerFactory.getLogger("DatabaseFactory")

object DatabaseFactory {

    fun create(
        jdbcUrl: String = System.getenv("DATABASE_JDBC_URL")
            ?: "jdbc:postgresql://localhost:5432/teamtalk",
        user: String = System.getenv("DATABASE_USER")
            ?: "teamtalk",
        password: String = System.getenv("DATABASE_PASSWORD")
            ?: "postgres",
    ) {
        val ds = HikariDataSource().apply {
            this.jdbcUrl = jdbcUrl
            this.username = user
            this.password = password
            maximumPoolSize = 10
            driverClassName = "org.postgresql.Driver"
            validate()
        }

        org.jetbrains.exposed.sql.Database.connect(ds)

        transaction(Connection.TRANSACTION_SERIALIZABLE) {
            SchemaUtils.createMissingTablesAndColumns(
                Users, Devices, Chats, GroupChats, GroupMembers, GroupMemberMutes,
                Conversations, Friends, FriendApplies, GroupInviteLinks, SyncEvents
            )
        }

        logger.info("Database initialized: $jdbcUrl")
    }
}
