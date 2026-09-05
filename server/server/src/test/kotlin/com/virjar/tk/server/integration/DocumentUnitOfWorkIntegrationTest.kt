package com.virjar.tk.server.integration

import com.virjar.tk.server.domain.document.DocumentAccessDeniedException
import com.virjar.tk.server.domain.document.DocumentNotFoundException
import com.virjar.tk.server.domain.document.DocumentPolicyMutationFence
import com.virjar.tk.server.domain.document.DocumentRepository
import com.virjar.tk.server.domain.document.DocumentService
import com.virjar.tk.server.domain.document.DocumentWriteAuthority
import com.virjar.tk.server.domain.transaction.PgUnitOfWork
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.infra.db.DocumentNodes
import com.virjar.tk.server.infra.db.DocumentSpaces
import com.virjar.tk.server.infra.db.ExposedPgUnitOfWork
import com.virjar.tk.server.infra.db.OrganizationMemberships
import com.virjar.tk.server.infra.db.PgUnitOfWorkHooks
import com.virjar.tk.server.infra.db.PgUnitOfWorkStage
import com.virjar.tk.server.infra.db.Users
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import com.virjar.tk.protocol.model.OrganizationMember
import com.virjar.tk.protocol.model.OrganizationUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 针对文档聚合事务与 ACL 线性化边界的真实 PostgreSQL 证明测试。 */
class DocumentUnitOfWorkIntegrationTest {
    companion object {
        private const val TEST_TIMEOUT_SECONDS = 10L
        private const val BLOCKED_ASSERT_MILLIS = 200L

        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `space grant node and immutable revision writes roll back as one command`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("document-rollback-owner"))
        val member = ctx.registerUser(uniqueUsername("document-rollback-member"))
        val space = ctx.documentService.createSpace(owner, "回滚空间", "提交前")
        val overview = ctx.documentService.createDocument(owner, space.spaceId, null, "原综述", "# 综述")
        val document = ctx.documentService.createDocument(
            owner,
            space.spaceId,
            overview.documentId,
            "原文档",
            "# v1",
        )
        val failing = documentService(failingUnitOfWork())

        expectInjectedRollback { failing.createSpace(owner, "不应提交的空间", null) }
        assertEquals(listOf(space.spaceId), ctx.documentService.listSpaces(owner).map { it.spaceId })

        expectInjectedRollback { failing.updateSpace(owner, space.spaceId, "不应提交", "提交后") }
        assertEquals("回滚空间", ctx.readDocuments { findSpace(it, space.spaceId) }?.name)

        expectInjectedRollback {
            failing.upsertGrant(
                owner,
                space.spaceId,
                DocumentSpaceGrant.PRINCIPAL_USER,
                member,
                DocumentSpace.ROLE_EDITOR,
                false,
                expectedPolicyRevision = space.policyRevision,
                operationId = UUID.randomUUID().toString(),
            )
        }
        assertTrue(ctx.readDocuments { listGrants(it, space.spaceId) }.isEmpty())
        val committedUpsert = ctx.documentService.upsertGrant(
            owner,
            space.spaceId,
            DocumentSpaceGrant.PRINCIPAL_USER,
            member,
            DocumentSpace.ROLE_EDITOR,
            false,
            expectedPolicyRevision = space.policyRevision,
            operationId = UUID.randomUUID().toString(),
        )
        expectInjectedRollback {
            failing.removeGrant(
                owner,
                space.spaceId,
                DocumentSpaceGrant.PRINCIPAL_USER,
                member,
                expectedPolicyRevision = committedUpsert.policyRevision,
                operationId = UUID.randomUUID().toString(),
            )
        }
        assertEquals(member, ctx.readDocuments { listGrants(it, space.spaceId) }.single().principalId)

        expectInjectedRollback { failing.createDocument(owner, space.spaceId, null, "临时根文档", "") }
        assertEquals(
            listOf(overview.documentId),
            ctx.readDocuments { listNodes(it, space.spaceId, null) }.map { it.nodeId },
        )

        expectInjectedRollback {
            failing.createDocument(owner, space.spaceId, overview.documentId, "临时文档", "不应提交")
        }
        assertEquals(
            listOf(document.documentId),
            ctx.readDocuments { listNodes(it, space.spaceId, overview.documentId) }.map { it.nodeId },
        )

        expectInjectedRollback {
            failing.updateDocument(
                owner,
                space.spaceId,
                document.documentId,
                "# v2",
                document.revision,
            )
        }
        assertEquals(document, ctx.readDocuments { findDocument(it, space.spaceId, document.documentId) })
        assertEquals(
            listOf(1L),
            ctx.readDocuments { listRevisions(it, document.documentId, beforeRevision = 0, limit = 100) }
                .map { it.revision },
        )

        expectInjectedRollback {
            failing.moveNode(
                owner,
                space.spaceId,
                document.documentId,
                null,
                "不应移动",
                document.revision,
            )
        }
        assertEquals(
            overview.documentId,
            ctx.readDocuments { findDocument(it, space.spaceId, document.documentId) }?.parentId,
        )

        expectInjectedRollback {
            failing.deleteNode(
                owner,
                space.spaceId,
                document.documentId,
                document.revision,
                UUID.randomUUID().toString(),
            )
        }
        assertNotNull(ctx.readDocuments { findDocument(it, space.spaceId, document.documentId) })

        expectInjectedRollback {
            failing.archiveSpace(owner, space.spaceId, UUID.randomUUID().toString())
        }
        assertNotNull(ctx.readDocuments { findSpace(it, space.spaceId) })
    }

    @Test
    fun `document creation and creator recent either commit or roll back together`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("document-recent-atomic-owner"))
        val space = ctx.documentService.createSpace(owner, "最近访问原子空间", null)
        val service = documentService(
            unitOfWork = ExposedPgUnitOfWork(database = ctx.database, onEventsCommitted = {}),
            repository = RecentFailureRepository(ctx.documentRepo),
        )

        assertIs<InjectedRecentFailureException>(
            runCatching {
                service.createDocument(owner, space.spaceId, null, "不能半提交", "# 正文")
            }.exceptionOrNull(),
        )
        assertTrue(ctx.readDocuments { listNodes(it, space.spaceId, null) }.isEmpty())
        assertTrue(ctx.documentService.listRecentDocuments(owner, 10).isEmpty())
    }

    @Test
    fun `user principal lock precedes membership authority and cannot deadlock primary assignment`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("document-lock-order-owner"))
        val admin = ctx.registerUser(uniqueUsername("document-lock-order-admin"))
        val unit = OrganizationUnit(UUID.randomUUID().toString(), name = "文档管理员组")
        ctx.seedOrganizationUnit(unit)
        ctx.seedOrganizationMember(OrganizationMember(unit.unitId, admin))
        val space = ctx.documentService.createSpace(owner, "锁序空间", null)
        ctx.documentService.upsertGrant(
            owner,
            space.spaceId,
            DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
            unit.unitId,
            DocumentSpace.ROLE_ADMIN,
            false,
            expectedPolicyRevision = space.policyRevision,
            operationId = UUID.randomUUID().toString(),
        )

        val userLocked = CountDownLatch(1)
        val allowMembershipUpdate = CountDownLatch(1)
        val primaryAssignment = async(Dispatchers.Default) {
            transaction(ctx.database) {
                check(Users.selectAll().where { Users.uid eq admin }.forUpdate().singleOrNull() != null)
                userLocked.countDown()
                check(allowMembershipUpdate.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    "timed out while holding primary-assignment user lock"
                }
                OrganizationMemberships.update({ OrganizationMemberships.uid eq admin }) {
                    it[OrganizationMemberships.primary] = true
                }
            }
        }
        assertTrue(userLocked.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))

        val authorityAttempted = CountDownLatch(1)
        val authorityAcquired = CountDownLatch(1)
        val grantingService = documentService(
            unitOfWork = ExposedPgUnitOfWork(database = ctx.database, onEventsCommitted = {}),
            repository = PolicyFenceProbeRepository(ctx.documentRepo, authorityAttempted, authorityAcquired),
        )
        val grant = async(Dispatchers.Default) {
            grantingService.upsertGrant(
                admin,
                space.spaceId,
                DocumentSpaceGrant.PRINCIPAL_USER,
                admin,
                DocumentSpace.ROLE_ADMIN,
                false,
                expectedPolicyRevision = ctx.currentDocumentPolicyRevision(space.spaceId),
                operationId = UUID.randomUUID().toString(),
            )
        }
        assertTrue(authorityAttempted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        // 反向实现会在阻塞于 Users(admin) 之前就已持有 admin 的成员关系，
        // 并报告 authority acquired，从而补全锁环的另一侧。
        assertFalse(authorityAcquired.await(BLOCKED_ASSERT_MILLIS, TimeUnit.MILLISECONDS))

        allowMembershipUpdate.countDown()
        primaryAssignment.await()
        val committedGrant = grant.await()
        assertTrue(authorityAcquired.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(ctx.currentDocumentPolicyRevision(space.spaceId), committedGrant.policyRevision)
        val storedGrant = ctx.documentService.listGrants(owner, space.spaceId)
            .single { it.principalType == DocumentSpaceGrant.PRINCIPAL_USER && it.principalId == admin }
        assertEquals(DocumentSpace.ROLE_ADMIN, storedGrant.role)
    }

    @Test
    fun `committed grant revocation rejects a writer already waiting on the space fence`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("document-revoke-owner"))
        val editor = ctx.registerUser(uniqueUsername("document-revoke-editor"))
        val space = ctx.documentService.createSpace(owner, "撤权空间", null)
        val committedGrant = ctx.documentService.upsertGrant(
            owner,
            space.spaceId,
            DocumentSpaceGrant.PRINCIPAL_USER,
            editor,
            DocumentSpace.ROLE_EDITOR,
            false,
            expectedPolicyRevision = space.policyRevision,
            operationId = UUID.randomUUID().toString(),
        )
        val document = ctx.documentService.createDocument(owner, space.spaceId, null, "撤权文档", "# before")

        val revocationMutated = CountDownLatch(1)
        val allowRevocationCommit = CountDownLatch(1)
        val revokingService = documentService(
            holdingUnitOfWork(revocationMutated, allowRevocationCommit),
        )
        val revocation = async(Dispatchers.Default) {
            revokingService.removeGrant(
                owner,
                space.spaceId,
                DocumentSpaceGrant.PRINCIPAL_USER,
                editor,
                expectedPolicyRevision = committedGrant.policyRevision,
                operationId = UUID.randomUUID().toString(),
            )
        }
        assertTrue(revocationMutated.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))

        val authorityAttempted = CountDownLatch(1)
        val authorityAcquired = CountDownLatch(1)
        val probingRepository = AuthorityProbeRepository(
            delegate = ctx.documentRepo,
            attempted = authorityAttempted,
            acquired = authorityAcquired,
        )
        val waitingWriter = documentService(
            unitOfWork = ExposedPgUnitOfWork(database = ctx.database, onEventsCommitted = {}),
            repository = probingRepository,
        )
        val write = async(Dispatchers.Default) {
            runCatching {
                waitingWriter.updateDocument(
                    editor,
                    space.spaceId,
                    document.documentId,
                    "# forbidden",
                    document.revision,
                )
            }
        }
        assertTrue(authorityAttempted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertFalse(authorityAcquired.await(BLOCKED_ASSERT_MILLIS, TimeUnit.MILLISECONDS))

        allowRevocationCommit.countDown()
        revocation.await()
        assertIs<DocumentAccessDeniedException>(write.await().exceptionOrNull())
        assertTrue(authorityAcquired.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals("# before", ctx.readDocuments { findDocument(it, space.spaceId, document.documentId) }?.markdown)
        assertTrue(ctx.readDocuments { listGrants(it, space.spaceId) }.isEmpty())
    }

    @Test
    fun `committed organization membership removal is rechecked by a waiting writer`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("document-org-owner"))
        val editor = ctx.registerUser(uniqueUsername("document-org-editor"))
        val unit = OrganizationUnit(UUID.randomUUID().toString(), name = "文档编辑组")
        ctx.seedOrganizationUnit(unit)
        ctx.seedOrganizationMember(OrganizationMember(unit.unitId, editor))
        val space = ctx.documentService.createSpace(owner, "组织撤权空间", null)
        ctx.documentService.upsertGrant(
            owner,
            space.spaceId,
            DocumentSpaceGrant.PRINCIPAL_ORGANIZATION_UNIT,
            unit.unitId,
            DocumentSpace.ROLE_EDITOR,
            false,
            expectedPolicyRevision = space.policyRevision,
            operationId = UUID.randomUUID().toString(),
        )
        val document = ctx.documentService.createDocument(owner, space.spaceId, null, "组织授权文档", "# before")

        val membershipDeleted = CountDownLatch(1)
        val allowMembershipCommit = CountDownLatch(1)
        val removal = async(Dispatchers.Default) {
            transaction(ctx.database) {
                OrganizationMemberships.deleteWhere {
                    (OrganizationMemberships.unitId eq unit.unitId) and
                        (OrganizationMemberships.uid eq editor)
                }
                membershipDeleted.countDown()
                check(allowMembershipCommit.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    "timed out while holding organization membership removal"
                }
            }
        }
        assertTrue(membershipDeleted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))

        val authorityAttempted = CountDownLatch(1)
        val authorityAcquired = CountDownLatch(1)
        val waitingWriter = documentService(
            unitOfWork = ExposedPgUnitOfWork(database = ctx.database, onEventsCommitted = {}),
            repository = AuthorityProbeRepository(ctx.documentRepo, authorityAttempted, authorityAcquired),
        )
        val write = async(Dispatchers.Default) {
            runCatching {
                waitingWriter.updateDocument(
                    editor,
                    space.spaceId,
                    document.documentId,
                    "# forbidden",
                    document.revision,
                )
            }
        }
        assertTrue(authorityAttempted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertFalse(authorityAcquired.await(BLOCKED_ASSERT_MILLIS, TimeUnit.MILLISECONDS))

        allowMembershipCommit.countDown()
        removal.await()
        assertIs<DocumentAccessDeniedException>(write.await().exceptionOrNull())
        assertTrue(authorityAcquired.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals("# before", ctx.readDocuments { findDocument(it, space.spaceId, document.documentId) }?.markdown)
    }

    @Test
    fun `concurrent identical archive commands converge after the aggregate fence`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("doc-archive-retry"))
        val space = ctx.documentService.createSpace(owner, "并发归档空间", null)
        val operationId = UUID.randomUUID().toString()

        val firstMutationFinished = CountDownLatch(1)
        val allowFirstCommit = CountDownLatch(1)
        val first = async(Dispatchers.Default) {
            documentService(holdingUnitOfWork(firstMutationFinished, allowFirstCommit))
                .archiveSpace(owner, space.spaceId, operationId)
        }
        assertTrue(firstMutationFinished.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))

        val retryFenceAttempted = CountDownLatch(1)
        val retryFenceAcquired = CountDownLatch(1)
        val retry = async(Dispatchers.Default) {
            documentService(
                unitOfWork = ExposedPgUnitOfWork(database = ctx.database, onEventsCommitted = {}),
                repository = DestructiveFenceProbeRepository(
                    ctx.documentRepo,
                    retryFenceAttempted,
                    retryFenceAcquired,
                ),
            ).archiveSpace(owner, space.spaceId, operationId)
        }
        assertTrue(retryFenceAttempted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertFalse(retryFenceAcquired.await(BLOCKED_ASSERT_MILLIS, TimeUnit.MILLISECONDS))

        allowFirstCommit.countDown()
        first.await()
        retry.await()
        assertTrue(retryFenceAcquired.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))

        transaction(ctx.database) {
            val row = DocumentSpaces.selectAll().where {
                DocumentSpaces.spaceId eq space.spaceId
            }.single()
            assertEquals(0, row[DocumentSpaces.status])
            assertEquals(operationId, row[DocumentSpaces.archiveCommandId])
        }
        assertIs<DocumentNotFoundException>(runCatching {
            ctx.documentService.archiveSpace(owner, space.spaceId, UUID.randomUUID().toString())
        }.exceptionOrNull())
    }

    @Test
    fun `concurrent identical delete commands converge after the aggregate fence`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("doc-delete-retry"))
        val space = ctx.documentService.createSpace(owner, "并发删除空间", null)
        val document = ctx.documentService.createDocument(
            owner,
            space.spaceId,
            null,
            "待并发删除",
            "# body",
        )
        val operationId = UUID.randomUUID().toString()

        val firstMutationFinished = CountDownLatch(1)
        val allowFirstCommit = CountDownLatch(1)
        val first = async(Dispatchers.Default) {
            documentService(holdingUnitOfWork(firstMutationFinished, allowFirstCommit)).deleteNode(
                owner,
                space.spaceId,
                document.documentId,
                document.revision,
                operationId,
            )
        }
        assertTrue(firstMutationFinished.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))

        val retryFenceAttempted = CountDownLatch(1)
        val retryFenceAcquired = CountDownLatch(1)
        val retry = async(Dispatchers.Default) {
            documentService(
                unitOfWork = ExposedPgUnitOfWork(database = ctx.database, onEventsCommitted = {}),
                repository = DestructiveFenceProbeRepository(
                    ctx.documentRepo,
                    retryFenceAttempted,
                    retryFenceAcquired,
                ),
            ).deleteNode(
                owner,
                space.spaceId,
                document.documentId,
                document.revision,
                operationId,
            )
        }
        assertTrue(retryFenceAttempted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertFalse(retryFenceAcquired.await(BLOCKED_ASSERT_MILLIS, TimeUnit.MILLISECONDS))

        allowFirstCommit.countDown()
        first.await()
        retry.await()
        assertTrue(retryFenceAcquired.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))

        transaction(ctx.database) {
            val row = DocumentNodes.selectAll().where {
                DocumentNodes.nodeId eq document.documentId
            }.single()
            assertEquals(0, row[DocumentNodes.status])
            assertEquals(operationId, row[DocumentNodes.deleteCommandId])
            assertEquals(document.revision + 1, row[DocumentNodes.revision])
        }
        assertIs<DocumentNotFoundException>(runCatching {
            ctx.documentService.deleteNode(
                owner,
                space.spaceId,
                document.documentId,
                document.revision,
                UUID.randomUUID().toString(),
            )
        }.exceptionOrNull())
        assertIs<DocumentNotFoundException>(runCatching {
            ctx.documentService.deleteNode(
                owner,
                space.spaceId,
                document.documentId,
                document.revision + 1,
                operationId,
            )
        }.exceptionOrNull())
    }

    @Test
    fun `admitted revision commits before a waiting archive and later writes fail closed`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("document-archive-owner"))
        val space = ctx.documentService.createSpace(owner, "归档空间", null)
        val document = ctx.documentService.createDocument(owner, space.spaceId, null, "归档文档", "# before")

        val revisionMutated = CountDownLatch(1)
        val allowRevisionCommit = CountDownLatch(1)
        val holdingWriter = documentService(holdingUnitOfWork(revisionMutated, allowRevisionCommit))
        val write = async(Dispatchers.Default) {
            holdingWriter.updateDocument(
                owner,
                space.spaceId,
                document.documentId,
                "# committed before archive",
                document.revision,
            )
        }
        assertTrue(revisionMutated.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))

        val archiveFenceAttempted = CountDownLatch(1)
        val archiveFenceAcquired = CountDownLatch(1)
        val waitingArchiver = documentService(
            unitOfWork = ExposedPgUnitOfWork(database = ctx.database, onEventsCommitted = {}),
            repository = DestructiveFenceProbeRepository(
                ctx.documentRepo,
                archiveFenceAttempted,
                archiveFenceAcquired,
            ),
        )
        val archive = async(Dispatchers.Default) {
            waitingArchiver.archiveSpace(owner, space.spaceId, UUID.randomUUID().toString())
        }
        assertTrue(archiveFenceAttempted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertFalse(archiveFenceAcquired.await(BLOCKED_ASSERT_MILLIS, TimeUnit.MILLISECONDS))

        allowRevisionCommit.countDown()
        val committed = write.await()
        archive.await()
        assertTrue(archiveFenceAcquired.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))

        assertEquals(2L, committed.revision)
        assertNull(ctx.readDocuments { findSpace(it, space.spaceId) })
        assertEquals(
            "# committed before archive",
            ctx.readDocuments { findDocument(it, space.spaceId, document.documentId) }?.markdown,
        )
        assertEquals(
            listOf(2L, 1L),
            ctx.readDocuments { listRevisions(it, document.documentId, beforeRevision = 0, limit = 100) }
                .map { it.revision },
        )
        assertIs<DocumentNotFoundException>(runCatching {
            ctx.documentService.updateDocument(
                owner,
                space.spaceId,
                document.documentId,
                "# must fail",
                committed.revision,
            )
        }.exceptionOrNull())
    }

    private fun documentService(
        unitOfWork: PgUnitOfWork,
        repository: DocumentRepository = ctx.documentRepo,
    ): DocumentService = DocumentService(
        repository = repository,
        unitOfWork = unitOfWork,
    )

    private fun failingUnitOfWork(): PgUnitOfWork = ExposedPgUnitOfWork(
        database = ctx.database,
        onEventsCommitted = {},
        hooks = PgUnitOfWorkHooks { stage ->
            if (stage == PgUnitOfWorkStage.AFTER_EVENT_FLUSH_BEFORE_COMMIT) {
                throw InjectedDocumentRollback
            }
        },
    )

    private fun holdingUnitOfWork(
        mutationFinished: CountDownLatch,
        allowCommit: CountDownLatch,
    ): PgUnitOfWork = ExposedPgUnitOfWork(
        database = ctx.database,
        onEventsCommitted = {},
        hooks = PgUnitOfWorkHooks { stage ->
            if (stage == PgUnitOfWorkStage.AFTER_EVENT_FLUSH_BEFORE_COMMIT) {
                mutationFinished.countDown()
                check(allowCommit.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    "timed out while holding document transaction"
                }
            }
        },
    )

    private suspend fun expectInjectedRollback(block: suspend () -> Unit) {
        assertIs<InjectedDocumentRollbackException>(runCatching { block() }.exceptionOrNull())
    }

    private class AuthorityProbeRepository(
        private val delegate: DocumentRepository,
        private val attempted: CountDownLatch,
        private val acquired: CountDownLatch,
    ) : DocumentRepository by delegate {
        override fun lockWriteAuthority(
            transaction: PgWriteTransactionContext,
            actorUid: String,
            spaceId: String,
            requiredOrganizationUnitIds: Set<String>,
            requiredUserIds: Set<String>,
        ): DocumentWriteAuthority {
            attempted.countDown()
            return try {
                delegate.lockWriteAuthority(
                    transaction,
                    actorUid,
                    spaceId,
                    requiredOrganizationUnitIds,
                    requiredUserIds,
                )
            } finally {
                acquired.countDown()
            }
        }
    }

    private class PolicyFenceProbeRepository(
        private val delegate: DocumentRepository,
        private val attempted: CountDownLatch,
        private val acquired: CountDownLatch,
    ) : DocumentRepository by delegate {
        override fun lockPolicyMutationFence(
            transaction: PgWriteTransactionContext,
            actorUid: String,
            spaceId: String,
            requiredUserIds: Set<String>,
        ): DocumentPolicyMutationFence {
            attempted.countDown()
            return try {
                delegate.lockPolicyMutationFence(transaction, actorUid, spaceId, requiredUserIds)
            } finally {
                acquired.countDown()
            }
        }
    }

    private class DestructiveFenceProbeRepository(
        private val delegate: DocumentRepository,
        private val attempted: CountDownLatch,
        private val acquired: CountDownLatch,
    ) : DocumentRepository by delegate {
        override fun lockDestructiveCommandSpace(
            transaction: PgWriteTransactionContext,
            actorUid: String,
            spaceId: String,
        ) {
            attempted.countDown()
            try {
                delegate.lockDestructiveCommandSpace(transaction, actorUid, spaceId)
            } finally {
                acquired.countDown()
            }
        }
    }

    private class RecentFailureRepository(
        private val delegate: DocumentRepository,
    ) : DocumentRepository by delegate {
        override fun touchRecentDocument(
            transaction: PgWriteTransactionContext,
            actorUid: String,
            documentId: String,
            accessedAt: Long,
        ): Unit = throw InjectedRecentFailureException()
    }

    private object InjectedDocumentRollback : InjectedDocumentRollbackException()
    private open class InjectedDocumentRollbackException : RuntimeException("injected document rollback")
    private class InjectedRecentFailureException : RuntimeException("injected recent failure")

}
