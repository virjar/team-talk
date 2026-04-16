package com.virjar.tk.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

object DatabaseFactory {
    private val logger = LoggerFactory.getLogger(DatabaseFactory::class.java)

    private var db: Database? = null

    fun init(jdbcUrl: String, username: String, password: String) {
        val config = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = username
            this.password = password
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 10
            minimumIdle = 2
            isAutoCommit = false
            validate()
        }
        val dataSource = HikariDataSource(config)
        db = Database.connect(GuardedDataSource(dataSource))

        logger.info("Database connected: {}", jdbcUrl)
        createTables()
    }

    private fun createTables() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(Users, Devices, Tokens, Channels, ChannelMembers, ChannelMemberMutes, Conversations, Friends, FriendApplies, GroupInviteLinks)
        }
        logger.info("Database tables ensured")
    }

}
