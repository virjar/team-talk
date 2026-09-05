package com.virjar.tk.server.integration

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.jetbrains.exposed.sql.transactions.TransactionManager

class ServerDatabaseOwnershipIntegrationTest {
    @Test
    fun `parallel containers keep database facts and pool lifecycle isolated`() = runBlocking {
        val environments = listOf(
            async(Dispatchers.IO) { TestEnvironment() },
            async(Dispatchers.IO) { TestEnvironment() },
        ).awaitAll()
        val first = environments[0]
        val second = environments[1]
        val firstDatabase = first.database
        val secondDatabase = second.database

        try {
            assertNotNull(TransactionManager.managerFor(firstDatabase))
            assertNotNull(TransactionManager.managerFor(secondDatabase))
            val firstUsername = uniqueUsername("database-owner-first")
            val secondUsername = uniqueUsername("database-owner-second")
            val registrations = listOf(
                async(Dispatchers.Default) { first.registerUser(firstUsername) },
                async(Dispatchers.Default) { second.registerUser(secondUsername) },
            ).awaitAll()

            assertNotNull(first.userRepo.findByUid(registrations[0]))
            assertNull(first.userRepo.findByUsername(secondUsername))
            assertNotNull(second.userRepo.findByUid(registrations[1]))
            assertNull(second.userRepo.findByUsername(firstUsername))

            // 关闭一个完整容器既不能关闭也不能重定向存活的图。
            first.close()
            assertNull(TransactionManager.managerFor(firstDatabase))
            assertNotNull(TransactionManager.managerFor(secondDatabase))
            val survivorUsername = uniqueUsername("database-owner-survivor")
            val survivorUid = second.registerUser(survivorUsername)
            assertNotNull(second.userRepo.findByUid(survivorUid))
        } finally {
            first.close()
            second.close()
        }
    }
}
