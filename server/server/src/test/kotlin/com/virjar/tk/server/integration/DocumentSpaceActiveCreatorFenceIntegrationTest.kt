package com.virjar.tk.server.integration

import com.virjar.tk.server.domain.document.DocumentAccessDeniedException
import com.virjar.tk.server.domain.document.DocumentRepository
import com.virjar.tk.server.domain.document.DocumentService
import com.virjar.tk.server.domain.document.DocumentWriteAuthority
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.infra.db.DocumentSpaces
import com.virjar.tk.server.infra.db.requireExposedTransaction
import com.virjar.tk.server.infra.db.repository.CredentialMutation
import com.virjar.tk.server.infra.db.repository.CredentialRepositoryHooks
import com.virjar.tk.server.infra.db.repository.ExposedCredentialRepository
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpaceCreateResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentSpaceActiveCreatorFenceIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `revoked user cannot create a document space`() = runBlocking {
        val uid = ctx.registerUser(uniqueUsername("document-space-revoked-creator"))
        val spaceId = UUID.randomUUID().toString()
        ctx.credentialAdministration.banUser(uid)

        val failure = assertFailsWith<IllegalArgumentException> {
            ctx.documentService.createSpaceCommand(uid, spaceId, "已封禁用户的空间", null)
        }

        assertEquals("只有活动普通用户可以创建文档空间", failure.message)
        assertSpaceAbsent(ctx.database, spaceId)
    }

    @Test
    fun `committed create exact retry survives ban while a new id is rejected`() = runBlocking {
        val uid = ctx.registerUser(uniqueUsername("document-space-revoked-replay"))
        val committedSpaceId = UUID.randomUUID().toString()
        val newSpaceId = UUID.randomUUID().toString()

        val committed = ctx.documentService.createSpaceCommand(uid, committedSpaceId, "已提交空间", "稳定请求")
        assertEquals(committedSpaceId, committed.spaceId)
        ctx.credentialAdministration.banUser(uid)

        val exactReplay = ctx.documentService.createSpaceCommand(uid, committedSpaceId, "已提交空间", "稳定请求")
        assertEquals(committedSpaceId, exactReplay.spaceId)
        assertNull(exactReplay.space, "receipt confirmation must not expose current data to a revoked actor")

        val rejected = assertFailsWith<IllegalArgumentException> {
            ctx.documentService.createSpaceCommand(uid, newSpaceId, "封禁后的新空间", null)
        }
        assertEquals("只有活动普通用户可以创建文档空间", rejected.message)
        assertSpaceAbsent(ctx.database, newSpaceId)
    }

    @Test
    fun `create waits for in flight ban and persistence rejects revoked creator`() = runBlocking {
        val uid = ctx.registerUser(uniqueUsername("document-space-create-ban-race"))
        val spaceId = UUID.randomUUID().toString()
        val repositoryCallPaused = CountDownLatch(1)
        val allowPersistence = CountDownLatch(1)
        val enteringPersistence = CountDownLatch(1)
        val createBackendPid = AtomicInteger(-1)
        val releaseBan = CompletableDeferred<Unit>()
        val banHoldingUserLock = CompletableDeferred<Int>()

        val pausingRepository = object : DocumentRepository by ctx.documentRepo {
            override fun createSpace(
                transaction: PgWriteTransactionContext,
                space: DocumentSpace,
                creationFingerprint: String,
            ): DocumentSpaceCreateResult {
                createBackendPid.set(transaction.requireExposedTransaction().backendPid())
                repositoryCallPaused.countDown()
                check(allowPersistence.await(10, TimeUnit.SECONDS)) {
                    "test did not release document persistence"
                }
                enteringPersistence.countDown()
                return ctx.documentRepo.createSpace(transaction, space, creationFingerprint)
            }
        }
        val documentService = DocumentService(pausingRepository, ctx.pgUnitOfWork)
        val credentialRepository = ExposedCredentialRepository(
            database = ctx.database,
            hooks = object : CredentialRepositoryHooks {
                override suspend fun afterUserLock(
                    operation: CredentialMutation,
                    uid: String,
                    deviceId: String?,
                ) {
                    if (operation == CredentialMutation.BAN_USER) {
                        val transaction = checkNotNull(TransactionManager.currentOrNull())
                        banHoldingUserLock.complete(transaction.backendPid())
                        releaseBan.await()
                    }
                }
            },
        )

        coroutineScope {
            val create = async(Dispatchers.IO) {
                runCatching {
                    documentService.createSpaceCommand(uid, spaceId, "封禁竞争空间", null)
                }
            }
            var ban: kotlinx.coroutines.Deferred<Long>? = null
            try {
                assertTrue(
                    repositoryCallPaused.await(10, TimeUnit.SECONDS),
                    "create did not reach repository admission",
                )

                ban = async(Dispatchers.IO) { credentialRepository.banUser(uid) }
                val banBackendPid = withTimeout(10_000) { banHoldingUserLock.await() }

                allowPersistence.countDown()
                assertTrue(
                    enteringPersistence.await(10, TimeUnit.SECONDS),
                    "create did not enter its persistence fence",
                )
                val blocked = awaitExpectedDatabaseLock(
                    database = ctx.database,
                    waitingPid = createBackendPid.get(),
                    blockerPid = banBackendPid,
                )
                assertEquals("Lock", blocked.waitEventType)
                assertTrue(blocked.query.contains("users", ignoreCase = true))

                releaseBan.complete(Unit)
                withTimeout(10_000) { ban.await() }
                val createFailure = withTimeout(10_000) { create.await() }.exceptionOrNull()
                val rejected = assertIs<IllegalArgumentException>(createFailure)
                assertEquals("只有活动普通用户可以创建文档空间", rejected.message)
            } finally {
                allowPersistence.countDown()
                releaseBan.complete(Unit)
                ban?.let { pendingBan -> runCatching { withTimeout(10_000) { pendingBan.await() } } }
            }
        }

        assertSpaceAbsent(ctx.database, spaceId)
    }

    @Test
    fun `ordinary document write waits for ban user lock and then rejects revoked actor`() = runBlocking {
        val uid = ctx.registerUser(uniqueUsername("document-write-ban-race"))
        val space = ctx.documentService.createSpace(uid, UUID.randomUUID().toString(), "封禁竞争原名", null)
        val repositoryCallPaused = CountDownLatch(1)
        val allowAuthorityLock = CountDownLatch(1)
        val enteringAuthorityLock = CountDownLatch(1)
        val writeBackendPid = AtomicInteger(-1)
        val releaseBan = CompletableDeferred<Unit>()
        val banHoldingUserLock = CompletableDeferred<Int>()

        val pausingRepository = object : DocumentRepository by ctx.documentRepo {
            override fun lockWriteAuthority(
                transaction: PgWriteTransactionContext,
                actorUid: String,
                spaceId: String,
                requiredOrganizationUnitIds: Set<String>,
                requiredUserIds: Set<String>,
            ): DocumentWriteAuthority {
                writeBackendPid.set(transaction.requireExposedTransaction().backendPid())
                repositoryCallPaused.countDown()
                check(allowAuthorityLock.await(10, TimeUnit.SECONDS)) {
                    "test did not release document write authority"
                }
                enteringAuthorityLock.countDown()
                return ctx.documentRepo.lockWriteAuthority(
                    transaction,
                    actorUid,
                    spaceId,
                    requiredOrganizationUnitIds,
                    requiredUserIds,
                )
            }
        }
        val documentService = DocumentService(pausingRepository, ctx.pgUnitOfWork)
        val credentialRepository = ExposedCredentialRepository(
            database = ctx.database,
            hooks = object : CredentialRepositoryHooks {
                override suspend fun afterUserLock(
                    operation: CredentialMutation,
                    lockedUid: String,
                    deviceId: String?,
                ) {
                    if (operation == CredentialMutation.BAN_USER && lockedUid == uid) {
                        val transaction = checkNotNull(TransactionManager.currentOrNull())
                        banHoldingUserLock.complete(transaction.backendPid())
                        releaseBan.await()
                    }
                }
            },
        )

        coroutineScope {
            val write = async(Dispatchers.IO) {
                runCatching { documentService.updateSpace(uid, space.spaceId, "不应提交的新名", null) }
            }
            var ban: kotlinx.coroutines.Deferred<Long>? = null
            try {
                assertTrue(repositoryCallPaused.await(10, TimeUnit.SECONDS), "write did not reach authority admission")
                ban = async(Dispatchers.IO) { credentialRepository.banUser(uid) }
                val banBackendPid = withTimeout(10_000) { banHoldingUserLock.await() }

                allowAuthorityLock.countDown()
                assertTrue(
                    enteringAuthorityLock.await(10, TimeUnit.SECONDS),
                    "write did not enter the actor User fence",
                )
                val blocked = awaitExpectedDatabaseLock(
                    database = ctx.database,
                    waitingPid = writeBackendPid.get(),
                    blockerPid = banBackendPid,
                )
                assertEquals("Lock", blocked.waitEventType)
                assertTrue(blocked.query.contains("users", ignoreCase = true))

                releaseBan.complete(Unit)
                withTimeout(10_000) { ban.await() }
                val writeFailure = withTimeout(10_000) { write.await() }.exceptionOrNull()
                assertIs<DocumentAccessDeniedException>(writeFailure)
            } finally {
                allowAuthorityLock.countDown()
                releaseBan.complete(Unit)
                ban?.let { pendingBan -> runCatching { withTimeout(10_000) { pendingBan.await() } } }
            }
        }

        val persistedName = transaction(ctx.database) {
            DocumentSpaces.selectAll().where { DocumentSpaces.spaceId eq space.spaceId }
                .single()[DocumentSpaces.name]
        }
        assertEquals("封禁竞争原名", persistedName)
    }
}

private data class DatabaseLockSnapshot(
    val waitEventType: String?,
    val query: String,
)

private suspend fun awaitExpectedDatabaseLock(
    database: Database,
    waitingPid: Int,
    blockerPid: Int,
): DatabaseLockSnapshot = withTimeout(10_000) {
    while (true) {
        val snapshot = withContext(Dispatchers.IO) {
            transaction(database) {
                exec(
                    """
                    SELECT wait_event_type,
                           query,
                           $blockerPid = ANY(pg_blocking_pids($waitingPid)) AS blocked_by_expected
                    FROM pg_stat_activity
                    WHERE pid = $waitingPid
                    """.trimIndent(),
                ) { rows ->
                    if (!rows.next()) return@exec null
                    Triple(
                        rows.getString("wait_event_type"),
                        rows.getString("query"),
                        rows.getBoolean("blocked_by_expected"),
                    )
                }
            }
        }
        if (snapshot != null && snapshot.third && snapshot.first == "Lock") {
            return@withTimeout DatabaseLockSnapshot(snapshot.first, snapshot.second)
        }
        delay(10)
    }
    error("unreachable")
}

private fun org.jetbrains.exposed.sql.Transaction.backendPid(): Int = requireNotNull(
    exec("SELECT pg_backend_pid()") { rows ->
        check(rows.next())
        rows.getInt(1)
    },
)

private fun assertSpaceAbsent(database: Database, spaceId: String) {
    val count = transaction(database) {
        DocumentSpaces.selectAll().where { DocumentSpaces.spaceId eq spaceId }.count()
    }
    assertEquals(0L, count)
}
