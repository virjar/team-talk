package com.virjar.tk.integration

import com.virjar.tk.domain.document.DocumentRepository
import com.virjar.tk.domain.document.DocumentService
import com.virjar.tk.domain.document.DocumentWriteAuthority
import com.virjar.tk.domain.transaction.PgTransactionContext
import com.virjar.tk.domain.transaction.PgUnitOfWork
import com.virjar.tk.infra.db.ExposedPgUnitOfWork
import com.virjar.tk.infra.db.OrganizationMemberships
import com.virjar.tk.infra.db.PgUnitOfWorkHooks
import com.virjar.tk.infra.db.PgUnitOfWorkStage
import com.virjar.tk.infra.db.Users
import com.virjar.tk.model.DocumentSpace
import com.virjar.tk.model.DocumentSpaceGrant
import com.virjar.tk.model.OrganizationMember
import com.virjar.tk.model.OrganizationUnit
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

/** Real PostgreSQL proof for the document aggregate transaction and ACL linearization boundary. */
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
        val folder = ctx.documentService.createFolder(owner, space.spaceId, null, "原目录")
        val document = ctx.documentService.createDocument(owner, space.spaceId, folder.nodeId, "原文档", "# v1")
        val failing = documentService(failingUnitOfWork())

        expectInjectedRollback { failing.createSpace(owner, "不应提交的空间", null) }
        assertEquals(listOf(space.spaceId), ctx.documentService.listSpaces(owner).map { it.spaceId })

        expectInjectedRollback { failing.updateSpace(owner, space.spaceId, "不应提交", "提交后") }
        assertEquals("回滚空间", ctx.documentRepo.findSpace(space.spaceId)?.name)

        expectInjectedRollback {
            failing.upsertGrant(
                owner,
                space.spaceId,
                DocumentSpaceGrant.PRINCIPAL_USER,
                member,
                DocumentSpace.ROLE_EDITOR,
                false,
            )
        }
        assertTrue(ctx.documentRepo.listGrants(space.spaceId).isEmpty())
        ctx.documentService.upsertGrant(
            owner,
            space.spaceId,
            DocumentSpaceGrant.PRINCIPAL_USER,
            member,
            DocumentSpace.ROLE_EDITOR,
            false,
        )
        expectInjectedRollback {
            failing.removeGrant(
                owner,
                space.spaceId,
                DocumentSpaceGrant.PRINCIPAL_USER,
                member,
            )
        }
        assertEquals(member, ctx.documentRepo.listGrants(space.spaceId).single().principalId)

        expectInjectedRollback { failing.createFolder(owner, space.spaceId, null, "临时目录") }
        assertEquals(listOf(folder.nodeId), ctx.documentRepo.listNodes(space.spaceId, null).map { it.nodeId })

        expectInjectedRollback {
            failing.createDocument(owner, space.spaceId, folder.nodeId, "临时文档", "不应提交")
        }
        assertEquals(listOf(document.documentId), ctx.documentRepo.listNodes(space.spaceId, folder.nodeId).map { it.nodeId })

        expectInjectedRollback {
            failing.updateDocument(
                owner,
                space.spaceId,
                document.documentId,
                "不应提交的标题",
                "# v2",
                document.revision,
            )
        }
        assertEquals(document, ctx.documentRepo.findDocument(document.documentId))
        assertEquals(listOf(1L), ctx.documentRepo.listRevisions(document.documentId).map { it.revision })

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
        assertEquals(folder.nodeId, ctx.documentRepo.findDocument(document.documentId)?.parentId)

        expectInjectedRollback {
            failing.deleteNode(owner, space.spaceId, document.documentId, document.revision)
        }
        assertNotNull(ctx.documentRepo.findDocument(document.documentId))

        expectInjectedRollback { failing.archiveSpace(owner, space.spaceId) }
        assertNotNull(ctx.documentRepo.findSpace(space.spaceId))
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
        )

        val userLocked = CountDownLatch(1)
        val allowMembershipUpdate = CountDownLatch(1)
        val primaryAssignment = async(Dispatchers.Default) {
            transaction {
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
            unitOfWork = ExposedPgUnitOfWork(onEventsCommitted = {}),
            repository = AuthorityProbeRepository(ctx.documentRepo, authorityAttempted, authorityAcquired),
        )
        val grant = async(Dispatchers.Default) {
            grantingService.upsertGrant(
                admin,
                space.spaceId,
                DocumentSpaceGrant.PRINCIPAL_USER,
                admin,
                DocumentSpace.ROLE_ADMIN,
                false,
            )
        }
        assertTrue(authorityAttempted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        // A reversed implementation would already hold admin's membership and report authority
        // acquired before blocking on Users(admin), completing the other side of the lock cycle.
        assertFalse(authorityAcquired.await(BLOCKED_ASSERT_MILLIS, TimeUnit.MILLISECONDS))

        allowMembershipUpdate.countDown()
        primaryAssignment.await()
        val committedGrant = grant.await()
        assertTrue(authorityAcquired.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(admin, committedGrant.principalId)
        assertEquals(DocumentSpace.ROLE_ADMIN, committedGrant.role)
    }

    @Test
    fun `committed grant revocation rejects a writer already waiting on the space fence`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("document-revoke-owner"))
        val editor = ctx.registerUser(uniqueUsername("document-revoke-editor"))
        val space = ctx.documentService.createSpace(owner, "撤权空间", null)
        ctx.documentService.upsertGrant(
            owner,
            space.spaceId,
            DocumentSpaceGrant.PRINCIPAL_USER,
            editor,
            DocumentSpace.ROLE_EDITOR,
            false,
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
            unitOfWork = ExposedPgUnitOfWork(onEventsCommitted = {}),
            repository = probingRepository,
        )
        val write = async(Dispatchers.Default) {
            runCatching {
                waitingWriter.updateDocument(
                    editor,
                    space.spaceId,
                    document.documentId,
                    document.title,
                    "# forbidden",
                    document.revision,
                )
            }
        }
        assertTrue(authorityAttempted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertFalse(authorityAcquired.await(BLOCKED_ASSERT_MILLIS, TimeUnit.MILLISECONDS))

        allowRevocationCommit.countDown()
        revocation.await()
        assertIs<IllegalArgumentException>(write.await().exceptionOrNull())
        assertTrue(authorityAcquired.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals("# before", ctx.documentRepo.findDocument(document.documentId)?.markdown)
        assertTrue(ctx.documentRepo.listGrants(space.spaceId).isEmpty())
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
        )
        val document = ctx.documentService.createDocument(owner, space.spaceId, null, "组织授权文档", "# before")

        val membershipDeleted = CountDownLatch(1)
        val allowMembershipCommit = CountDownLatch(1)
        val removal = async(Dispatchers.Default) {
            transaction {
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
            unitOfWork = ExposedPgUnitOfWork(onEventsCommitted = {}),
            repository = AuthorityProbeRepository(ctx.documentRepo, authorityAttempted, authorityAcquired),
        )
        val write = async(Dispatchers.Default) {
            runCatching {
                waitingWriter.updateDocument(
                    editor,
                    space.spaceId,
                    document.documentId,
                    document.title,
                    "# forbidden",
                    document.revision,
                )
            }
        }
        assertTrue(authorityAttempted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertFalse(authorityAcquired.await(BLOCKED_ASSERT_MILLIS, TimeUnit.MILLISECONDS))

        allowMembershipCommit.countDown()
        removal.await()
        assertIs<IllegalArgumentException>(write.await().exceptionOrNull())
        assertTrue(authorityAcquired.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals("# before", ctx.documentRepo.findDocument(document.documentId)?.markdown)
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
                document.title,
                "# committed before archive",
                document.revision,
            )
        }
        assertTrue(revisionMutated.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))

        val authorityAttempted = CountDownLatch(1)
        val authorityAcquired = CountDownLatch(1)
        val waitingArchiver = documentService(
            unitOfWork = ExposedPgUnitOfWork(onEventsCommitted = {}),
            repository = AuthorityProbeRepository(ctx.documentRepo, authorityAttempted, authorityAcquired),
        )
        val archive = async(Dispatchers.Default) { waitingArchiver.archiveSpace(owner, space.spaceId) }
        assertTrue(authorityAttempted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertFalse(authorityAcquired.await(BLOCKED_ASSERT_MILLIS, TimeUnit.MILLISECONDS))

        allowRevisionCommit.countDown()
        val committed = write.await()
        archive.await()
        assertTrue(authorityAcquired.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))

        assertEquals(2L, committed.revision)
        assertNull(ctx.documentRepo.findSpace(space.spaceId))
        assertEquals("# committed before archive", ctx.documentRepo.findDocument(document.documentId)?.markdown)
        assertEquals(listOf(2L, 1L), ctx.documentRepo.listRevisions(document.documentId).map { it.revision })
        assertIs<IllegalArgumentException>(runCatching {
            ctx.documentService.updateDocument(
                owner,
                space.spaceId,
                document.documentId,
                document.title,
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
        organizations = ctx.organizationRepo,
        users = ctx.userStore,
        unitOfWork = unitOfWork,
    )

    private fun failingUnitOfWork(): PgUnitOfWork = ExposedPgUnitOfWork(
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
            transaction: PgTransactionContext,
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

    private object InjectedDocumentRollback : InjectedDocumentRollbackException()
    private open class InjectedDocumentRollbackException : RuntimeException("injected document rollback")

}
