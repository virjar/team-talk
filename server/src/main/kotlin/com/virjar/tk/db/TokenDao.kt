package com.virjar.tk.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

object TokenDao {

    fun create(uid: String, refreshToken: String, deviceFlag: Int, expiresAt: Long) {
        transaction {
            Tokens.insert {
                it[Tokens.uid] = uid
                it[Tokens.refreshToken] = refreshToken
                it[Tokens.deviceFlag] = deviceFlag
                it[Tokens.expiresAt] = expiresAt
                it[Tokens.createdAt] = System.currentTimeMillis()
            }
        }
    }

    fun findByRefreshToken(token: String): Pair<String, Int>? {
        return transaction {
            val now = System.currentTimeMillis()
            val row = Tokens.selectAll().where {
                Tokens.refreshToken eq token
            }.singleOrNull()
            if (row != null) {
                val exp = row[Tokens.expiresAt]
                if (exp > now) {
                    row[Tokens.uid] to row[Tokens.deviceFlag]
                } else {
                    null
                }
            } else {
                null
            }
        }
    }

    fun deleteByRefreshToken(token: String) {
        transaction {
            Tokens.deleteWhere {
                with(SqlExpressionBuilder) { refreshToken eq token }
            }
        }
    }

    fun deleteByUid(userId: String) {
        transaction {
            Tokens.deleteWhere {
                with(SqlExpressionBuilder) { uid eq userId }
            }
        }
    }
}
